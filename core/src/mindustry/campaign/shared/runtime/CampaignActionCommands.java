package mindustry.campaign.shared.runtime;

import arc.util.*;
import arc.struct.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.api.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.game.Objectives.*;
import mindustry.type.*;
import arc.files.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Authenticated campaign-client operations over already-created Shared Actions.
 *
 * <p>This layer deliberately owns no transport. Campaign-client control connections call it after authentication,
 * while all durable mutations still go through {@link SharedCampaignStore}. Action presence is never fabricated from
 * token issuance: only Action heartbeats may populate participants/spectators.</p>
 */
public final class CampaignActionCommands{
    private final SharedCampaignStore store;
    private final CoordinatorCredentials credentials;
    private final ActionRuntimeCoordinator runtimes;
    private final SharedActionEntryRouter entry;
    private final SharedCampaignMissionRegistry missions;
    private final SharedCampaignPlanetRegistry planetPolicies;
    private final Object startMutex = new Object();
    private final Object researchMutex = new Object();
    private final Object logisticsMutex = new Object();
    /** Terminal live-origin decisions acknowledged during this coordinator lifetime. They remain durably idempotent
     * on the Action side, so a host restart intentionally clears this cache and replays once again. */
    private final Set<String> replayedLaunchDecisions = ConcurrentHashMap.newKeySet();

    public CampaignActionCommands(SharedCampaignStore store, CoordinatorCredentials credentials,
                                  ActionRuntimeCoordinator runtimes, SharedActionEntryRouter entry,
                                  SharedCampaignMissionRegistry missions, SharedCampaignPlanetRegistry planetPolicies){
        this.store = Objects.requireNonNull(store, "store");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.missions = missions;
        this.planetPolicies = Objects.requireNonNull(planetPolicies, "planetPolicies");
    }

    /**
     * Reconciles durable launch transactions after coordinator/runtime state changes.
     *
     * <p>The transaction and Action are committed in separate durability windows by design: a live source may already
     * have a persisted reservation while the coordinator still says PREPARING; an Action may already be RUNNING while
     * its launch transaction is only PREPARED; and a terminal COMMIT/ABORT decision may have been lost after the
     * coordinator durably chose it. This method makes every one of those crash windows converge without double debit
     * or refund. It is safe to call repeatedly.</p>
     */
    public void reconcileLaunchTransactions(){
        SharedCampaignState snapshot = store.snapshot();
        boolean needsMutation = false;
        for(LaunchTransaction tx : snapshot.launchTransactions.values()){
            if(tx.status == LaunchTransactionStatus.preparing){
                needsMutation = true;
                break;
            }
            if(tx.status != LaunchTransactionStatus.prepared) continue;
            ActionState action = snapshot.actions.get(tx.actionId);
            if(action == null || action.status == ActionStatus.failed || action.status == ActionStatus.incompatible
                || action.status == ActionStatus.running || action.status == ActionStatus.suspending
                || action.status == ActionStatus.suspended || action.status == ActionStatus.completed || action.launchCommitted){
                needsMutation = true;
                break;
            }
        }

        if(needsMutation){
            snapshot = store.transact("coordinator", "shared-campaign:reconcile-launch-transactions", state -> {
                Seq<LaunchTransaction> transactions = state.launchTransactions.values().toSeq();
                for(LaunchTransaction tx : transactions){
                    if(tx.status == LaunchTransactionStatus.preparing){
                        // PREPARING never changed the coordinator-owned strategic mirror. A live origin may already
                        // have a durable Action-side reservation, so terminal ABORT is persisted here and replayed
                        // below whenever that source Action is available.
                        SharedLaunchTransactions.abort(state, tx.transactionId, "Coordinator restarted before launch prepare committed", Time.millis());
                        continue;
                    }
                    if(tx.status != LaunchTransactionStatus.prepared) continue;
                    ActionState action = state.actions.get(tx.actionId);
                    if(action == null || action.status == ActionStatus.failed || action.status == ActionStatus.incompatible){
                        SharedLaunchTransactions.abort(state, tx.transactionId, "Launch Action did not survive coordinator restart", Time.millis());
                    }else if(action.launchCommitted || action.status == ActionStatus.running || action.status == ActionStatus.suspending
                        || action.status == ActionStatus.suspended || action.status == ActionStatus.completed){
                        // Once an Action progressed beyond STARTING, launch resources must stay consumed. This also
                        // closes the crash window after a successful runtime hello but before the coordinator's final
                        // launch-commit transaction.
                        SharedLaunchTransactions.commit(state, tx.transactionId, Time.millis());
                    }
                    // STARTING/PREPARING Actions are deliberately left PREPARED. A surviving child may reconnect and
                    // commit; otherwise the ordinary runtime lease monitor marks it FAILED, which triggers this method
                    // again through snapshotCommitted and refunds exactly once.
                }
            });
        }
        replayTerminalLiveLaunchDecisions(snapshot);
    }

    /** Replays terminal decisions sourced by one Action after it reconnects. */
    public void reconcileLaunchTransactionsForSource(String sourceActionId){
        if(sourceActionId == null || sourceActionId.isBlank()) return;
        replayTerminalLiveLaunchDecisions(store.snapshot(), sourceActionId);
    }

    private void replayTerminalLiveLaunchDecisions(SharedCampaignState state){
        replayTerminalLiveLaunchDecisions(state, null);
    }

    private void replayTerminalLiveLaunchDecisions(SharedCampaignState state, String onlySourceActionId){
        for(LaunchTransaction tx : state.launchTransactions.values()){
            if(tx.sourceActionId == null || tx.sourceActionId.isBlank()) continue;
            if(onlySourceActionId != null && !onlySourceActionId.equals(tx.sourceActionId)) continue;
            if(tx.status != LaunchTransactionStatus.committed && tx.status != LaunchTransactionStatus.aborted) continue;
            if(replayedLaunchDecisions.contains(tx.transactionId)) continue;
            boolean commit = tx.status == LaunchTransactionStatus.committed;
            if(decideLiveLaunch(tx.sourceActionId, tx.transactionId, commit, false, false)){
                replayedLaunchDecisions.add(tx.transactionId);
            }
        }
    }


    /**
     * Chooses resume/existing/fresh semantics from durable campaign state rather than from optional client launch fields.
     * A retained save from a lost clearSectorOnLose=false sector is reconstruction input for a fresh expedition, not a
     * resumable authoritative base.
     */
    public RuntimePayloads.StartResult startSector(String memberId, String planetName, String sectorName, String missionId, RuntimePayloads.LaunchPlan requestedLaunch){
        requireMember(memberId);
        if(planetName == null || planetName.isBlank() || sectorName == null || sectorName.isBlank()) throw new IllegalArgumentException("Planet and sector are required");
        SharedCampaignState snapshot = store.snapshot();
        requireMember(snapshot, memberId);
        for(ActionState candidate : snapshot.actions.values()){
            if(!planetName.equals(candidate.planetName) || !sectorName.equals(candidate.sectorName)) continue;
            if(candidate.status.isLive()) return new RuntimePayloads.StartResult(candidate.actionId, candidate.bindAddress, candidate.port, "", "");
            if(candidate.status == ActionStatus.suspended){
                ActionRuntimeCoordinator.RuntimeStart resumed = runtimes.resumeSuspended(memberId, candidate.actionId);
                return new RuntimePayloads.StartResult(candidate.actionId, resumed.host(), resumed.port(), "", resumed.error());
            }
        }

        String key = SharedCampaignSectors.sectorKey(planetName, sectorName);
        SectorState sector = snapshot.sectors.get(key);
        if(sector != null && sector.saveRelativePath != null && !sector.saveRelativePath.isBlank() && !requiresLostSectorRelaunch(sector)){
            return startExistingSector(memberId, planetName, sectorName, missionId);
        }

        Fi reconstructionSave = null;
        if(requiresLostSectorRelaunch(sector)){
            Fi candidate = store.root().child(sector.saveRelativePath);
            if(candidate.exists()) reconstructionSave = candidate;
        }
        return startFreshSector(memberId, planetName, sectorName, missionId, requestedLaunch, reconstructionSave);
    }

    static boolean requiresLostSectorRelaunch(SectorState sector){
        return sector != null && sector.saveRelativePath != null && !sector.saveRelativePath.isBlank() && !sector.hasBase && !sector.captured;
    }


    /** Starts an Action from an already-authoritative sector save; fresh expedition launch economics are handled separately. */
    public RuntimePayloads.StartResult startExistingSector(String memberId, String planetName, String sectorName, String missionId){
        requireMember(memberId);
        if(planetName == null || planetName.isBlank() || sectorName == null || sectorName.isBlank()) throw new IllegalArgumentException("Planet and sector are required");
        synchronized(startMutex){
            SharedCampaignState current = store.snapshot();
            requireMember(current, memberId);
            for(ActionState candidate : current.actions.values()){
                if(planetName.equals(candidate.planetName) && sectorName.equals(candidate.sectorName)){
                    if(candidate.status.isLive()) return new RuntimePayloads.StartResult(candidate.actionId, candidate.bindAddress, candidate.port, "", "");
                    if(candidate.status == ActionStatus.suspended){
                        ActionRuntimeCoordinator.RuntimeStart resumed = runtimes.resumeSuspended(memberId, candidate.actionId);
                        return new RuntimePayloads.StartResult(candidate.actionId, resumed.host(), resumed.port(), "", resumed.error());
                    }
                }
            }
            planetPolicies.validateActionStart(current, planetName, sectorName, missionId);
            if(current.runningActions() >= current.maxActiveActions) return new RuntimePayloads.StartResult("", "", 0, "", "No free action slot");
            String sectorKey = SharedCampaignSectors.sectorKey(planetName, sectorName);
            SectorState sector = current.sectors.get(sectorKey);
            if(sector == null || sector.saveRelativePath == null || sector.saveRelativePath.isBlank()) return new RuntimePayloads.StartResult("", "", 0, "", "Sector has no authoritative save");
            Fi sourceSave = store.root().child(sector.saveRelativePath);
            if(!sourceSave.exists()) return new RuntimePayloads.StartResult("", "", 0, "", "Sector save is missing: " + sector.saveRelativePath);

            SharedCampaignMissionRegistry.MissionDefinition mission = null;
            String cleanMission = missionId == null ? "" : missionId.trim();
            if(!cleanMission.isEmpty()){
                if(missions == null) throw new IllegalStateException("Mission registry is unavailable");
                mission = missions.require(cleanMission);
                String boundPlanet = mission.definition().planetName(), boundSector = mission.definition().sectorName();
                if(!boundPlanet.isBlank() && (!boundPlanet.equals(planetName) || !boundSector.equals(sectorName))) throw new IllegalArgumentException("Mission is bound to a different sector: " + cleanMission);
            }

            String actionId = UUID.randomUUID().toString();
            String attemptId = cleanMission.isEmpty() ? "" : UUID.randomUUID().toString();
            int port = runtimes.allocateGamePortForStart();
            String joinSecret = credentials.createActionJoinSecret(actionId);
            credentials.createActionControlSecret(actionId);
            String joinHash = SharedCampaignCodec.sha256(ControlProtocol.deriveKey(joinSecret));
            long now = Time.millis();
            SharedCampaignMissionRegistry.MissionDefinition finalMission = mission;
            SharedCampaignState committed;
            try{
                committed = store.transact(memberId, "shared-campaign:start-existing-sector", state -> {
                    requireMember(state, memberId);
                    planetPolicies.validateActionStart(state, planetName, sectorName, cleanMission);
                    if(state.runningActions() >= state.maxActiveActions) throw new IllegalStateException("No free action slot");
                    for(ActionState candidate : state.actions.values()) if(candidate.status.isLive() && planetName.equals(candidate.planetName) && sectorName.equals(candidate.sectorName)) throw new IllegalStateException("Sector already has a live Action");
                    ActionState action = new ActionState();
                    action.actionId = actionId; action.planetName = planetName; action.sectorName = sectorName; action.missionId = cleanMission; action.attemptId = attemptId;
                    action.status = ActionStatus.starting; action.kind = cleanMission.isEmpty() ? ActionKind.expedition : ActionKind.storyMission;
                    action.hostId = "coordinator"; action.hostGeneration = state.authorityGeneration; action.runtimeIncarnation = 1L; action.leaseExpiresAt = now + ActionAuthorityTransitions.leaseDurationMillis;
                    action.bindAddress = runtimes.advertisedHost(); action.port = port; action.joinSecretHash = joinHash;
                    action.saveRelativePath = "actions/" + actionId + "/config/saves/action.msav"; action.launchCommitted = true;
                    SectorState authoritative = state.sectors.get(sectorKey);
                    if(authoritative == null || authoritative.saveRelativePath == null || authoritative.saveRelativePath.isBlank()) throw new IllegalStateException("Sector save disappeared before Action commit");
                    action.summary = authoritative.summary == null ? new SectorSummary() : authoritative.summary.strategicCopy();
                    action.summary.planetName = planetName;
                    planetPolicies.beforeActionCommit(state, action);
                    state.actions.put(actionId, action);
                    touchMember(state, memberId, actionId);
                    planetPolicies.afterActionCommit(state, action);
                    if(finalMission != null){
                        for(ActionState candidate : state.actions.values()) if(candidate != action && candidate.status.isLive() && cleanMission.equals(candidate.missionId)) throw new IllegalStateException("Mission already has a live authoritative Action");
                        MissionState ms = state.missions.get(cleanMission, MissionState::new);
                        ms.missionId = cleanMission; ms.planetName = planetName; ms.sectorName = sectorName; ms.attemptId = attemptId;
                        ms.definitionVersion = Integer.toString(finalMission.version()); ms.definitionFingerprint = finalMission.fingerprint(); ms.status = MissionStatus.preparing;
                        state.missions.put(cleanMission, ms);
                    }
                });
            }catch(Throwable error){
                credentials.deleteActionSecrets(actionId);
                throw error;
            }
            ActionRuntimeCoordinator.RuntimeStart started = runtimes.startCommitted(actionId, sourceSave);
            if(started.error() != null && !started.error().isBlank()) return new RuntimePayloads.StartResult(actionId, runtimes.advertisedHost(), port, "", started.error());
            ActionState action = committed.actions.get(actionId);
            return new RuntimePayloads.StartResult(actionId, action.bindAddress, action.port, "", "");
        }
    }

    /**
     * Starts a fresh expedition with authoritative launch economics. Live origins use the same durable Action-side
     * reservation protocol as research: the source world prepares and saves the debit before the destination Action
     * is committed, while the coordinator records the launch transaction and never debits that live inventory itself.
     */
    public RuntimePayloads.StartResult startFreshSector(String memberId, String planetName, String sectorName,
                                                        String missionId, RuntimePayloads.LaunchPlan requestedLaunch){
        return startFreshSector(memberId, planetName, sectorName, missionId, requestedLaunch, null);
    }

    private RuntimePayloads.StartResult startFreshSector(String memberId, String planetName, String sectorName,
                                                         String missionId, RuntimePayloads.LaunchPlan requestedLaunch, Fi reconstructionSave){
        requireMember(memberId);
        if(planetName == null || planetName.isBlank() || sectorName == null || sectorName.isBlank()) throw new IllegalArgumentException("Planet and sector are required");
        synchronized(startMutex){
            SharedCampaignState current = store.snapshot();
            requireMember(current, memberId);
            SectorState durableSector = current.sectors.get(SharedCampaignSectors.sectorKey(planetName, sectorName));
            if(durableSector != null && durableSector.saveRelativePath != null && !durableSector.saveRelativePath.isBlank() && !requiresLostSectorRelaunch(durableSector)){
                return new RuntimePayloads.StartResult("", "", 0, "", "Sector already has an authoritative resumable save");
            }
            for(ActionState candidate : current.actions.values()){
                if(planetName.equals(candidate.planetName) && sectorName.equals(candidate.sectorName)){
                    if(candidate.status.isLive()) return new RuntimePayloads.StartResult(candidate.actionId, candidate.bindAddress, candidate.port, "", "");
                    if(candidate.status == ActionStatus.suspended){
                        ActionRuntimeCoordinator.RuntimeStart resumed = runtimes.resumeSuspended(memberId, candidate.actionId);
                        return new RuntimePayloads.StartResult(candidate.actionId, resumed.host(), resumed.port(), "", resumed.error());
                    }
                }
            }
            String cleanMission = missionId == null ? "" : missionId.trim();
            planetPolicies.validateActionStart(current, planetName, sectorName, cleanMission);
            if(current.runningActions() >= current.maxActiveActions) return new RuntimePayloads.StartResult("", "", 0, "", "No free action slot");

            SharedCampaignMissionRegistry.MissionDefinition mission = requireMission(cleanMission, planetName, sectorName);
            SharedLaunchPlanner.PreparedLaunch launch = SharedLaunchPlanner.prepare(current, planetName, sectorName,
                requestedLaunch == null ? RuntimePayloads.LaunchPlan.empty() : requestedLaunch);
            String actionId = UUID.randomUUID().toString();
            String attemptId = cleanMission.isEmpty() ? "" : UUID.randomUUID().toString();
            String transactionId = launch.costs().isEmpty() ? "" : UUID.randomUUID().toString();
            RuntimePayloads.ResearchPrepareResult livePrepared = null;

            if(!transactionId.isBlank()){
                store.transact(memberId, "shared-campaign:launch-begin", state -> {
                    requireMember(state, memberId);
                    SharedLaunchTransactions.begin(state, transactionId, actionId, memberId, launch, Time.millis());
                });
                if(!launch.sourceActionId().isBlank()){
                    try{
                        livePrepared = prepareLiveLaunch(transactionId, launch);
                    }catch(Throwable failure){
                        String error = message(failure);
                        abortLaunch(memberId, transactionId, launch.sourceActionId(), error);
                        return new RuntimePayloads.StartResult(actionId, runtimes.advertisedHost(), 0, "", error);
                    }
                    if(!livePrepared.success()){
                        String error = livePrepared.error() == null || livePrepared.error().isBlank() ? "Live launch origin rejected reservation" : livePrepared.error();
                        abortLaunch(memberId, transactionId, launch.sourceActionId(), error);
                        return new RuntimePayloads.StartResult(actionId, runtimes.advertisedHost(), 0, "", error);
                    }
                }
            }

            int port;
            String joinSecret;
            try{
                port = runtimes.allocateGamePortForStart();
                joinSecret = credentials.createActionJoinSecret(actionId);
                credentials.createActionControlSecret(actionId);
            }catch(Throwable error){
                abortLaunch(memberId, transactionId, launch.sourceActionId(), message(error));
                credentials.deleteActionSecrets(actionId);
                return new RuntimePayloads.StartResult(actionId, runtimes.advertisedHost(), 0, "", message(error));
            }
            String joinHash = SharedCampaignCodec.sha256(ControlProtocol.deriveKey(joinSecret));
            SectorSummary preparedSummary = livePrepared == null ? null : livePrepared.summary();
            SharedCampaignMissionRegistry.MissionDefinition finalMission = mission;
            long now = Time.millis();

            try{
                store.transact(memberId, "shared-campaign:start-fresh-sector", state -> {
                    requireMember(state, memberId);
                    planetPolicies.validateActionStart(state, planetName, sectorName, cleanMission);
                    if(state.runningActions() >= state.maxActiveActions) throw new IllegalStateException("No free action slot");
                    for(ActionState candidate : state.actions.values()){
                        if(candidate.status.isLive() && planetName.equals(candidate.planetName) && sectorName.equals(candidate.sectorName)) throw new IllegalStateException("Sector already has a live Action");
                        if(!cleanMission.isEmpty() && candidate.status.isLive() && cleanMission.equals(candidate.missionId)) throw new IllegalStateException("Mission already has a live authoritative Action");
                    }
                    var destination = SharedCampaignProgress.findSector(planetName, sectorName);
                    var availability = SharedCampaignProgress.sectorAvailability(state, destination);
                    if(!availability.available()) throw new IllegalStateException("Sector is no longer available for launch: " + sectorName);
                    if(!launch.plan().originSector().isBlank()){
                        SectorState origin = state.sectors.get(launch.plan().originSector());
                        if(!SharedCampaignProgress.launchSourceEligible(origin, destination)) throw new IllegalStateException("Launch origin is no longer eligible: " + launch.plan().originSector());
                    }
                    SectorState durableDestination = state.sectors.get(SharedCampaignSectors.sectorKey(planetName, sectorName));
                    if(reconstructionSave != null && !requiresLostSectorRelaunch(durableDestination)) throw new IllegalStateException("Lost-sector reconstruction is no longer applicable");
                    if(reconstructionSave == null && durableDestination != null && durableDestination.saveRelativePath != null && !durableDestination.saveRelativePath.isBlank() && !requiresLostSectorRelaunch(durableDestination)) throw new IllegalStateException("Sector became resumable before fresh Action commit");
                    if(!transactionId.isBlank()){
                        if(launch.sourceActionId().isBlank()) SharedLaunchTransactions.prepareOffline(state, transactionId, Time.millis());
                        else SharedLaunchTransactions.prepareLive(state, transactionId, preparedSummary, Time.millis());
                    }

                    ActionState action = new ActionState();
                    action.actionId = actionId; action.planetName = planetName; action.sectorName = sectorName;
                    action.missionId = cleanMission; action.attemptId = attemptId; action.status = ActionStatus.starting;
                    action.kind = cleanMission.isEmpty() ? ActionKind.expedition : ActionKind.storyMission;
                    action.hostId = "coordinator"; action.hostGeneration = state.authorityGeneration; action.runtimeIncarnation = 1L;
                    action.leaseExpiresAt = now + ActionAuthorityTransitions.leaseDurationMillis;
                    action.bindAddress = runtimes.advertisedHost(); action.port = port; action.joinSecretHash = joinHash;
                    action.saveRelativePath = "actions/" + actionId + "/config/saves/action.msav";
                    action.launchOriginSector = launch.plan().originSector(); action.launchLoadout = launch.plan().loadout();
                    action.launchResources.putAll(launch.plan().resources()); action.launchCosts.putAll(launch.costs());
                    action.launchCommitted = transactionId.isBlank();

                    String sectorKey = SharedCampaignSectors.sectorKey(planetName, sectorName);
                    SectorState destinationState = state.sectors.get(sectorKey);
                    if(destinationState == null){ destinationState = new SectorState(); destinationState.planetName = planetName; destinationState.sectorName = sectorName; state.sectors.put(sectorKey, destinationState); }
                    if(destinationState.summary == null) destinationState.summary = new SectorSummary();
                    destinationState.summary.planetName = planetName;
                    action.summary = destinationState.summary.strategicCopy();
                    action.summary.planetName = planetName;
                    planetPolicies.beforeActionCommit(state, action);
                    state.actions.put(actionId, action);
                    touchMember(state, memberId, actionId);
                    planetPolicies.afterActionCommit(state, action);
                    if(finalMission != null){
                        MissionState ms = state.missions.get(cleanMission, MissionState::new);
                        ms.missionId = cleanMission; ms.planetName = planetName; ms.sectorName = sectorName; ms.attemptId = attemptId;
                        ms.definitionVersion = Integer.toString(finalMission.version()); ms.definitionFingerprint = finalMission.fingerprint(); ms.status = MissionStatus.preparing;
                        state.missions.put(cleanMission, ms);
                    }
                });
            }catch(Throwable error){
                abortLaunch(memberId, transactionId, launch.sourceActionId(), message(error));
                credentials.deleteActionSecrets(actionId);
                if(error instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(error);
            }

            ActionRuntimeCoordinator.RuntimeStart started = runtimes.startCommitted(actionId, reconstructionSave);
            if(started.error() != null && !started.error().isBlank()){
                abortLaunch(memberId, transactionId, launch.sourceActionId(), started.error());
                credentials.deleteActionSecrets(actionId);
                return new RuntimePayloads.StartResult(actionId, runtimes.advertisedHost(), port, "", started.error());
            }

            if(!transactionId.isBlank()){
                store.transact(memberId, "shared-campaign:launch-commit", state -> SharedLaunchTransactions.commit(state, transactionId, Time.millis()));
                if(!launch.sourceActionId().isBlank()) decideLiveLaunch(launch.sourceActionId(), transactionId, true, false);
            }
            ActionState action = requireAction(store.snapshot(), actionId);
            return new RuntimePayloads.StartResult(actionId, action.bindAddress, action.port, "", "");
        }
    }

    public RuntimePayloads.JoinResult join(String memberId, String actionId, boolean spectator, String networkUuid){
        requireMember(memberId);
        ActionState before = requireAction(store.snapshot(), actionId);
        if(before.status == ActionStatus.starting) return joinError(before, "Action is still starting");
        if(before.status != ActionStatus.running) return joinError(before, "Action is not joinable: " + before.status);
        if(networkUuid == null || networkUuid.isBlank()) return joinError(before, "Mindustry network UUID is required for action admission");
        if(spectator && !before.spectatorsAllowed) return joinError(before, "This action does not allow spectators");

        final String[] previousActionId = new String[1];
        SharedCampaignState committed = store.transact(memberId, spectator ? "shared-campaign:spectate-action" : "shared-campaign:join-action", state -> {
            requireMember(state, memberId);
            ActionState action = requireAction(state, actionId);
            if(action.status != ActionStatus.running) throw new IllegalStateException("Action is not joinable: " + action.status);
            if(spectator && !action.spectatorsAllowed) throw new SecurityException("This action does not allow spectators");
            // The join is the only place where "member switched from A to B" is observable, so record it as a campaign
            // event before touchMember overwrites durable history: every join appends, keeping this member's latest event
            // equal to their current connection (empty "from" means they entered from outside any live Action).
            ActionState previous = SharedCampaignProgress.currentMemberAction(state, memberId, true);
            previousActionId[0] = previous == null ? "" : previous.actionId;
            appendMemberSectorJoin(state, memberId,
                (previous == null ? "" : ActionAuthorityTransitions.sectorKey(previous)) + " -> " + ActionAuthorityTransitions.sectorKey(action));
            touchMember(state, memberId, actionId);
        });
        // A member may enroll after this Action completed its initial control handshake. Keep Action-side
        // membership validation strict: before issuing a signed one-shot join token, push the exact authority
        // revision that contains this membership/touch mutation and wait for the live Action to apply it.
        // This is the enhanced-client equivalent of the vanilla-transfer synchronization barrier below.
        synchronizeActionSnapshot(actionId, committed, committed.revision);
        // Hot-switch closes the source Action's world side right after this returns, racing the eventual snapshot
        // writer; push the committed event onto the source synchronously so its disconnect notice can still say
        // "from A to B" instead of degrading to "exit". A dead/slow source must not fail the join, only the notice.
        String sourceActionId = previousActionId[0] == null ? "" : previousActionId[0];
        if(!sourceActionId.isBlank() && !sourceActionId.equals(actionId)){
            try{
                runtimes.controlPlane().request(sourceActionId, ControlProtocol.Type.snapshotApplyRequest,
                    SharedCampaignCodec.encodeStrategic(committed), ControlProtocol.Type.snapshotApplyResponse, 2_000L);
            }catch(RuntimeException error){
                Log.warn("Join transition notice for action @ was not applied in time: @", sourceActionId, error.getMessage());
            }
        }
        ActionState action = requireAction(committed, actionId);
        String token = credentials.issueActionJoinToken(actionId, networkUuid, memberId, spectator, admissionValidityMillis());
        return new RuntimePayloads.JoinResult(actionId, action.bindAddress, action.port, token, "");
    }

    /** Appends the {@code member-sector-join} campaign event consumed by NetServer's connect/disconnect notices. */
    private static void appendMemberSectorJoin(SharedCampaignState state, String memberId, String payload){
        SharedCampaignState.CampaignEvent event = new SharedCampaignState.CampaignEvent();
        event.sequence = state.recentEvents.isEmpty() ? 1L : state.recentEvents.peek().sequence + 1L;
        event.timestamp = arc.util.Time.millis();
        event.type = "member-sector-join";
        event.subjectId = memberId;
        event.payload = payload;
        state.recentEvents.add(event);
        while(state.recentEvents.size > 2048) state.recentEvents.remove(0);
    }

    /**
     * Stages a short-lived direct Action admission for an unmodified vanilla client that is currently authenticated
     * on another Shared Action. Existing suspended Actions may be resumed, but this path never creates a fresh
     * expedition and therefore cannot bypass launch-cost/loadout/origin rules.
     */
    public RuntimePayloads.VanillaTransferResult prepareVanillaTransfer(String sourceActionId, RuntimePayloads.VanillaTransferRequest request){
        Objects.requireNonNull(request, "vanilla transfer request");
        try{
            String sourceId = sourceActionId == null ? "" : sourceActionId.trim();
            if(sourceId.isBlank() || !sourceId.equals(request.fromActionId())) throw new SecurityException("Vanilla transfer source Action mismatch");
            String memberId = request.memberId() == null ? "" : request.memberId().trim();
            String networkUuid = request.networkUuid() == null ? "" : request.networkUuid().trim();
            String reportedAddress = request.remoteAddress() == null ? "" : request.remoteAddress().trim();
            String targetName = request.target() == null ? "" : request.target().trim();
            requireMember(memberId);
            if(networkUuid.isBlank()) throw new IllegalArgumentException("Mindustry network UUID is required");
            if(reportedAddress.isBlank()) throw new IllegalArgumentException("Source client address is required");
            if(targetName.isBlank()) throw new IllegalArgumentException("Target sector or Action is required");
            String remoteAddress = authoritativeVanillaTransferAddress(sourceId, memberId, reportedAddress);

            SharedCampaignState snapshot = store.snapshot();
            ActionState source = requireAction(snapshot, sourceId);
            if(source.status != ActionStatus.running) throw new IllegalStateException("Source Action is not running");
            ActionState target = findVanillaTargetAction(snapshot, targetName);
            String targetActionId;
            if(target == null){
                if(request.spectator()) throw new SecurityException("Spectators cannot start a fresh Shared Campaign sector");
                Sector destination = resolveFreshVanillaTarget(targetName);
                if(source.planetName.equals(destination.planet.name) && source.sectorName.equals(SharedCampaignSectors.sectorId(destination))){
                    throw new IllegalArgumentException("Player is already in the requested sector");
                }
                String missionId = "";
                if(missions != null){
                    SharedCampaignMissionRegistry.MissionDefinition mission = missions.forSector(destination.planet.name, SharedCampaignSectors.sectorId(destination));
                    if(mission != null) missionId = mission.id();
                }
                RuntimePayloads.StartResult started = startSector(memberId, destination.planet.name, SharedCampaignSectors.sectorId(destination), missionId, RuntimePayloads.LaunchPlan.empty());
                if(started.error() != null && !started.error().isBlank()) throw new IllegalStateException("Cannot start target sector: " + started.error());
                if(started.actionId() == null || started.actionId().isBlank()) throw new IllegalStateException("Target sector start did not return an Action identity");
                targetActionId = started.actionId();
                target = requireAction(store.snapshot(), targetActionId);
            }else{
                if(target.actionId.equals(sourceId)) throw new IllegalArgumentException("Player is already in the requested Action");
                if(request.spectator() && !target.spectatorsAllowed) throw new SecurityException("Target Action does not allow spectators");
                targetActionId = target.actionId;
                if(target.status == ActionStatus.suspended){
                    ActionRuntimeCoordinator.RuntimeStart resumed = runtimes.resumeSuspended(memberId, targetActionId);
                    if(resumed.error() != null && !resumed.error().isBlank()) throw new IllegalStateException("Cannot resume target Action: " + resumed.error());
                }else if(target.status != ActionStatus.running && target.status != ActionStatus.starting){
                    throw new IllegalStateException("Target Action is not joinable: " + target.status);
                }
            }
            target = awaitVanillaTransferTarget(targetActionId, 15_000L);
            final ActionState readyTarget = target;
            // A member may have joined the campaign after this Action's last strategic handshake. The source Action
            // can authenticate that member while the destination still holds an older membership snapshot, which
            // would make an otherwise valid one-shot vanilla grant fail its final Action-side authorization check.
            // Bring the destination to the current authority revision before installing the grant.
            synchronizeAdmissionSnapshot(readyTarget.actionId);

            String grantId = UUID.randomUUID().toString();
            long expiresAt = Time.millis() + vanillaAdmissionValidityMillis();
            RuntimePayloads.VanillaAdmissionPrepare prepare = new RuntimePayloads.VanillaAdmissionPrepare(
                readyTarget.actionId, grantId, memberId, networkUuid, remoteAddress, request.spectator(), expiresAt);
            ControlProtocol.Frame frame = runtimes.controlPlane().request(readyTarget.actionId, ControlProtocol.Type.vanillaAdmissionPrepareRequest,
                RuntimePayloads.encode(prepare), ControlProtocol.Type.vanillaAdmissionPrepareResponse, 5_000L);
            RuntimePayloads.VanillaAdmissionPrepareResult prepared = RuntimePayloads.vanillaAdmissionPrepareResult(frame.payload());
            if(!grantId.equals(prepared.grantId())) throw new SecurityException("Vanilla admission grant acknowledgement mismatch");
            if(!prepared.accepted()) throw new IllegalStateException(prepared.error().isBlank() ? "Target Action rejected vanilla admission" : prepared.error());

            try{
                store.transact(memberId, "shared-campaign:vanilla-transfer", state -> {
                    requireMember(state, memberId);
                    ActionState current = requireAction(state, readyTarget.actionId);
                    if(current.status != ActionStatus.running) throw new IllegalStateException("Target Action stopped while preparing vanilla transfer");
                    touchMember(state, memberId, readyTarget.actionId);
                });
            }catch(Throwable commitFailure){
                try{
                    RuntimePayloads.VanillaAdmissionRevoke revoke = new RuntimePayloads.VanillaAdmissionRevoke(readyTarget.actionId, grantId, networkUuid);
                    runtimes.controlPlane().request(readyTarget.actionId, ControlProtocol.Type.vanillaAdmissionRevokeRequest, RuntimePayloads.encode(revoke),
                        ControlProtocol.Type.vanillaAdmissionRevokeResponse, 5_000L);
                }catch(Throwable revokeFailure){
                    commitFailure.addSuppressed(revokeFailure);
                }
                throw commitFailure;
            }
            return new RuntimePayloads.VanillaTransferResult(readyTarget.actionId, readyTarget.bindAddress, readyTarget.port, expiresAt, "");
        }catch(Throwable failure){
            return new RuntimePayloads.VanillaTransferResult("", "", 0, 0L, message(failure));
        }
    }

    /**
     * Operator-assisted first entry for an unmodified vanilla client. Unlike a transfer, there is no authenticated
     * source Action to derive an address from, so the trusted operator must supply the observed client IP explicitly.
     * The resulting grant is still short-lived, one-shot, UUID/IP/member-bound and validated by the Action itself.
     */
    public RuntimePayloads.VanillaTransferResult prepareVanillaBootstrap(String memberId, String targetName, String networkUuid, String remoteAddress, boolean spectator){
        try{
            String cleanMember = memberId == null ? "" : memberId.trim();
            String cleanTarget = targetName == null ? "" : targetName.trim();
            String cleanUuid = networkUuid == null ? "" : networkUuid.trim();
            String cleanAddress = normalizeRemoteAddress(remoteAddress);
            requireMember(cleanMember);
            if(cleanTarget.isBlank()) throw new IllegalArgumentException("Target sector or Action is required");
            if(cleanUuid.isBlank()) throw new IllegalArgumentException("Mindustry network UUID is required");
            if(cleanAddress.isBlank()) throw new IllegalArgumentException("Client IP address is required");

            SharedCampaignState snapshot = store.snapshot();
            ActionState target = findVanillaTargetAction(snapshot, cleanTarget);
            String targetActionId;
            if(target == null){
                if(spectator) throw new SecurityException("Spectators cannot start a fresh Shared Campaign sector");
                Sector destination = resolveFreshVanillaTarget(cleanTarget);
                String missionId = "";
                if(missions != null){
                    SharedCampaignMissionRegistry.MissionDefinition mission = missions.forSector(destination.planet.name, SharedCampaignSectors.sectorId(destination));
                    if(mission != null) missionId = mission.id();
                }
                RuntimePayloads.StartResult started = startSector(cleanMember, destination.planet.name, SharedCampaignSectors.sectorId(destination), missionId, RuntimePayloads.LaunchPlan.empty());
                if(started.error() != null && !started.error().isBlank()) throw new IllegalStateException("Cannot start target sector: " + started.error());
                if(started.actionId() == null || started.actionId().isBlank()) throw new IllegalStateException("Target sector start did not return an Action identity");
                targetActionId = started.actionId();
            }else{
                if(spectator && !target.spectatorsAllowed) throw new SecurityException("Target Action does not allow spectators");
                targetActionId = target.actionId;
                if(target.status == ActionStatus.suspended){
                    ActionRuntimeCoordinator.RuntimeStart resumed = runtimes.resumeSuspended(cleanMember, targetActionId);
                    if(resumed.error() != null && !resumed.error().isBlank()) throw new IllegalStateException("Cannot resume target Action: " + resumed.error());
                }else if(target.status != ActionStatus.running && target.status != ActionStatus.starting){
                    throw new IllegalStateException("Target Action is not joinable: " + target.status);
                }
            }

            ActionState readyTarget = awaitVanillaTransferTarget(targetActionId, 15_000L);
            synchronizeAdmissionSnapshot(readyTarget.actionId);
            String grantId = UUID.randomUUID().toString();
            long expiresAt = Time.millis() + vanillaAdmissionValidityMillis();
            RuntimePayloads.VanillaAdmissionPrepare prepare = new RuntimePayloads.VanillaAdmissionPrepare(
                readyTarget.actionId, grantId, cleanMember, cleanUuid, cleanAddress, spectator, expiresAt);
            ControlProtocol.Frame frame = runtimes.controlPlane().request(readyTarget.actionId, ControlProtocol.Type.vanillaAdmissionPrepareRequest,
                RuntimePayloads.encode(prepare), ControlProtocol.Type.vanillaAdmissionPrepareResponse, 5_000L);
            RuntimePayloads.VanillaAdmissionPrepareResult prepared = RuntimePayloads.vanillaAdmissionPrepareResult(frame.payload());
            if(!grantId.equals(prepared.grantId())) throw new SecurityException("Vanilla admission grant acknowledgement mismatch");
            if(!prepared.accepted()) throw new IllegalStateException(prepared.error().isBlank() ? "Target Action rejected vanilla admission" : prepared.error());

            try{
                store.transact(cleanMember, "shared-campaign:vanilla-bootstrap", state -> {
                    requireMember(state, cleanMember);
                    ActionState current = requireAction(state, readyTarget.actionId);
                    if(current.status != ActionStatus.running) throw new IllegalStateException("Target Action stopped while preparing vanilla admission");
                    touchMember(state, cleanMember, readyTarget.actionId);
                });
            }catch(Throwable commitFailure){
                try{
                    RuntimePayloads.VanillaAdmissionRevoke revoke = new RuntimePayloads.VanillaAdmissionRevoke(readyTarget.actionId, grantId, cleanUuid);
                    runtimes.controlPlane().request(readyTarget.actionId, ControlProtocol.Type.vanillaAdmissionRevokeRequest, RuntimePayloads.encode(revoke),
                        ControlProtocol.Type.vanillaAdmissionRevokeResponse, 5_000L);
                }catch(Throwable revokeFailure){ commitFailure.addSuppressed(revokeFailure); }
                throw commitFailure;
            }
            return new RuntimePayloads.VanillaTransferResult(readyTarget.actionId, readyTarget.bindAddress, readyTarget.port, expiresAt, "");
        }catch(Throwable failure){
            return new RuntimePayloads.VanillaTransferResult("", "", 0, 0L, message(failure));
        }
    }

    /**
     * Enrollment and membership changes can happen after an Action's initial control handshake. Do not weaken
     * Action-side member validation to accommodate that race: push the current durable strategic snapshot and wait
     * for the Action to report the exact applied revision before installing a direct vanilla grant.
     */
    private void synchronizeAdmissionSnapshot(String actionId){
        SharedCampaignState strategic = store.strategicSnapshot();
        synchronizeActionSnapshot(actionId, strategic, strategic.revision);
    }

    /**
     * Pushes the current authoritative strategic snapshot to every live Action that is still connected and waits until
     * each one has applied at least {@code minimumRevision}. Membership revocation uses this as a security barrier: a
     * successful control-plane response must not leave an already-prepared pure-vanilla grant backed by stale member
     * state on another live Action. Disconnected Actions cannot accept new correlated requests and will refresh their
     * snapshot during the next authenticated control handshake.
     */
    public void synchronizeConnectedActionSnapshots(long minimumRevision){
        SharedCampaignState strategic = store.strategicSnapshot();
        if(strategic.revision < minimumRevision){
            throw new IllegalStateException("Campaign snapshot revision " + strategic.revision + " is older than required revision " + minimumRevision);
        }
        for(ActionState action : strategic.actions.values()){
            if(!action.status.isLive() || !runtimes.controlPlane().connected(action.actionId)) continue;
            synchronizeActionSnapshot(action.actionId, strategic, minimumRevision);
        }
    }

    /**
     * Forces every currently-running Action world to a durable save and records the resulting hashes in authority
     * state. Suspended Actions are already backed by a durable suspension save; STARTING/SUSPENDING Actions own their
     * own transition durability and are intentionally not raced by an operator maintenance checkpoint.
     */
    public int forceSaveRunningActions(String actor){
        SharedCampaignState strategic = store.strategicSnapshot();
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        ArrayList<String> failures = new ArrayList<>();
        for(ActionState action : strategic.actions.values()){
            if(action.status != ActionStatus.running) continue;
            if(!runtimes.controlPlane().connected(action.actionId)){
                failures.add(action.actionId + " (control channel unavailable)");
                continue;
            }
            try{
                ControlProtocol.Frame frame = runtimes.controlPlane().request(action.actionId,
                    ControlProtocol.Type.actionSaveNowRequest, new byte[0], ControlProtocol.Type.actionSaveNowResponse, 15_000L);
                String hash = RuntimePayloads.decodeString(frame.payload());
                if(hash == null || hash.isBlank()) throw new IllegalStateException("Action returned an empty save hash");
                hashes.put(action.actionId, hash);
            }catch(Throwable error){
                failures.add(action.actionId + " (" + (error.getMessage() == null ? error.toString() : error.getMessage()) + ")");
            }
        }

        if(!hashes.isEmpty()){
            String commitActor = actor == null || actor.isBlank() ? "authority" : actor;
            store.transact(commitActor, "shared-campaign:force-save-actions", state -> {
                for(Map.Entry<String, String> entry : hashes.entrySet()){
                    ActionState action = state.actions.get(entry.getKey());
                    if(action != null && action.status.isLive()) action.lastSaveHash = entry.getValue();
                }
            });
        }
        if(!failures.isEmpty()) throw new IllegalStateException("Could not force-save all running Actions: " + String.join(", ", failures));
        return hashes.size();
    }

    private void synchronizeActionSnapshot(String actionId, SharedCampaignState strategic, long minimumRevision){
        ControlProtocol.Frame frame = runtimes.controlPlane().request(actionId, ControlProtocol.Type.snapshotApplyRequest,
            SharedCampaignCodec.encodeStrategic(strategic), ControlProtocol.Type.snapshotApplyResponse, 5_000L);
        RuntimePayloads.SnapshotApplied applied = RuntimePayloads.snapshotApplied(frame.payload());
        if(applied.revision() < minimumRevision){
            throw new IllegalStateException("Action applied stale campaign revision " + applied.revision() + ", expected at least " + minimumRevision);
        }
    }

    private ActionState awaitVanillaTransferTarget(String actionId, long timeoutMillis) throws InterruptedException{
        long deadline = Time.millis() + Math.max(1_000L, timeoutMillis);
        while(true){
            ActionState current = requireAction(store.snapshot(), actionId);
            if(current.status == ActionStatus.running && runtimes.controlPlane().connected(actionId)) return current;
            if(current.status == ActionStatus.failed || current.status == ActionStatus.completed || current.status == ActionStatus.incompatible || current.status == ActionStatus.suspended){
                throw new IllegalStateException("Target Action did not become joinable: " + current.status + (current.failureReason == null || current.failureReason.isBlank() ? "" : " (" + current.failureReason + ")"));
            }
            if(Time.millis() >= deadline) throw new IllegalStateException("Timed out waiting for target Action to become joinable");
            Thread.sleep(25L);
        }
    }

    private static ActionState findVanillaTargetAction(SharedCampaignState state, String requested){
        ActionState byId = state.actions.get(requested);
        if(byId != null) return byId;
        String normalized = requested.replace('/', ':');
        ActionState exact = null;
        ActionState shortName = null;
        int shortMatches = 0;
        for(ActionState action : state.actions.values()){
            String sectorKey = SharedCampaignSectors.sectorKey(action.planetName, action.sectorName);
            if(sectorKey.equalsIgnoreCase(normalized)){
                if(exact != null && exact != action) throw new IllegalArgumentException("Ambiguous target sector: " + requested);
                exact = action;
            }
            if(action.sectorName.equalsIgnoreCase(requested)){
                shortMatches++;
                if(shortMatches == 1) shortName = action;
            }
        }
        if(exact != null) return exact;
        if(shortMatches > 1) throw new IllegalArgumentException("Ambiguous target sector name: " + requested);
        return shortName;
    }

    /** Fresh vanilla launches require an explicit planet-qualified identity; short names remain Action-only aliases. */
    private static Sector resolveFreshVanillaTarget(String requested){
        String normalized = requested == null ? "" : requested.trim().replace('/', ':');
        int separator = normalized.indexOf(':');
        if(separator <= 0 || separator + 1 >= normalized.length()){
            throw new IllegalArgumentException("No Shared Action exists for target; use planet:sector to launch a fresh sector: " + requested);
        }
        String planetName = normalized.substring(0, separator), sectorName = normalized.substring(separator + 1);
        Sector sector = SharedCampaignProgress.findSector(planetName, sectorName);
        if(sector == null) throw new IllegalArgumentException("Unknown Shared Campaign sector: " + requested);
        return sector;
    }

    /**
     * Shared-entry relays connect to an Action from loopback, so the Action-side NetConnection address is not the
     * player's Internet address. Prefer the coordinator broker's authenticated session address when there is exactly
     * one live relay for this member/source Action; direct Action joins fall back to the trusted Action-reported IP.
     */
    private String authoritativeVanillaTransferAddress(String sourceActionId, String memberId, String reportedAddress){
        String brokerAddress = null;
        int matches = 0;
        for(ActionSessionBroker.Session session : entry.broker().sessionsSnapshot()){
            if(session.state != ActionSessionBroker.SessionState.bound) continue;
            if(!sourceActionId.equals(session.actionId) || !memberId.equals(session.memberId)) continue;
            matches++;
            brokerAddress = normalizeRemoteAddress(session.remoteAddress);
        }
        if(matches > 1) throw new IllegalStateException("Multiple live shared-entry sessions exist for this member; vanilla transfer source is ambiguous");
        if(matches == 1){
            if(brokerAddress == null || brokerAddress.isBlank()) throw new IllegalStateException("Shared-entry session has no usable client address");
            return brokerAddress;
        }
        return normalizeRemoteAddress(reportedAddress);
    }

    static String normalizeRemoteAddress(String value){
        String address = value == null ? "" : value.trim();
        if(address.startsWith("/")) address = address.substring(1);
        int slash = address.lastIndexOf('/');
        if(slash >= 0) address = address.substring(slash + 1);
        if(address.startsWith("[")){
            int close = address.indexOf(']');
            if(close > 1) return address.substring(1, close);
        }
        int colon = address.lastIndexOf(':');
        if(colon > 0 && address.indexOf(':') == colon){
            String port = address.substring(colon + 1);
            if(!port.isBlank() && port.chars().allMatch(Character::isDigit)) address = address.substring(0, colon);
        }
        return address;
    }

    /** Durable owner-only suspend intent followed by a correlated Action-host request. */
    public SharedCampaignState suspend(String memberId, String actionId){
        SharedCampaignState snapshot = store.snapshot();
        requireOwner(snapshot, memberId);
        ActionState before = requireAction(snapshot, actionId);
        if(before.status == ActionStatus.suspended) return snapshot;
        if(before.status == ActionStatus.suspending){
            if(runtimes.controlPlane().connected(actionId)) requestSuspend(actionId);
            return snapshot;
        }
        if(before.status != ActionStatus.running) throw new IllegalStateException("Action is not running: " + before.status);
        if(before.connectedPlayers > 0) throw new IllegalStateException("Action cannot suspend while players are present");
        if(!runtimes.controlPlane().connected(actionId)) throw new IllegalStateException("Action host is not connected");

        SharedCampaignState committed = store.transact(memberId, "shared-campaign:suspend-action", state -> {
            requireOwner(state, memberId);
            ActionState action = requireAction(state, actionId);
            if(action.status != ActionStatus.running) throw new IllegalStateException("Action is not running: " + action.status);
            if(action.connectedPlayers > 0) throw new IllegalStateException("Action cannot suspend while players are present");
            action.status = ActionStatus.suspending;
            action.updatedAt = Time.millis();
        });
        // The intent is authoritative even if transport fails after this point. A reconnecting Action can be asked again.
        requestSuspend(actionId);
        return committed;
    }

    public ActionRuntimeCoordinator.RuntimeStart resume(String memberId, String actionId){
        requireMember(memberId);
        return runtimes.resumeSuspended(memberId, actionId);
    }

    /** Contributes as many currently available shared-sector resources as possible toward one technology node. */
    public SharedCampaignState research(String memberId, String contentName, String planetName){
        synchronized(researchMutex){
            return researchSerial(memberId, contentName, planetName);
        }
    }

    private SharedCampaignState researchSerial(String memberId, String contentName, String planetName){
        requireMember(memberId);
        UnlockableContent target = researchContent(contentName);
        TechTree.TechNode node = researchNode(target, planetName);
        String researchPlanet = researchPlanetName(node, planetName);
        SharedCampaignState snapshot = store.snapshot();
        requireMember(snapshot, memberId);
        if(SharedCampaignProgress.researchUnlocked(snapshot, target)) return snapshot;
        validateResearch(snapshot, target, node);
        ResearchState existing = snapshot.research.get(target.name, ResearchState::new);
        ResearchTransaction transaction = planResearch(memberId, target, node, researchPlanet, snapshot, existing);
        if(transaction.debits.isEmpty()) throw new SharedCampaignUserException("No available resources can currently be contributed to " + target.localizedName + ".");

        store.transact(memberId, "shared-campaign:research-prepare", state -> {
            validateResearch(state, target, node);
            if(state.researchTransactions.containsKey(transaction.transactionId)) throw new IllegalStateException("Duplicate research transaction");
            state.researchTransactions.put(transaction.transactionId, transaction);
        });

        ObjectMap<String, SectorSummary> preparedSummaries = new ObjectMap<>();
        ObjectSet<String> preparedActions = new ObjectSet<>();
        try{
            for(ResearchDebit debit : transaction.debits.values()){
                if(debit.actionId.isBlank()) continue;
                RuntimePayloads.ResearchPrepareResult response = prepareLiveDebit(transaction.transactionId, debit);
                if(!response.success()) throw new IllegalStateException(response.error().isBlank() ? "Live research reservation failed" : response.error());
                preparedActions.add(debit.actionId);
                if(response.summary() != null) preparedSummaries.put(debit.sectorName, response.summary());
            }

            SharedCampaignState committed = store.transact(memberId, "shared-campaign:research-commit", state -> {
                ResearchTransaction durable = state.researchTransactions.get(transaction.transactionId);
                if(durable == null || durable.status != ResearchTransactionStatus.preparing) throw new IllegalStateException("Research transaction is no longer preparable");
                validateResearch(state, target, node);
                planetPolicies.beforeResearchCommit(state, researchPlanet, target.name);
                ResearchState progress = state.research.get(target.name, ResearchState::new);
                progress.contentName = target.name;
                if(progress.startedAtRevision < 0) progress.startedAtRevision = state.revision + 1L;
                if(progress.required.isEmpty()) for(ItemStack requirement : node.requirements) progress.required.put(requirement.item.name, requirement.amount);

                for(ResearchDebit debit : durable.debits.values()){
                    SectorState sector = state.sectors.get(debit.sectorName);
                    if(sector == null) throw new IllegalStateException("Research debit sector disappeared: " + debit.sectorName);
                    if(debit.actionId.isBlank()){
                        for(ObjectMap.Entry<String, Integer> item : debit.items){
                            int available = sector.items.get(item.key, 0);
                            if(available < item.value) throw new IllegalStateException("Strategic resources changed while preparing research");
                            sector.items.put(item.key, available - item.value);
                            sector.summary.items.put(item.key, available - item.value);
                        }
                    }else{
                        SectorSummary prepared = preparedSummaries.get(debit.sectorName);
                        if(prepared == null) throw new IllegalStateException("Live action did not return a prepared sector summary");
                        ActionAuthorityTransitions.copySummaryToSector(sector, prepared);
                    }
                    for(ObjectMap.Entry<String, Integer> item : debit.items){
                        progress.contributed.put(item.key, Math.addExact(progress.contributed.get(item.key, 0), item.value));
                    }
                }
                state.research.put(target.name, progress);
                durable.preparedActions.addAll(preparedActions);
                durable.status = ResearchTransactionStatus.committed;
                durable.updatedAt = Time.millis();
                if(progress.complete()){
                    progress.completedAtRevision = state.revision + 1L;
                    for(TechTree.TechNode current = node; current != null; current = current.parent) state.researched.add(current.content.name);
                    SharedCampaignProgress.applyAutomaticUnlocks(state);
                }
                planetPolicies.afterResearchCommit(state, researchPlanet, target.name, progress.complete());
            });
            for(String actionId : preparedActions) decideResearch(actionId, transaction.transactionId, true, false);
            return committed;
        }catch(Throwable error){
            store.transact(memberId, "shared-campaign:research-abort", state -> {
                ResearchTransaction durable = state.researchTransactions.get(transaction.transactionId);
                if(durable != null && durable.status == ResearchTransactionStatus.preparing){
                    durable.status = ResearchTransactionStatus.aborted;
                    durable.updatedAt = Time.millis();
                    durable.preparedActions.addAll(preparedActions);
                    durable.failureReason = message(error);
                }
            });
            for(String actionId : preparedActions) decideResearch(actionId, transaction.transactionId, false, false);
            if(error instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(error);
        }
    }

    /** Updates authoritative launch-pad destination metadata for one established base. */
    public SharedCampaignState updateSectorLogistics(String memberId, String sourceSector, String destinationSector){
        synchronized(logisticsMutex){
            requireMember(memberId);
            String sourceKey = sourceSector == null ? "" : sourceSector.trim();
            String destinationKey = destinationSector == null ? "" : destinationSector.trim();
            SharedCampaignState before = store.snapshot();
            requireMember(before, memberId);
            SectorState source = before.sectors.get(sourceKey);
            if(source == null || !source.hasBase) throw new IllegalArgumentException("Logistics source must be an established shared-campaign base");
            if(!destinationKey.isBlank()){
                SectorState destination = before.sectors.get(destinationKey);
                if(destination == null || !destination.hasBase) throw new IllegalArgumentException("Logistics destination must be an established shared-campaign base");
                if(sourceKey.equals(destinationKey)) throw new IllegalArgumentException("Logistics source and destination must differ");
                if(!Objects.equals(source.planetName, destination.planetName)) throw new IllegalArgumentException("Launch-pad logistics cannot cross planets");
            }
            ActionState live = before.actions.values().toSeq().find(action -> action.status == ActionStatus.running && sourceKey.equals(ActionAuthorityTransitions.sectorKey(action)));
            SharedCampaignState committed = store.transact(memberId, "shared-campaign:sector-logistics-target", state -> {
                SectorState durable = state.sectors.get(sourceKey);
                if(durable == null || !durable.hasBase) throw new IllegalStateException("Logistics source changed while updating its target");
                if(!destinationKey.isBlank()){
                    SectorState destination = state.sectors.get(destinationKey);
                    if(destination == null || !destination.hasBase || !Objects.equals(durable.planetName, destination.planetName)) throw new IllegalStateException("Logistics destination changed while updating its target");
                }
                durable.destinationSector = destinationKey;
                durable.summary.destinationSector = destinationKey;
                durable.logisticsWarning = destinationKey.isBlank() ? "" : durable.legacyLaunchPads ? "" : "No active launch-pad export has been observed for this base";
            });
            if(live != null && runtimes.controlPlane().connected(live.actionId)){
                try{
                    runtimes.controlPlane().request(live.actionId, ControlProtocol.Type.actionLogisticsRequest,
                        RuntimePayloads.encode(new RuntimePayloads.ActionLogistics(live.actionId, sourceKey, destinationKey)),
                        ControlProtocol.Type.actionLogisticsResponse, 30_000L);
                }catch(Throwable error){
                    Log.warn("Shared Campaign live logistics reconciliation for Action @ is deferred: @", live.actionId, message(error));
                }
            }
            return committed;
        }
    }

    private UnlockableContent researchContent(String contentName){
        if(contentName == null || contentName.isBlank()) throw new IllegalArgumentException("Research content is required");
        UnlockableContent target = null;
        for(ContentType type : new ContentType[]{ContentType.block, ContentType.unit, ContentType.item, ContentType.liquid}){
            var candidate = mindustry.Vars.content.getByName(type, contentName);
            if(candidate instanceof UnlockableContent unlock){ target = unlock; break; }
        }
        if(target == null) throw new IllegalArgumentException("Unknown research content: " + contentName);
        return target;
    }

    private TechTree.TechNode researchNode(UnlockableContent target, String planetName){
        String requested = planetName == null ? "" : planetName.trim();
        if(!requested.isBlank()){
            Planet planet = mindustry.Vars.content.planet(requested);
            if(planet == null) throw new IllegalArgumentException("Unknown research planet: " + requested);
            TechTree.TechNode node = TechTree.all.find(candidate -> candidate.content == target && SharedCampaignProgress.researchPlanetContains(candidate, requested));
            if(node == null) throw new IllegalArgumentException("Content is not researchable on planet " + requested + ": " + target.name);
            return node;
        }
        if(target.techNode == null) throw new IllegalArgumentException("Content is not researchable: " + target.name);
        return target.techNode;
    }

    private String researchPlanetName(TechTree.TechNode node, String requestedPlanet){
        if(requestedPlanet != null && !requestedPlanet.isBlank()) return requestedPlanet.trim();
        Seq<Planet> planets = SharedCampaignProgress.researchPlanets(node);
        if(planets.isEmpty()) throw new IllegalArgumentException("Research technology tree has no planet: " + node.content.name);
        return planets.first().name;
    }

    private void validateResearch(SharedCampaignState state, UnlockableContent target, TechTree.TechNode node){
        if(SharedCampaignProgress.researchUnlocked(state, target)) return;
        if(node.parent != null && !SharedCampaignProgress.researchUnlocked(state, node.parent.content)) throw new IllegalStateException("Parent technology is not researched or otherwise unlocked");
        for(var objective : node.objectives){
            SharedObjectiveEvaluator.Result evaluation = SharedObjectiveEvaluator.evaluate(state, objective);
            if(!evaluation.complete()) throw new IllegalStateException(evaluation.supported() ? "Research objective is incomplete: " + objective.display() : "Unsupported Shared Campaign research objective: " + objective.getClass().getSimpleName());
        }
    }

    private ResearchTransaction planResearch(String memberId, UnlockableContent target, TechTree.TechNode node, String researchPlanet, SharedCampaignState state, ResearchState progress){
        ResearchTransaction transaction = new ResearchTransaction();
        transaction.transactionId = UUID.randomUUID().toString();
        transaction.actorId = memberId;
        transaction.contentName = target.name;
        transaction.createdAt = transaction.updatedAt = Time.millis();
        ActionState currentAction = SharedCampaignProgress.currentMemberPlayerAction(state, memberId);
        String currentSector = currentAction == null ? "" : ActionAuthorityTransitions.sectorKey(currentAction);
        Seq<SectorState> eligible = SharedCampaignProgress.researchEligibleSectors(state, node, researchPlanet);
        eligible.sort(Comparator.comparing(sector -> SharedCampaignProgress.sectorKey(sector.planetName, sector.sectorName)));
        for(ItemStack requirement : node.requirements){
            int required = progress.required.isEmpty() ? requirement.amount : progress.required.get(requirement.item.name, requirement.amount);
            int remaining = Math.max(0, required - progress.contributed.get(requirement.item.name, 0));
            for(SectorState sector : eligible){
                if(remaining == 0) break;
                String key = SharedCampaignProgress.sectorKey(sector.planetName, sector.sectorName);
                if(key.equals(currentSector)) continue;
                int used = Math.min(remaining, Math.max(0, sector.items.get(requirement.item.name, 0)));
                if(used > 0){ appendResearchDebit(transaction, state, sector, requirement.item.name, used); remaining -= used; }
            }
            if(remaining > 0 && !currentSector.isBlank()){
                SectorState sector = state.sectors.get(currentSector);
                if(sector != null && eligible.contains(sector, true)){
                    int used = Math.min(remaining, Math.max(0, sector.items.get(requirement.item.name, 0)));
                    if(used > 0) appendResearchDebit(transaction, state, sector, requirement.item.name, used);
                }
            }
        }
        return transaction;
    }

    private static void appendResearchDebit(ResearchTransaction transaction, SharedCampaignState state, SectorState sector, String itemName, int amount){
        String key = SharedCampaignProgress.sectorKey(sector.planetName, sector.sectorName);
        ResearchDebit debit = transaction.debits.get(key, ResearchDebit::new);
        debit.sectorName = key;
        ActionState live = state.actions.values().toSeq().find(action -> key.equals(ActionAuthorityTransitions.sectorKey(action)) && action.status.isLive());
        debit.actionId = live == null ? "" : live.actionId;
        debit.items.put(itemName, Math.addExact(debit.items.get(itemName, 0), amount));
        transaction.debits.put(key, debit);
    }

    private RuntimePayloads.ResearchPrepareResult prepareLiveDebit(String transactionId, ResearchDebit debit){
        ControlProtocol.Frame frame = runtimes.controlPlane().request(debit.actionId, ControlProtocol.Type.researchPrepareRequest,
            RuntimePayloads.encode(new RuntimePayloads.ResearchPrepare(transactionId, debit.actionId, debit.items)),
            ControlProtocol.Type.researchPrepareResponse, 45_000L);
        RuntimePayloads.ResearchPrepareResult result = RuntimePayloads.researchPrepareResult(frame.payload());
        if(!transactionId.equals(result.transactionId())) throw new SecurityException("Research reservation transaction mismatch");
        return result;
    }

    private boolean decideResearch(String actionId, String transactionId, boolean commit, boolean throwOnFailure){
        try{
            ControlProtocol.Frame frame = runtimes.controlPlane().request(actionId, ControlProtocol.Type.researchDecisionRequest,
                RuntimePayloads.encode(new RuntimePayloads.ResearchDecision(transactionId, commit)), ControlProtocol.Type.researchDecisionResponse, 30_000L);
            RuntimePayloads.ResearchDecisionResult result = RuntimePayloads.researchDecisionResult(frame.payload());
            if(!transactionId.equals(result.transactionId())) throw new SecurityException("Research decision transaction mismatch");
            if(!result.success()) throw new IllegalStateException(result.error().isBlank() ? "Action rejected research decision" : result.error());
            return true;
        }catch(Throwable error){
            if(throwOnFailure){ if(error instanceof RuntimeException runtime) throw runtime; throw new RuntimeException(error); }
            Log.warn("Research transaction @ decision for Action @ remains pending: @", transactionId, actionId, message(error));
            return false;
        }
    }

    public RuntimePayloads.HotSwitchResult beginHotSwitch(String memberId, RuntimePayloads.HotSwitchRequest request){
        Objects.requireNonNull(request, "request");
        RuntimePayloads.JoinResult joined = join(memberId, request.toActionId(), request.spectator(), request.networkUuid());
        if(joined.error() != null && !joined.error().isBlank()){
            return new RuntimePayloads.HotSwitchResult("", request.toActionId(), "", 0, "", false, joined.error());
        }
        ActionSessionBroker.Session session = entry.beginHotSwitch(memberId, request.sessionId(), request.fromActionId(), request.toActionId());
        if(session == null){
            return new RuntimePayloads.HotSwitchResult("", joined.actionId(), joined.host(), joined.port(), joined.joinToken(), false,
                "hot-switch unavailable; use ordinary join");
        }
        return new RuntimePayloads.HotSwitchResult(session.sessionId, joined.actionId(), joined.host(), joined.port(), joined.joinToken(), true, "");
    }

    public boolean resumeHotSwitch(String memberId, String sessionId){ requireMember(memberId); return entry.resumeHotSwitch(memberId, sessionId); }
    public boolean abortHotSwitch(String memberId, String sessionId){ requireMember(memberId); return entry.abortHotSwitch(memberId, sessionId); }

    private SharedCampaignMissionRegistry.MissionDefinition requireMission(String missionId, String planetName, String sectorName){
        if(missionId == null || missionId.isBlank()) return null;
        if(missions == null) throw new IllegalStateException("Mission registry is unavailable");
        SharedCampaignMissionRegistry.MissionDefinition definition = missions.require(missionId);
        String boundPlanet = definition.definition().planetName(), boundSector = definition.definition().sectorName();
        if(!boundPlanet.isBlank() && (!boundPlanet.equals(planetName) || !boundSector.equals(sectorName))) throw new IllegalArgumentException("Mission is bound to a different sector: " + missionId);
        return definition;
    }

    private RuntimePayloads.ResearchPrepareResult prepareLiveLaunch(String transactionId, SharedLaunchPlanner.PreparedLaunch launch){
        RuntimePayloads.ResearchPrepare request = new RuntimePayloads.ResearchPrepare(transactionId, launch.sourceActionId(), launch.costs());
        ControlProtocol.Frame frame = runtimes.controlPlane().request(launch.sourceActionId(), ControlProtocol.Type.researchPrepareRequest,
            RuntimePayloads.encode(request), ControlProtocol.Type.researchPrepareResponse, 30_000L);
        RuntimePayloads.ResearchPrepareResult result = RuntimePayloads.researchPrepareResult(frame.payload());
        if(!transactionId.equals(result.transactionId())) throw new SecurityException("Launch reservation response transaction mismatch");
        return result;
    }

    private boolean decideLiveLaunch(String actionId, String transactionId, boolean commit, boolean throwOnFailure){
        return decideLiveLaunch(actionId, transactionId, commit, throwOnFailure, true);
    }

    private boolean decideLiveLaunch(String actionId, String transactionId, boolean commit, boolean throwOnFailure, boolean logPending){
        try{
            ControlProtocol.Frame frame = runtimes.controlPlane().request(actionId, ControlProtocol.Type.researchDecisionRequest,
                RuntimePayloads.encode(new RuntimePayloads.ResearchDecision(transactionId, commit)),
                ControlProtocol.Type.researchDecisionResponse, 30_000L);
            RuntimePayloads.ResearchDecisionResult result = RuntimePayloads.researchDecisionResult(frame.payload());
            if(!transactionId.equals(result.transactionId())) throw new SecurityException("Launch decision response transaction mismatch");
            if(!result.success()) throw new IllegalStateException(result.error().isBlank() ? "Action rejected launch decision" : result.error());
            return true;
        }catch(Throwable error){
            if(throwOnFailure){
                if(error instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(error);
            }
            if(logPending) Log.warn("Shared Campaign launch decision @ for Action @ remains pending: @", commit ? "commit" : "abort", actionId, message(error));
            return false;
        }
    }

    private void abortLaunch(String actorId, String transactionId, String sourceActionId, String reason){
        if(transactionId == null || transactionId.isBlank()) return;
        try{
            store.transact(actorId == null || actorId.isBlank() ? "coordinator" : actorId, "shared-campaign:launch-abort", state -> {
                LaunchTransaction tx = state.launchTransactions.get(transactionId);
                if(tx == null || tx.status == LaunchTransactionStatus.committed || tx.status == LaunchTransactionStatus.aborted) return;
                SharedLaunchTransactions.abort(state, transactionId, reason, Time.millis());
            });
        }finally{
            if(sourceActionId != null && !sourceActionId.isBlank()) decideLiveLaunch(sourceActionId, transactionId, false, false);
        }
    }

    private static String message(Throwable error){
        if(error == null) return "Launch failed";
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.toString() : value;
    }

    private void requestSuspend(String actionId){
        runtimes.controlPlane().request(actionId, ControlProtocol.Type.suspendActionRequest, RuntimePayloads.encodeString(actionId),
            ControlProtocol.Type.suspendActionResponse, 30_000L);
    }

    private void requireMember(String memberId){ requireMember(store.snapshot(), memberId); }
    private static void requireMember(SharedCampaignState state, String memberId){
        if(memberId == null || memberId.isBlank() || !state.members.containsKey(memberId)) throw new SecurityException("Unknown or revoked campaign member");
    }
    private static void requireOwner(SharedCampaignState state, String memberId){
        requireMember(state, memberId);
        if(!Objects.equals(state.ownerId, memberId)) throw new SecurityException("Only the campaign owner may suspend an Action");
    }
    private static ActionState requireAction(SharedCampaignState state, String actionId){
        ActionState action = state.actions.get(actionId);
        if(action == null) throw new IllegalArgumentException("Unknown action: " + actionId);
        return action;
    }
    private static void touchMember(SharedCampaignState state, String memberId, String actionId){
        MemberState member = state.members.get(memberId);
        if(member == null) throw new SecurityException("Unknown or revoked campaign member");
        member.memberId = memberId;
        if(member.displayName == null || member.displayName.isBlank()) member.displayName = memberId;
        member.lastActionId = actionId == null ? "" : actionId;
        member.lastSeenAt = Time.millis();
    }
    private static RuntimePayloads.JoinResult joinError(ActionState action, String error){
        return new RuntimePayloads.JoinResult(action.actionId, action.bindAddress, action.port, "", error);
    }
    private static long admissionValidityMillis(){
        return Math.max(10_000L, Math.min(Long.getLong("mindustry.sharedCampaign.actionAdmissionMillis", 120_000L), 10L * 60L * 1000L));
    }
    private static long vanillaAdmissionValidityMillis(){
        return Math.max(5_000L, Math.min(Long.getLong("mindustry.sharedCampaign.vanillaAdmissionMillis", 15_000L), 60_000L));
    }
}
