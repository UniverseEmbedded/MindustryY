package mindustry.net;

import arc.*;
import arc.func.*;
import arc.math.*;
import arc.net.*;
import arc.net.FrameworkMessage.*;
import arc.net.Server.*;
import arc.net.dns.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Log.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.runtime.*;
import mindustry.campaign.shared.net.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.net.Administration.*;
import mindustry.net.Net.*;
import mindustry.net.Packets.*;
import net.jpountz.lz4.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.concurrent.*;

import static mindustry.Vars.*;

public class ArcNetProvider implements NetProvider{
    public static final int clientReadBufferSize = 25_000;

    private final GameContext owner = RuntimeContexts.requireCurrent();
    final Client client;
    final Prov<DatagramPacket> packetSupplier = () -> new DatagramPacket(new byte[512], 512);

    final Server server;
    final CopyOnWriteArrayList<ArcConnection> connections = new CopyOnWriteArrayList<>();
    Thread serverThread;

    private static final ThreadLocal<Seq<Connection>> writeConnections = Threads.local(Seq::new);

    private volatile int playerLimitCache, packetSpamLimit;
    private volatile boolean tcpOnlyServer;
    private Ratekeeper clientUdpErrorRate = new Ratekeeper();

    public ArcNetProvider(){
        ArcNet.errorHandler = e -> {
            var finalCause = Strings.getFinalCause(e);
            // "connection is closed" is the routine EOF behind every remote close and carries no diagnostic value.
            if(finalCause != null && "Connection is closed.".equals(finalCause.getMessage())) return;
            if(Log.level == LogLevel.debug){
                Log.debug(Strings.getStackTrace(e));
            }else{
                // Send/serialize failures used to vanish at the default log level, so a server that dropped a
                // client mid-join left no trace in any captured log. Keep the message, skip the stack.
                Log.warn("Network error: @", finalCause == null ? String.valueOf(e) : finalCause.getMessage());
            }
        };

        //fetch this in the main thread to prevent threading issues
        Events.run(Trigger.update, () -> {
            playerLimitCache = owner.netServer.admins.getPlayerLimit();
            packetSpamLimit = Config.packetSpamLimit.num();
        });

        client = new Client(16384, clientReadBufferSize, new PacketSerializer(owner)){
            @Override
            public void handleNetException(ArcNetException e){
                //allow occasional UDP network errors
                if(owner.net.client() && e.getMessage() != null && e.getMessage().contains("UDP deserialization") && clientUdpErrorRate.allow(5000, 5)){
                    Log.err("UDP network error", e);
                }else{
                    super.handleNetException(e);
                }
            }
        };
        client.setDiscoveryPacket(packetSupplier);
        client.addListener(new NetListener(){
            @Override
            public void connected(Connection connection){
                Connect c = new Connect();
                c.addressTCP = connection.getRemoteAddressTCP().getAddress().getHostAddress();
                if(connection.getRemoteAddressTCP() != null) c.addressTCP = connection.getRemoteAddressTCP().toString();

                postOwner(() -> owner.net.handleClientReceived(c));
            }

            @Override
            public void disconnected(Connection connection, DcReason reason){
                if(connection.getLastProtocolError() != null){
                    owner.netClient.setQuiet();
                }

                Disconnect c = new Disconnect();
                c.reason = reason.toString();
                postOwner(() -> owner.net.handleClientReceived(c));
            }

            @Override
            public void received(Connection connection, Object object){
                if(!(object instanceof Packet p)) return;

                postOwner(() -> {
                    try{
                        owner.net.handleClientReceived(p);
                    }catch(Throwable e){
                        owner.net.handleException(e);
                    }
                });

            }
        });

        //include extra 16kb headroom for when the write buffer is full
        server = new Server(clientReadBufferSize + 16_000, 16384, new PacketSerializer(owner));
        server.setMulticast(multicastGroup, multicastPort);
        server.setDiscoveryHandler((address, handler) -> {
            // ArcNet invokes discovery handlers from its own multicast thread. Server-data serialization touches
            // Groups/rules and therefore must execute under this provider's authoritative runtime owner.
            if(!owner.acceptingWork()) return;
            try(RuntimeContexts.Scope ignored = RuntimeContexts.enter(owner)){
                ByteBuffer buffer = NetworkIO.writeServerData();
                int length = buffer.position();
                buffer.position(0);
                buffer.limit(length);
                handler.respond(buffer);
            }catch(java.util.concurrent.RejectedExecutionException ignored){
                // The owner entered teardown between the acceptingWork check and binding. Discovery is optional.
            }
        });

        server.addListener(new NetListener(){

            @Override
            public void connected(Connection connection){
                String ip = connection.getRemoteAddressTCP().getAddress().getHostAddress();

                //kill connections above the limit to prevent spam
                if((playerLimitCache > 0 && server.getConnections().length > playerLimitCache) || owner.netServer.admins.isDosBlacklisted(ip)){
                    Log.info("Closing connection @ - IP marked as a potential DOS attack.", ip);

                    connection.close(DcReason.closed);
                    return;
                }

                ArcConnection kn = new ArcConnection(ip, connection);

                Connect c = new Connect();
                c.addressTCP = ip;

                Log.debug("&bReceived connection: @", c.addressTCP);

                connection.setArbitraryData(kn);
                connections.add(kn);
                postOwner(() -> owner.net.handleServerReceived(kn, c));
            }

            @Override
            public void disconnected(Connection connection, DcReason reason){
                if(!(connection.getArbitraryData() instanceof ArcConnection k)) return;

                Disconnect c = new Disconnect();
                c.reason = reason.toString();

                postOwner(() -> {
                    owner.net.handleServerReceived(k, c);
                    connections.remove(k);
                });
            }

            @Override
            public void received(Connection connection, Object object){
                if(!(connection.getArbitraryData() instanceof ArcConnection k)) return;

                if(packetSpamLimit > 0 && !k.packetRate.allow(3000, packetSpamLimit)){
                    Log.warn("Blacklisting IP '@' as potential DOS attack - packet spam.", k.address);
                    connection.close(DcReason.closed);
                    k.blacklist();
                    return;
                }

                if(!(object instanceof Packet pack)) return;

                postOwner(() -> {
                    try{
                        owner.net.handleServerReceived(k, pack);
                    }catch(Throwable e){
                        long time = Time.millis();
                        //only kick due to errors if there are two within a short span of time
                        if(Time.timeSinceMillis(k.lastErrorTime) < 2000){
                            k.connection.close(DcReason.error);
                            Log.err("Closing connection due to error: " + k.address + " / " + k.uuid, e);
                        }else{
                            k.lastErrorTime = time;
                            Log.err("Error reading packet from connection: " + k.address + " / " + k.uuid, e);
                        }
                    }
                });
            }
        });
    }

    /** Routes transport callbacks back into this provider's owning GameContext. */
    private void postOwner(Runnable runnable){
        // Transport shutdown may synchronously notify listeners after GameContext.dispose() has entered its closing
        // phase. Such late notifications are intentionally not game work and must never abort socket cleanup merely
        // because RuntimeContexts correctly rejects new callbacks for a closing owner.
        if(!owner.acceptingWork()) return;
        try{
            RuntimeContexts.post(owner, runnable);
        }catch(RejectedExecutionException ignored){
            // The owner can cross into closing between the optimistic check and queue insertion.
        }
    }

    @Override
    public void setConnectFilter(Server.ServerConnectFilter connectFilter){
        server.setConnectFilter(connectFilter);
    }

    @Override
    public @Nullable ServerConnectFilter getConnectFilter(){
        return server.getConnectFilter();
    }

    private static boolean isLocal(InetAddress addr){
        if(addr.isAnyLocalAddress() || addr.isLoopbackAddress()) return true;

        try{
            return NetworkInterface.getByInetAddress(addr) != null;
        }catch(Exception e){
            return false;
        }
    }

    @Override
    public void connectClient(String ip, int port, Runnable success){
        clientUdpErrorRate.reset();

        Threads.daemon(RuntimeContexts.capture(owner, () -> {
            try{
                client.stop();

                Threads.daemon("Net Client", RuntimeContexts.capture(owner, () -> {
                    try{
                        client.run();
                    }catch(Exception e){
                        if(!(e instanceof ClosedSelectorException)) owner.net.handleException(e);
                    }
                }));

                SharedCampaignNet network = SharedCampaignNet.find(owner);
                byte[] brokerPreface = network == null ? null : network.clientConnectionPreamble();
                if(brokerPreface != null){
                    client.connect(5000, InetAddress.getByName(ip), port, -1, brokerPreface);
                }else{
                    client.connect(5000, ip, port, port);
                }
                success.run();
            }catch(Exception e){
                SharedCampaignNet network = SharedCampaignNet.find(owner);
                if(network != null) network.clearPreparedJoin();
                if(owner.netClient != null && owner.netClient.isConnecting()) owner.net.handleException(e);
            }
        }));
    }

    @Override
    public void disconnectClient(){
        clientUdpErrorRate.reset();
        client.close();
    }

    @Override
    public void sendClient(Object object, boolean reliable){
        // A same-TCP Shared Campaign switch deliberately detaches ArcNet for a short interval.
        if(!client.isConnected()) return;
        try{
            if(reliable || client.getRemoteAddressUDP() == null){
                client.sendTCP(object);
            }else{
                client.sendUDP(object);
            }
        }catch(BufferOverflowException | BufferUnderflowException e){
            owner.net.showError(e);
        }
    }

    @Override
    public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> invalid){
        try{
            var host = pingHostImpl(address, port);
            postOwner(() -> valid.get(host));
        }catch(IOException e){
            if(port == Vars.port){
                for(var record : ArcDns.getSrvRecords("_mindustry._tcp." + address)){
                    try{
                        var host = pingHostImpl(record.target, record.port);
                        postOwner(() -> valid.get(host));
                        return;
                    }catch(IOException ignored){
                    }
                }
            }
            postOwner(() -> invalid.get(e));
        }
    }

    private Host pingHostImpl(String address, int port) throws IOException{
        try(DatagramSocket socket = new DatagramSocket()){
            long time = Time.millis();

            socket.send(new DatagramPacket(new byte[]{-2, 1}, 2, InetAddress.getByName(address), port));
            socket.setSoTimeout(2000);

            DatagramPacket packet = packetSupplier.get();
            socket.receive(packet);

            ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
            Host host = NetworkIO.readServerData((int)Time.timeSinceMillis(time), packet.getAddress().getHostAddress(), buffer);
            host.port = port;
            return host;
        }
    }

    @Override
    public void discoverServers(Cons<Host> callback, Runnable done){
        Seq<InetAddress> foundAddresses = new Seq<>();
        long time = Time.millis();

        client.discoverHosts(port, multicastGroup, multicastPort, 3000, packet -> {
            synchronized(foundAddresses){
                try{
                    if(foundAddresses.contains(address -> address.equals(packet.getAddress()) || (isLocal(address) && isLocal(packet.getAddress())))){
                        return;
                    }
                    ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
                    Host host = NetworkIO.readServerData((int)Time.timeSinceMillis(time), packet.getAddress().getHostAddress(), buffer);
                    postOwner(() -> callback.get(host));
                    foundAddresses.add(packet.getAddress());
                }catch(Exception e){
                    //don't crash when there's an error pinging a server or parsing data
                    e.printStackTrace();
                }
            }
        }, () -> postOwner(done));
    }

    @Override
    public void dispose(){
        disconnectClient();

        // GameContext disposal is a terminal lifecycle boundary, unlike an ordinary Net.closeServer() call.
        // Do not enqueue the stop on Vars.mainExecutor here: in-process Action runtimes may be disposed from
        // their scheduler worker after a crash, while the primary application thread is not pumping that executor.
        // Leaving the stop queued keeps the sector listener bound and prevents the recovered/replacement Action
        // from reclaiming its port. ArcNet Server.stop() closes the listening channels synchronously and is safe
        // to invoke from outside the server update thread.
        connections.clear();
        tcpOnlyServer = false;
        server.stop();

        Thread thread = serverThread;
        if(thread != null && thread != Thread.currentThread()){
            try{
                thread.join(2000L);
            }catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();
            }
        }

        try{
            client.dispose();
        }catch(IOException ignored){
        }
    }

    @Override
    public Iterable<ArcConnection> getConnections(){
        return connections;
    }

    @Override
    public void sendAllServer(Object object, Iterable<NetConnection> connections, boolean reliable){
        //build up list of underlying arcnet connections for faster bulk transfer
        var cons = writeConnections.get();
        cons.clear();
        for(var con : connections){
            if(con instanceof ArcConnection ac){
                cons.add(ac.connection);
            }
        }

        if(reliable || tcpOnlyServer){
            server.sendToAllTCP(object, cons);
        }else{
            server.sendToAllUDP(object, cons);
        }

        cons.clear();
    }

    @Override
    public void sendAllServer(Object object, boolean reliable){
        if(reliable || tcpOnlyServer){
            server.sendToAllTCP(object);
        }else{
            server.sendToAllUDP(object);
        }
    }

    @Override
    public void sendExceptServer(NetConnection except, Object object, boolean reliable){
        if(!(except instanceof ArcConnection con)){
            NetProvider.super.sendExceptServer(except, object, reliable);
            return;
        }

        if(reliable || tcpOnlyServer){
            server.sendToAllExceptTCP(con.connection.getID(), object);
        }else{
            server.sendToAllExceptUDP(con.connection.getID(), object);
        }
    }

    @Override
    public void hostServer(int port) throws IOException{
        connections.clear();
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.find(owner);
        tcpOnlyServer = shared != null && shared.actionEnabled();
        if(tcpOnlyServer){
            // Shared Action traffic is application-level TCP-only so broker-injected connections do not depend on
            // a UDP side channel. Still bind UDP on the loopback game port: an unmodified Mindustry/ArcNet client
            // performs the normal TCP+UDP registration sequence before it can consume a vanilla Call.connect
            // redirect. Keeping the UDP listener available makes the direct compatibility lane genuinely vanilla
            // while all Action payloads continue to use TCP because tcpOnlyServer remains true.
            InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
            server.bind(loopback, loopback);
        }else{
            server.bind(port, port);
        }

        serverThread = new Thread(RuntimeContexts.capture(owner, () -> {
            try{
                server.run();
            }catch(Throwable e){
                if(!(e instanceof ClosedSelectorException)) Threads.throwAppException(e);
            }
        }), "Net Server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @Override
    public void injectExternalConnection(SocketChannel channel, ByteBuffer preRead){
        server.injectExternalConnection(channel, preRead);
    }

    @Override
    public SocketChannel detachClientChannel(){
        return client.detachTcpChannel();
    }

    @Override
    public void rebindClientChannel(SocketChannel channel, Runnable success, Cons<Throwable> failure){
        clientUdpErrorRate.reset();
        Threads.daemon(RuntimeContexts.capture(owner, () -> {
            try{
                Threads.daemon("Net Client", RuntimeContexts.capture(owner, () -> {
                    try{ client.run(); }
                    catch(Exception e){ if(!(e instanceof ClosedSelectorException)) owner.net.handleException(e); }
                }));
                client.connectDetached(5000, channel);
                success.run();
            }catch(Exception e){
                try{ channel.close(); }catch(IOException ignored){}
                SharedCampaignNet network = SharedCampaignNet.find(owner);
                if(network != null) network.clearPreparedJoin();
                if(failure != null) failure.get(e);
                if(owner.netClient != null && owner.netClient.isConnecting()) owner.net.handleException(e);
            }
        }));
    }

    @Override
    public void closeServer(){
        connections.clear();
        tcpOnlyServer = false;
        mainExecutor.submit(server::stop);
    }

    public class ArcConnection extends NetConnection{
        public final Connection connection;

        long lastErrorTime;

        public ArcConnection(String address, Connection connection){
            super(address);
            this.connection = connection;
        }

        @Override
        public boolean isConnected(){
            return connection.isConnected();
        }

        @Override
        public void blacklist(){
            //Blacklist TCP address
            super.blacklist();
            //Blacklist UDP address
            var address = connection.getRemoteAddressUDP();
            if(address != null){
                owner.netServer.admins.blacklistDos(address.getAddress().getHostAddress());
            }
        }

        @Override
        public void sendStream(Streamable stream){
            //listeners are processed in the order they're added and each reads into the buffer greedily before the next gets a turn, so concurrent streams are sent in FIFO order
            connection.addListener(new InputStreamSender(stream.stream, 1024){
                int id;

                @Override
                protected void start(){
                    //send an object so the receiving side knows how to handle the following chunks
                    StreamBegin begin = new StreamBegin();
                    begin.total = stream.stream.available();
                    begin.type = Net.getPacketId(stream);
                    connection.sendTCP(begin);
                    id = begin.id;
                }

                @Override
                protected Object next(byte[] bytes){
                    StreamChunk chunk = new StreamChunk();
                    chunk.id = id;
                    chunk.data = bytes;
                    return chunk; //wrap the byte[] with an object so the receiving side knows how to handle it.
                }
            });
        }

        @Override
        public void send(Object object, boolean reliable){
            try{
                if(connection.isConnected()){
                    if(reliable || tcpOnlyServer || connection.getRemoteAddressUDP() == null){
                        connection.sendTCP(object);
                    }else{
                        connection.sendUDP(object);
                    }
                }
            }catch(Exception e){
                Log.err("Error sending packet. Disconnecting invalid client!", e);
                connection.close(DcReason.error);

                if(connection.getArbitraryData() instanceof ArcConnection k){
                    connections.remove(k);
                }
            }
        }

        @Override
        public void close(){
            if(connection.isConnected()) connection.close(DcReason.closed);
        }
    }

    public static class PacketSerializer implements NetSerializer{
        private final GameContext owner;
        private final LZ4SafeDecompressor decompressor = LZ4Factory.fastestInstance().safeDecompressor();
        private final LZ4Compressor compressor = LZ4Factory.fastestInstance().fastCompressor();

        public PacketSerializer(){ this(RuntimeContexts.requireCurrent()); }
        public PacketSerializer(GameContext owner){ this.owner = owner; }

        //for debugging total read/write speeds
        private static final boolean debug = false;

        ThreadLocal<ByteBuffer> decompressBuffer = Threads.local(() -> ByteBuffer.allocate(32768));
        ThreadLocal<Reads> reads = Threads.local(() -> new Reads(new ByteBufferInput(decompressBuffer.get())));
        ThreadLocal<Writes> writes = Threads.local(() -> new Writes(new ByteBufferOutput(decompressBuffer.get())));

        //for debugging network write counts
        final WindowedMean upload = new WindowedMean(5), download = new WindowedMean(5);
        long lastUpload, lastDownload, uploadAccum, downloadAccum;
        int lastPos;

        @Override
        public Object read(ByteBuffer byteBuffer){
            //fixes invalid 0-length packets on some servers
            if(byteBuffer.limit() == 0) return null;

            if(debug){
                if(Time.timeSinceMillis(lastDownload) >= 1000){
                    lastDownload = Time.millis();
                    download.add(downloadAccum);
                    downloadAccum = 0;
                    Log.info("Download: @ b/s", download.mean());
                }
                downloadAccum += byteBuffer.remaining();
            }

            byte id = byteBuffer.get();
            if(id == -2){
                return readFramework(byteBuffer);
            }else{
                //read length int, followed by compressed lz4 data
                Packet packet = Net.newPacket(id);
                if(!packet.allow(owner.net.server())) throw new RuntimeException("Invalid packet type for endpoint: " + packet.getClass());
                var buffer = decompressBuffer.get();
                int length = byteBuffer.getShort() & 0xffff;
                byte compression = byteBuffer.get();

                //no compression, copy over buffer
                if(compression == 0){
                    buffer.position(0).limit(length);
                    buffer.put(byteBuffer.array(), byteBuffer.position(), length);
                    buffer.position(0);
                    packet.read(reads.get(), length);
                    //move read packets forward
                    byteBuffer.position(byteBuffer.position() + buffer.position());
                }else{
                    //decompress otherwise
                    int compressedLength = byteBuffer.limit() - byteBuffer.position();
                    decompressor.decompress(byteBuffer, byteBuffer.position(), compressedLength, buffer, 0, length);

                    buffer.position(0);
                    buffer.limit(length);
                    packet.read(reads.get(), length);
                    //move buffer forward based on bytes read by decompressor
                    byteBuffer.position(byteBuffer.position() + compressedLength);
                }

                return packet;
            }
        }

        @Override
        public void write(ByteBuffer byteBuffer, Object o){
            if(debug){
                lastPos = byteBuffer.position();
            }

            //write raw buffer
            if(o instanceof ByteBuffer raw){
                byteBuffer.put(raw);
            }else if(o instanceof FrameworkMessage msg){
                byteBuffer.put((byte)-2); //code for framework message
                writeFramework(byteBuffer, msg);
            }else{
                if(!(o instanceof Packet pack)) throw new RuntimeException("All sent objects must extend Packet! Class: " + o.getClass());
                byte id = Net.getPacketId(pack);
                byteBuffer.put(id);

                var temp = decompressBuffer.get();
                temp.position(0);
                temp.limit(temp.capacity());
                pack.write(writes.get());

                short length = (short)temp.position();

                //write length, uncompressed
                byteBuffer.putShort(length);

                //don't bother with small packets
                if(length < 36 || pack instanceof StreamChunk){
                    //write direct contents...
                    byteBuffer.put((byte)0); //0 = no compression
                    byteBuffer.put(temp.array(), 0, length);
                }else{
                    byteBuffer.put((byte)1); //1 = compression
                    //write compressed data; this does not modify position!
                    int written = compressor.compress(temp, 0, temp.position(), byteBuffer, byteBuffer.position(), byteBuffer.remaining());
                    //skip to indicate the written, compressed data
                    byteBuffer.position(byteBuffer.position() + written);
                }
            }

            if(debug){
                if(Time.timeSinceMillis(lastUpload) >= 1000){
                    lastUpload = Time.millis();
                    upload.add(uploadAccum);
                    uploadAccum = 0;
                    Log.info("Upload: @ b/s", upload.mean());
                }
                uploadAccum += byteBuffer.position() - lastPos;
            }
        }

        public void writeFramework(ByteBuffer buffer, FrameworkMessage message){
            if(message instanceof Ping p){
                buffer.put((byte)0);
                buffer.putInt(p.id);
                buffer.put(p.isReply ? 1 : (byte)0);
            }else if(message instanceof DiscoverHost){
                buffer.put((byte)1);
            }else if(message instanceof KeepAlive){
                buffer.put((byte)2);
            }else if(message instanceof RegisterUDP p){
                buffer.put((byte)3);
                buffer.putInt(p.connectionID);
            }else if(message instanceof RegisterTCP p){
                buffer.put((byte)4);
                buffer.putInt(p.connectionID);
            }
        }

        public FrameworkMessage readFramework(ByteBuffer buffer){
            byte id = buffer.get();

            if(id == 0){
                Ping p = new Ping();
                p.id = buffer.getInt();
                p.isReply = buffer.get() == 1;
                return p;
            }else if(id == 1){
                return FrameworkMessage.discoverHost;
            }else if(id == 2){
                return FrameworkMessage.keepAlive;
            }else if(id == 3){
                RegisterUDP p = new RegisterUDP();
                p.connectionID = buffer.getInt();
                return p;
            }else if(id == 4){
                RegisterTCP p = new RegisterTCP();
                p.connectionID = buffer.getInt();
                return p;
            }else{
                throw new RuntimeException("Unknown framework message!");
            }
        }
    }

}
