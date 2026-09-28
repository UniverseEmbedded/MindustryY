package mindustry.campaign.shared.runtime;

import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.io.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Multiplexed control-plane client. The logical client session survives TCP transport loss: a request keeps the same
 * high-entropy request ID while reconnecting and resending, allowing the coordinator replay cache to return an already
 * committed response without applying a mutation twice.
 */
public class SharedCampaignClient implements Closeable{
    private static final long requestTimeoutMillis = 45_000L;
    /** Hot-switch pause/resume/abort must replay before the broker's preserved-TCP lease expires. */
    private static final long hotSwitchRequestTimeoutMillis = 10_000L;
    private static final int requestAttempts = 3;

    private final String host;
    private final int port;
    private volatile int entryPort = ControlProtocol.noEntryPort;
    private final String memberId;
    private final Credential credential;
    private final byte[] key;
    private final AtomicLong requestIds = new AtomicLong(ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE / 2L));
    private final ConcurrentHashMap<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object transportLock = new Object();
    private final ConcurrentLinkedQueue<Thread> receivers = new ConcurrentLinkedQueue<>();
    private volatile ControlProtocol.Connection connection;
    private volatile SharedCampaignState latestSnapshot;
    /** Serializes revision compare-and-publish across overlapping old/new receiver threads during reconnect. */
    private final Object snapshotLock = new Object();
    private volatile Consumer<SharedCampaignState> snapshotListener = ignored -> {};

    private record PendingRequest(ControlProtocol.Connection transport, CompletableFuture<ControlProtocol.Frame> future){}

    /** Durable identity material issued by the coordinator after invite-authenticated enrollment. */
    public record Credential(String memberId, String secret){
        public Credential{
            if(memberId == null || memberId.isBlank()) throw new IllegalArgumentException("Member ID is required");
            if(secret == null || secret.isBlank()) throw new IllegalArgumentException("Member credential is required");
        }
    }

    /**
     * Enrolls with an invitation and immediately switches to a per-member credential. The caller-supplied value is a
     * display/enrollment hint only; it is never trusted as the authenticated member identity.
     */
    public SharedCampaignClient(String host, int port, String inviteCode, String memberHint) throws IOException{
        this(host, port, enroll(host, port, inviteCode, memberHint));
    }

    /** Connects using already-issued per-member identity material, e.g. the local owner credential. */
    public SharedCampaignClient(String host, int port, Credential credential) throws IOException{
        this.host = host;
        this.port = port;
        this.memberId = credential.memberId();
        this.credential = credential;
        this.key = ControlProtocol.deriveKey(credential.secret());
        installTransport(openTransport());
    }

    public static Credential enroll(String host, int port, String inviteCode, String memberHint) throws IOException{
        byte[] inviteKey = ControlProtocol.deriveKey(inviteCode);
        try(ControlProtocol.Connection enrollment = ControlProtocol.Connection.connect(host, port, ControlProtocol.Role.campaignEnroll, "enroll", inviteKey)){
            long requestId = enrollment.nextRequestId();
            String hint = memberHint == null ? "" : memberHint.trim();
            if(hint.length() > 256) hint = hint.substring(0, 256);
            enrollment.send(ControlProtocol.Type.memberEnrollRequest, requestId, ControlProtocol.strings(hint));
            ControlProtocol.Frame response = enrollment.receive();
            if(response.requestId() != requestId) throw new IOException("Unexpected enrollment response ID");
            if(response.type() == ControlProtocol.Type.error) throw new IOException(RuntimePayloads.decodeString(response.payload()));
            if(response.type() != ControlProtocol.Type.memberEnrollResponse) throw new IOException("Unexpected enrollment response " + response.type());
            String[] fields = ControlProtocol.readStrings(response.payload());
            if(fields.length != 2) throw new IOException("Invalid member enrollment response");
            return new Credential(fields[0], fields[1]);
        }
    }

    /** Registers a snapshot observer and immediately supplies the newest already-received state, if any. */
    public void onSnapshot(Consumer<SharedCampaignState> listener){
        Consumer<SharedCampaignState> installed = listener == null ? ignored -> {} : listener;
        SharedCampaignState current;
        synchronized(snapshotLock){
            snapshotListener = installed;
            current = latestSnapshot == null ? null : SharedCampaignStateCopy.copy(latestSnapshot);
        }
        if(current != null) installed.accept(current);
    }

    public SharedCampaignState snapshot() throws IOException{
        ControlProtocol.Frame frame = request(ControlProtocol.Type.snapshotRequest, new byte[0], ControlProtocol.Type.snapshotResponse);
        SharedCampaignState state = SharedCampaignCodec.decode(frame.payload());
        acceptSnapshot(state);
        return SharedCampaignStateCopy.copy(state);
    }

    public RuntimePayloads.StartResult startAction(String planetName, String sectorName, String missionId) throws IOException{
        return startAction(planetName, sectorName, missionId, RuntimePayloads.LaunchPlan.empty());
    }

    public RuntimePayloads.StartResult startAction(String planetName, String sectorName, String missionId, RuntimePayloads.LaunchPlan launch) throws IOException{
        return RuntimePayloads.startResult(request(ControlProtocol.Type.startActionRequest,
            RuntimePayloads.encode(new RuntimePayloads.StartAction(planetName, sectorName, missionId, launch)), ControlProtocol.Type.startActionResponse).payload());
    }

    public RuntimePayloads.JoinResult joinAction(String actionId, String networkUuid) throws IOException{ return joinAction(actionId, false, networkUuid); }

    public RuntimePayloads.JoinResult joinAction(String actionId, boolean spectator, String networkUuid) throws IOException{
        if(networkUuid == null || networkUuid.isBlank()) throw new IOException("Mindustry network UUID is required for action admission");
        RuntimePayloads.JoinResult result = RuntimePayloads.joinResult(request(ControlProtocol.Type.joinActionRequest,
            RuntimePayloads.encode(new RuntimePayloads.JoinAction(actionId, spectator, networkUuid)), ControlProtocol.Type.joinActionResponse).payload());
        if(result.error() != null && !result.error().isBlank()){
            return new RuntimePayloads.JoinResult(result.actionId(), "", 0, "", result.error());
        }
        int sharedPort = entryPort;
        if(sharedPort <= 0){
            return new RuntimePayloads.JoinResult(result.actionId(), "", 0, "", "Coordinator did not advertise the shared Action entry port");
        }
        // Never expose the loopback/TCP-only Action listener returned by the authority. Every member-facing game
        // endpoint is the same authenticated coordinator socket used by this control client.
        int actualPort = -1;
        SharedCampaignState snapshot = latestSnapshot;
        if(snapshot != null){
            SharedCampaignState.ActionState action = snapshot.actions.get(result.actionId());
            if(action != null) actualPort = action.port;
        }
        Log.info("Shared action join ready: action=@ host=@ ports=@",
            result.actionId(), host, "entry@actual=" + mindustry.y.util.YPortLog.entryAtActual(sharedPort, actualPort));
        return new RuntimePayloads.JoinResult(result.actionId(), host, sharedPort, result.joinToken(), "");
    }

    /**
     * Asks the coordinator to pause the exact live entry relay and prepare {@code toActionId} on the same TCP.
     * The product caller detaches Arc and begins draining the preserved socket before this request, preventing a full
     * client receive window from blocking the old world→client pump while the coordinator establishes the barrier.
     */
    public RuntimePayloads.HotSwitchResult hotSwitchAction(String sessionId, String fromActionId, String toActionId, boolean spectator, String networkUuid) throws IOException{
        if(sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if(networkUuid == null || networkUuid.isBlank()) throw new IOException("Mindustry network UUID is required for action admission");
        if(fromActionId == null || fromActionId.isBlank()) throw new IllegalArgumentException("fromActionId is required");
        if(toActionId == null || toActionId.isBlank()) throw new IllegalArgumentException("toActionId is required");
        RuntimePayloads.HotSwitchResult result = RuntimePayloads.hotSwitchResult(request(ControlProtocol.Type.hotSwitchRequest,
            RuntimePayloads.encode(new RuntimePayloads.HotSwitchRequest(sessionId, fromActionId, toActionId, spectator, networkUuid)),
            ControlProtocol.Type.hotSwitchResponse, hotSwitchRequestTimeoutMillis).payload());
        if(result.error() != null && !result.error().isBlank()){
            return new RuntimePayloads.HotSwitchResult(result.sessionId(), result.actionId(), "", 0, "", result.sameTcp(), result.error());
        }
        int sharedPort = entryPort;
        if(sharedPort <= 0){
            return new RuntimePayloads.HotSwitchResult(result.sessionId(), result.actionId(), "", 0, "", false,
                "Coordinator did not advertise the shared Action entry port");
        }
        // Even the ordinary-join fallback after a failed same-TCP attempt must go back through the public shared
        // entry. Never surface the destination world's loopback listener returned by the coordinator.
        return new RuntimePayloads.HotSwitchResult(result.sessionId(), result.actionId(), host, sharedPort, result.joinToken(), result.sameTcp(), "");
    }

    /** After Arc detach: resume the paused entry relay for this session onto the destination action. */
    public boolean resumeHotSwitch(String sessionId) throws IOException{
        if(sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        ControlProtocol.Frame frame = request(ControlProtocol.Type.hotSwitchResumeRequest,
            RuntimePayloads.encodeString(sessionId), ControlProtocol.Type.hotSwitchResumeResponse, hotSwitchRequestTimeoutMillis);
        return RuntimePayloads.decodeBoolean(frame.payload());
    }

    /** Cancels a paused same-TCP switch and closes its preserved game transport. */
    public boolean abortHotSwitch(String sessionId) throws IOException{
        if(sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        ControlProtocol.Frame frame = request(ControlProtocol.Type.hotSwitchAbortRequest,
            RuntimePayloads.encodeString(sessionId), ControlProtocol.Type.hotSwitchAbortResponse, hotSwitchRequestTimeoutMillis);
        return RuntimePayloads.decodeBoolean(frame.payload());
    }

    public SharedCampaignState research(String contentName) throws IOException{
        return research(contentName, "");
    }

    public SharedCampaignState research(String contentName, String planetName) throws IOException{
        SharedCampaignState state = SharedCampaignCodec.decode(request(ControlProtocol.Type.researchRequest,
            RuntimePayloads.encode(new RuntimePayloads.ResearchRequest(contentName, planetName)), ControlProtocol.Type.researchResponse).payload());
        acceptSnapshot(state); return SharedCampaignStateCopy.copy(state);
    }

    public SharedCampaignState updateSettings(int maxActiveActions, boolean freezeWhenEmpty, boolean multiFrontEnabled, SharedCampaignState.InvitePolicy invitePolicy,
                                              SharedCampaignState.PersistenceProfile persistenceProfile) throws IOException{
        SharedCampaignState state = SharedCampaignCodec.decode(request(ControlProtocol.Type.campaignSettingsRequest,
            RuntimePayloads.encode(new RuntimePayloads.CampaignSettings(maxActiveActions, freezeWhenEmpty, multiFrontEnabled, invitePolicy, persistenceProfile)), ControlProtocol.Type.campaignSettingsResponse).payload());
        acceptSnapshot(state); return SharedCampaignStateCopy.copy(state);
    }

    /** Compatibility overload for callers that do not change the persistence profile. */
    public SharedCampaignState updateSettings(int maxActiveActions, boolean freezeWhenEmpty, boolean multiFrontEnabled, SharedCampaignState.InvitePolicy invitePolicy) throws IOException{
        SharedCampaignState current = latestSnapshot;
        SharedCampaignState.PersistenceProfile profile = current == null || current.persistenceProfile == null
            ? SharedCampaignState.PersistenceProfile.lowFrequencyWal : current.persistenceProfile;
        return updateSettings(maxActiveActions, freezeWhenEmpty, multiFrontEnabled, invitePolicy, profile);
    }

    public String requestInviteCode() throws IOException{
        return RuntimePayloads.decodeString(request(ControlProtocol.Type.inviteCodeRequest, new byte[0], ControlProtocol.Type.inviteCodeResponse).payload());
    }

    /** Owner-only: invalidates every previously issued invite code and returns the new invitation secret. */
    public String rotateInviteCode() throws IOException{
        return RuntimePayloads.decodeString(request(ControlProtocol.Type.inviteRotateRequest, new byte[0], ControlProtocol.Type.inviteRotateResponse).payload());
    }

    /** Removes a durable campaign membership. Members may remove themselves; only the owner may remove another member. */
    public SharedCampaignState removeMember(String targetMemberId) throws IOException{
        SharedCampaignState state = SharedCampaignCodec.decode(request(ControlProtocol.Type.memberRemoveRequest,
            RuntimePayloads.encodeString(targetMemberId == null ? "" : targetMemberId), ControlProtocol.Type.memberRemoveResponse).payload());
        acceptSnapshot(state);
        return SharedCampaignStateCopy.copy(state);
    }

    public SharedCampaignState updateSectorLogistics(String sourceSector, String destinationSector) throws IOException{
        SharedCampaignState state = SharedCampaignCodec.decode(request(ControlProtocol.Type.sectorLogisticsRequest,
            RuntimePayloads.encode(new RuntimePayloads.SectorLogistics(sourceSector, destinationSector)), ControlProtocol.Type.sectorLogisticsResponse).payload());
        acceptSnapshot(state); return SharedCampaignStateCopy.copy(state);
    }

    public void suspendAction(String actionId) throws IOException{
        request(ControlProtocol.Type.suspendActionRequest, RuntimePayloads.encodeString(actionId), ControlProtocol.Type.suspendActionResponse);
    }

    private ControlProtocol.Frame request(ControlProtocol.Type requestType, byte[] payload, ControlProtocol.Type responseType) throws IOException{
        return request(requestType, payload, responseType, requestTimeoutMillis);
    }

    private ControlProtocol.Frame request(ControlProtocol.Type requestType, byte[] payload, ControlProtocol.Type responseType, long timeoutMillis) throws IOException{
        if(!running.get()) throw new EOFException("Shared campaign control client is closed");
        long requestId = requestIds.getAndIncrement();
        IOException last = null;
        for(int attempt = 0; attempt < requestAttempts; attempt++){
            ControlProtocol.Connection transport = ensureTransport();
            CompletableFuture<ControlProtocol.Frame> future = new CompletableFuture<>();
            PendingRequest request = new PendingRequest(transport, future);
            pending.put(requestId, request);
            try{
                transport.send(requestType, requestId, payload);
                ControlProtocol.Frame frame = future.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
                if(frame.type() == ControlProtocol.Type.error) throw new IOException(RuntimePayloads.decodeString(frame.payload()));
                if(frame.type() != responseType) throw new IOException("Expected " + responseType + ", got " + frame.type());
                return frame;
            }catch(InterruptedException error){
                Thread.currentThread().interrupt(); throw new InterruptedIOException("Interrupted while waiting for shared campaign coordinator");
            }catch(TimeoutException error){
                last = new SocketTimeoutException("Timed out waiting for shared campaign coordinator response");
                invalidateTransport(transport);
            }catch(ExecutionException error){
                Throwable cause = error.getCause();
                last = cause instanceof IOException io ? io : new IOException("Shared campaign control request failed", cause);
                invalidateTransport(transport);
            }catch(IOException error){
                last = error;
                invalidateTransport(transport);
            }finally{
                pending.remove(requestId, request);
            }
            if(attempt + 1 < requestAttempts){
                try{ Thread.sleep(50L << attempt); }
                catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); throw new InterruptedIOException("Interrupted before shared campaign reconnect"); }
            }
        }
        throw last == null ? new EOFException("Shared campaign control request failed") : last;
    }

    private ControlProtocol.Connection ensureTransport() throws IOException{
        if(!running.get()) throw new EOFException("Shared campaign control client is closed");
        ControlProtocol.Connection current = connection;
        if(current != null) return current;
        synchronized(transportLock){
            if(!running.get()) throw new EOFException("Shared campaign control client is closed");
            if(connection == null) installTransport(openTransport());
            return connection;
        }
    }

    private ControlProtocol.Connection openTransport() throws IOException{
        ControlProtocol.Connection opened = ControlProtocol.Connection.connect(host, port, ControlProtocol.Role.campaignClient, memberId, key);
        // MYCT and MYCS are deliberately multiplexed on the exact same public listener. The server can only know its
        // local bind port, which may differ from the externally dialed port behind NAT/FRP. Treat a non-zero advertised
        // entry value as the capability bit, but route game joins back to the endpoint this client actually reached.
        entryPort = opened.entryPort > ControlProtocol.noEntryPort ? port : ControlProtocol.noEntryPort;
        opened.setReadTimeout(0);
        return opened;
    }

    /** Public Shared Action entry as reached by this client; zero means this peer cannot join Shared Actions. */
    public int entryPort(){ return entryPort; }

    private void installTransport(ControlProtocol.Connection opened){
        connection = opened;
        Thread receiver = new Thread(() -> receiveLoop(opened), "shared-campaign-client-reader-" + Long.toUnsignedString(requestIds.get()));
        receiver.setDaemon(true); receivers.add(receiver); receiver.start();
    }

    private void invalidateTransport(ControlProtocol.Connection failed){
        synchronized(transportLock){
            if(connection == failed) connection = null;
        }
        try{ failed.close(); }catch(IOException ignored){}
    }

    private void receiveLoop(ControlProtocol.Connection owned){
        Throwable terminal = null;
        try{
            while(running.get() && connection == owned){
                ControlProtocol.Frame frame = owned.receive();
                if(frame.requestId() == 0L && frame.type() == ControlProtocol.Type.snapshotResponse){
                    acceptSnapshot(SharedCampaignCodec.decode(frame.payload()));
                    continue;
                }
                PendingRequest request = pending.get(frame.requestId());
                if(request != null && request.transport() == owned) request.future().complete(frame);
            }
        }catch(Throwable error){ terminal = error; }
        finally{
            synchronized(transportLock){ if(connection == owned) connection = null; }
            IOException failure = terminal instanceof IOException io ? io : new IOException("Shared campaign control connection closed", terminal);
            for(var entry : pending.entrySet()){
                PendingRequest request = entry.getValue();
                if(request.transport() == owned && pending.remove(entry.getKey(), request)) request.future().completeExceptionally(failure);
            }
            try{ owned.close(); }catch(IOException ignored){}
        }
    }

    private void acceptSnapshot(SharedCampaignState state){
        if(state == null) return;
        SharedCampaignState copy = SharedCampaignStateCopy.copy(state);
        Consumer<SharedCampaignState> listener;
        synchronized(snapshotLock){
            SharedCampaignState previous = latestSnapshot;
            if(previous != null && previous.campaignId.equals(copy.campaignId) && previous.revision > copy.revision) return;
            latestSnapshot = copy;
            listener = snapshotListener;
        }
        try{ listener.accept(SharedCampaignStateCopy.copy(copy)); }
        catch(Throwable error){ Log.err("Shared campaign snapshot listener failed", error); }
    }

    public String host(){ return host; }
    public int port(){ return port; }
    public String memberId(){ return memberId; }
    public Credential credential(){ return credential; }

    @Override public void close() throws IOException{
        if(!running.compareAndSet(true, false)) return;
        ControlProtocol.Connection current;
        synchronized(transportLock){ current = connection; connection = null; }
        if(current != null) current.close();
        EOFException closed = new EOFException("Shared campaign control client is closed");
        for(var entry : pending.entrySet()) if(pending.remove(entry.getKey(), entry.getValue())) entry.getValue().future().completeExceptionally(closed);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for(Thread receiver : receivers){
            long remaining = deadline - System.nanoTime(); if(remaining <= 0) break;
            try{ receiver.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))); }
            catch(InterruptedException error){ Thread.currentThread().interrupt(); break; }
        }
    }
}
