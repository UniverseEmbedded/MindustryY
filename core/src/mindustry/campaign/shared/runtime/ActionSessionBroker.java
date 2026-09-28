package mindustry.campaign.shared.runtime;

import arc.util.Log;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Single-entry Connection Broker for Shared Campaign (Stage 3 Connection Broker).
 *
 * Owns public accept demultiplexing of MYCT control and MYCS action-session handshakes. Action clients must identify
 * the destination before ArcNet registration; ordinary ArcNet is server-first (RegisterTCP), so first-frame Arc
 * sniffing cannot safely route a shared entry and is intentionally rejected. Maintains a session registry
 * (session → authenticated member/action identity) and routes Action traffic to live world listeners.
 *
 * Security: the broker verifies the signed Action admission token before opening an internal world socket. The Action
 * world independently verifies the same grant, network UUID and one-use nonce before admitting gameplay. Missing
 * routes, unknown prefaces, bad framing, and oversize handshakes fail closed.
 */
public final class ActionSessionBroker implements Closeable{
    /** Default bound for concurrent Action sessions accepted by this broker. */
    public static final int defaultMaxActionSessions = 128;
    /** Default per-member concurrent Action session cap (anti-amplification). */
    public static final int defaultMaxSessionsPerMember = 8;
    public static final int defaultSniffTimeoutMillis = 15_000;
    /** Blocking relay reads wake periodically so a same-TCP hot-switch can pause without closing the client socket. */
    public static final int relayPollTimeoutMillis = 250;
    /** Test/operations override; lower values speed pause detection at the cost of more idle read wakeups. */
    private static int configuredRelayPollTimeoutMillis(){
        return Math.max(10, Integer.getInteger("mindustry.sharedCampaign.entry.relayPollTimeoutMillis", relayPollTimeoutMillis));
    }
    /** Must exceed the control client's replay/reconnect window for hot-switch mutations. */
    public static final int hotSwitchPauseTimeoutMillis = 60_000;
    public static final int hotSwitchBarrierTimeoutMillis = 5_000;
    public static final int hotSwitchBarrierMaxDrainBytes = 1024 * 1024;

    public enum SessionState{
        handshaking,
        bound,
        switching,
        closed
    }

    public static final class Session{
        public final String sessionId;
        public final long createdAtMillis;
        public volatile String memberId;
        public volatile String actionId;
        public volatile SessionState state;
        public volatile String remoteAddress;
        public volatile long lastActivityMillis;
        public volatile long switchCount;
        public volatile String lastError;

        Session(String sessionId, String memberId, String actionId, String remoteAddress){
            this.sessionId = sessionId;
            this.memberId = memberId == null ? "" : memberId;
            this.actionId = actionId == null ? "" : actionId;
            this.remoteAddress = remoteAddress == null ? "" : remoteAddress;
            this.state = SessionState.handshaking;
            this.createdAtMillis = System.currentTimeMillis();
            this.lastActivityMillis = this.createdAtMillis;
        }
    }

    /** Audit line for diagnostics; never contains admission secrets. */
    public static final class AuditEvent{
        public final long atMillis = System.currentTimeMillis();
        public final String sessionId;
        public final String memberId;
        public final String actionId;
        public final String kind;
        public final String detail;

        AuditEvent(String sessionId, String memberId, String actionId, String kind, String detail){
            this.sessionId = sessionId == null ? "" : sessionId;
            this.memberId = memberId == null ? "" : memberId;
            this.actionId = actionId == null ? "" : actionId;
            this.kind = kind == null ? "" : kind;
            this.detail = detail == null ? "" : detail;
        }

        @Override public String toString(){
            return atMillis + " session=" + sessionId + " member=" + memberId + " action=" + actionId + " kind=" + kind + " detail=" + detail;
        }
    }

    /** Optional coordinator-side admission verifier. Returns a non-empty authenticated memberId, or null on rejection. */
    public interface AdmissionValidator{
        String validate(String actionId, String admissionToken);
    }

    private static final class Route{
        final int gamePort;
        final AdmissionValidator admissionValidator;

        Route(int gamePort, AdmissionValidator admissionValidator){
            this.gamePort = gamePort;
            this.admissionValidator = admissionValidator;
        }
    }

    /** Live client↔world relay for one session; client side is kept across hot-switch pauses. */
    private static final class ActiveRelay{
        final String sessionId;
        final ActionSessionBroker owner;
        final ConcurrentHashMap<String, ActiveRelay> ownerRelays;
        final Object lock = new Object();
        volatile Socket client;
        volatile Socket world;
        volatile boolean paused;
        volatile boolean closed;
        /** Serializes resume attempts without holding {@link #lock} while blocking on the client barrier echo. */
        volatile boolean resuming;
        /** Expected client echo for the current paused switch; null outside the barrier phase. */
        volatile byte[] switchBarrier;
        /** Incremented whenever a pump pair starts; older pumps exit when generation changes. */
        final AtomicInteger pumpGeneration = new AtomicInteger();
        volatile Thread pumpUp;
        volatile Thread pumpDown;

        ActiveRelay(String sessionId, Socket client, ActionSessionBroker owner, ConcurrentHashMap<String, ActiveRelay> ownerRelays){
            this.sessionId = sessionId;
            this.client = client;
            this.owner = owner;
            this.ownerRelays = ownerRelays;
        }

        void closeWorldOnly(){
            synchronized(lock){
                Socket local = world;
                world = null;
                closeQuietly(local);
            }
        }

        void closeAll(){
            synchronized(lock){
                closed = true;
                paused = false;
                pumpGeneration.incrementAndGet();
                Socket local = world;
                world = null;
                closeQuietly(local);
                Socket clientSocket = client;
                client = null;
                closeQuietly(clientSocket);
            }
            joinPumps(1_000L);
            ownerRelays.remove(sessionId, this);
        }

        /** Stops pumps and world side; keeps client TCP. Returns false when an old pump could not be stopped safely. */
        boolean pauseAndStopWorld(){
            synchronized(lock){
                if(closed) return false;
                paused = true;
                pumpGeneration.incrementAndGet();
                Socket local = world;
                world = null;
                closeQuietly(local);
            }
            return joinPumps(2_000L);
        }

        boolean joinPumps(long timeoutMillis){
            long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
            Thread up = pumpUp, down = pumpDown;
            for(Thread thread : new Thread[]{up, down}){
                if(thread == null || thread == Thread.currentThread()) continue;
                long remaining = deadline - System.currentTimeMillis();
                if(remaining <= 0L) break;
                try{
                    thread.join(remaining);
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean stopped = (up == null || !up.isAlive() || up == Thread.currentThread())
                && (down == null || !down.isAlive() || down == Thread.currentThread());
            if(stopped){
                if(pumpUp == up) pumpUp = null;
                if(pumpDown == down) pumpDown = null;
            }
            return stopped;
        }

        int beginPumpPair(String threadPrefix){
            synchronized(lock){
                if(closed || client == null) return -1;
                int generation = pumpGeneration.incrementAndGet();
                Thread up = new Thread(() -> pumpGenerationGuarded(this, generation, true), threadPrefix + "-up");
                Thread down = new Thread(() -> pumpGenerationGuarded(this, generation, false), threadPrefix + "-down");
                up.setDaemon(true);
                down.setDaemon(true);
                pumpUp = up;
                pumpDown = down;
                up.start();
                down.start();
                return generation;
            }
        }
    }

    private final String label;
    private final int maxActionSessions;
    private final int maxSessionsPerMember;
    private final int handshakeTimeoutMillis;
    private final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    /** Serializes session-count admission with insertion/removal so concurrent handshakes cannot overrun limits. */
    private final Object sessionAdmissionLock = new Object();
    private final ConcurrentHashMap<String, ActiveRelay> activeRelays = new ConcurrentHashMap<>();
    private final Set<Closeable> trackedEndpoints = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<AuditEvent> auditLog = new LinkedBlockingQueue<>(4096);
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger acceptedConnections = new AtomicInteger();
    private final AtomicInteger rejectedConnections = new AtomicInteger();
    private final AtomicInteger relayedConnections = new AtomicInteger();
    private final ExecutorService ioPool;
    /** Barrier writes must not block the control request that tells the client to begin draining the preserved TCP. */
    private final ExecutorService barrierPool;
    private final ScheduledExecutorService maintenance;
    private volatile ControlBranch controlBranch;
    /** Public Shared Campaign entry port used in audit labels; Action ports remain private loopback endpoints. */
    private volatile int entryPort = -1;

    /** Installed by ActionCoordinator so MYCT control connections keep using the existing frame loop. */
    public interface ControlBranch{
        void accept(Socket socket, byte[] preReadPrefix) throws Exception;
    }

    public ActionSessionBroker(String label){
        this(label, defaultMaxActionSessions, defaultMaxSessionsPerMember);
    }

    public ActionSessionBroker(String label, int maxActionSessions, int maxSessionsPerMember){
        this.label = label == null || label.isBlank() ? "shared-campaign-broker" : label;
        this.maxActionSessions = Math.max(1, maxActionSessions);
        this.maxSessionsPerMember = Math.max(1, maxSessionsPerMember);
        this.handshakeTimeoutMillis = Math.max(1_000, Integer.getInteger("mindustry.sharedCampaign.entry.handshakeTimeoutMillis", defaultSniffTimeoutMillis));
        // Do not queue unauthenticated sockets behind a handful of blocked handshake workers. A normal bounded queue
        // makes ThreadPoolExecutor stay at corePoolSize until the queue is full, so slowloris peers can serialize
        // handshakeTimeoutMillis in batches of eight. Direct handoff expands immediately up to 64 and then fails
        // excess pre-auth sockets closed instead of retaining an unbounded/long-lived pre-auth backlog.
        this.ioPool = new ThreadPoolExecutor(8, 64, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, this.label + "-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        this.barrierPool = new ThreadPoolExecutor(0, this.maxActionSessions, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, this.label + "-barrier");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        this.maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, this.label + "-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        running.set(true);
    }

    public boolean isRunning(){ return running.get(); }

    public void controlBranch(ControlBranch branch){ this.controlBranch = branch; }

    /** Binds the public Shared Campaign entry port for unambiguous entry@actual diagnostics. */
    public void bindEntryPort(int entryPort){
        this.entryPort = entryPort > 0 && entryPort <= 65535 ? entryPort : -1;
    }

    private String ports(int gamePort){
        return "entry@actual=" + (entryPort > 0 ? Integer.toString(entryPort) : "?") + "@" + gamePort;
    }

    public int acceptedConnections(){ return acceptedConnections.get(); }
    public int rejectedConnections(){ return rejectedConnections.get(); }
    public int relayedConnections(){ return relayedConnections.get(); }
    public int liveSessionCount(){ return sessions.size(); }
    public int liveRouteCount(){ return routes.size(); }
    public int liveRelayCount(){ return activeRelays.size(); }

    /**
     * Registers (or replaces) the loopback relay route for one Action world.
     *
     * Product shared-entry sessions deliberately stay broker-owned for their full lifetime: same-TCP world switching
     * requires the broker to pause and reattach the preserved client socket. Transferring ownership into an Arc server
     * would make that socket impossible to recover safely, so the obsolete broker inject route has been removed.
     */
    public void registerActionRoute(String actionId, int gamePort, AdmissionValidator admissionValidator){
        if(actionId == null || actionId.isBlank()) throw new IllegalArgumentException("actionId is required");
        if(gamePort <= 0 || gamePort > 65535) throw new IllegalArgumentException("Invalid gamePort: " + gamePort);
        Objects.requireNonNull(admissionValidator, "admissionValidator");
        routes.put(actionId, new Route(gamePort, admissionValidator));
        audit("", "", actionId, "route-register", ports(gamePort) + " mode=relay");
    }

    public void unregisterActionRoute(String actionId){
        if(actionId == null || actionId.isBlank()) return;
        if(routes.remove(actionId) != null) audit("", "", actionId, "route-unregister", "");
        // Close sessions bound to this world; fail-closed rather than leave half-routed sessions.
        for(Session session : sessions.values()){
            if(actionId.equals(session.actionId)) closeSession(session.sessionId, "action-unregistered");
        }
    }

    public Collection<Session> sessionsSnapshot(){
        return List.copyOf(sessions.values());
    }

    public Session session(String sessionId){
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public List<AuditEvent> drainAuditSnapshot(){
        return List.copyOf(auditLog);
    }

    /**
     * Same-TCP hot-switch step 1: pause the exact authenticated relay for {@code fromActionId}, stop old pumps,
     * close only the world side, and keep the client TCP open. Returns the live session, or null when the supplied
     * session/member/action tuple is not the currently bound relay.
     */
    public Session beginHotSwitch(String memberId, String sessionId, String fromActionId, String toActionId){
        if(sessionId == null || sessionId.isBlank()) return null;
        if(fromActionId == null || fromActionId.isBlank()) throw new IllegalArgumentException("fromActionId is required");
        if(toActionId == null || toActionId.isBlank()) throw new IllegalArgumentException("toActionId is required");
        Route targetRoute = routes.get(toActionId);
        if(targetRoute == null) return null;
        Session session = sessions.get(sessionId);
        String member = memberId == null ? "" : memberId;
        if(session == null || session.state != SessionState.bound || !member.equals(session.memberId) || !fromActionId.equals(session.actionId)) return null;
        ActiveRelay relay = activeRelays.get(session.sessionId);
        if(relay == null) return null;
        synchronized(relay.lock){
            if(relay.closed || relay.paused) return null;
        }
        if(!relay.pauseAndStopWorld()){
            closeSession(session.sessionId, "hot-switch-pump-timeout");
            return null;
        }
        // The destination can disappear while the old relay is being drained/stopped. Since the session still named
        // the source action during that window, unregisterActionRoute(target) could not have found it. Re-check the
        // exact route generation before committing SWITCHING or the preserved socket could wait on a dead target.
        if(routes.get(toActionId) != targetRoute){
            closeSession(session.sessionId, "hot-switch-target-changed");
            return null;
        }
        final byte[] barrier = ActionSessionHandshake.hotSwitchBarrier(session.sessionId, fromActionId, toActionId);
        final long switchCount;
        synchronized(relay.lock){
            if(relay.closed || relay.client == null) return null;
            relay.switchBarrier = barrier;
            session.actionId = toActionId;
            session.state = SessionState.switching;
            session.switchCount++;
            switchCount = session.switchCount;
            session.lastActivityMillis = System.currentTimeMillis();
            audit(session.sessionId, session.memberId, session.actionId, "hot-switch-pause", "from=" + fromActionId + " to=" + toActionId);
        }
        // Pumps are fully stopped now. Anything the old world already wrote is ordered before this marker on the
        // preserved TCP. Do not write it on the control-request thread: if the client's receive buffer is full while
        // Arc is detached, a blocking write would prevent the pause response that tells the client to start draining.
        // The writer is bounded by the session cap; the normal hot-switch timeout closes the socket and releases any
        // writer that stays blocked. Resume cannot pass the echo check until this write has actually completed.
        try{
            barrierPool.execute(() -> {
                try{
                    Socket client;
                    synchronized(relay.lock){
                        if(relay.closed || !relay.paused || relay.switchBarrier != barrier || session.switchCount != switchCount) return;
                        client = relay.client;
                    }
                    if(client == null) throw new EOFException("Preserved client socket is unavailable");
                    OutputStream output = client.getOutputStream();
                    output.write(barrier);
                    output.flush();
                }catch(IOException failure){
                    session.lastError = failure.getMessage();
                    closeSession(session.sessionId, "hot-switch-barrier-write-failed");
                }
            });
        }catch(RejectedExecutionException saturated){
            session.lastError = "Hot-switch barrier capacity reached";
            closeSession(session.sessionId, "hot-switch-barrier-capacity");
            return null;
        }
        final String pausedSessionId = session.sessionId;
        maintenance.schedule(() -> {
            Session live = sessions.get(pausedSessionId);
            ActiveRelay active = activeRelays.get(pausedSessionId);
            if(live == null || active == null) return;
            boolean expired;
            synchronized(active.lock){
                expired = !active.closed && active.paused && live.state == SessionState.switching && live.switchCount == switchCount;
            }
            if(expired) closeSession(pausedSessionId, "hot-switch-timeout");
        }, hotSwitchPauseTimeoutMillis, TimeUnit.MILLISECONDS);
        return session;
    }

    /**
     * Same-TCP hot-switch step 2: resume the paused client TCP by relaying it to the session's current action.
     * Call only after the client Arc layer has detached and is waiting for RegisterTCP on the same TCP.
     * Old pumps from the previous generation are not reused; a new generation starts on the same client socket.
     */
    public boolean resumeHotSwitch(String memberId, String sessionId){
        Session session = sessions.get(sessionId);
        ActiveRelay relay = activeRelays.get(sessionId);
        String member = memberId == null ? "" : memberId;
        if(session == null || relay == null || !member.equals(session.memberId)) return false;

        Route route;
        Socket client;
        byte[] barrier;
        long switchCount;
        boolean terminalMissingState;
        synchronized(relay.lock){
            if(relay.closed || !relay.paused || relay.resuming || session.state != SessionState.switching) return false;
            route = routes.get(session.actionId);
            client = relay.client;
            barrier = relay.switchBarrier;
            terminalMissingState = route == null || client == null || barrier == null;
            switchCount = session.switchCount;
            if(!terminalMissingState) relay.resuming = true;
        }
        if(terminalMissingState){
            closeSession(sessionId, "hot-switch-resume-state-missing");
            return false;
        }

        Socket local = null;
        try{
            // Do not hold relay.lock while waiting for the echo. The server-side barrier writer also needs that lock
            // briefly to snapshot the preserved socket; holding it here would deadlock an immediate resume request.
            if(!readThroughMarker(client.getInputStream(), barrier, hotSwitchBarrierTimeoutMillis, hotSwitchBarrierMaxDrainBytes)){
                throw new IOException("Timed out waiting for hot-switch stream barrier");
            }
            local = new Socket();
            local.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), route.gamePort), 5_000);
            local.setTcpNoDelay(true);
            if(!relay.joinPumps(1_000L)) throw new IOException("Previous relay pumps did not stop");
            client.setSoTimeout(configuredRelayPollTimeoutMillis());
            local.setSoTimeout(configuredRelayPollTimeoutMillis());

            synchronized(relay.lock){
                if(relay.closed || !relay.paused || relay.client != client || relay.switchBarrier != barrier
                    || session.switchCount != switchCount || session.state != SessionState.switching){
                    throw new IOException("Hot-switch state changed while resuming");
                }
                relay.world = local;
                local = null; // relay now owns it
                relay.switchBarrier = null;
                relay.paused = false;
                relay.resuming = false;
                session.state = SessionState.bound;
                session.lastActivityMillis = System.currentTimeMillis();
                relayedConnections.incrementAndGet();
                audit(sessionId, session.memberId, session.actionId, "hot-switch-resume", ports(route.gamePort));
                if(relay.beginPumpPair(label + "-hot") >= 0) return true;
            }
            closeSession(sessionId, "hot-switch-pump-start-failed");
            return false;
        }catch(IOException e){
            closeQuietly(local);
            synchronized(relay.lock){ relay.resuming = false; }
            session.lastError = e.getMessage();
            audit(sessionId, session.memberId, session.actionId, "hot-switch-resume-failed", e.getMessage());
            closeSession(sessionId, "hot-switch-resume-failed");
            return false;
        }
    }

    /** Authenticated control-plane cancellation after a pause that cannot be completed safely. */
    public boolean abortHotSwitch(String memberId, String sessionId){
        Session session = sessions.get(sessionId);
        if(session == null || !Objects.equals(memberId == null ? "" : memberId, session.memberId)) return false;
        if(session.state != SessionState.switching) return false;
        closeSession(sessionId, "hot-switch-aborted");
        return true;
    }

    /** Reads and discards bytes through an exact marker, bounded by both time and byte count. */
    static boolean readThroughMarker(InputStream input, byte[] marker, long timeoutMillis, int maxBytes) throws IOException{
        if(input == null || marker == null || marker.length == 0 || maxBytes <= 0) return false;
        int[] fallback = new int[marker.length];
        for(int i = 1, prefix = 0; i < marker.length; i++){
            while(prefix > 0 && marker[i] != marker[prefix]) prefix = fallback[prefix - 1];
            if(marker[i] == marker[prefix]) prefix++;
            fallback[i] = prefix;
        }
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
        int matched = 0, consumed = 0;
        byte[] buffer = new byte[Math.min(4096, Math.max(256, marker.length * 2))];
        while(consumed < maxBytes && System.currentTimeMillis() < deadline){
            int read;
            try{
                read = input.read(buffer, 0, Math.min(buffer.length, maxBytes - consumed));
            }catch(SocketTimeoutException timeout){
                continue;
            }
            if(read < 0) return false;
            if(read == 0) continue;
            consumed += read;
            for(int i = 0; i < read; i++){
                byte value = buffer[i];
                while(matched > 0 && value != marker[matched]) matched = fallback[matched - 1];
                if(value == marker[matched]) matched++;
                if(matched == marker.length) return true;
            }
        }
        return false;
    }

    /** Generation-guarded pump: exits when the relay pauses, closes, or a newer generation starts. */
    private static void pumpGenerationGuarded(ActiveRelay relay, int generation, boolean clientToWorld){
        Socket client;
        Socket world;
        synchronized(relay.lock){
            if(relay.closed || relay.paused || relay.pumpGeneration.get() != generation) return;
            client = relay.client;
            world = relay.world;
        }
        if(client == null || world == null) return;
        boolean transportEnded = false;
        try{
            // Do not use try-with-resources here. Closing either stream closes the preserved client socket, which
            // makes same-TCP hot-switch impossible. SO_TIMEOUT gives pause/generation changes a bounded wake-up.
            InputStream in = (clientToWorld ? client : world).getInputStream();
            OutputStream out = (clientToWorld ? world : client).getOutputStream();
            byte[] buffer = new byte[16 * 1024];
            while(true){
                if(relay.closed || relay.paused || relay.pumpGeneration.get() != generation) return;
                int read;
                try{
                    read = in.read(buffer);
                }catch(SocketTimeoutException timeout){
                    continue;
                }
                if(read < 0){
                    transportEnded = true;
                    break;
                }
                if(read == 0) continue;
                if(relay.closed || relay.paused || relay.pumpGeneration.get() != generation) return;
                out.write(buffer, 0, read);
                out.flush();
            }
        }catch(IOException failure){
            // Closing the old world socket is how a hot-switch wakes its pumps; that path must not close the client.
            if(!relay.paused && !relay.closed && relay.pumpGeneration.get() == generation) transportEnded = true;
        }finally{
            if(transportEnded && !relay.paused && !relay.closed && relay.pumpGeneration.get() == generation){
                relay.owner.closeSession(relay.sessionId, "relay-closed");
            }
        }
    }

    /** Hands an accepted public-entry SocketChannel to the broker. Ownership transfers immediately. */
    public void handleAccepted(SocketChannel channel){
        if(channel == null) return;
        if(!running.get()){
            closeQuietly(channel);
            return;
        }
        trackedEndpoints.add(channel);
        acceptedConnections.incrementAndGet();
        try{
            ioPool.execute(() -> {
                try{
                    demuxChannel(channel);
                }catch(Throwable t){
                    rejectedConnections.incrementAndGet();
                    audit("", "", "", "demux-error", String.valueOf(t.getMessage()));
                    closeQuietly(channel);
                }finally{
                    trackedEndpoints.remove(channel);
                }
            });
        }catch(RejectedExecutionException rejected){
            rejectedConnections.incrementAndGet();
            trackedEndpoints.remove(channel);
            closeQuietly(channel);
        }
    }

    /** Compatibility entry for blocking-socket accept loops. */
    public void handleAccepted(Socket socket){
        handleAccepted(socket, false);
    }

    /**
     * @param synchronous when true, demux runs on the caller thread (tests / deterministic harness). Production
     *                    accept loops pass false so the entry listener can continue accepting while IO is in flight.
     */
    public void handleAccepted(Socket socket, boolean synchronous){
        if(socket == null) return;
        SocketChannel channel = socket.getChannel();
        if(channel != null){
            handleAccepted(channel);
            return;
        }
        // Plain accepted Socket cannot become a SocketChannel on all JDKs; use the same relay-only demux contract.
        if(!running.get()){
            closeQuietly(socket);
            return;
        }
        trackedEndpoints.add(socket);
        acceptedConnections.incrementAndGet();
        Runnable work = () -> {
            try{
                demuxPlainSocket(socket);
            }catch(Throwable t){
                rejectedConnections.incrementAndGet();
                audit("", "", "", "demux-error", String.valueOf(t.getMessage()));
                closeQuietly(socket);
            }finally{
                trackedEndpoints.remove(socket);
            }
        };
        try{
            if(synchronous){
                work.run();
            }else{
                ioPool.execute(work);
            }
        }catch(RejectedExecutionException rejected){
            rejectedConnections.incrementAndGet();
            trackedEndpoints.remove(socket);
            closeQuietly(socket);
        }
    }

    /** Blocks until in-flight demux/relay work finishes closing tracked sockets. */
    public void shutdown(long timeoutMillis){
        if(!running.compareAndSet(true, false)) return;
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
        for(Closeable endpoint : trackedEndpoints) closeQuietly(endpoint);
        trackedEndpoints.clear();
        synchronized(sessionAdmissionLock){
            for(Session session : sessions.values()) session.state = SessionState.closed;
            sessions.clear();
        }
        for(ActiveRelay relay : activeRelays.values()) relay.closeAll();
        activeRelays.clear();
        routes.clear();
        maintenance.shutdownNow();
        barrierPool.shutdownNow();
        ioPool.shutdown();
        try{
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            if(!ioPool.awaitTermination(remaining, TimeUnit.MILLISECONDS)) ioPool.shutdownNow();
            remaining = Math.max(1L, deadline - System.currentTimeMillis());
            if(!barrierPool.awaitTermination(remaining, TimeUnit.MILLISECONDS)) barrierPool.shutdownNow();
        }catch(InterruptedException interrupted){
            ioPool.shutdownNow();
            barrierPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override public void close(){
        shutdown(2_000L);
    }

    private void demuxChannel(SocketChannel channel) throws Exception{
        channel.configureBlocking(true);
        Socket socket = channel.socket();
        socket.setTcpNoDelay(true);
        // SocketChannel streams do not consistently honor Socket SO_TIMEOUT on every JDK. Use the Socket stream plus
        // an absolute deadline so a slow peer cannot monopolize one broker IO worker by dripping handshake bytes.
        DeadlineInputStream in = new DeadlineInputStream(socket, socket.getInputStream(), handshakeTimeoutMillis);
        byte[] prefix = new byte[4];
        readFully(in, prefix);
        int first = readInt(prefix, 0);
        if(first == ControlProtocol.magic){
            branchControl(socket, prefix);
            return;
        }
        if(first == ActionSessionHandshake.magic){
            handleStructuredSession(channel, prefix, in);
            return;
        }
        rejectInline(channel, "unsupported-preface");
    }

    private void demuxPlainSocket(Socket socket) throws Exception{
        socket.setTcpNoDelay(true);
        DeadlineInputStream in = new DeadlineInputStream(socket, socket.getInputStream(), handshakeTimeoutMillis);
        byte[] prefix = new byte[4];
        readFully(in, prefix);
        int first = readInt(prefix, 0);
        if(first == ControlProtocol.magic){
            branchControl(socket, prefix);
            return;
        }
        if(first == ActionSessionHandshake.magic){
            handleStructuredSessionPlain(socket, prefix, in);
            return;
        }
        rejectInline(socket, "unsupported-preface");
    }

    private void branchControl(Socket socket, byte[] prefix) throws Exception{
        ControlBranch branch = controlBranch;
        if(branch == null) throw new IOException("Broker has no control-plane branch");
        audit("", "", "", "control-branch", remote(socket));
        // Control branch now owns the socket; stop tracking it as a broker endpoint.
        trackedEndpoints.remove(socket);
        branch.accept(socket, prefix);
    }

    private void handleStructuredSession(SocketChannel channel, byte[] magicBytes, InputStream input) throws IOException{
        ActionSessionHandshake.Request request = ActionSessionHandshake.readRequest(input, magicBytes);
        String rejection = validateInlineRequest(request);
        if(rejection != null){
            rejectInline(channel, rejection);
            return;
        }
        Route route = routes.get(request.actionId);
        if(route == null){
            rejectInline(channel, "no-route");
            return;
        }
        String memberId = authenticatedRoutingMember(route, request.actionId, request.admission, request.memberId);
        if(memberId == null){
            rejectInline(channel, "invalid-admission");
            return;
        }
        Session session = tryBindSession(request.sessionId, memberId, request.actionId, remote(channel));
        if(session == null){
            rejectInline(channel, "session-limit");
            return;
        }
        audit(session.sessionId, session.memberId, session.actionId, "session-bound", "structured-inline");
        try{
            // The selected Arc server must speak first with RegisterTCP; any broker response would corrupt the stream.
            routeActionTraffic(channel, session, route, new byte[0]);
        }catch(IOException | RuntimeException failure){
            closeSession(session.sessionId, "structured-route-failed");
            throw failure;
        }
    }

    private void handleStructuredSessionPlain(Socket socket, byte[] magicBytes, InputStream input) throws IOException{
        ActionSessionHandshake.Request request = ActionSessionHandshake.readRequest(input, magicBytes);
        String rejection = validateInlineRequest(request);
        if(rejection != null){
            rejectInline(socket, rejection);
            return;
        }
        Route route = routes.get(request.actionId);
        if(route == null){
            rejectInline(socket, "no-route");
            return;
        }
        String memberId = authenticatedRoutingMember(route, request.actionId, request.admission, request.memberId);
        if(memberId == null){
            rejectInline(socket, "invalid-admission");
            return;
        }
        Session session = tryBindSession(request.sessionId, memberId, request.actionId, remote(socket));
        if(session == null){
            rejectInline(socket, "session-limit");
            return;
        }
        audit(session.sessionId, session.memberId, session.actionId, "session-bound", "structured-inline");
        try{
            relayPlain(socket, session, route.gamePort, new byte[0]);
        }catch(IOException | RuntimeException failure){
            closeSession(session.sessionId, "structured-route-failed");
            throw failure;
        }
    }

    private static String validateInlineRequest(ActionSessionHandshake.Request request){
        if(request.kind != ActionSessionHandshake.Kind.action) return "unsupported-kind";
        if(!request.inlineArc() || request.flags != ActionSessionHandshake.flagInlineArc) return "inline-arc-required";
        if(!validSessionId(request.sessionId)) return "invalid-session-id";
        if(request.capabilities != 0) return "unsupported-capabilities";
        if(request.actionId.isBlank() || request.admission.isBlank()) return "missing-route-admission";
        return null;
    }

    private String authenticatedRoutingMember(Route route, String actionId, String admission, String claimedMember){
        String claimed = claimedMember == null ? "" : claimedMember;
        final String authenticated;
        try{
            authenticated = route.admissionValidator.validate(actionId, admission);
        }catch(Throwable failure){
            audit("", claimed, actionId, "admission-reject", "validator-error");
            return null;
        }
        if(authenticated == null || authenticated.isBlank()) return null;
        if(!claimed.isBlank() && !authenticated.equals(claimed)) return null;
        return authenticated;
    }

    private static boolean validSessionId(String sessionId){
        if(sessionId == null || sessionId.length() < 8 || sessionId.length() > 128) return false;
        for(int i = 0; i < sessionId.length(); i++){
            char c = sessionId.charAt(i);
            if(!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.')) return false;
        }
        return true;
    }

    private Session tryBindSession(String requestedSessionId, String memberId, String actionId, String remote){
        synchronized(sessionAdmissionLock){
            if(!allowMemberUnlocked(memberId)){
                audit("", memberId, actionId, "handshake-reject", "session-limit");
                return null;
            }
            if(!validSessionId(requestedSessionId)){
                audit("", memberId, actionId, "handshake-reject", "invalid-session-id");
                return null;
            }
            String sessionId = requestedSessionId;
            if(sessions.containsKey(sessionId)){
                audit(sessionId, memberId, actionId, "handshake-reject", "duplicate-session-id");
                return null;
            }
            Session session = new Session(sessionId, memberId, actionId, remote);
            session.state = SessionState.bound;
            sessions.put(sessionId, session);
            return session;
        }
    }

    private void routeActionTraffic(SocketChannel channel, Session session, Route route, byte[] preRead) throws IOException{
        // A route may disappear between MYCS verification and relay setup (Action stop/crash or coordinator shutdown).
        // Never resurrect a session that unregisterActionRoute()/shutdown() already invalidated.
        if(!running.get() || sessions.get(session.sessionId) != session || session.state != SessionState.bound || routes.get(session.actionId) != route){
            throw new IOException("Action route changed before relay setup");
        }
        session.lastActivityMillis = System.currentTimeMillis();
        relayChannel(channel, session, route.gamePort, preRead);
    }

    private void relayChannel(SocketChannel channel, Session session, int gamePort, byte[] preRead) throws IOException{
        // Keep the same lifecycle guarantees as relayPlain(). Channel based production paths
        // must also create an ActiveRelay; otherwise hot-switch and cleanup cannot see them.
        ActiveRelay relay = new ActiveRelay(session.sessionId, channel.socket(), this, activeRelays);
        if(activeRelays.putIfAbsent(session.sessionId, relay) != null){
            closeQuietly(channel);
            throw new IOException("Duplicate active relay for " + session.sessionId);
        }
        // unregisterActionRoute()/shutdown() can win just before the relay becomes visible. Re-check after publication so
        // either side of the race closes this client rather than leaving an orphan relay to a private world listener.
        if(!running.get() || sessions.get(session.sessionId) != session || session.state != SessionState.bound){
            activeRelays.remove(session.sessionId, relay);
            relay.closeAll();
            throw new IOException("Session closed before relay setup");
        }
        Socket local = new Socket();
        try{
            channel.configureBlocking(true);
            channel.socket().setTcpNoDelay(true);
            channel.socket().setSoTimeout(configuredRelayPollTimeoutMillis());
            local.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), gamePort), 5_000);
            local.setTcpNoDelay(true);
            local.setSoTimeout(configuredRelayPollTimeoutMillis());
            if(preRead != null && preRead.length > 0){
                local.getOutputStream().write(preRead);
                local.getOutputStream().flush();
            }
        }catch(IOException e){
            session.lastError = e.getMessage();
            audit(session.sessionId, session.memberId, session.actionId, "relay-failed", e.getMessage());
            activeRelays.remove(session.sessionId, relay);
            relay.closeAll();
            closeQuietly(channel);
            closeQuietly(local);
            throw e;
        }
        relay.world = local;
        relayedConnections.incrementAndGet();
        audit(session.sessionId, session.memberId, session.actionId, "relay", ports(gamePort));
        if(relay.beginPumpPair(label + "-relay") < 0){
            activeRelays.remove(session.sessionId, relay);
            relay.closeAll();
            throw new IOException("Failed to start channel relay pumps for " + session.sessionId);
        }
    }

    private void relayPlain(Socket client, Session session, int gamePort, byte[] preRead) throws IOException{
        Socket local = new Socket();
        ActiveRelay relay = new ActiveRelay(session.sessionId, client, this, activeRelays);
        if(activeRelays.putIfAbsent(session.sessionId, relay) != null){
            closeQuietly(client);
            throw new IOException("Duplicate active relay for " + session.sessionId);
        }
        if(!running.get() || sessions.get(session.sessionId) != session || session.state != SessionState.bound){
            activeRelays.remove(session.sessionId, relay);
            relay.closeAll();
            throw new IOException("Session closed before relay setup");
        }
        try{
            local.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), gamePort), 5_000);
            local.setTcpNoDelay(true);
            local.setSoTimeout(configuredRelayPollTimeoutMillis());
            client.setTcpNoDelay(true);
            client.setSoTimeout(configuredRelayPollTimeoutMillis());
            relay.world = local;
            if(preRead != null && preRead.length > 0){
                local.getOutputStream().write(preRead);
                local.getOutputStream().flush();
            }
        }catch(IOException e){
            session.lastError = e.getMessage();
            audit(session.sessionId, session.memberId, session.actionId, "relay-failed", e.getMessage());
            activeRelays.remove(session.sessionId);
            relay.closeAll();
            closeQuietly(client);
            closeQuietly(local);
            throw e;
        }
        relayedConnections.incrementAndGet();
        audit(session.sessionId, session.memberId, session.actionId, "relay", ports(gamePort));
        if(relay.beginPumpPair(label + "-relay") < 0){
            activeRelays.remove(session.sessionId);
            relay.closeAll();
            throw new IOException("Failed to start relay pumps for " + session.sessionId);
        }
    }

    /** Caller holds {@link #sessionAdmissionLock}. */
    private boolean allowMemberUnlocked(String memberId){
        if(sessions.size() >= maxActionSessions) return false;
        if(memberId == null || memberId.isBlank()) return true;
        int count = 0;
        for(Session session : sessions.values()){
            if(memberId.equals(session.memberId) && session.state != SessionState.closed) count++;
        }
        return count < maxSessionsPerMember;
    }

    private void closeSession(String sessionId, String reason){
        Session session;
        synchronized(sessionAdmissionLock){
            session = sessions.remove(sessionId);
            if(session != null) session.state = SessionState.closed;
        }
        ActiveRelay relay = activeRelays.remove(sessionId);
        if(relay != null) relay.closeAll();
        if(session == null) return;
        audit(sessionId, session.memberId, session.actionId, "session-closed", reason);
    }

    private void rejectInline(SocketChannel channel, String reason){
        rejectedConnections.incrementAndGet();
        audit("", "", "", "handshake-reject", reason);
        closeQuietly(channel);
    }

    private void rejectInline(Socket socket, String reason){
        rejectedConnections.incrementAndGet();
        audit("", "", "", "handshake-reject", reason);
        closeQuietly(socket);
    }

    private void audit(String sessionId, String memberId, String actionId, String kind, String detail){
        AuditEvent event = new AuditEvent(sessionId, memberId, actionId, kind, detail);
        if(!auditLog.offer(event)){
            auditLog.poll();
            auditLog.offer(event);
        }
        Log.debug("[SharedCampaignBroker] @", event);
    }

    private static int readInt(byte[] data, int offset){
        return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16) | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }

    private static String remote(Socket socket){
        try{
            return String.valueOf(socket.getRemoteSocketAddress());
        }catch(Exception ignored){
            return "";
        }
    }

    private static String remote(SocketChannel channel){
        try{
            return String.valueOf(channel.getRemoteAddress());
        }catch(Exception ignored){
            return "";
        }
    }

    private static void closeQuietly(Closeable endpoint){
        if(endpoint == null) return;
        try{ endpoint.close(); }catch(IOException ignored){}
    }

    /** Absolute pre-routing handshake deadline; disallows slowloris byte-drip across MYCT/MYCS classification. */
    private static final class DeadlineInputStream extends FilterInputStream{
        private final Socket socket;
        private final long deadlineNanos;

        DeadlineInputStream(Socket socket, InputStream input, long timeoutMillis) throws IOException{
            super(input);
            this.socket = socket;
            this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
            applyDeadline();
        }

        private void applyDeadline() throws IOException{
            long remaining = deadlineNanos - System.nanoTime();
            if(remaining <= 0L) throw new SocketTimeoutException("Shared Campaign entry handshake deadline exceeded");
            long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
            socket.setSoTimeout((int)Math.min(Integer.MAX_VALUE, millis));
        }

        @Override public int read() throws IOException{
            applyDeadline();
            return super.read();
        }

        @Override public int read(byte[] b, int off, int len) throws IOException{
            applyDeadline();
            return super.read(b, off, len);
        }
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException{
        readFully(in, buffer, 0, buffer.length);
    }

    private static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException{
        int read = 0;
        while(read < length){
            int n = in.read(buffer, offset + read, length - read);
            if(n < 0) throw new EOFException("Connection closed during handshake");
            read += n;
        }
    }


}
