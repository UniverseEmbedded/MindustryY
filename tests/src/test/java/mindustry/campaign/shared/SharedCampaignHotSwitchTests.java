package mindustry.campaign.shared;

import mindustry.campaign.shared.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Transport-level acceptance for same-TCP Shared Campaign action switching. */
public class SharedCampaignHotSwitchTests{
    @Test @Timeout(10)
    void serviceBarrierDrainsOldBytesAndEchoesMarker() throws Exception{
        byte[] marker = ActionSessionHandshake.hotSwitchBarrier("session", "action-a", "action-b");
        try(ServerSocketChannel server = ServerSocketChannel.open()){
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress)server.getLocalAddress()).getPort();
            CompletableFuture<byte[]> echoed = new CompletableFuture<>();
            Thread peer = new Thread(() -> {
                try(SocketChannel accepted = server.accept()){
                    accepted.write(ByteBuffer.wrap(new byte[]{9, 8, 7, 6}));
                    accepted.write(ByteBuffer.wrap(marker));
                    ByteBuffer received = ByteBuffer.allocate(marker.length);
                    while(received.hasRemaining()){
                        int read = accepted.read(received);
                        if(read < 0) throw new EOFException();
                    }
                    echoed.complete(received.array());
                }catch(Throwable failure){ echoed.completeExceptionally(failure); }
            }, "hot-switch-barrier-peer");
            peer.setDaemon(true);
            peer.start();
            try(SocketChannel client = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))){
                assertTrue(SharedCampaignService.synchronizeHotSwitchStream(client, marker, 3_000L, 4096));
                assertArrayEquals(marker, echoed.get(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test @Timeout(15)
    void brokerMovesOneAuthenticatedTcpBetweenActionsWithoutLeakingSession() throws Exception{
        try(ActionSessionBroker broker = new ActionSessionBroker("hot-switch-test", 16, 4);
            Entry entry = new Entry(broker);
            World a = new World('A');
            World b = new World('B')){
            broker.registerActionRoute("action-a", a.port(), (action, token) -> "token-a".equals(token) ? "member" : null);
            broker.registerActionRoute("action-b", b.port(), (action, token) -> "token-b".equals(token) ? "member" : null);

            try(Socket client = new Socket(InetAddress.getLoopbackAddress(), entry.port())){
                client.setSoTimeout(3_000);
                client.getOutputStream().write(ActionSessionHandshake.encode(new ActionSessionHandshake.Request(
                    ActionSessionHandshake.version, ActionSessionHandshake.Kind.action, ActionSessionHandshake.flagInlineArc,
                    "session-hot", "action-a", "member", "token-a", 0)));
                client.getOutputStream().flush();
                assertEquals('A', client.getInputStream().read());
                waitUntil(() -> broker.liveSessionCount() == 1 && broker.liveRelayCount() == 1, 2_000);

                assertNotNull(broker.beginHotSwitch("member", "session-hot", "action-a", "action-b"));
                echoBarrier(client, "session-hot", "action-a", "action-b");
                assertTrue(broker.resumeHotSwitch("member", "session-hot"));
                assertEquals('B', client.getInputStream().read());

                ActionSessionBroker.Session session = broker.session("session-hot");
                assertNotNull(session);
                assertEquals("action-b", session.actionId);
                assertEquals(1, session.switchCount);
                assertEquals(1, broker.liveSessionCount());
                assertEquals(1, broker.liveRelayCount());

                assertNotNull(broker.beginHotSwitch("member", "session-hot", "action-b", "action-a"));
                echoBarrier(client, "session-hot", "action-b", "action-a");
                assertTrue(broker.resumeHotSwitch("member", "session-hot"));
                assertEquals('A', client.getInputStream().read());

                session = broker.session("session-hot");
                assertNotNull(session);
                assertEquals("action-a", session.actionId);
                assertEquals(2, session.switchCount);
                assertEquals(1, broker.liveSessionCount());
                assertEquals(1, broker.liveRelayCount());
            }
            waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, 3_000);
        }
    }

    private static void echoBarrier(Socket socket, String session, String from, String to) throws Exception{
        byte[] marker = ActionSessionHandshake.hotSwitchBarrier(session, from, to);
        byte[] actual = socket.getInputStream().readNBytes(marker.length);
        assertArrayEquals(marker, actual);
        socket.getOutputStream().write(marker);
        socket.getOutputStream().flush();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception{
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(System.nanoTime() < deadline){
            if(condition.getAsBoolean()) return;
            Thread.sleep(10L);
        }
        fail("condition not met in " + timeoutMillis + " ms");
    }

    private static final class Entry implements AutoCloseable{
        private final ServerSocketChannel server = ServerSocketChannel.open();
        private final Thread thread;
        private final ActionSessionBroker broker;

        Entry(ActionSessionBroker broker) throws IOException{
            this.broker = broker;
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            thread = new Thread(() -> {
                while(server.isOpen()){
                    try{
                        SocketChannel accepted = server.accept();
                        if(accepted != null) broker.handleAccepted(accepted);
                    }catch(IOException closed){ return; }
                }
            }, "hot-switch-entry");
            thread.setDaemon(true);
            thread.start();
        }

        int port() throws IOException{ return ((InetSocketAddress)server.getLocalAddress()).getPort(); }
        @Override public void close() throws Exception{ server.close(); thread.join(1_000L); }
    }

    private static final class World implements AutoCloseable{
        private final ServerSocket server;
        private final ExecutorService pool;
        private final byte banner;
        private final Set<Socket> clients = ConcurrentHashMap.newKeySet();

        World(char banner) throws IOException{
            this.banner = (byte)banner;
            server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "hot-switch-world"); t.setDaemon(true); return t; });
            pool.execute(() -> {
                while(!server.isClosed()){
                    try{
                        Socket client = server.accept();
                        clients.add(client);
                        pool.execute(() -> serve(client));
                    }catch(IOException closed){ return; }
                }
            });
        }

        int port(){ return server.getLocalPort(); }

        private void serve(Socket client){
            try(client){
                client.getOutputStream().write(banner);
                client.getOutputStream().flush();
                byte[] buffer = new byte[256];
                while(client.getInputStream().read(buffer) >= 0){}
            }catch(IOException ignored){}finally{ clients.remove(client); }
        }

        @Override public void close(){
            try{ server.close(); }catch(IOException ignored){}
            for(Socket client : clients) try{ client.close(); }catch(IOException ignored){}
            pool.shutdownNow();
        }
    }
}
