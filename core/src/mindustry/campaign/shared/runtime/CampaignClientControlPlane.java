package mindustry.campaign.shared.runtime;

import mindustry.campaign.shared.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.api.*;
import mindustry.runtime.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import arc.struct.*;
import arc.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Single public Shared Campaign entry for authenticated campaign clients plus MYCS Action sessions. */
public final class CampaignClientControlPlane implements Closeable{
    private final GameContext owner;
    private final SharedCampaignStore store;
    private final CoordinatorCredentials credentials;
    private final CampaignActionCommands commands;
    private final SharedCampaignPlanetRegistry planetPolicies;
    private final SharedActionEntryRouter entry;
    private final int requestedPort;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService clients;
    /** Serial, coalescing writer for unsolicited campaign snapshot pushes. Never block a durable store commit on a slow client. */
    private final ExecutorService snapshotPush;
    private final Set<ControlProtocol.Connection> campaignConnections = ConcurrentHashMap.newKeySet();
    private final AtomicReference<SharedCampaignState> pendingSnapshot = new AtomicReference<>();
    private final AtomicBoolean snapshotPushScheduled = new AtomicBoolean();
    private final Semaphore clientSlots;
    private final Object enrollmentMutex = new Object();
    private final int maxCampaignMembers = Math.max(2, Integer.getInteger("mindustry.sharedCampaign.maxMembers", 256));
    private volatile ServerSocket server;
    private volatile Thread acceptThread;

    public CampaignClientControlPlane(GameContext owner, SharedCampaignStore store, CoordinatorCredentials credentials,
                                      CampaignActionCommands commands, SharedCampaignPlanetRegistry planetPolicies,
                                      SharedActionEntryRouter entry, int requestedPort){
        this.owner = Objects.requireNonNull(owner, "owner");
        this.store = Objects.requireNonNull(store, "store");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.planetPolicies = Objects.requireNonNull(planetPolicies, "planetPolicies");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.requestedPort = requestedPort;
        AtomicInteger ids = new AtomicInteger();
        int threads = Math.max(2, Integer.getInteger("mindustry.sharedCampaign.control.clientThreads", 16));
        this.clients = Executors.newFixedThreadPool(threads, r -> { Thread t = new Thread(RuntimeContexts.capture(owner, r), "shared-campaign-client-" + ids.incrementAndGet()); t.setDaemon(true); return t; });
        this.snapshotPush = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(RuntimeContexts.capture(owner, r), "shared-campaign-snapshot-push"); t.setDaemon(true); return t; });
        this.clientSlots = new Semaphore(Math.max(1, Math.min(24, Integer.getInteger("mindustry.sharedCampaign.control.maxCampaignClients", 24))));
    }

    public synchronized void start(){
        if(!running.compareAndSet(false, true)) return;
        try{
            ServerSocket socket = new ServerSocket(); socket.setReuseAddress(true); socket.bind(new InetSocketAddress(requestedPort)); server = socket;
            entry.broker().bindEntryPort(socket.getLocalPort());
            entry.controlBranch(this::acceptControl);
            acceptThread = new Thread(RuntimeContexts.capture(owner, this::acceptLoop), "shared-campaign-entry-accept");
            acceptThread.setDaemon(true); acceptThread.start();
        }catch(IOException error){ running.set(false); throw new UncheckedIOException(error); }
    }

    public boolean isRunning(){ return running.get(); }
    public int port(){ ServerSocket value = server; return value == null ? requestedPort : value.getLocalPort(); }

    private void acceptLoop(){
        while(running.get()){
            try{ entry.handleAccepted(server.accept(), false); }
            catch(IOException error){ if(running.get()) throw new UncheckedIOException(error); }
        }
    }

    private void acceptControl(Socket socket, byte[] preRead) throws Exception{
        ControlProtocol.Accepted accepted = ControlProtocol.Connection.accept(socket, preRead, (role, identity) -> {
            if(role == ControlProtocol.Role.campaignClient) return activeMemberKey(identity);
            if(role == ControlProtocol.Role.campaignEnroll && "enroll".equals(identity)) return credentials.inviteKey();
            return null;
        }, port());
        if(accepted.role() == ControlProtocol.Role.campaignEnroll){
            handleEnrollment(accepted.connection());
            return;
        }
        if(accepted.role() != ControlProtocol.Role.campaignClient) throw new SecurityException("Campaign client connection required");
        String memberId = accepted.identity();
        if(activeMemberKey(memberId) == null) throw new SecurityException("Unknown or revoked campaign member");
        // Campaign-client connections are long-lived push subscriptions. The generic control transport defaults to a
        // bounded read timeout so abandoned request/response peers do not occupy readers forever, but applying that
        // timeout here silently drops an otherwise healthy remote lobby after 45 seconds of inactivity. The client
        // only reconnects on its next explicit request, so unsolicited snapshot pushes would then stop indefinitely.
        // Authentication has already completed and the connection is capacity-bounded below; keep this channel open
        // until EOF/revocation/shutdown so durable/runtime snapshot pushes remain genuinely push-driven.
        accepted.connection().setReadTimeout(0);
        if(!clientSlots.tryAcquire()){
            accepted.connection().close();
            throw new IOException("Shared Campaign client capacity reached");
        }
        try{
            clients.execute(() -> { try{ serve(memberId, accepted.connection()); } finally{ clientSlots.release(); } });
        }catch(RejectedExecutionException rejected){
            clientSlots.release();
            accepted.connection().close();
            throw rejected;
        }
    }

    private byte[] activeMemberKey(String memberId){
        if(memberId == null || memberId.isBlank()) return null;
        if(!store.strategicSnapshot().members.containsKey(memberId)) return null;
        return credentials.memberKey(memberId);
    }

    private void handleEnrollment(ControlProtocol.Connection connection) throws Exception{
        try(connection){
            ControlProtocol.Frame frame = connection.receive();
            if(frame.type() != ControlProtocol.Type.memberEnrollRequest) throw new IOException("Member enrollment request required");
            String[] fields = ControlProtocol.readStrings(frame.payload());
            if(fields.length != 1) throw new IOException("Invalid member enrollment payload");
            String displayName = fields[0] == null ? "" : fields[0].trim();
            if(displayName.length() > 128) displayName = displayName.substring(0, 128);
            if(displayName.isBlank()) displayName = "Member";
            final String cleanName = displayName;

            CoordinatorCredentials.MemberCredential issued = enrollTrustedLocal(cleanName);
            connection.send(ControlProtocol.Type.memberEnrollResponse, frame.requestId(), ControlProtocol.strings(issued.memberId(), issued.secret()));
        }
    }

    /** Trusted authority-only enrollment used by dedicated-server operator compatibility flows. */
    public CoordinatorCredentials.MemberCredential enrollTrustedLocal(String displayName){
        String cleanName = displayName == null ? "" : displayName.trim();
        if(cleanName.length() > 128) cleanName = cleanName.substring(0, 128);
        if(cleanName.isBlank()) cleanName = "Member";
        final String finalName = cleanName;
        synchronized(enrollmentMutex){
            SharedCampaignState current = store.strategicSnapshot();
            if(current.members.size >= maxCampaignMembers) throw new IllegalStateException("Shared Campaign member capacity reached");
            CoordinatorCredentials.MemberCredential issued = credentials.issueMemberCredential();
            try{
                store.transact(issued.memberId(), "shared-campaign:member-enrolled", state -> {
                    if(state.members.size >= maxCampaignMembers) throw new IllegalStateException("Shared Campaign member capacity reached");
                    MemberState member = new MemberState();
                    member.memberId = issued.memberId();
                    member.displayName = finalName;
                    member.lastSeenAt = Time.millis();
                    member.lastConnectedAt = member.lastSeenAt;
                    state.members.put(member.memberId, member);
                });
                return issued;
            }catch(Throwable failure){
                credentials.revokeMemberCredential(issued.memberId());
                throw failure;
            }
        }
    }

    /** Rolls back a trusted local enrollment that never reached a usable client admission. */
    public void rollbackTrustedEnrollment(String memberId){
        String target = memberId == null ? "" : memberId.trim();
        if(target.isBlank()) return;
        synchronized(enrollmentMutex){
            SharedCampaignState current = store.strategicSnapshot();
            if(target.equals(current.ownerId)) throw new IllegalArgumentException("The campaign owner cannot be removed");
            if(current.members.containsKey(target)){
                store.transact("coordinator", "shared-campaign:member-enrollment-rollback", state -> {
                    state.members.remove(target);
                    for(ActionState action : state.actions.values()){ action.participants.remove(target); action.spectators.remove(target); }
                });
            }
            credentials.revokeMemberCredential(target);
        }
    }

    private void serve(String memberId, ControlProtocol.Connection connection){
        campaignConnections.add(connection);
        try(connection){ while(running.get()) handle(memberId, connection, connection.receive()); }
        catch(SocketTimeoutException | EOFException ignored){}
        catch(Exception ignored){}
        finally{ campaignConnections.remove(connection); }
    }

    /**
     * Pushes the newest authoritative strategic snapshot to every authenticated campaign client. Multiple commits may
     * arrive faster than a remote UI can consume them (especially Action heartbeats), so pending revisions coalesce to
     * the newest state and are written on a dedicated daemon rather than on the durable commit thread.
     */
    public void publishSnapshot(SharedCampaignState state){
        if(state == null || !running.get()) return;
        pendingSnapshot.set(SharedCampaignStateCopy.strategicCopy(state));
        scheduleSnapshotPush();
    }

    private void scheduleSnapshotPush(){
        if(!running.get() || !snapshotPushScheduled.compareAndSet(false, true)) return;
        try{ snapshotPush.execute(this::drainSnapshotPush); }
        catch(RejectedExecutionException ignored){ snapshotPushScheduled.set(false); }
    }

    private void drainSnapshotPush(){
        try{
            while(running.get()){
                SharedCampaignState state = pendingSnapshot.getAndSet(null);
                if(state == null) break;
                byte[] payload = SharedCampaignCodec.encodeStrategic(state);
                for(ControlProtocol.Connection connection : campaignConnections){
                    try{ connection.send(ControlProtocol.Type.snapshotResponse, 0L, payload); }
                    catch(IOException failure){
                        campaignConnections.remove(connection);
                        try{ connection.close(); }catch(IOException ignored){}
                    }
                }
            }
        }finally{
            snapshotPushScheduled.set(false);
            if(running.get() && pendingSnapshot.get() != null) scheduleSnapshotPush();
        }
    }

    private void handle(String memberId, ControlProtocol.Connection connection, ControlProtocol.Frame frame) throws IOException{
        try{
            switch(frame.type()){
                case snapshotRequest -> connection.send(ControlProtocol.Type.snapshotResponse, frame.requestId(), SharedCampaignCodec.encodeStrategic(store.strategicSnapshot()));
                case startActionRequest -> {
                    RuntimePayloads.StartAction request = RuntimePayloads.startAction(frame.payload());
                    RuntimePayloads.StartResult result = commands.startSector(memberId, request.planetName(), request.sectorName(), request.missionId(), request.launch());
                    connection.send(ControlProtocol.Type.startActionResponse, frame.requestId(), RuntimePayloads.encode(result));
                }
                case joinActionRequest -> {
                    RuntimePayloads.JoinAction request = RuntimePayloads.joinAction(frame.payload());
                    connection.send(ControlProtocol.Type.joinActionResponse, frame.requestId(), RuntimePayloads.encode(commands.join(memberId, request.actionId(), request.spectator(), request.networkUuid())));
                }
                case suspendActionRequest -> {
                    commands.suspend(memberId, RuntimePayloads.decodeString(frame.payload()));
                    connection.send(ControlProtocol.Type.suspendActionResponse, frame.requestId(), new byte[0]);
                }
                case hotSwitchRequest -> connection.send(ControlProtocol.Type.hotSwitchResponse, frame.requestId(), RuntimePayloads.encode(commands.beginHotSwitch(memberId, RuntimePayloads.hotSwitchRequest(frame.payload()))));
                case hotSwitchResumeRequest -> connection.send(ControlProtocol.Type.hotSwitchResumeResponse, frame.requestId(), RuntimePayloads.encodeBoolean(commands.resumeHotSwitch(memberId, RuntimePayloads.decodeString(frame.payload()))));
                case hotSwitchAbortRequest -> connection.send(ControlProtocol.Type.hotSwitchAbortResponse, frame.requestId(), RuntimePayloads.encodeBoolean(commands.abortHotSwitch(memberId, RuntimePayloads.decodeString(frame.payload()))));
                case campaignSettingsRequest -> {
                    RuntimePayloads.CampaignSettings request = RuntimePayloads.campaignSettings(frame.payload());
                    SharedCampaignState updated = updateSettings(memberId, request);
                    connection.send(ControlProtocol.Type.campaignSettingsResponse, frame.requestId(), SharedCampaignCodec.encodeStrategic(updated));
                }
                case inviteCodeRequest -> connection.send(ControlProtocol.Type.inviteCodeResponse, frame.requestId(), RuntimePayloads.encodeString(inviteCode(memberId)));
                case inviteRotateRequest -> connection.send(ControlProtocol.Type.inviteRotateResponse, frame.requestId(), RuntimePayloads.encodeString(rotateInviteCode(memberId)));
                case memberRemoveRequest -> {
                    SharedCampaignState updated = removeMember(memberId, RuntimePayloads.decodeString(frame.payload()));
                    connection.send(ControlProtocol.Type.memberRemoveResponse, frame.requestId(), SharedCampaignCodec.encodeStrategic(updated));
                }
                case researchRequest -> {
                    RuntimePayloads.ResearchRequest request = RuntimePayloads.researchRequest(frame.payload());
                    SharedCampaignState updated = commands.research(memberId, request.contentName(), request.planetName());
                    connection.send(ControlProtocol.Type.researchResponse, frame.requestId(), SharedCampaignCodec.encodeStrategic(updated));
                }
                case sectorLogisticsRequest -> {
                    RuntimePayloads.SectorLogistics request = RuntimePayloads.sectorLogistics(frame.payload());
                    SharedCampaignState updated = commands.updateSectorLogistics(memberId, request.sourceSector(), request.destinationSector());
                    connection.send(ControlProtocol.Type.sectorLogisticsResponse, frame.requestId(), SharedCampaignCodec.encodeStrategic(updated));
                }
                case ping -> connection.send(ControlProtocol.Type.pong, frame.requestId(), frame.payload());
                default -> throw new IOException("Unsupported campaign-client request: " + frame.type());
            }
        }catch(Exception error){ connection.send(ControlProtocol.Type.error, frame.requestId(), RuntimePayloads.encodeString(error.getMessage() == null ? error.toString() : error.getMessage())); }
    }

    private SharedCampaignState updateSettings(String memberId, RuntimePayloads.CampaignSettings request){
        requireActiveMember(memberId);
        if(request.invitePolicy() == null) throw new IllegalArgumentException("Invite policy is required");
        if(request.persistenceProfile() == null) throw new IllegalArgumentException("Persistence profile is required");
        SharedCampaignState current = store.snapshot();
        SharedCampaignPlanetRegistry.PlanetPolicy primaryPolicy = planetPolicies.resolve(current, current.primaryPlanetName);
        boolean requestedMultiFront = request.multiFrontEnabled() && primaryPolicy.supportsMultipleActions();
        int requestedMax = requestedMultiFront ? request.maxActiveActions() : 1;
        if(requestedMax < 1 || requestedMax > 32) throw new IllegalArgumentException("Maximum active actions must be between 1 and 32");
        SharedCampaignState updated = store.transact(memberId, "shared-campaign:update-settings", state -> {
            if(!memberId.equals(state.ownerId)) throw new SecurityException("Only the campaign owner may change settings");
            planetPolicies.validateSettings(state, requestedMultiFront, requestedMax);
            state.maxActiveActions = requestedMax;
            state.freezeWhenEmpty = request.freezeWhenEmpty();
            state.multiFrontEnabled = requestedMultiFront;
            state.invitePolicy = request.invitePolicy();
            state.persistenceProfile = request.persistenceProfile();
        });
        // Settings are authority state, but persistence policy also changes the autosave cadence of already-running
        // Action worlds. Push the exact committed revision to every connected Action before reporting success so a
        // Desktop settings change cannot claim "traditional" while old child JVMs keep fsyncing every minute.
        commands.synchronizeConnectedActionSnapshots(updated.revision);
        return updated;
    }

    private String inviteCode(String memberId){
        SharedCampaignState state = requireActiveMember(memberId);
        if(state.invitePolicy == InvitePolicy.ownerOnly && !memberId.equals(state.ownerId)) throw new SecurityException("Only the campaign owner may view the invite code");
        return credentials.inviteCode();
    }

    private String rotateInviteCode(String memberId){
        SharedCampaignState state = requireActiveMember(memberId);
        if(!memberId.equals(state.ownerId)) throw new SecurityException("Only the campaign owner may rotate the invite code");
        return credentials.rotateInviteSecret();
    }

    private SharedCampaignState removeMember(String memberId, String targetMemberId){
        SharedCampaignState current = requireActiveMember(memberId);
        String target = targetMemberId == null ? "" : targetMemberId.trim();
        if(target.isBlank()) throw new IllegalArgumentException("Member ID is required");
        if(target.equals(current.ownerId)) throw new IllegalArgumentException("The campaign owner cannot be removed");
        if(!target.equals(memberId) && !memberId.equals(current.ownerId)) throw new SecurityException("Only the owner may remove another member");
        SharedCampaignState updated = store.transact(memberId, "shared-campaign:member-removed", state -> {
            if(!state.members.containsKey(target)) throw new IllegalArgumentException("Unknown campaign member: " + target);
            state.members.remove(target);
            for(ActionState action : state.actions.values()){ action.participants.remove(target); action.spectators.remove(target); }
            Seq<String> receipts = new Seq<>();
            for(ObjectMap.Entry<String, ControlRequestReceipt> entry : state.controlRequestReceipts){
                if(target.equals(entry.value.memberId)) receipts.add(entry.key);
            }
            for(String key : receipts) state.controlRequestReceipts.remove(key);
        });
        credentials.revokeMemberCredential(target);
        // Durable removal is not enough for pure-vanilla direct grants: a live Action may already hold a short-lived
        // one-shot grant whose final authorization check reads its Action-local strategic snapshot. Wait until every
        // connected live Action has observed at least this removal revision before reporting success.
        commands.synchronizeConnectedActionSnapshots(updated.revision);
        return updated;
    }

    private SharedCampaignState requireActiveMember(String memberId){
        SharedCampaignState state = store.strategicSnapshot();
        if(memberId == null || memberId.isBlank() || !state.members.containsKey(memberId)) throw new SecurityException("Unknown or revoked campaign member");
        return state;
    }

    @Override public synchronized void close(){
        if(!running.compareAndSet(true, false)) return;
        ServerSocket socket = server; server = null; if(socket != null) try{ socket.close(); }catch(IOException ignored){}
        Thread thread = acceptThread; acceptThread = null; if(thread != null) thread.interrupt();
        for(ControlProtocol.Connection connection : campaignConnections){
            try{ connection.close(); }catch(IOException ignored){}
        }
        campaignConnections.clear();
        clients.shutdownNow();
        snapshotPush.shutdownNow();
        entry.close();
    }
}
