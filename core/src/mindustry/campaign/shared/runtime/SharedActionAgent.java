package mindustry.campaign.shared.runtime;

import arc.*;
import arc.files.*;
import arc.util.*;
import mindustry.*;
import mindustry.ctype.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.mission.*;
import mindustry.campaign.shared.net.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.maps.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static mindustry.Vars.*;

/** Runtime agent embedded in an action server process. */
public class SharedActionAgent implements ApplicationListener, Closeable, SharedCampaignRuntimeState.TickHook{
    public static final String finalOutcomeRelativePath = "config/action-final-outcome.bin";
    public static final String captureOutcomeRelativePath = "config/action-capture-outcome.bin";
    private final ActionRuntimeConfig runtimeConfig;
    private final Runnable exitHandler;
    private mindustry.runtime.GameContext ownerContext;
    private final String actionId;
    private final String secret;
    private final String coordinatorHost;
    private final int coordinatorPort;
    private final long authorityGeneration;
    private final long runtimeIncarnation;
    private final String saveSlot;
    private final String summaryPath;
    private final String launchPath;
    private final String planetName;
    private final String sectorName;
    private final int gamePort;
    private final boolean newAction;
    private final String reservationsPath;
    private final String transportReservationsPath;

    public SharedActionAgent(){
        this(ActionRuntimeConfig.fromProcessBootstrap(), Core.app == null ? () -> {} : Core.app::exit);
    }

    public SharedActionAgent(ActionRuntimeConfig runtimeConfig){
        this(runtimeConfig, () -> {});
    }

    public SharedActionAgent(ActionRuntimeConfig runtimeConfig, Runnable exitHandler){
        this.runtimeConfig = runtimeConfig == null ? ActionRuntimeConfig.disabled() : runtimeConfig;
        this.exitHandler = exitHandler == null ? () -> {} : exitHandler;
        ActionRuntimeDescriptor descriptor = this.runtimeConfig.descriptor();
        actionId = descriptor == null ? "" : descriptor.actionId();
        secret = this.runtimeConfig.controlSecret();
        coordinatorHost = descriptor == null ? "127.0.0.1" : descriptor.coordinatorHost();
        coordinatorPort = descriptor == null ? 6570 : descriptor.coordinatorPort();
        authorityGeneration = descriptor == null ? 0L : descriptor.authorityGeneration();
        runtimeIncarnation = descriptor == null ? 0L : descriptor.runtimeIncarnation();
        saveSlot = descriptor == null ? "action" : descriptor.saveSlot();
        summaryPath = descriptor == null ? "config/action-summary.bin" : descriptor.summaryPath();
        launchPath = descriptor == null ? "config/action-launch.bin" : descriptor.launchPath();
        planetName = descriptor == null ? "" : descriptor.planetName();
        sectorName = descriptor == null ? "" : descriptor.sectorName();
        gamePort = descriptor == null ? Vars.port : descriptor.gamePort();
        newAction = descriptor != null && descriptor.newAction();
        reservationsPath = descriptor == null ? "config/research-reservations.bin" : descriptor.reservationsPath();
        transportReservationsPath = descriptor == null ? "config/transport-reservations.bin" : descriptor.transportReservationsPath();
        int autosaveSeconds = descriptor == null ? 5 * 60 : Math.max(15, descriptor.autosaveSeconds());
        actionAutosaveIntervalMillis = TimeUnit.SECONDS.toMillis(autosaveSeconds);
    }

    private boolean completionReported;
    private boolean captureReported;
    /** Bootstrap scheduling is an explicit one-shot handoff from the authenticated control plane to the owning
     * GameContext. update() also calls scheduleBootstrap() as an idempotent fallback, but correctness no longer
     * depends on observing an authorityReady/menu polling edge on the simulation thread. */
    private final AtomicBoolean bootstrapQueued = new AtomicBoolean();
    private volatile boolean bootstrapStarted;
    /** True only after a new embedded Sector has completed its world/bootstrap transaction and opened its server. */
    private volatile boolean bootstrapCompleted;
    private volatile String bootstrapFailure = "";
    /** Suppresses authoritative summary materialization while a failed save is loaded only as vanilla ruins input. */
    private volatile boolean loadingLossReconstructionSource;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean authorityReady = new AtomicBoolean();
    /** True only after SaveIO/world bootstrap has fully returned and the Action GameState is playable. */
    private final AtomicBoolean worldReady = new AtomicBoolean();
    private volatile CountDownLatch stopAcknowledged;
    private volatile long stopRequestId;
    private volatile CountDownLatch captureAcknowledged;
    private volatile long captureRequestId;
    private final AtomicBoolean finalizing = new AtomicBoolean();
    private volatile byte[] pendingFinalPayload;
    private Thread finalizerThread;
    private final AtomicBoolean suspendRequested = new AtomicBoolean();
    /** Correlates the coordinator suspend command with the Action-side accept/reject decision. */
    private volatile long suspendRequestId;
    private volatile ControlProtocol.Connection connection;
    /** Source-Action requests initiated by vanilla /sector commands and completed by the control reader thread. */
    private final ConcurrentHashMap<Long, CompletableFuture<ControlProtocol.Frame>> vanillaTransferRequests = new ConcurrentHashMap<>();
    private Thread controlThread;
    private volatile String controlPhase = "created";
    private volatile String lastControlError = "";
    private final AtomicInteger controlAttempts = new AtomicInteger();
    private volatile long lastControlProgressAt;
    private long lastHeartbeat;
    private long lastStateUpdate;
    private long lastExtendedStateUpdate;
    private static final long stateUpdateIntervalMillis = Math.max(2_000L, Long.getLong("sharedCampaign.actionStateUpdateMillis", 10_000L));
    private static final long extendedStateUpdateIntervalMillis = Math.max(stateUpdateIntervalMillis, Long.getLong("sharedCampaign.actionExtendedStateUpdateMillis", 60_000L));
    private long lastObjectiveResync;
    /** UI-test-only liveness fault: keep the world/server alive but stop control-plane heartbeat/state updates. */
    private volatile boolean uiTestSuppressHeartbeats;
    private final arc.struct.ObjectSet<String> produced = new arc.struct.ObjectSet<>();
    private ActionResearchReservations researchReservations;
    private ActionTransportReservations transportReservations;
    private ActionTransportDispatchQueue transportDispatches;
    private SharedCampaignRuntimeState sharedRuntime;
    private SharedCampaignNet network;
    private ActionCampaignSnapshot campaignSnapshot;
    private ErekirMissionRuntime missions;
    private long lastTransportDispatchFlush;
    /** Effective world autosave cadence. Child JVMs mirror this into server Config; embedded Actions enforce it here. */
    private volatile long actionAutosaveIntervalMillis;
    private long lastEmbeddedAutosave;

    public boolean enabled(){ return !actionId.isBlank(); }
    public String actionId(){ return actionId; }
    public String secret(){ return secret; }

    /** Requests a one-shot standard Arc endpoint transfer for an unmodified vanilla client without blocking the Action game lane. */
    public CompletableFuture<RuntimePayloads.VanillaTransferResult> requestVanillaTransferAsync(Player player, String target){
        if(player == null || player.con == null) return CompletableFuture.completedFuture(vanillaTransferError("Player connection is unavailable"));
        if(target == null || target.isBlank()) return CompletableFuture.completedFuture(vanillaTransferError("Target sector or Action is required"));
        SharedCampaignNet currentNetwork = network;
        if(currentNetwork == null) return CompletableFuture.completedFuture(vanillaTransferError("Shared Campaign network state is unavailable"));
        String memberId = currentNetwork.authenticatedMemberId(player.con);
        if(memberId.isBlank()) return CompletableFuture.completedFuture(vanillaTransferError("This connection is not authenticated as a Shared Campaign member"));
        String networkUuid = mindustry.net.Packets.ConnectPacket.platformUuid(player.uuid());
        if(networkUuid == null || networkUuid.isBlank()) return CompletableFuture.completedFuture(vanillaTransferError("Mindustry network UUID is unavailable"));
        String address = player.con.address == null ? "" : player.con.address.trim();
        if(address.isBlank()) return CompletableFuture.completedFuture(vanillaTransferError("Client network address is unavailable"));
        ControlProtocol.Connection current = connection;
        if(current == null || !authorityReady.get()) return CompletableFuture.completedFuture(vanillaTransferError("Shared Campaign coordinator is not connected"));

        long requestId = current.nextRequestId();
        CompletableFuture<ControlProtocol.Frame> wire = new CompletableFuture<>();
        if(vanillaTransferRequests.putIfAbsent(requestId, wire) != null) return CompletableFuture.completedFuture(vanillaTransferError("Duplicate vanilla transfer request ID"));
        try{
            RuntimePayloads.VanillaTransferRequest request = new RuntimePayloads.VanillaTransferRequest(
                actionId, target.trim(), memberId, networkUuid, address, player.spectator());
            current.send(ControlProtocol.Type.vanillaTransferRequest, requestId, RuntimePayloads.encode(request));
        }catch(IOException error){
            vanillaTransferRequests.remove(requestId, wire);
            return CompletableFuture.completedFuture(vanillaTransferError(error.getMessage() == null ? error.toString() : error.getMessage()));
        }

        return wire.orTimeout(25L, TimeUnit.SECONDS).handle((frame, failure) -> {
            vanillaTransferRequests.remove(requestId, wire);
            if(failure != null){
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                return vanillaTransferError(cause instanceof TimeoutException ? "Timed out preparing vanilla sector transfer" : cause.toString());
            }
            if(frame.type() == ControlProtocol.Type.error) return vanillaTransferError(RuntimePayloads.decodeString(frame.payload()));
            if(frame.type() != ControlProtocol.Type.vanillaTransferResponse) return vanillaTransferError("Unexpected coordinator response: " + frame.type());
            return RuntimePayloads.vanillaTransferResult(frame.payload());
        });
    }

    /** Blocking compatibility wrapper for non-game-thread callers and focused tests. */
    public RuntimePayloads.VanillaTransferResult requestVanillaTransfer(Player player, String target){
        try{
            return requestVanillaTransferAsync(player, target).get(26L, TimeUnit.SECONDS);
        }catch(InterruptedException error){
            Thread.currentThread().interrupt();
            return vanillaTransferError("Interrupted while preparing vanilla sector transfer");
        }catch(ExecutionException | TimeoutException error){
            Throwable cause = error.getCause();
            return vanillaTransferError(cause == null ? error.toString() : cause.toString());
        }
    }

    private static RuntimePayloads.VanillaTransferResult vanillaTransferError(String error){
        return new RuntimePayloads.VanillaTransferResult("", "", 0, 0L, error == null ? "Vanilla transfer failed" : error);
    }

    public void suppressHeartbeatsForTesting(){
        if(!Boolean.getBoolean("mindustry.uiTest")) throw new SecurityException("Shared Campaign heartbeat fault injection requires mindustry.uiTest=true");
        uiTestSuppressHeartbeats = true;
    }
    /** New-world creation still touches legacy process-shared campaign/content helpers. It must finish before this
     * runtime can participate in parallel authoritative ticks. Resumed saves are loaded synchronously before register. */
    public boolean parallelTickReady(){ return !newAction || bootstrapCompleted; }

    @Override public void init(){
        if(!enabled()) return;
        if(secret.isBlank() || authorityGeneration <= 0) throw new IllegalStateException("Incomplete Shared Campaign action process configuration");
        running.set(true);
        researchReservations = new ActionResearchReservations(runtimeFile(reservationsPath), saveSlot, this::actionSaveFile);
        transportReservations = new ActionTransportReservations(runtimeFile(transportReservationsPath), saveSlot, this::actionSaveFile);
        transportDispatches = new ActionTransportDispatchQueue(runtimeFile(transportReservationsPath + ".dispatches"));
        ownerContext = mindustry.runtime.RuntimeContexts.requireCurrent();
        sharedRuntime = SharedCampaignRuntimeState.install(ownerContext, runtimeConfig);
        sharedRuntime.attach(SharedActionAgent.class, this);
        sharedRuntime.attach(SharedCampaignRuntimeState.TickHook.class, this);
        network = SharedCampaignNet.install(ownerContext);
        campaignSnapshot = sharedRuntime.component(ActionCampaignSnapshot.class);
        if(campaignSnapshot == null) campaignSnapshot = sharedRuntime.attach(ActionCampaignSnapshot.class, new ActionCampaignSnapshot());
        if(sharedRuntime.component(SharedCampaignNet.CampaignStateSource.class) == null){
            sharedRuntime.attach(SharedCampaignNet.CampaignStateSource.class, campaignSnapshot);
        }
        missions = sharedRuntime.component(ErekirMissionRuntime.class);
        if(missions == null) missions = sharedRuntime.attach(ErekirMissionRuntime.class, new ErekirMissionRuntime());
        var owner = ownerContext;
        controlThread = new Thread(mindustry.runtime.RuntimeContexts.capture(owner, this::controlLoop), "shared-action-agent"); controlThread.setDaemon(true); controlThread.start();
        Events.on(WorldLoadEvent.class, e -> {
            if(loadingLossReconstructionSource) return;
            applySummary();
            // The control handshake receives and applies the authoritative campaign snapshot before a fresh Action
            // loads its Sector. world.loadSector() replaces Rules, so runtime-scoped unlocks written into the old
            // menu Rules would otherwise be lost forever when no newer campaign revision is broadcast. Re-apply the
            // already authenticated snapshot at the world-load boundary so the Action server validates the same
            // research state that Shared clients see.
            reapplyAuthoritativeCampaignSnapshot();
            // Do not reconcile durable reservations here. Vanilla fires WorldLoadEvent from inside SaveIO/world load,
            // before an embedded resumed Action has transitioned its GameState to playing. Reservation recovery may
            // inspect/mutate the Core and persist the world, so it must wait for the explicit post-load barrier below.
        });
        Events.on(PlayerConnectionConfirmed.class, e -> {
            // World-data and one-shot objective-completion packets can race during a graphical Shared Action join.
            // After the client explicitly confirms that its world is installed, replay only the authoritative completed
            // objective indexes. NetClient.completeObjective is idempotent, so this is safe for already-current clients.
            if(enabled() && e.player != null && e.player.con != null && mindustry.Vars.game().net.server()) replayCompletedObjectives(e.player);
        });
        Events.on(SectorCaptureEvent.class, e -> {
            if(enabled() && !captureReported && e.sector == mindustry.Vars.game().state.getSector()){
                // Vanilla capture does not eject the player from the Sector. Persist and report the strategic capture
                // milestone, but keep this Action alive; leaving/suspending remains a separate lifecycle operation.
                captureReported = true;
                postGame(this::commitCaptureMilestone);
            }
        });
        Events.on(GameOverEvent.class, e -> {
            if(enabled() && mindustry.Vars.game().state.isCampaign() && !completionReported){
                completionReported = true;
                postGame(() -> failAction("Mission failed"));
            }
        });
        Events.on(UnlockEvent.class, e -> {
            // UnlockEvent only ever fires the first time a campaign item is unlocked. UnlockableContent.unlock()
            // (and Logic.research) adds the content to Rules.researched BEFORE firing, so inspecting that set at
            // callback time would always exclude the freshly-unlocked content and produced() would never record
            // any item. Record every campaign unlock as newly produced instead.
            if(enabled() && mindustry.Vars.game().state.isCampaign() && e.content instanceof UnlockableContent){
                produced.add(((UnlockableContent)e.content).name);
                // Publish newly discovered content promptly instead of waiting for the normal 10s state cadence.
                lastStateUpdate = 0L;
                lastExtendedStateUpdate = 0L;
            }
        });
        Events.on(LaunchItemEvent.class, e -> {
            if(!enabled() || !mindustry.Vars.game().state.isCampaign() || e.stack == null || e.stack.item == null || e.stack.amount <= 0) return;
            var sector = mindustry.Vars.game().state.rules.sector;
            if(sector == null || sector.planet.campaignRules.legacyLaunchPads) return;
            var destination = sector.info().destination;
            if(destination == null || destination == sector) return;
            String sourceKey = SharedCampaignSectors.sectorKey(sector);
            String destinationKey = SharedCampaignSectors.sectorKey(destination);
            String dispatchId = actionId + ":" + authorityGeneration + ":" + runtimeIncarnation + ":" + Long.toUnsignedString((long)mindustry.Vars.game().state.tick) + ":" + java.util.UUID.randomUUID();
            transportDispatches.enqueue(actionId, dispatchId, sourceKey, destinationKey, e.stack.item.name, e.stack.amount, 1L);
            lastTransportDispatchFlush = 0L;
            flushTransportDispatches();
        });
        // A process shutdown hook is only meaningful for an Action hosted by its own JVM (its GameContext is the
        // process primary). Embedded Actions are disposed while the host JVM stays alive; capturing their context in a
        // JVM shutdown hook would later try to re-enter an already-closed lane.
        if(runtimeConfig.enabled() && mindustry.runtime.RuntimeContexts.isPrimary()) Runtime.getRuntime().addShutdownHook(new Thread(mindustry.runtime.RuntimeContexts.capture(this::bestEffortShutdownSave), "shared-action-shutdown"));
    }

    @Override public void update(){
        if(!enabled() || !running.get()) return;
        // Child-JVM resume is loaded by ServerControl rather than InProcessSectorRuntime. Recover durable Action-side
        // reservations at the first authoritative playable update; onWorldReady is idempotent, so the explicit
        // in-process post-load barrier remains safe.
        if(!newAction && !worldReady.get() && mindustry.Vars.game().state.isGame()) onWorldReady();
        if(newAction && authorityReady.get() && !bootstrapCompleted) scheduleBootstrap();
        mirrorVanillaCoreItemDiscovery();
        if(missions != null) missions.update();
        long now = Time.millis();
        if(mindustry.Vars.game().state.isGame() && connection != null && !uiTestSuppressHeartbeats && now - lastHeartbeat >= 2_000L){
            lastHeartbeat = now;
            arc.struct.ObjectSet<String> participants = new arc.struct.ObjectSet<>();
            arc.struct.ObjectSet<String> spectators = new arc.struct.ObjectSet<>();
            Groups.current().player.each(player -> {
                String memberId = network == null ? "" : network.authenticatedMemberId(player.con);
                if(memberId.isBlank()){
                    Log.warn("[SharedCampaign] Ignoring action heartbeat participant without member-bound admission: action=@ networkUuid=@", actionId, player.uuid());
                    return;
                }
                participants.add(memberId);
                if(player.spectator()) spectators.add(memberId);
            });
            RuntimePayloads.ActionHeartbeat heartbeat = new RuntimePayloads.ActionHeartbeat(actionId, authorityGeneration, runtimeIncarnation, (long)mindustry.Vars.game().state.tick, participants, spectators);
            send(ControlProtocol.Type.actionHeartbeat, 0L, RuntimePayloads.encode(heartbeat));
            if(lastStateUpdate == 0L || now - lastStateUpdate >= stateUpdateIntervalMillis){
                lastStateUpdate = now;
                boolean includeExtendedState = lastExtendedStateUpdate == 0L || now - lastExtendedStateUpdate >= extendedStateUpdateIntervalMillis;
                if(includeExtendedState) lastExtendedStateUpdate = now;
                RuntimePayloads.ActionStateUpdate update = new RuntimePayloads.ActionStateUpdate(actionId, authorityGeneration, runtimeIncarnation,
                    (long)mindustry.Vars.game().state.tick, includeExtendedState, includeExtendedState ? SectorSummaryCollector.collect() : SectorSummaryCollector.collectStrategic(),
                    missions == null ? null : missions.snapshot(),
                    new arc.struct.ObjectSet<>(produced),
                    missions == null ? new arc.struct.ObjectMap<>() : missions.tutorialSnapshot());
                send(ControlProtocol.Type.actionStateUpdate, 0L, RuntimePayloads.encode(update));
            }
        }
        // Vanilla normally delivers objective completion as a one-shot reliable RPC. Shared Action clients can cross
        // world-data/reconnect boundaries while the authoritative headless world keeps advancing, so periodically
        // replay only completed objective indexes as an idempotent convergence repair. This also heals a client that
        // missed the transition without requiring the player to leave and re-enter the Action.
        if(mindustry.Vars.game().state.isGame() && mindustry.Vars.game().net.server() && (lastObjectiveResync == 0L || now - lastObjectiveResync >= 5_000L)){
            lastObjectiveResync = now;
            Groups.current().player.each(this::replayCompletedObjectives);
        }
        if(transportDispatches != null && authorityReady.get() && connection != null && (lastTransportDispatchFlush == 0L || now - lastTransportDispatchFlush >= 1_000L)){
            lastTransportDispatchFlush = now;
            flushTransportDispatches();
        }
        maybeAutosaveEmbedded(now);
        if(suspendRequested.compareAndSet(true, false)) performSuspension();
    }

    /**
     * Embedded/headless Actions do not run graphical {@code Control.update()}, whose vanilla campaign contract scans
     * the Core inventory and calls {@code item.unlock()}. Reproduce that exact authority-side bridge here. Because
     * Action runtimes use runtime-scoped unlocks, {@code unlock()} writes only this Action's Rules.researched and fires
     * UnlockEvent; the listener above then carries the discovery into the durable Shared Campaign snapshot.
     */
    /**
     * Transaction responses are required to remain bounded, but a future schema regression should fail the request
     * immediately instead of tearing down the whole authenticated Action control connection and forcing a timeout.
     */
    private static void sendBoundedResponse(ControlProtocol.Connection connection, ControlProtocol.Type type, long requestId, byte[] payload) throws IOException{
        int limit = ControlProtocol.maxPayload(type);
        if(payload.length > limit){
            connection.send(ControlProtocol.Type.error, requestId, RuntimePayloads.encodeString(
                "Action response " + type + " exceeded its control-plane budget: " + payload.length + " > " + limit));
            return;
        }
        connection.send(type, requestId, payload);
    }

    private void replayCompletedObjectives(Player target){
        if(target == null || target.con == null || mindustry.Vars.game().state == null || mindustry.Vars.game().state.rules == null) return;
        var state = mindustry.Vars.game().state;
        var objectives = state.rules.objectives;
        if(objectives != null){
            for(int index = 0; index < objectives.all.size; index++){
                var objective = objectives.all.get(index);
                if(objective != null && objective.isCompleted()) Call.completeObjective(target.con, index);
            }
        }

        // Wave-control convergence is a Shared Action runtime contract, not conditional on a map having an objective
        // executor. The completed bit is not the whole objective state. In particular, MapObjective.done() may enable wave
        // sending/timers and mutate objectiveFlags. Send those authoritative observables separately instead of
        // re-running arbitrary completion logic on an already-completed client objective.
        Call.syncSharedObjectiveState(target.con, state.rules.waveTimer, state.rules.waveSending, state.wavetime, state.wave, encodeObjectiveFlags(state.rules.objectiveFlags));
    }

    private static String encodeObjectiveFlags(arc.struct.ObjectSet<String> flags){
        try{
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try(DataOutputStream out = new DataOutputStream(bytes)){
                var sorted = flags == null ? new arc.struct.Seq<String>() : flags.toSeq().sort();
                out.writeInt(sorted.size);
                for(String flag : sorted) out.writeUTF(flag == null ? "" : flag);
            }
            return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        }catch(IOException impossible){
            throw new UncheckedIOException(impossible);
        }
    }

    private void mirrorVanillaCoreItemDiscovery(){
        if(!mindustry.Vars.game().state.isCampaign() || mindustry.Vars.game().state.rules == null) return;
        var core = mindustry.Vars.game().state.rules.defaultTeam.core();
        if(core == null) return;
        core.items.each((item, amount) -> {
            if(amount > 0) item.unlock();
        });
    }

    private void controlLoop(){
        long retry = 1_000L;
        // TCP connected but the coordinator closed during the control handshake: almost always a missing/rotated
        // action control secret (wrong campaign, secrets cleared after stop, or a stale child still retrying).
        // Connection-refused while the host is down must keep retrying; credential rejections must not spin forever.
        int consecutiveCredentialRejections = 0;
        while(running.get()){
            controlAttempts.incrementAndGet();
            controlPhase = "connecting"; lastControlProgressAt = Time.millis();
            boolean[] handshakeSucceeded = {false};
            try(ControlProtocol.Connection connected = ControlProtocol.Connection.connect(coordinatorHost, coordinatorPort, ControlProtocol.Role.actionHost, actionId, ControlProtocol.deriveKey(secret))){
                handshakeSucceeded[0] = true;
                connection = connected; retry = 1_000L; lastControlError = ""; consecutiveCredentialRejections = 0;
                controlPhase = "handshake-complete"; lastControlProgressAt = Time.millis();
                RuntimePayloads.ActionHello hello = new RuntimePayloads.ActionHello(actionId, authorityGeneration, runtimeIncarnation, gamePort, contentFingerprint());
                long request = connected.nextRequestId(); connected.send(ControlProtocol.Type.actionHello, request, RuntimePayloads.encode(hello));
                controlPhase = "hello-sent"; lastControlProgressAt = Time.millis();
                // A correlated request must not assume that the next frame on a multiplexed control socket is its
                // response. Snapshot broadcasts (and, defensively, ping) may be interleaved by another coordinator
                // thread. Consume those without losing the actionHello authority edge, and reject any other frame
                // rather than silently proceeding unauthorised.
                boolean helloAccepted = false;
                boolean snapshotAccepted = false;
                while(!(helloAccepted && snapshotAccepted)){
                    ControlProtocol.Frame response = connected.receive();
                    controlPhase = "hello-response-" + response.type(); lastControlProgressAt = Time.millis();
                    if(response.requestId() == request && response.type() == ControlProtocol.Type.error){
                        throw new IOException(RuntimePayloads.decodeString(response.payload()));
                    }
                    if(response.requestId() == request && response.type() == ControlProtocol.Type.actionHello){
                        String acknowledgedAction = RuntimePayloads.decodeString(response.payload());
                        if(!acknowledgedAction.isBlank() && !actionId.equals(acknowledgedAction)){
                            throw new IOException("Action hello acknowledgement mismatch: " + acknowledgedAction);
                        }
                        helloAccepted = true;
                        continue;
                    }
                    if(response.type() == ControlProtocol.Type.snapshotResponse){
                        applyAuthoritativeSnapshot(response.payload());
                        snapshotAccepted = true;
                        continue;
                    }
                    if(response.type() == ControlProtocol.Type.ping){
                        connected.send(ControlProtocol.Type.pong, response.requestId(), response.payload());
                        continue;
                    }
                    throw new IOException("Unexpected control frame before actionHello response: " + response.type() + " request=" + response.requestId());
                }
                authorityReady.set(true);
                controlPhase = "authority-ready"; lastControlProgressAt = Time.millis();
                // Authentication plus the matching authoritative snapshot is the edge that permits a fresh world to
                // start. Queue bootstrap directly onto the owning GameContext so startup cannot be lost merely because
                // a simulation update did not observe the ready/menu condition at the right instant.
                if(newAction) scheduleBootstrap();
                // The authoritative game thread may legitimately spend longer than the normal heartbeat lease in
                // serialized world/bootstrap work when several embedded Sectors start together. Keep the authenticated
                // control plane alive independently; the coordinator only treats these pings as lease keepalives while
                // the action is still STARTING. Once the first real world heartbeat commits RUNNING state, only real
                // action heartbeats renew the lease, so a hung game thread cannot be hidden by this control thread.
                connected.setReadTimeout(2_000);
                while(running.get()){
                    ControlProtocol.Frame frame;
                    try{
                        frame = connected.receive();
                    }catch(SocketTimeoutException timeout){
                        connected.send(ControlProtocol.Type.ping, connected.nextRequestId(), new byte[0]);
                        continue;
                    }
                    CompletableFuture<ControlProtocol.Frame> vanillaFuture = frame.requestId() == 0L ? null : vanillaTransferRequests.get(frame.requestId());
                    if(vanillaFuture != null && (frame.type() == ControlProtocol.Type.vanillaTransferResponse || frame.type() == ControlProtocol.Type.error)){
                        vanillaFuture.complete(frame);
                        continue;
                    }
                    if(frame.type() == ControlProtocol.Type.suspendActionRequest){
                        suspendRequestId = frame.requestId();
                        suspendRequested.set(true);
                    }else if(frame.type() == ControlProtocol.Type.snapshotResponse){
                        applyAuthoritativeSnapshot(frame.payload());
                    }else if(frame.type() == ControlProtocol.Type.snapshotApplyRequest){
                        long appliedRevision = applyAuthoritativeSnapshot(frame.payload());
                        connected.send(ControlProtocol.Type.snapshotApplyResponse, frame.requestId(), RuntimePayloads.encode(new RuntimePayloads.SnapshotApplied(appliedRevision)));
                    }else if(frame.type() == ControlProtocol.Type.actionStopped && frame.requestId() == stopRequestId){
                        CountDownLatch latch = stopAcknowledged;
                        if(latch != null) latch.countDown();
                    }else if(frame.type() == ControlProtocol.Type.actionSaveCommitted && frame.requestId() == captureRequestId){
                        CountDownLatch latch = captureAcknowledged;
                        if(latch != null) latch.countDown();
                    }else if(frame.type() == ControlProtocol.Type.researchPrepareRequest){
                        RuntimePayloads.ResearchPrepare requestPayload = RuntimePayloads.researchPrepare(frame.payload());
                        RuntimePayloads.ResearchPrepareResult result = onGameThread(() -> researchReservations.prepare(requestPayload));
                        sendBoundedResponse(connected, ControlProtocol.Type.researchPrepareResponse, frame.requestId(), RuntimePayloads.encode(result));
                    }else if(frame.type() == ControlProtocol.Type.researchDecisionRequest){
                        RuntimePayloads.ResearchDecision decision = RuntimePayloads.researchDecision(frame.payload());
                        RuntimePayloads.ResearchDecisionResult result = onGameThread(() -> researchReservations.decide(decision));
                        sendBoundedResponse(connected, ControlProtocol.Type.researchDecisionResponse, frame.requestId(), RuntimePayloads.encode(result));
                    }else if(frame.type() == ControlProtocol.Type.transportPrepareRequest){
                        RuntimePayloads.TransportPrepare prepare = RuntimePayloads.transportPrepare(frame.payload());
                        if(!actionId.equals(prepare.actionId())) throw new SecurityException("Transport action identity mismatch");
                        RuntimePayloads.TransportPrepareResult result = onGameThread(() -> transportReservations.prepare(prepare));
                        sendBoundedResponse(connected, ControlProtocol.Type.transportPrepareResponse, frame.requestId(), RuntimePayloads.encode(result));
                    }else if(frame.type() == ControlProtocol.Type.transportDecisionRequest){
                        RuntimePayloads.TransportDecision decision = RuntimePayloads.transportDecision(frame.payload());
                        RuntimePayloads.TransportDecisionResult result = onGameThread(() -> transportReservations.decide(decision));
                        sendBoundedResponse(connected, ControlProtocol.Type.transportDecisionResponse, frame.requestId(), RuntimePayloads.encode(result));
                    }else if(frame.type() == ControlProtocol.Type.transportDispatchResponse){
                        RuntimePayloads.TransportDispatchResult result = RuntimePayloads.transportDispatchResult(frame.payload());
                        if(result.accepted()){
                            if(transportDispatches != null) transportDispatches.acknowledge(result.dispatchId());
                        }else{
                            Log.err("Shared Campaign transport dispatch @ was rejected after LaunchPad cargo left the source: @", result.dispatchId(), result.error());
                        }
                    }else if(frame.type() == ControlProtocol.Type.actionLogisticsRequest){
                        RuntimePayloads.ActionLogistics logistics = RuntimePayloads.actionLogistics(frame.payload());
                        onGameThread(() -> { applyLogisticsTarget(logistics); return null; });
                        connected.send(ControlProtocol.Type.actionLogisticsResponse, frame.requestId(), RuntimePayloads.encodeString(logistics.destinationSector()));
                    }else if(frame.type() == ControlProtocol.Type.vanillaAdmissionPrepareRequest){
                        RuntimePayloads.VanillaAdmissionPrepare prepare = RuntimePayloads.vanillaAdmissionPrepare(frame.payload());
                        if(!actionId.equals(prepare.actionId())) throw new SecurityException("Vanilla admission destination Action mismatch");
                        if(network == null) throw new IllegalStateException("Shared Campaign network state is unavailable");
                        network.prepareVanillaAdmission(prepare.grantId(), prepare.networkUuid(), prepare.memberId(), prepare.remoteAddress(), prepare.spectator(), prepare.expiresAt());
                        connected.send(ControlProtocol.Type.vanillaAdmissionPrepareResponse, frame.requestId(),
                            RuntimePayloads.encode(new RuntimePayloads.VanillaAdmissionPrepareResult(prepare.grantId(), true, "")));
                    }else if(frame.type() == ControlProtocol.Type.vanillaAdmissionRevokeRequest){
                        RuntimePayloads.VanillaAdmissionRevoke revoke = RuntimePayloads.vanillaAdmissionRevoke(frame.payload());
                        if(!actionId.equals(revoke.actionId())) throw new SecurityException("Vanilla admission revoke destination Action mismatch");
                        if(network == null) throw new IllegalStateException("Shared Campaign network state is unavailable");
                        boolean removed = network.revokeVanillaAdmission(revoke.grantId(), revoke.networkUuid());
                        connected.send(ControlProtocol.Type.vanillaAdmissionRevokeResponse, frame.requestId(), RuntimePayloads.encodeBoolean(removed));
                    }else if(frame.type() == ControlProtocol.Type.actionSaveNowRequest){
                        // Maintenance checkpoint: respond with an error frame instead of tearing down the control
                        // lane so one unwritable world does not force a full reconnect.
                        try{
                            String hash = onGameThread(this::forceSaveWorldNow);
                            connected.send(ControlProtocol.Type.actionSaveNowResponse, frame.requestId(), RuntimePayloads.encodeString(hash));
                        }catch(Exception saveFailure){
                            connected.send(ControlProtocol.Type.error, frame.requestId(), RuntimePayloads.encodeString(
                                saveFailure.getMessage() == null ? saveFailure.toString() : saveFailure.getMessage()));
                        }
                    }else if(frame.type() == ControlProtocol.Type.ping){
                        connected.send(ControlProtocol.Type.pong, frame.requestId(), frame.payload());
                    }else if(frame.type() == ControlProtocol.Type.error){
                        String message = RuntimePayloads.decodeString(frame.payload());
                        lastControlError = message;
                        Log.warn("Shared campaign coordinator rejected action control frame @ for @: @", frame.requestId(), actionId, message);
                    }
                }
            }catch(SocketTimeoutException timeout){
                lastControlError = timeout.toString(); controlPhase = "timeout"; lastControlProgressAt = Time.millis();
            }catch(ConnectException refused){
                // Coordinator is not listening yet — keep retrying with backoff.
                lastControlError = refused.toString(); controlPhase = "refused"; lastControlProgressAt = Time.millis();
                if(running.get()) Log.warn("Shared action agent reconnecting after control error: @", refused.getMessage());
            }catch(Exception e){
                lastControlError = e.toString(); controlPhase = "error"; lastControlProgressAt = Time.millis();
                boolean credentialStyle = !handshakeSucceeded[0]
                    && (e instanceof EOFException || e instanceof java.net.SocketException || e instanceof SecurityException);
                if(credentialStyle){
                    // TCP came up, then the coordinator closed during the control handshake — missing/rotated secret.
                    consecutiveCredentialRejections++;
                    if(consecutiveCredentialRejections >= 3){
                        Log.err("Shared campaign coordinator rejected control credentials for @ (@); stopping action agent", actionId, e.getMessage() == null ? e.toString() : e.getMessage());
                        running.set(false);
                        break;
                    }
                    if(running.get()) Log.warn("Shared action agent control handshake failed (@/3): @", consecutiveCredentialRejections, e.getMessage() == null ? e.toString() : e.getMessage());
                }else if(running.get()){
                    Log.warn("Shared action agent reconnecting after control error: @", e.getMessage());
                }
            }finally{
                connection = null;
                IOException disconnected = new EOFException("Shared Campaign coordinator control connection closed");
                for(CompletableFuture<ControlProtocol.Frame> future : vanillaTransferRequests.values()) future.completeExceptionally(disconnected);
                vanillaTransferRequests.clear();
            }
            if(running.get()){
                try{ Thread.sleep(retry); }catch(InterruptedException e){ Thread.currentThread().interrupt(); break; }
                retry = Math.min(retry * 2L, 15_000L);
            }
        }
    }


    private void postGame(Runnable runnable){
        var owner = ownerContext == null ? mindustry.runtime.RuntimeContexts.requireCurrent() : ownerContext;
        Runnable captured = mindustry.runtime.RuntimeContexts.capture(owner, runnable);
        if(owner == mindustry.runtime.RuntimeContexts.primary()){
            Core.app.post(captured);
        }else{
            owner.post(captured);
        }
    }

    private <T> T onGameThread(Callable<T> operation) throws Exception{
        FutureTask<T> task = new FutureTask<>(operation);
        postGame(task);
        return task.get(30L, TimeUnit.SECONDS);
    }


    /** Installs a coordinator snapshot on the owning simulation lane and returns the revision that is visible there. */
    private long applyAuthoritativeSnapshot(byte[] payload) throws Exception{
        SharedCampaignState snapshot = mindustry.campaign.shared.io.SharedCampaignCodec.decode(payload);
        return onGameThread(() -> {
            long appliedRevision = campaignSnapshot.accept(snapshot);
            applyPersistencePolicy(snapshot);
            applyAuthoritativeUnlocks(snapshot);
            if(network != null) network.reconcileMembership(snapshot);
            reconcileAuthoritativeLogistics(snapshot);
            return appliedRevision;
        });
    }

    /** Child-JVM Action servers mirror the campaign's selected autosave cadence; embedded contexts save explicitly. */
    private void applyPersistencePolicy(SharedCampaignState snapshot){
        if(snapshot == null || ownerContext == null) return;
        int seconds = SharedCampaignPersistence.policy(snapshot.persistenceProfile).actionAutosaveSeconds();
        actionAutosaveIntervalMillis = TimeUnit.SECONDS.toMillis(seconds);
        if(ownerContext != mindustry.runtime.RuntimeContexts.primary()) return;
        mindustry.net.Administration.Config.autosave.set(true);
        mindustry.net.Administration.Config.autosaveSpacing.set(seconds);
    }

    /** Embedded Action contexts have no ServerControl autosave timer, so enforce the selected profile on their lane. */
    private void maybeAutosaveEmbedded(long now){
        if(ownerContext == null || ownerContext == mindustry.runtime.RuntimeContexts.primary()) return;
        if(!worldReady.get() || !mindustry.Vars.game().state.isGame() || finalizing.get()) return;
        long interval = Math.max(15_000L, actionAutosaveIntervalMillis);
        if(lastEmbeddedAutosave != 0L && now - lastEmbeddedAutosave < interval) return;
        try{
            SaveIO.save(actionSaveFile());
            lastEmbeddedAutosave = now;
        }catch(Throwable error){
            Log.warn("Shared Campaign embedded Action autosave failed for @: @", actionId, error.toString());
        }
    }

    /** Maintenance checkpoint used by Desktop/headless hosts before backup, upgrade or shutdown. */
    private String forceSaveWorldNow(){
        if(!mindustry.Vars.game().state.isGame()) throw new IllegalStateException("Action world is not currently playable");
        Fi save = actionSaveFile();
        SaveIO.save(save);
        lastEmbeddedAutosave = Time.millis();
        return SharedFileDigests.sha256(save);
    }

    /**
     * Logistics target is Campaign authority, not an Action-local transaction. Applying it from every authoritative
     * snapshot makes target changes crash-consistent: the coordinator durably commits first, then the live Action
     * installs that revision through the normal snapshot barrier/reconnect path. No Action-first ACK window remains.
     */
    private void reconcileAuthoritativeLogistics(SharedCampaignState snapshot){
        if(snapshot == null || !mindustry.Vars.game().state.isGame() || mindustry.Vars.game().state.rules.sector == null) return;
        String sourceKey = mindustry.campaign.shared.SharedCampaignSectors.sectorKey(mindustry.Vars.game().state.rules.sector);
        SharedCampaignState.SectorState source = snapshot.sectors.get(sourceKey);
        if(source == null) return;
        Sector destination = source.destinationSector == null || source.destinationSector.isBlank() ? null :
            mindustry.campaign.shared.SharedCampaignSectors.findSectorKey(source.destinationSector);
        if(destination != null && destination.planet != mindustry.Vars.game().state.rules.sector.planet){
            throw new IllegalStateException("Authoritative logistics target crosses planets: " + source.destinationSector);
        }
        mindustry.Vars.game().state.rules.sector.info().destination = destination;
    }

    private void scheduleBootstrap(){
        if(!newAction || bootstrapCompleted || !authorityReady.get()) return;
        if(!bootstrapQueued.compareAndSet(false, true)) return;
        try{
            postGame(this::bootstrapNewSector);
        }catch(RuntimeException rejected){
            bootstrapQueued.set(false);
            throw rejected;
        }
    }

    private void bootstrapNewSector(){
        bootstrapStarted = true;
        try{
            // A fresh action must still be in its pristine menu state. If another path has already started a world,
            // fail closed instead of silently declaring a half-bootstrapped runtime healthy.
            if(!mindustry.Vars.game().state.isMenu()){
                if(mindustry.Vars.game().state.isGame()) throw new IllegalStateException("Fresh action entered game state before authoritative bootstrap");
                throw new IllegalStateException("Fresh action is not in menu state before authoritative bootstrap: " + mindustry.Vars.game().state.getState());
            }
            if(mindustry.Vars.game().netServer == null) throw new IllegalStateException("Fresh action has no runtime-owned NetServer");
            var sector = mindustry.campaign.shared.SharedCampaignSectors.findSector(planetName, sectorName);
                if(sector == null) throw new IllegalArgumentException("Unknown campaign sector: " + planetName + "/" + sectorName);
                RuntimePayloads.LaunchPlan plan = readLaunchPlan();
                Sector origin = plan.originSector().isBlank() ? null : mindustry.campaign.shared.SharedCampaignSectors.findSectorKey(plan.originSector());
                if(!plan.originSector().isBlank() && origin == null) throw new IllegalArgumentException("Unknown launch origin: " + plan.originSector());

                Schematic loadout;
                if(plan.loadout().isBlank()){
                    if(sector.planet.generator == null || sector.planet.generator.defaultLoadout == null) throw new IllegalStateException("Destination planet has no default launch loadout");
                    loadout = sector.planet.generator.defaultLoadout;
                }else{
                    loadout = schematics.readBase64(plan.loadout());
                }
                CoreBlock core = loadout.findCore();
                mindustry.Vars.game().universe.updateLoadout(core, loadout);
                ItemSeq launchResources = new ItemSeq();
                for(var entry : plan.resources()){
                    Item item = content.item(entry.key);
                    if(item == null) throw new IllegalArgumentException("Unknown launch item: " + entry.key);
                    if(entry.value < 0) throw new IllegalArgumentException("Negative launch item amount: " + entry.key);
                    launchResources.set(item, entry.value);
                }
                mindustry.Vars.game().universe.updateLaunchResources(launchResources);

                if(sector.preset != null) sector.preset.quietUnlock();

                // A fresh Action may carry a failed clearSectorOnLose=false save solely as reconstruction input.
                // Reproduce vanilla Control.playSector semantics: damage/capture the old battlefield, reset it, then
                // generate a fresh sector at the historical spawn and restore old buildings as derelict plus plans.
                SectorLossReconstruction.Snapshot lost = captureLostSectorReconstruction(sector);
                WorldParams params = new WorldParams();
                if(lost != null) params.corePositionOverride = lost.spawnPosition();
                mindustry.Vars.game().world.loadSector(sector, params);
                mindustry.Vars.game().state.rules.sector = sector;
                sector.info().origin = origin;
                // Match vanilla bootstrap first, then re-apply the Shared authoritative summary. WorldLoadEvent fires
                // inside world.loadSector(), before these vanilla origin/destination defaults are assigned; without
                // this second application, destination=origin overwrites a preconfigured LaunchPad target and the
                // first Action state update durably commits the wrong self-target back to the coordinator.
                sector.info().destination = origin;
                sector.info().attempts++;
                if(lost != null) SectorLossReconstruction.restore(lost);
                applySummary();
                reapplyAuthoritativeCampaignSnapshot();
                mindustry.Vars.game().logic.play();
                onWorldReady();
                SaveIO.save(actionSaveFile());
                mindustry.Vars.game().netServer.openServer(gamePort);
                bootstrapCompleted = true;
                if(origin != null) Events.fire(new SectorLaunchLoadoutEvent(sector, origin, loadout));
            Events.fire(new SectorLaunchEvent(sector));
            Events.fire(Trigger.newGame);
        }catch(Throwable t){
            bootstrapFailure = t.toString();
            Log.err("Failed to bootstrap shared campaign action", t);
            failAction("Failed to bootstrap shared campaign action: " + t);
        }
    }

    private SectorLossReconstruction.Snapshot captureLostSectorReconstruction(Sector sector){
        Fi failed = actionSaveFile();
        if(failed == null || !failed.exists()) return null;
        try{
            if(!SaveIO.isSaveValid(failed)) throw new IllegalStateException("Lost-sector reconstruction save is invalid: " + failed);
            loadingLossReconstructionSource = true;
            SaveIO.load(failed);
            mindustry.Vars.game().state.rules.sector = sector;
            mindustry.Vars.game().state.rules.cloudColor = sector.planet.landCloudColor;
            var snapshot = SectorLossReconstruction.capture(sector);
            mindustry.Vars.game().logic.reset();
            if(snapshot == null){
                Log.warn("[SharedCampaign] Lost-sector save metadata no longer matches @/@; rebuilding a clean authored sector instead.", planetName, sectorName);
            }
            return snapshot;
        }catch(Throwable error){
            try{ mindustry.Vars.game().logic.reset(); }catch(Throwable ignored){}
            throw new IllegalStateException("Failed to reconstruct lost Shared Campaign sector " + planetName + "/" + sectorName, error);
        }finally{
            loadingLossReconstructionSource = false;
        }
    }

    /**
     * Explicit post-load barrier for durable Action-side transactions. WorldLoadEvent is too early: it is fired from
     * inside SaveIO/world loading before resumed embedded runtimes set GameState.playing. This method is idempotent and
     * must run on the owning game thread only after the world, Rules and Core are authoritative and playable.
     */
    void onWorldReady(){
        if(!enabled() || !mindustry.Vars.game().state.isGame()) throw new IllegalStateException("Action world-ready barrier requires a loaded game");
        if(!worldReady.compareAndSet(false, true)) return;
        if(researchReservations != null) researchReservations.recoverAll();
        if(transportReservations != null) transportReservations.recoverAll();
    }

    boolean worldReady(){ return worldReady.get(); }

    /** Compact runtime diagnostics used by lifecycle gates and failure reports; no mutable state escapes. */
    public String bootstrapDiagnostics(){
        var context = ownerContext;
        return "action=" + actionId +
            ", authorityReady=" + authorityReady.get() +
            ", worldReady=" + worldReady.get() +
            ", controlPhase=" + controlPhase +
            ", controlAttempts=" + controlAttempts.get() +
            ", lastControlError=" + lastControlError +
            ", controlIdleMs=" + (lastControlProgressAt == 0L ? -1L : Math.max(0L, Time.millis() - lastControlProgressAt)) +
            ", queued=" + bootstrapQueued.get() +
            ", started=" + bootstrapStarted +
            ", completed=" + bootstrapCompleted +
            ", failure=" + bootstrapFailure +
            ", state=" + (context == null || context.state == null ? "null" : context.state.getState()) +
            ", pendingPostedTasks=" + (context == null ? -1 : context.pendingPostedTasks()) +
            ", contextClosed=" + (context != null && context.closed());
    }

    private void reapplyAuthoritativeCampaignSnapshot(){
        SharedCampaignState snapshot = campaignSnapshot == null ? null : campaignSnapshot.state();
        if(snapshot == null) return;
        applyAuthoritativeUnlocks(snapshot);
        if(network != null) network.reconcileMembership(snapshot);
        reconcileAuthoritativeLogistics(snapshot);
    }

    private void applyAuthoritativeUnlocks(SharedCampaignState snapshot){
        if(snapshot == null || mindustry.Vars.game().state == null || mindustry.Vars.game().state.rules == null) return;
        mindustry.Vars.game().state.rules.researched.clear();
        content.each(value -> {
            if(value instanceof UnlockableContent unlock &&
                (unlock.alwaysUnlocked || SharedCampaignState.effectiveUnlocked(snapshot.researched, snapshot.discovered, unlock.name))){
                mindustry.Vars.game().state.rules.researched.add(unlock);
            }
        });
    }

    private Fi runtimeFile(String path){
        if(ownerContext != null && ownerContext.storage != null) return ownerContext.storage.resolve(path);
        return Core.files.absolute(path);
    }

    private Fi actionSaveFile(){
        if(ownerContext != null && ownerContext.storage != null) return ownerContext.storage.actionSave(saveSlot);
        return saveDirectory.child(saveSlot + "." + saveExtension);
    }

    private RuntimePayloads.LaunchPlan readLaunchPlan(){
        Fi file = runtimeFile(launchPath);
        if(!file.exists() || file.length() == 0) return RuntimePayloads.LaunchPlan.empty();
        return RuntimePayloads.launchPlan(file.readBytes());
    }

    private void applySummary(){
        if(!enabled()) return;
        Fi file = runtimeFile(summaryPath);
        if(!file.exists() || file.length() == 0) return;
        try{
            var summary = RuntimePayloads.decodeSummary(file.readBytes());
            // A fresh Sector has just loaded its authored map rules/objectives. The empty pre-world strategic
            // summary must never overwrite those rules (e.g. Ground Zero waves/tutorial state). Only resumed saves
            // restore wave/attack fields from durable summary; fresh worlds still receive strategic inventory below.
            if(!newAction){
                mindustry.Vars.game().state.wave = Math.max(1, summary.wave);
                mindustry.Vars.game().state.rules.winWave = summary.winWave;
                mindustry.Vars.game().state.rules.waves = summary.waves;
                mindustry.Vars.game().state.rules.attackMode = summary.attackMode;
            }
            var core = mindustry.Vars.game().state.rules.defaultTeam.core();
            if(core != null){
                for(var entry : summary.items){
                    var item = content.item(entry.key);
                    if(item != null) core.items.set(item, Math.max(0, Math.min(entry.value, core.storageCapacity)));
                }
            }
            if(mindustry.Vars.game().state.rules.sector != null){
                var info = mindustry.Vars.game().state.rules.sector.info();
                info.items.clear();
                for(var entry : summary.items){
                    var item = content.item(entry.key);
                    if(item != null) info.items.set(item, Math.max(0, entry.value));
                }
                info.storageCapacity = summary.storageCapacity;
                if(!newAction){
                    info.wave = summary.wave;
                    info.winWave = summary.winWave;
                    info.waves = summary.waves;
                    info.attack = summary.attackMode;
                    info.hasSpawns = summary.hasSpawns;
                }
                info.minutesCaptured = summary.minutesCaptured;
                info.production.clear();
                for(var entry : summary.productionPerSecond){
                    var item = content.item(entry.key);
                    if(item != null) info.production.get(item, mindustry.game.SectorInfo.ExportStat::new).mean = entry.value;
                }
                info.export.clear();
                for(var entry : summary.exportPerSecond){
                    var item = content.item(entry.key);
                    if(item != null) info.export.get(item, mindustry.game.SectorInfo.ExportStat::new).mean = entry.value;
                }
                info.imports.clear();
                for(var entry : summary.importPerSecond){
                    var item = content.item(entry.key);
                    if(item != null) info.imports.get(item, mindustry.game.SectorInfo.ExportStat::new).mean = entry.value;
                }
                info.destination = summary.destinationSector.isBlank() ? null : mindustry.campaign.shared.SharedCampaignSectors.findSectorKey(summary.destinationSector);
            }
        }catch(Throwable t){
            throw new RuntimeException("Failed to apply authoritative suspended-sector summary", t);
        }
    }

    private void applyLogisticsTarget(RuntimePayloads.ActionLogistics logistics){
        if(!actionId.equals(logistics.actionId())) throw new SecurityException("Logistics action identity mismatch");
        if(!mindustry.Vars.game().state.isGame() || mindustry.Vars.game().state.rules.sector == null) throw new IllegalStateException("Action world is not available for a logistics update");
        String current = mindustry.campaign.shared.SharedCampaignSectors.sectorKey(mindustry.Vars.game().state.rules.sector);
        if(!current.equals(logistics.sourceSector())) throw new SecurityException("Logistics source does not match the running action sector");
        Sector destination = logistics.destinationSector().isBlank() ? null : mindustry.campaign.shared.SharedCampaignSectors.findSectorKey(logistics.destinationSector());
        if(destination != null && destination.planet != mindustry.Vars.game().state.rules.sector.planet) throw new IllegalArgumentException("Launch-pad logistics cannot cross planets");
        mindustry.Vars.game().state.rules.sector.info().destination = destination;
    }

    private void performSuspension(){
        if(finalizing.get()) return;
        long requestId = suspendRequestId;
        if(!mindustry.Vars.game().state.isGame()){
            try{
                Fi save = actionSaveFile();
                if(save != null && save.exists() && SaveIO.isSaveValid(save)){
                    String hash = SharedFileDigests.sha256(save);
                    send(ControlProtocol.Type.suspendActionResponse, requestId, new byte[0]);
                    beginFinalization(true, false, hash, "Action world was already stopped with a durable save");
                }else{
                    send(ControlProtocol.Type.error, requestId, RuntimePayloads.encodeString("Action cannot suspend cleanly because no valid durable save exists"));
                    failAction("Action stopped before suspension and no valid durable save exists");
                }
            }catch(Throwable error){
                send(ControlProtocol.Type.error, requestId, RuntimePayloads.encodeString("Action suspension recovery failed: " + error));
                failAction("Action suspension recovery failed: " + error);
            }
            return;
        }
        if(!Groups.current().player.isEmpty()){
            send(ControlProtocol.Type.error, requestId, RuntimePayloads.encodeString("Action cannot suspend while players are present"));
            return;
        }
        mindustry.Vars.game().state.set(mindustry.core.GameState.State.paused);
        try{
            Fi save = actionSaveFile();
            SaveIO.save(save);
            String hash = SharedFileDigests.sha256(save);
            // ACK only after the suspension save is durable. The coordinator can then keep SUSPENDING and await the
            // final ActionStopped proof; a rejected request instead rolls back to RUNNING.
            send(ControlProtocol.Type.suspendActionResponse, requestId, new byte[0]);
            beginFinalization(true, false, hash, "");
        }catch(Throwable t){
            mindustry.Vars.game().state.set(mindustry.core.GameState.State.playing);
            send(ControlProtocol.Type.error, requestId, RuntimePayloads.encodeString("Action suspension save failed: " + t));
        }
    }

    private void bestEffortShutdownSave(){
        if(!enabled() || !running.get() || !mindustry.Vars.game().state.isGame()) return;
        try{
            Fi save = actionSaveFile(); SaveIO.save(save);
            byte[] payload = stoppedPayload(false, false, SharedFileDigests.sha256(save), "Action process terminated outside an orderly suspension");
            persistFinalOutcome(payload);
            if(sendStoppedOnce(payload, 5_000L)) deleteFinalOutcome();
        }catch(Throwable ignored){}
    }

    private void commitCaptureMilestone(){
        if(finalizing.get()) return;
        try{
            Fi save = actionSaveFile();
            SaveIO.save(save);
            byte[] payload = capturePayload(SharedFileDigests.sha256(save));
            persistCaptureOutcome(payload);
            var captureOwner = ownerContext == null ? mindustry.runtime.RuntimeContexts.requireCurrent() : ownerContext;
            Thread reporter = new Thread(mindustry.runtime.RuntimeContexts.capture(captureOwner, () -> {
                long retry = 500L;
                while(running.get() && captureOutcomeFile().exists()){
                    if(sendCaptureOnce(payload, 10_000L)){
                        deleteCaptureOutcome();
                        return;
                    }
                    sleep(retry);
                    retry = Math.min(10_000L, retry * 2L);
                }
            }), "shared-action-capture-reporter");
            reporter.setDaemon(true);
            reporter.start();
        }catch(Throwable error){
            // The world has already crossed the vanilla capture edge. Continuing without a durable strategic marker
            // would let a crash erase that fact, so fail closed instead of silently returning to gameplay.
            failAction("Could not durably commit Sector capture milestone: " + error);
        }
    }

    private void completeAction(){
        if(finalizing.get()) return;
        try{
            Fi save = actionSaveFile(); SaveIO.save(save);
            beginFinalization(true, true, SharedFileDigests.sha256(save), "");
        }catch(Throwable t){ failAction(t.toString()); }
    }

    private void failAction(String reason){
        if(finalizing.get()) return;
        String hash = "";
        try{
            if(mindustry.Vars.game().state.isGame()){
                Fi save = actionSaveFile(); SaveIO.save(save);
                hash = SharedFileDigests.sha256(save);
            }
        }catch(Throwable saveError){ reason = reason + "; final save failed: " + saveError; }
        beginFinalization(false, false, hash, reason);
    }

    private void beginFinalization(boolean clean, boolean completed, String saveHash, String reason){
        if(!finalizing.compareAndSet(false, true)) return;
        if(mindustry.Vars.game().state.isGame()) mindustry.Vars.game().state.set(mindustry.core.GameState.State.paused);
        pendingFinalPayload = stoppedPayload(clean, completed, saveHash, reason);
        try{
            persistFinalOutcome(pendingFinalPayload);
        }catch(IOException error){
            finalizing.set(false);
            throw new RuntimeException("Could not durably persist the Shared Action final outcome", error);
        }
        finalizerThread = new Thread(() -> {
            long retry = 1_000L;
            while(running.get()){
                if((researchReservations != null && !researchReservations.pending().isEmpty()) ||
                    (transportReservations != null && !transportReservations.pending().isEmpty())){
                    sleep(500L);
                    continue;
                }
                if(sendStoppedOnce(pendingFinalPayload, 10_000L)){
                    deleteFinalOutcome();
                    running.set(false);
                    var owner = ownerContext;
                    if(owner != null && !owner.closed()){
                        try{ postGame(exitHandler); }
                        catch(java.util.concurrent.RejectedExecutionException ignored){ /* coordinator already closed this embedded runtime */ }
                    }
                    return;
                }
                sleep(retry);
                retry = Math.min(15_000L, retry * 2L);
            }
        }, "shared-action-finalizer");
        finalizerThread.setDaemon(true);
        finalizerThread.start();
    }


    private Fi finalOutcomeFile(){
        return runtimeFile(finalOutcomeRelativePath);
    }

    private Fi captureOutcomeFile(){
        return runtimeFile(captureOutcomeRelativePath);
    }

    private void persistFinalOutcome(byte[] payload) throws IOException{
        persistOutcome(finalOutcomeFile(), payload);
    }

    private void persistCaptureOutcome(byte[] payload) throws IOException{
        persistOutcome(captureOutcomeFile(), payload);
    }

    private static void persistOutcome(Fi target, byte[] payload) throws IOException{
        target.parent().mkdirs();
        Fi temp = target.sibling(target.name() + ".tmp");
        try(FileOutputStream stream = new FileOutputStream(temp.file())){
            stream.write(payload);
            stream.flush();
            stream.getFD().sync();
        }
        try{
            java.nio.file.Files.move(temp.file().toPath(), target.file().toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }catch(AtomicMoveNotSupportedException ignored){
            java.nio.file.Files.move(temp.file().toPath(), target.file().toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Path parent = target.file().toPath().toAbsolutePath().getParent();
        if(parent != null) try(FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)){ channel.force(true); }catch(Exception ignored){}
    }

    private void deleteFinalOutcome(){
        try{ finalOutcomeFile().delete(); }catch(Throwable ignored){}
    }

    private void deleteCaptureOutcome(){
        try{ captureOutcomeFile().delete(); }catch(Throwable ignored){}
    }

    private byte[] capturePayload(String saveHash){
        var summary = mindustry.Vars.game().state.isGame() ? SectorSummaryCollector.collectStrategic() : null;
        var mission = missions == null ? null : missions.snapshot();
        return RuntimePayloads.encode(new RuntimePayloads.ActionStopped(actionId, authorityGeneration, runtimeIncarnation, true, true, saveHash, "", summary, mission, new arc.struct.ObjectSet<>(produced)));
    }

    private byte[] stoppedPayload(boolean clean, boolean completed, String saveHash, String reason){
        var summary = mindustry.Vars.game().state.isGame() ? SectorSummaryCollector.collect() : null;
        var mission = missions == null ? null : missions.snapshot();
        return RuntimePayloads.encode(new RuntimePayloads.ActionStopped(actionId, authorityGeneration, runtimeIncarnation, clean, completed, saveHash, reason, summary, mission, new arc.struct.ObjectSet<>(produced)));
    }

    private boolean sendCaptureOnce(byte[] payload, long timeoutMillis){
        ControlProtocol.Connection current = connection;
        if(current == null) return false;
        CountDownLatch latch = new CountDownLatch(1);
        long request = current.nextRequestId();
        captureAcknowledged = latch;
        captureRequestId = request;
        try{
            current.send(ControlProtocol.Type.actionSaveCommitted, request, payload);
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }catch(IOException ignored){
            return false;
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return false;
        }finally{
            if(captureAcknowledged == latch) captureAcknowledged = null;
        }
    }

    private boolean sendStoppedOnce(byte[] payload, long timeoutMillis){
        ControlProtocol.Connection current = connection;
        if(current == null) return false;
        CountDownLatch latch = new CountDownLatch(1);
        long request = current.nextRequestId();
        stopAcknowledged = latch;
        stopRequestId = request;
        try{
            current.send(ControlProtocol.Type.actionStopped, request, payload);
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }catch(IOException ignored){
            return false;
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return false;
        }finally{
            if(stopAcknowledged == latch) stopAcknowledged = null;
        }
    }

    private static void sleep(long millis){
        try{ Thread.sleep(millis); }catch(InterruptedException e){ Thread.currentThread().interrupt(); }
    }

    private void send(ControlProtocol.Type type, long requestId, byte[] payload){
        ControlProtocol.Connection current = connection; if(current == null) return;
        try{ current.send(type, requestId, payload); }catch(IOException e){ Log.warn("Failed to send shared action control frame: @", e.getMessage()); }
    }

    private void flushTransportDispatches(){
        if(transportDispatches == null || !authorityReady.get()) return;
        ControlProtocol.Connection current = connection;
        if(current == null) return;
        int sent = 0;
        for(RuntimePayloads.TransportDispatch dispatch : transportDispatches.pending()){
            if(sent++ >= 32) break;
            try{
                current.send(ControlProtocol.Type.transportDispatchRequest, current.nextRequestId(), RuntimePayloads.encode(dispatch));
            }catch(IOException error){
                Log.warn("Failed to dispatch Shared Campaign LaunchPad transport @: @", dispatch.dispatchId(), error.getMessage());
                break;
            }
        }
    }

    private String contentFingerprint(){ return mindustry.campaign.shared.SharedContentFingerprint.calculate(); }

    @Override public void close(){
        if(sharedRuntime != null){
            if(sharedRuntime.component(SharedActionAgent.class) == this) sharedRuntime.detach(SharedActionAgent.class);
            if(sharedRuntime.component(SharedCampaignRuntimeState.TickHook.class) == this) sharedRuntime.detach(SharedCampaignRuntimeState.TickHook.class);
        }
        running.set(false); if(controlThread != null) controlThread.interrupt(); if(finalizerThread != null) finalizerThread.interrupt();
        IOException closing = new EOFException("Shared Action agent is closing");
        for(CompletableFuture<ControlProtocol.Frame> future : vanillaTransferRequests.values()) future.completeExceptionally(closing);
        vanillaTransferRequests.clear();
        ControlProtocol.Connection current = connection; if(current != null) try{ current.close(); }catch(IOException ignored){}
    }

    @Override public void dispose(){ close(); }
}
