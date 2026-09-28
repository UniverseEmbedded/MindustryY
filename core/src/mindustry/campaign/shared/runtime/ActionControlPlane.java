package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.io.*;
import mindustry.runtime.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Coordinator-side authenticated control channel for live Action runtimes.
 *
 * <p>This layer deliberately accepts {@link ControlProtocol.Role#actionHost} only. Campaign-client mutations and the
 * public MYCS entry broker are separate product surfaces; this class owns the authority edge between a committed
 * Action record and the runtime that proves its generation/incarnation, heartbeats, strategic state and final save.</p>
 */
public final class ActionControlPlane implements Closeable{
    public static final long heartbeatCommitIntervalMillis = 15_000L;

    @FunctionalInterface public interface LossPolicy{ boolean clearSectorOnLoss(ActionState action); }

    @FunctionalInterface public interface VanillaTransferHandler{
        RuntimePayloads.VanillaTransferResult prepare(String sourceActionId, RuntimePayloads.VanillaTransferRequest request);
    }

    public interface Listener{
        default void snapshotCommitted(SharedCampaignState state){}
        default void routeAvailable(String actionId, int gamePort){}
        default void routeUnavailable(String actionId){}
        default void actionBecameRunning(ActionState action){}
        default void actionStopped(ActionState action){}
        default void actionIncompatible(ActionState action){}
    }

    private final GameContext owner;
    private final SharedCampaignStore store;
    private final CoordinatorCredentials credentials;
    private final SharedCampaignMissionRegistry missionDefinitions;
    private final LossPolicy lossPolicy;
    private final Listener listener;
    private volatile VanillaTransferHandler vanillaTransferHandler;
    private final int requestedPort;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ConcurrentHashMap<String, ControlProtocol.Connection> actionConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastHeartbeatCommit = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastHeartbeatSeen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<ControlProtocol.Frame>> pending = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor readers;
    private volatile ServerSocket server;
    private volatile Thread acceptThread;

    public ActionControlPlane(GameContext owner, SharedCampaignStore store, CoordinatorCredentials credentials,
                              SharedCampaignMissionRegistry missionDefinitions, int controlPort,
                              LossPolicy lossPolicy, Listener listener){
        this.owner = Objects.requireNonNull(owner, "owner");
        this.store = Objects.requireNonNull(store, "store");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.missionDefinitions = Objects.requireNonNull(missionDefinitions, "missionDefinitions");
        this.requestedPort = controlPort;
        this.lossPolicy = lossPolicy == null ? action -> false : lossPolicy;
        this.listener = listener == null ? new Listener(){} : listener;
        int threads = Math.max(2, Integer.getInteger("mindustry.sharedCampaign.control.actionThreads", 16));
        int queue = Math.max(threads, Integer.getInteger("mindustry.sharedCampaign.control.actionQueue", 32));
        AtomicInteger ids = new AtomicInteger();
        readers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queue), runnable -> {
                Thread thread = new Thread(RuntimeContexts.capture(owner, runnable), "shared-action-control-" + ids.incrementAndGet());
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized void start(){
        if(!running.compareAndSet(false, true)) return;
        try{
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(requestedPort));
            server = socket;
        }catch(IOException error){
            running.set(false);
            throw new UncheckedIOException(error);
        }
        acceptThread = new Thread(RuntimeContexts.capture(owner, this::acceptLoop), "shared-action-control-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public boolean isRunning(){ return running.get(); }
    public int port(){ ServerSocket socket = server; return socket == null ? requestedPort : socket.getLocalPort(); }
    public long lastHeartbeatSeen(String actionId, long runtimeIncarnation){ return lastHeartbeatSeen.getOrDefault(heartbeatKey(actionId, runtimeIncarnation), 0L); }
    public boolean connected(String actionId){ return actionConnections.containsKey(actionId); }

    public void vanillaTransferHandler(VanillaTransferHandler handler){ this.vanillaTransferHandler = handler; }

    /** Correlated coordinator→Action request used by suspend/research/transport orchestration. */
    public ControlProtocol.Frame request(String actionId, ControlProtocol.Type requestType, byte[] payload,
                                         ControlProtocol.Type responseType, long timeoutMillis){
        ControlProtocol.Connection connection = actionConnections.get(actionId);
        if(connection == null) throw new IllegalStateException("Action host is not connected: " + actionId);
        long requestId = connection.nextRequestId();
        String key = pendingKey(actionId, requestId);
        CompletableFuture<ControlProtocol.Frame> future = new CompletableFuture<>();
        if(pending.putIfAbsent(key, future) != null) throw new IllegalStateException("Duplicate Action request ID");
        try{
            connection.send(requestType, requestId, payload == null ? new byte[0] : payload);
            ControlProtocol.Frame frame = future.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
            if(frame.type() == ControlProtocol.Type.error){
                throw new IllegalStateException(RuntimePayloads.decodeString(frame.payload()));
            }
            if(frame.type() != responseType) throw new IllegalStateException("Unexpected Action response " + frame.type() + ", expected " + responseType);
            return frame;
        }catch(TimeoutException error){
            throw new IllegalStateException("Timed out waiting for Action " + actionId + " response to " + requestType, error);
        }catch(ExecutionException error){
            Throwable cause = error.getCause();
            if(cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Action request failed", cause);
        }catch(InterruptedException error){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for Action response", error);
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }finally{
            pending.remove(key, future);
        }
    }

    private void acceptLoop(){
        while(running.get()){
            try{
                Socket socket = server.accept();
                try{
                    readers.execute(() -> handle(socket));
                }catch(RejectedExecutionException overloaded){
                    try{ socket.close(); }catch(IOException ignored){}
                    if(running.get()) Log.warn("Shared Action control reader capacity reached");
                }
            }catch(IOException error){
                if(running.get()) Log.err("Shared Action control accept failed", error);
            }
        }
    }

    private void handle(Socket socket){
        ControlProtocol.Accepted accepted = null;
        try{
            accepted = ControlProtocol.Connection.accept(socket, null, (role, identity) ->
                role == ControlProtocol.Role.actionHost ? credentials.actionKey(identity) : null, ControlProtocol.noEntryPort);
            if(accepted.role() != ControlProtocol.Role.actionHost) throw new SecurityException("Action-host control connection required");
            try(ControlProtocol.Connection connection = accepted.connection()){
                while(running.get()) handleFrame(accepted.identity(), connection, connection.receive());
            }
        }catch(SocketTimeoutException | EOFException ignored){
        }catch(Exception error){
            if(running.get()){
                //error.getMessage() is null for whole exception families (ClosedChannelException, CME, no-message NPE);
                //log the type unconditionally and keep the stack so an abnormal close is attributable.
                Log.warn("Shared Action control connection closed: @", error.toString());
                Log.err(error);
            }
        }finally{
            if(accepted != null){
                actionConnections.remove(accepted.identity(), accepted.connection());
                listener.routeUnavailable(accepted.identity());
                failPendingFor(accepted.identity(), new EOFException("Action control connection closed"));
            }
            try{ socket.close(); }catch(IOException ignored){}
        }
    }

    private void handleFrame(String identity, ControlProtocol.Connection connection, ControlProtocol.Frame frame) throws IOException{
        if(frame.type() != ControlProtocol.Type.actionHello && actionConnections.get(identity) != connection){
            throw new SecurityException("Stale Action control connection");
        }
        if(frame.requestId() != 0L && isResponse(frame.type())){
            CompletableFuture<ControlProtocol.Frame> future = pending.get(pendingKey(identity, frame.requestId()));
            if(future != null){ future.complete(frame); return; }
        }
        try{
            switch(frame.type()){
                case actionHello -> handleHello(identity, connection, frame);
                case actionHeartbeat -> handleHeartbeat(identity, RuntimePayloads.heartbeat(frame.payload()));
                case actionStateUpdate -> handleStateUpdate(identity, RuntimePayloads.stateUpdate(frame.payload()));
                case actionSaveCommitted -> handleCapture(identity, connection, frame);
                case actionStopped -> handleStopped(identity, connection, frame);
                case snapshotRequest -> connection.send(ControlProtocol.Type.snapshotResponse, frame.requestId(), SharedCampaignCodec.encodeStrategic(store.strategicSnapshot()));
                case vanillaTransferRequest -> {
                    VanillaTransferHandler handler = vanillaTransferHandler;
                    if(handler == null) throw new IllegalStateException("Vanilla compatibility transfer handler is unavailable");
                    RuntimePayloads.VanillaTransferRequest request = RuntimePayloads.vanillaTransferRequest(frame.payload());
                    if(!identity.equals(request.fromActionId())) throw new SecurityException("Vanilla transfer source Action mismatch");
                    RuntimePayloads.VanillaTransferResult result = handler.prepare(identity, request);
                    connection.send(ControlProtocol.Type.vanillaTransferResponse, frame.requestId(), RuntimePayloads.encode(result));
                }
                case ping -> {
                    renewStartingLease(identity);
                    connection.send(ControlProtocol.Type.pong, frame.requestId(), frame.payload());
                }
                default -> throw new IOException("Unexpected Action control frame " + frame.type());
            }
        }catch(Exception error){
            connection.send(ControlProtocol.Type.error, frame.requestId(), RuntimePayloads.encodeString(error.getMessage() == null ? error.toString() : error.getMessage()));
        }
    }

    private void handleHello(String identity, ControlProtocol.Connection connection, ControlProtocol.Frame frame) throws IOException{
        RuntimePayloads.ActionHello hello = RuntimePayloads.actionHello(frame.payload());
        long now = Time.millis();
        SharedCampaignState committed = store.transact("action:" + identity, "shared-campaign:action-online",
            state -> ActionAuthorityTransitions.applyHello(state, identity, hello, now));
        ActionState action = ActionAuthorityTransitions.requireAction(committed, identity);
        if(action.status == ActionStatus.incompatible){
            connection.send(ControlProtocol.Type.error, frame.requestId(), RuntimePayloads.encodeString(action.failureReason));
            credentials.deleteActionSecrets(identity);
            listener.actionIncompatible(action);
            listener.snapshotCommitted(committed);
            return;
        }

        ControlProtocol.Connection previous = actionConnections.put(identity, connection);
        if(previous != null && previous != connection){ try{ previous.close(); }catch(IOException ignored){} }
        connection.send(ControlProtocol.Type.actionHello, frame.requestId(), RuntimePayloads.encodeString(identity));
        connection.send(ControlProtocol.Type.snapshotResponse, 0L, SharedCampaignCodec.encodeStrategic(committed));
        listener.routeAvailable(identity, hello.gamePort());
        listener.snapshotCommitted(committed);
    }

    private void handleHeartbeat(String identity, RuntimePayloads.ActionHeartbeat heartbeat){
        long now = Time.millis();
        SharedCampaignStore.ActionAuthorityView view = store.actionAuthority(identity);
        ActionState current = view.action();
        if(current == null) throw new IllegalArgumentException("Unknown action " + identity);
        if(!current.status.isLive()) throw new SecurityException("Heartbeat came from a non-live Action: " + current.status);
        if(current.hostGeneration != heartbeat.generation() || view.authorityGeneration() != heartbeat.generation()
            || current.runtimeIncarnation != heartbeat.runtimeIncarnation()) throw new SecurityException("Stale action heartbeat runtime identity");
        if(heartbeat.actionTick() < current.actionTick) throw new SecurityException("Stale action heartbeat tick");
        String key = heartbeatKey(identity, heartbeat.runtimeIncarnation());
        lastHeartbeatSeen.put(key, now);
        Long last = lastHeartbeatCommit.get(key);
        boolean becameRunning = current.status == ActionStatus.starting;
        boolean presenceChanged = !ActionAuthorityTransitions.sameMembers(current.participants, heartbeat.participants())
            || !ActionAuthorityTransitions.sameMembers(current.spectators, heartbeat.spectators());
        if(!becameRunning && !presenceChanged && last != null && now - last < heartbeatCommitIntervalMillis) return;

        final boolean[] promoted = {false};
        SharedCampaignState committed = store.transactCoalesced("action:" + identity, "shared-campaign:action-heartbeat", state ->
            promoted[0] = ActionAuthorityTransitions.applyHeartbeat(state, identity, heartbeat, now));
        lastHeartbeatCommit.put(key, now);
        listener.snapshotCommitted(committed);
        if(promoted[0]) listener.actionBecameRunning(ActionAuthorityTransitions.requireAction(committed, identity));
    }

    private void handleStateUpdate(String identity, RuntimePayloads.ActionStateUpdate update){
        long now = Time.millis();
        SharedCampaignState committed = store.transactCoalesced("action:" + identity, "shared-campaign:action-state-update",
            state -> ActionAuthorityTransitions.applyStateUpdate(state, identity, update, missionDefinitions, now));
        listener.snapshotCommitted(committed);
    }

    private void handleCapture(String identity, ControlProtocol.Connection connection, ControlProtocol.Frame frame) throws IOException{
        RuntimePayloads.ActionStopped captured = RuntimePayloads.stopped(frame.payload());
        verifyCleanSave(identity, captured);
        long now = Time.millis();
        SharedCampaignState committed = store.transact("action:" + identity, "shared-campaign:action-captured",
            state -> ActionAuthorityTransitions.applyCaptured(state, identity, captured, now));
        connection.send(ControlProtocol.Type.actionSaveCommitted, frame.requestId(), new byte[0]);
        listener.snapshotCommitted(committed);
    }

    private void handleStopped(String identity, ControlProtocol.Connection connection, ControlProtocol.Frame frame) throws IOException{
        RuntimePayloads.ActionStopped stopped = RuntimePayloads.stopped(frame.payload());
        if(stopped.clean()) verifyCleanSave(identity, stopped);
        SharedCampaignState before = store.snapshot();
        ActionState beforeAction = ActionAuthorityTransitions.requireAction(before, identity);
        boolean clearOnLoss = lossPolicy.clearSectorOnLoss(beforeAction);
        long now = Time.millis();
        SharedCampaignState committed = store.transact("action:" + identity,
            stopped.clean() ? "shared-campaign:action-stopped-clean" : "shared-campaign:action-failed",
            state -> ActionAuthorityTransitions.applyStopped(state, identity, stopped, clearOnLoss, now));
        connection.send(ControlProtocol.Type.actionStopped, frame.requestId(), new byte[0]);
        clearHeartbeatTracking(identity);
        credentials.deleteActionSecrets(identity);
        actionConnections.remove(identity, connection);
        listener.routeUnavailable(identity);
        ActionState action = ActionAuthorityTransitions.requireAction(committed, identity);
        listener.snapshotCommitted(committed);
        listener.actionStopped(action);
    }

    private void verifyCleanSave(String identity, RuntimePayloads.ActionStopped payload){
        SharedCampaignState snapshot = store.snapshot();
        ActionState action = ActionAuthorityTransitions.requireAction(snapshot, identity);
        if(action.hostGeneration != payload.generation() || action.runtimeIncarnation != payload.runtimeIncarnation()){
            throw new SecurityException("Stale Action save runtime identity");
        }
        Fi save = store.root().child(action.saveRelativePath);
        if(!save.exists()) throw new IllegalStateException("Action reported a clean save without a save file");
        String actual = SharedFileDigests.sha256(save);
        String claimed = payload.saveHash() == null ? "" : payload.saveHash();
        if(claimed.isBlank() || !MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), claimed.getBytes(StandardCharsets.US_ASCII))){
            throw new SecurityException("Action save hash mismatch");
        }
    }

    private void renewStartingLease(String identity){
        SharedCampaignStore.ActionAuthorityView view = store.actionAuthority(identity);
        ActionState action = view.action();
        if(action == null || action.status != ActionStatus.starting) return;
        long now = Time.millis();
        store.transactCoalesced("action:" + identity, "shared-campaign:action-starting-keepalive", state -> {
            ActionState current = state.actions.get(identity);
            if(current != null && current.status == ActionStatus.starting && current.hostGeneration == state.authorityGeneration){
                current.leaseExpiresAt = now + ActionAuthorityTransitions.leaseDurationMillis;
                current.updatedAt = now;
            }
        });
    }

    private static boolean isResponse(ControlProtocol.Type type){
        return switch(type){
            case researchPrepareResponse, researchDecisionResponse, snapshotApplyResponse, suspendActionResponse,
                 transportPrepareResponse, transportDecisionResponse, actionLogisticsResponse,
                 vanillaAdmissionPrepareResponse, vanillaAdmissionRevokeResponse, actionSaveNowResponse, error -> true;
            default -> false;
        };
    }

    private void failPendingFor(String actionId, Throwable failure){
        String prefix = actionId + ":";
        for(Map.Entry<String, CompletableFuture<ControlProtocol.Frame>> entry : pending.entrySet()){
            if(entry.getKey().startsWith(prefix)) entry.getValue().completeExceptionally(failure);
        }
    }

    private void clearHeartbeatTracking(String actionId){
        String prefix = actionId + "#";
        lastHeartbeatSeen.keySet().removeIf(key -> key.startsWith(prefix));
        lastHeartbeatCommit.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static String pendingKey(String actionId, long requestId){ return actionId + ":" + requestId; }
    private static String heartbeatKey(String actionId, long runtimeIncarnation){ return actionId + "#" + runtimeIncarnation; }

    @Override public synchronized void close(){
        if(!running.getAndSet(false)) return;
        ServerSocket socket = server; server = null;
        if(socket != null) try{ socket.close(); }catch(IOException ignored){}
        Thread accept = acceptThread; acceptThread = null;
        if(accept != null) accept.interrupt();
        for(ControlProtocol.Connection connection : actionConnections.values()) try{ connection.close(); }catch(IOException ignored){}
        actionConnections.clear();
        for(CompletableFuture<ControlProtocol.Frame> future : pending.values()) future.completeExceptionally(new EOFException("Action control plane closed"));
        pending.clear();
        readers.shutdownNow();
    }
}
