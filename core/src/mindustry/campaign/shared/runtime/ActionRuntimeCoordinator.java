package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.io.*;
import mindustry.io.*;
import mindustry.runtime.*;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

/**
 * Owns host-local Shared Action runtimes independently from campaign-client/UI command handling.
 *
 * <p>The outer campaign coordinator commits launch/research/logistics decisions. This component owns the narrower
 * runtime lifecycle: it starts an already-authoritative STARTING Action, resumes a SUSPENDED Action as a new runtime
 * incarnation, monitors liveness, and converts an unexpected runtime death back into durable campaign truth. All
 * Action-originated state changes still pass through {@link ActionControlPlane} and {@link ActionAuthorityTransitions}.</p>
 */
public final class ActionRuntimeCoordinator implements Closeable, ActionControlPlane.Listener{
    public static final long leaseDurationMillis = 30_000L;
    public static final long monitorIntervalMillis = 1_000L;

    public interface Listener{
        default void snapshotCommitted(SharedCampaignState state){}
        default void routeAvailable(String actionId, int gamePort){}
        default void routeUnavailable(String actionId){}
        default void actionBecameRunning(ActionState action){}
        default void actionStopped(ActionState action){}
        default void actionRecovered(ActionState action, String reason){}
    }

    private final GameContext owner;
    private final SharedCampaignStore store;
    private final CoordinatorCredentials credentials;
    private final Fi actionRoot;
    private final String advertisedHost;
    private final Fi modsSource;
    private final SharedCampaignMissionRegistry missionDefinitions;
    private final ActionControlPlane.LossPolicy lossPolicy;
    private final SectorRuntimeFactory runtimeFactory;
    private final Listener listener;
    private final ActionControlPlane controlPlane;
    private final ConcurrentHashMap<String, SectorRuntime> runtimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService monitor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object resumeMutex = new Object();

    public ActionRuntimeCoordinator(GameContext owner, SharedCampaignStore store, CoordinatorCredentials credentials,
                                    Fi actionRoot, String advertisedHost, int controlPort, Fi modsSource,
                                    SharedCampaignMissionRegistry missionDefinitions, ActionControlPlane.LossPolicy lossPolicy,
                                    SectorRuntimeFactory runtimeFactory, Listener listener){
        this.owner = Objects.requireNonNull(owner, "owner");
        this.store = Objects.requireNonNull(store, "store");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.actionRoot = Objects.requireNonNull(actionRoot, "actionRoot");
        this.advertisedHost = advertisedHost == null || advertisedHost.isBlank() ? "127.0.0.1" : advertisedHost.trim();
        this.modsSource = Objects.requireNonNull(modsSource, "modsSource");
        this.missionDefinitions = Objects.requireNonNull(missionDefinitions, "missionDefinitions");
        this.lossPolicy = lossPolicy == null ? action -> false : lossPolicy;
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.listener = listener == null ? new Listener(){} : listener;
        this.controlPlane = new ActionControlPlane(owner, store, credentials, missionDefinitions, controlPort, this.lossPolicy, this);
        AtomicInteger ids = new AtomicInteger();
        this.monitor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(RuntimeContexts.capture(owner, task), "shared-action-runtime-monitor-" + ids.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start(){
        if(!running.compareAndSet(false, true)) return;
        try{
            actionRoot.mkdirs();
            controlPlane.start();
            monitor.scheduleWithFixedDelay(this::monitorSafely, monitorIntervalMillis, monitorIntervalMillis, TimeUnit.MILLISECONDS);
        }catch(Throwable error){
            running.set(false);
            try{ controlPlane.close(); }catch(Throwable ignored){}
            monitor.shutdownNow();
            throw error;
        }
    }

    public boolean isRunning(){ return running.get(); }
    public int controlPort(){ return controlPlane.port(); }
    public String advertisedHost(){ return advertisedHost; }
    public int allocateGamePortForStart(){ requireStarted(); return allocateGamePort(store.snapshot()); }
    public ActionControlPlane controlPlane(){ return controlPlane; }
    public SectorRuntime runtime(String actionId){ return runtimes.get(actionId); }

    /** Starts an Action that the outer coordinator has already committed as STARTING. */
    public RuntimeStart startCommitted(String actionId, Fi sourceSave){
        requireStarted();
        Objects.requireNonNull(actionId, "actionId");
        SharedCampaignState snapshot = store.snapshot();
        ActionState action = snapshot.actions.get(actionId);
        if(action == null) return RuntimeStart.error(actionId, "Unknown Action");
        if(action.status != ActionStatus.starting) return RuntimeStart.error(actionId, "Action is not STARTING: " + action.status);
        if(action.hostGeneration != snapshot.authorityGeneration) return RuntimeStart.error(actionId, "Action host generation is stale");
        if(runtimes.containsKey(actionId)) return RuntimeStart.error(actionId, "Action runtime is already registered");
        String admission = memoryAdmissionError(snapshot, true);
        if(!admission.isEmpty()) return RuntimeStart.error(actionId, admission);

        try{
            return startRuntime(action, sourceSave, false);
        }catch(Throwable error){
            failStart(actionId, "Action runtime start failed: " + error);
            return RuntimeStart.error(actionId, message(error));
        }
    }

    /**
     * Resumes one durable suspended Action as a new runtime incarnation. Campaign-client join/token minting remains an
     * outer coordinator concern; this method returns the newly authoritative endpoint only after the runtime is started.
     */
    public RuntimeStart resumeSuspended(String actorId, String actionId){
        requireStarted();
        synchronized(resumeMutex){
            SharedCampaignState current = store.snapshot();
            ActionState suspended = current.actions.get(actionId);
            if(suspended == null) return RuntimeStart.error(actionId, "Unknown Action");
            if(suspended.status != ActionStatus.suspended) return RuntimeStart.error(actionId, "Action is not suspended: " + suspended.status);
            if(current.runningActions() >= current.maxActiveActions) return RuntimeStart.error(actionId, "No free action slot");
            String admission = memoryAdmissionError(current, false);
            if(!admission.isEmpty()) return RuntimeStart.error(actionId, admission);
            String incompatibility = missionResumeError(current, suspended);
            if(!incompatibility.isEmpty()){
                SharedCampaignState committed = store.transact(actorId, "shared-campaign:mission-incompatible", state -> {
                    ActionState action = requireAction(state, actionId);
                    action.status = ActionStatus.incompatible;
                    action.failureReason = incompatibility;
                    MissionState mission = state.missions.get(action.missionId);
                    if(mission != null) mission.status = MissionStatus.incompatible;
                });
                listener.snapshotCommitted(committed);
                return RuntimeStart.error(actionId, incompatibility);
            }

            int port;
            try{ port = allocateGamePort(current); }
            catch(Throwable error){ return RuntimeStart.error(actionId, message(error)); }

            credentials.deleteActionSecrets(actionId);
            String controlSecret = credentials.createActionControlSecret(actionId);
            String joinSecret = credentials.createActionJoinSecret(actionId);
            String joinHash = SharedCampaignCodec.sha256(ControlProtocol.deriveKey(joinSecret));
            long now = Time.millis();
            long generation = current.authorityGeneration;

            SharedCampaignState committed;
            try{
                committed = store.transact(actorId, "shared-campaign:resume-action", state -> {
                    ActionState action = requireAction(state, actionId);
                    if(action.status != ActionStatus.suspended) throw new IllegalStateException("Action is no longer suspended: " + action.status);
                    if(state.runningActions() >= state.maxActiveActions) throw new IllegalStateException("No free action slot");
                    requireMemoryAdmission(state, false);
                    action.status = ActionStatus.starting;
                    action.hostId = "coordinator";
                    action.hostGeneration = generation;
                    action.runtimeIncarnation = Math.addExact(action.runtimeIncarnation, 1L);
                    action.leaseExpiresAt = now + leaseDurationMillis;
                    action.actionTick = 0L;
                    action.bindAddress = advertisedHost;
                    action.port = port;
                    action.joinSecretHash = joinHash;
                    action.failureReason = "";
                    action.participants.clear();
                    action.spectators.clear();
                    action.connectedPlayers = 0;
                    action.connectedSpectators = 0;
                    action.updatedAt = now;
                    if(!action.missionId.isEmpty()){
                        MissionState mission = state.missions.get(action.missionId);
                        if(mission == null) throw new IllegalStateException("Suspended Action lost mission state: " + action.missionId);
                        mission.status = MissionStatus.preparing;
                    }
                });
            }catch(Throwable error){
                credentials.deleteActionSecrets(actionId);
                return RuntimeStart.error(actionId, message(error));
            }

            listener.snapshotCommitted(committed);
            ActionState runtimeView = SharedCampaignStateCopy.copyAction(requireAction(committed, actionId));
            Fi sourceSave = store.root().child(runtimeView.saveRelativePath);
            try{
                if(!sourceSave.exists()) throw new IOException("Suspended Action save is missing: " + runtimeView.saveRelativePath);
                clearDurableOutcomesForNewIncarnation(actionId);
                // Resume always loads the authoritative save; retained launch metadata is audit/UI history only.
                runtimeView.launchOriginSector = "";
                runtimeView.launchLoadout = "";
                runtimeView.launchResources.clear();
                runtimeView.launchCosts.clear();
                return startRuntime(runtimeView, sourceSave, true, controlSecret, joinSecret);
            }catch(Throwable error){
                failStart(actionId, "Action resume failed: " + error);
                credentials.deleteActionSecrets(actionId);
                return RuntimeStart.error(actionId, message(error));
            }
        }
    }

    /** One deterministic monitor turn, public for product/fault tests. */
    public void monitorOnce(){
        requireStarted();
        long now = Time.millis();
        SharedCampaignState snapshot = store.snapshot();
        ArrayList<Failure> failures = new ArrayList<>();
        for(ActionState action : snapshot.actions.values()){
            if(!action.status.isLive()) continue;
            SectorRuntime runtime = runtimes.get(action.actionId);
            if(runtime != null && !runtime.isAlive()){
                failures.add(new Failure(action.actionId, "Action runtime (" + runtime.backend() + ") exited with code " + runtime.exitCode()));
                continue;
            }
            long observed = controlPlane.lastHeartbeatSeen(action.actionId, action.runtimeIncarnation);
            boolean expired = observed > 0L ? now - observed > leaseDurationMillis : action.leaseExpiresAt > 0L && action.leaseExpiresAt < now;
            if(expired) failures.add(new Failure(action.actionId, "Action heartbeat lease expired"));
        }
        for(Failure failure : failures){
            SectorRuntime runtime = runtimes.remove(failure.actionId);
            if(runtime != null) try{ runtime.close(); }catch(Throwable ignored){}
            listener.routeUnavailable(failure.actionId);
            recoverUnexpected(failure.actionId, failure.reason);
        }
    }

    private RuntimeStart startRuntime(ActionState action, Fi sourceSave, boolean resume) throws IOException{
        String controlSecret = credentials.createActionControlSecret(action.actionId);
        String joinSecret = credentials.createActionJoinSecret(action.actionId);
        String expectedJoinHash = SharedCampaignCodec.sha256(ControlProtocol.deriveKey(joinSecret));
        if(!Objects.equals(expectedJoinHash, action.joinSecretHash)) throw new SecurityException("Action join credential does not match durable authority");
        return startRuntime(action, sourceSave, resume, controlSecret, joinSecret);
    }

    private RuntimeStart startRuntime(ActionState action, Fi sourceSave, boolean resume, String controlSecret, String joinSecret) throws IOException{
        SharedCampaignState campaign = store.snapshot();
        SectorRuntime runtime = runtimeFactory.create(action, actionRoot.child(action.actionId), "127.0.0.1", controlPlane.port(), controlSecret, joinSecret, modsSource, campaign.persistenceProfile);
        try{
            runtime.start(sourceSave);
            SectorRuntime previous = runtimes.putIfAbsent(action.actionId, runtime);
            if(previous != null){ runtime.close(); throw new IllegalStateException("Action runtime was concurrently registered"); }
            return new RuntimeStart(action.actionId, advertisedHost, action.port, action.runtimeIncarnation, runtime.backend(), resume, "");
        }catch(Throwable error){
            try{ runtime.close(); }catch(Throwable ignored){}
            if(error instanceof IOException io) throw io;
            throw new IOException("Could not start Action runtime " + action.actionId, error);
        }
    }

    private void recoverUnexpected(String actionId, String reason){
        SharedCampaignState snapshot = store.snapshot();
        ActionState before = snapshot.actions.get(actionId);
        if(before == null || !before.status.isLive()) return;

        OutcomeRead capture = readOutcome(actionId, SharedActionAgent.captureOutcomeRelativePath, before, true);
        if(capture.invalid){ listener.actionRecovered(before, reason + "; invalid durable capture marker: " + capture.error); return; }
        if(capture.payload != null){
            try{
                SharedCampaignState committed = store.transact("coordinator", "shared-campaign:recover-capture", state ->
                    ActionAuthorityTransitions.applyCaptured(state, actionId, capture.payload, Time.millis()));
                deleteOutcome(actionId, SharedActionAgent.captureOutcomeRelativePath);
                listener.snapshotCommitted(committed);
                before = committed.actions.get(actionId);
                if(before == null || !before.status.isLive()){ credentials.deleteActionSecrets(actionId); return; }
            }catch(Throwable error){ listener.actionRecovered(before, reason + "; capture recovery failed: " + error); return; }
        }

        OutcomeRead finalOutcome = readOutcome(actionId, SharedActionAgent.finalOutcomeRelativePath, before, false);
        if(finalOutcome.invalid){ listener.actionRecovered(before, reason + "; invalid durable final marker: " + finalOutcome.error); return; }
        if(finalOutcome.payload != null){
            try{
                verifyCleanSave(before, finalOutcome.payload);
                final boolean clear = lossPolicy.clearSectorOnLoss(before);
                SharedCampaignState committed = store.transact("coordinator", "shared-campaign:recover-final-outcome", state ->
                    ActionAuthorityTransitions.applyStopped(state, actionId, finalOutcome.payload, clear, Time.millis()));
                deleteOutcome(actionId, SharedActionAgent.finalOutcomeRelativePath);
                credentials.deleteActionSecrets(actionId);
                ActionState recovered = committed.actions.get(actionId);
                listener.snapshotCommitted(committed);
                if(recovered != null) listener.actionStopped(recovered);
                return;
            }catch(Throwable error){ listener.actionRecovered(before, reason + "; final-outcome recovery failed: " + error); return; }
        }

        Fi save = store.root().child(before.saveRelativePath);
        try{
            Fi valid = recoverPrimarySave(save);
            if(valid == null){
                failStart(actionId, reason + "; no valid Action autosave was available");
                credentials.deleteActionSecrets(actionId);
                return;
            }
            String hash = SharedFileDigests.sha256(valid);
            SharedCampaignState committed = store.transact("coordinator", "shared-campaign:recover-action-save", state ->
                ActionAuthorityTransitions.recoverSuspended(state, actionId, hash, reason, Time.millis()));
            credentials.deleteActionSecrets(actionId);
            listener.snapshotCommitted(committed);
            ActionState recovered = committed.actions.get(actionId);
            if(recovered != null) listener.actionRecovered(recovered, reason);
        }catch(Throwable error){
            failStart(actionId, reason + "; autosave recovery failed: " + error);
            credentials.deleteActionSecrets(actionId);
        }
    }

    private String missionResumeError(SharedCampaignState state, ActionState action){
        if(action.missionId == null || action.missionId.isBlank()) return "";
        MissionState persisted = state.missions.get(action.missionId);
        SharedCampaignMissionRegistry.MissionDefinition definition;
        try{ definition = missionDefinitions.require(action.missionId); }
        catch(Throwable error){ return "Mission definition is unavailable: " + action.missionId; }
        if(persisted == null || !Objects.equals(persisted.definitionVersion, Integer.toString(definition.version()))
            || !Objects.equals(persisted.definitionFingerprint, definition.fingerprint())) return "Mission definition changed incompatibly";
        return "";
    }

    private int allocateGamePort(SharedCampaignState state){
        HashSet<Integer> used = new HashSet<>();
        for(ActionState action : state.actions.values()) if(action.status.isLive() && action.port > 0) used.add(action.port);
        for(int port = 6567; port <= 6667; port++) if(port != controlPlane.port() && !used.contains(port) && portAvailable(port)) return port;
        throw new IllegalStateException("No free Action server port");
    }

    private static boolean portAvailable(int port){
        try(ServerSocket socket = new ServerSocket()){
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        }catch(IOException ignored){ return false; }
    }

    private String memoryAdmissionError(SharedCampaignState state, boolean candidateAlreadyLive){
        SectorRuntime.Backend backend = runtimeFactory.expectedBackend();
        if(backend == null) return "";
        int live = state.runningActions() - (candidateAlreadyLive ? 1 : 0);
        ActionMemoryAdmission.Decision decision = ActionMemoryAdmission.evaluate(backend, Math.max(0, live));
        return decision.allowed() ? "" : decision.reason();
    }

    private void requireMemoryAdmission(SharedCampaignState state, boolean candidateAlreadyLive){
        String error = memoryAdmissionError(state, candidateAlreadyLive);
        if(!error.isEmpty()) throw new IllegalStateException(error);
    }

    private void failStart(String actionId, String reason){
        SharedCampaignState committed = store.transact("coordinator", "shared-campaign:runtime-start-failed", state ->
            ActionAuthorityTransitions.markFailed(state, actionId, reason, Time.millis()));
        listener.snapshotCommitted(committed);
    }

    private void verifyCleanSave(ActionState action, RuntimePayloads.ActionStopped stopped){
        if(!stopped.clean()) return;
        Fi save = store.root().child(action.saveRelativePath);
        if(!save.exists()) throw new SecurityException("Clean Action stop has no save");
        String actual = SharedFileDigests.sha256(save);
        if(!Objects.equals(actual, stopped.saveHash())) throw new SecurityException("Clean Action save hash mismatch");
    }

    private OutcomeRead readOutcome(String actionId, String relative, ActionState expected, boolean capture){
        Fi file = actionRoot.child(actionId).child(relative);
        if(!file.exists()) return OutcomeRead.absent();
        try{
            if(file.length() <= 0) throw new IOException("Durable outcome is empty");
            RuntimePayloads.ActionStopped value = RuntimePayloads.stopped(file.readBytes());
            if(!Objects.equals(actionId, value.actionId()) || value.generation() != expected.hostGeneration || value.runtimeIncarnation() != expected.runtimeIncarnation){
                throw new SecurityException("Durable outcome identity/generation/incarnation mismatch");
            }
            if(capture && (!value.clean() || !value.completed())) throw new SecurityException("Invalid durable capture outcome");
            return OutcomeRead.valid(value);
        }catch(Throwable error){ return OutcomeRead.invalid(error); }
    }

    private void clearDurableOutcomesForNewIncarnation(String actionId) throws IOException{
        deleteOutcomeChecked(actionId, SharedActionAgent.captureOutcomeRelativePath);
        deleteOutcomeChecked(actionId, SharedActionAgent.finalOutcomeRelativePath);
    }

    private void deleteOutcome(String actionId, String relative){
        try{ deleteOutcomeChecked(actionId, relative); }
        catch(IOException error){ Log.err("Could not delete stale Shared Action outcome", error); }
    }

    private void deleteOutcomeChecked(String actionId, String relative) throws IOException{
        Path path = actionRoot.child(actionId).child(relative).file().toPath();
        Files.deleteIfExists(path);
        Path parent = path.toAbsolutePath().getParent();
        if(parent != null){
            try(FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)){ channel.force(true); }
            catch(IOException | UnsupportedOperationException ignored){}
        }
    }

    private static Fi recoverPrimarySave(Fi primary) throws IOException{
        if(isSaveValid(primary)) return primary;
        Fi backup = SaveIO.backupFileFor(primary);
        if(!isSaveValid(backup)) return null;
        primary.parent().mkdirs();
        if(primary.exists()) primary.moveTo(primary.sibling(primary.name() + ".corrupt-" + Time.millis()));
        Files.copy(backup.file().toPath(), primary.file().toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return isSaveValid(primary) ? primary : null;
    }

    private static boolean isSaveValid(Fi file){
        if(file == null || !file.exists()) return false;
        try(DataInputStream stream = new DataInputStream(new InflaterInputStream(new BufferedInputStream(file.read())))){
            return SaveIO.isSaveValid(stream);
        }catch(Throwable ignored){ return false; }
    }

    private static ActionState requireAction(SharedCampaignState state, String actionId){
        ActionState action = state.actions.get(actionId);
        if(action == null) throw new IllegalArgumentException("Unknown Action " + actionId);
        return action;
    }

    private static String message(Throwable error){
        String value = error == null ? "unknown error" : error.getMessage();
        return value == null || value.isBlank() ? String.valueOf(error) : value;
    }

    private void monitorSafely(){
        if(!running.get()) return;
        try{ monitorOnce(); }
        catch(Throwable error){ if(running.get()) Log.err("Shared Action runtime monitor failed", error); }
    }

    private void requireStarted(){ if(!running.get()) throw new IllegalStateException("Action runtime coordinator is not started"); }

    @Override public void snapshotCommitted(SharedCampaignState state){ listener.snapshotCommitted(state); }
    @Override public void routeAvailable(String actionId, int gamePort){ listener.routeAvailable(actionId, gamePort); }
    @Override public void routeUnavailable(String actionId){ listener.routeUnavailable(actionId); }
    @Override public void actionBecameRunning(ActionState action){ listener.actionBecameRunning(action); }
    @Override public void actionIncompatible(ActionState action){ listener.snapshotCommitted(store.snapshot()); }
    @Override public void actionStopped(ActionState action){
        SectorRuntime runtime = runtimes.remove(action.actionId);
        if(runtime != null) try{ runtime.close(); }catch(Throwable ignored){}
        listener.actionStopped(action);
    }

    @Override public synchronized void close(){
        if(!running.compareAndSet(true, false)) return;
        monitor.shutdownNow();
        controlPlane.close();
        for(SectorRuntime runtime : runtimes.values()) try{ runtime.close(); }catch(Throwable ignored){}
        runtimes.clear();
    }

    public record RuntimeStart(String actionId, String host, int port, long runtimeIncarnation, SectorRuntime.Backend backend,
                               boolean resumed, String error){
        static RuntimeStart error(String actionId, String error){ return new RuntimeStart(actionId == null ? "" : actionId, "", 0, 0L, null, false, error == null ? "" : error); }
        public boolean success(){ return error == null || error.isBlank(); }
    }

    private record Failure(String actionId, String reason){}
    private record OutcomeRead(RuntimePayloads.ActionStopped payload, boolean invalid, String error){
        static OutcomeRead absent(){ return new OutcomeRead(null, false, ""); }
        static OutcomeRead valid(RuntimePayloads.ActionStopped payload){ return new OutcomeRead(payload, false, ""); }
        static OutcomeRead invalid(Throwable error){ return new OutcomeRead(null, true, message(error)); }
    }
}
