package mindustry.campaign.shared.runtime;

import arc.struct.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;

import java.util.*;

/**
 * Pure authoritative state transitions for one live Shared Campaign Action.
 *
 * <p>This class deliberately owns no sockets, files, threads, runtimes or store locks. The Coordinator validates
 * transport/filesystem facts and commits these mutations through {@code SharedCampaignStore}; keeping the campaign
 * mutations here makes JVM_PROCESS and IN_PROCESS backends share exactly the same stale-runtime, mission, presence,
 * summary and terminal-state rules.</p>
 */
public final class ActionAuthorityTransitions{
    private ActionAuthorityTransitions(){}

    public static final long leaseDurationMillis = 30_000L;

    public record HelloResult(boolean incompatible, boolean pendingSuspend){}

    public static HelloResult applyHello(SharedCampaignState state, String identity, RuntimePayloads.ActionHello hello, long now){
        requireIdentity(identity, hello.actionId());
        ActionState action = requireAction(state, identity);
        requireLiveIdentity(state, action, hello.generation(), hello.runtimeIncarnation());

        if(state.contentFingerprint != null && !state.contentFingerprint.isEmpty()
            && !Objects.equals(state.contentFingerprint, hello.contentFingerprint())){
            arc.util.Log.err("Action @ rejected an actionHello: content fingerprint mismatch (coordinator=@ host=@)",
                identity, state.contentFingerprint, hello.contentFingerprint());
            action.status = ActionStatus.incompatible;
            action.failureReason = "Content fingerprint mismatch";
            action.leaseExpiresAt = 0L;
            action.connectedPlayers = 0;
            action.connectedSpectators = 0;
            action.spectators.clear();
            action.updatedAt = now;
            return new HelloResult(true, false);
        }

        boolean pendingSuspend = action.status == ActionStatus.suspending;
        if(!pendingSuspend && action.status != ActionStatus.running) action.status = ActionStatus.starting;
        action.port = hello.gamePort();
        action.leaseExpiresAt = now + leaseDurationMillis;
        action.updatedAt = now;
        return new HelloResult(false, pendingSuspend);
    }

    /** Returns true when the first real world heartbeat promoted STARTING to RUNNING. */
    public static boolean applyHeartbeat(SharedCampaignState state, String identity, RuntimePayloads.ActionHeartbeat heartbeat, long now){
        requireIdentity(identity, heartbeat.actionId());
        ActionState action = requireAction(state, identity);
        requireLiveIdentity(state, action, heartbeat.generation(), heartbeat.runtimeIncarnation());
        if(heartbeat.actionTick() < action.actionTick) throw new SecurityException("Stale action heartbeat tick");

        boolean becameRunning = action.status == ActionStatus.starting;
        if(becameRunning){
            action.status = ActionStatus.running;
            if(!action.missionId.isEmpty()){
                MissionState mission = state.missions.get(action.missionId);
                if(mission != null) mission.status = MissionStatus.running;
            }
        }

        action.actionTick = heartbeat.actionTick();
        action.leaseExpiresAt = now + leaseDurationMillis;
        action.updatedAt = now;
        action.participants.clear();
        if(heartbeat.participants() != null){
            for(String memberId : heartbeat.participants()){
                if(state.members.containsKey(memberId)) action.participants.add(memberId);
            }
        }
        action.spectators.clear();
        if(heartbeat.spectators() != null){
            for(String memberId : heartbeat.spectators()){
                if(state.members.containsKey(memberId) && action.participants.contains(memberId)) action.spectators.add(memberId);
            }
        }
        action.connectedSpectators = action.spectators.size;
        action.connectedPlayers = Math.max(0, action.participants.size - action.connectedSpectators);
        for(String memberId : action.participants) touchMember(state, memberId, identity, now);
        return becameRunning;
    }

    public static void applyStateUpdate(SharedCampaignState state, String identity, RuntimePayloads.ActionStateUpdate update,
                                        SharedCampaignMissionRegistry missionDefinitions, long now){
        requireIdentity(identity, update.actionId());
        if(update.summary() == null) throw new SecurityException("Action state update is missing its Sector summary");
        ActionState action = requireAction(state, identity);
        requireLiveIdentity(state, action, update.generation(), update.runtimeIncarnation());
        if(update.actionTick() < action.actionTick) throw new SecurityException("Stale action state update tick");
        if(update.summary().sampledAtTick != update.actionTick()) throw new SecurityException("Action state summary tick does not match its envelope");

        action.actionTick = update.actionTick();
        action.summary = update.summary().strategicCopy();
        action.updatedAt = now;

        if(update.tutorialSteps() != null){
            for(ObjectMap.Entry<String, ObjectSet<String>> tutorial : update.tutorialSteps()){
                MemberState member = state.members.get(tutorial.key);
                if(member == null) continue;
                member.memberId = tutorial.key;
                if(tutorial.value != null) member.tutorialSteps.addAll(tutorial.value);
                member.lastSeenAt = now;
                state.members.put(member.memberId, member);
            }
        }
        if(update.produced() != null) state.discovered.addAll(update.produced());

        String key = sectorKey(action);
        SectorState sector = state.sectors.get(key, SectorState::new);
        sector.planetName = action.planetName;
        sector.sectorName = action.sectorName;
        sector.hasBase = update.summary().coreCount > 0;
        copySummaryToSector(sector, update.summary());
        sector.lastSummaryTick = update.actionTick();
        state.sectors.put(key, sector);

        if(update.mission() != null){
            MissionState incoming = update.mission();
            if(missionIdentityMismatch(action.missionId, action.attemptId, incoming)){
                throw new SecurityException("Mission state update identity does not match its action");
            }
            if(!Objects.equals(incoming.planetName, action.planetName) || !Objects.equals(incoming.sectorName, action.sectorName)){
                throw new SecurityException("Mission state update sector does not match its action");
            }
            if(missionDefinitions != null){
                SharedCampaignMissionRegistry.MissionDefinition definition = missionDefinitions.require(incoming.missionId);
                if(!Objects.equals(incoming.definitionVersion, Integer.toString(definition.version()))
                    || !Objects.equals(incoming.definitionFingerprint, definition.fingerprint())){
                    throw new SecurityException("Mission definition fingerprint mismatch for " + incoming.missionId);
                }
            }
            MissionState existing = state.missions.get(incoming.missionId);
            if(existing == null || existing.attemptId.isEmpty() || Objects.equals(existing.attemptId, incoming.attemptId)){
                state.missions.put(incoming.missionId, incoming);
            }else{
                throw new SecurityException("Mission attempt mismatch for " + incoming.missionId);
            }
        }else if(!action.missionId.isEmpty()){
            MissionState mission = state.missions.get(action.missionId);
            if(mission != null) mission.actionTick = update.actionTick();
        }
    }

    /** Applies the durable capture milestone without ending the runtime. */
    public static void applyCaptured(SharedCampaignState state, String identity, RuntimePayloads.ActionStopped captured, long now){
        requireIdentity(identity, captured.actionId());
        if(!captured.clean() || !captured.completed()) throw new SecurityException("Invalid Action capture milestone payload");
        ActionState action = requireAction(state, identity);
        requireLiveIdentity(state, action, captured.generation(), captured.runtimeIncarnation());

        action.lastSaveHash = clean(captured.saveHash());
        action.updatedAt = now;
        SectorState sector = state.sectors.get(sectorKey(action), SectorState::new);
        sector.planetName = action.planetName;
        sector.sectorName = action.sectorName;
        sector.saveRelativePath = action.saveRelativePath;
        sector.lastSavedAt = now;
        if(captured.summary() != null){
            action.summary = captured.summary().strategicCopy();
            action.actionTick = captured.summary().sampledAtTick;
            copySummaryToSector(sector, captured.summary());
            sector.lastSummaryTick = captured.summary().sampledAtTick;
        }
        sector.captured = true;
        sector.hasBase = true;
        sector.attacked = false;
        state.sectors.put(sectorKey(action), sector);
        if(captured.produced() != null) state.discovered.addAll(captured.produced());

        if(!action.missionId.isEmpty()){
            MissionState existing = state.missions.get(action.missionId);
            MissionState incoming = captured.mission();
            if(incoming != null){
                if(!action.missionId.equals(incoming.missionId)) throw new SecurityException("Capture mission identity mismatch");
                if(existing != null && !existing.attemptId.isEmpty() && !existing.attemptId.equals(incoming.attemptId)){
                    throw new SecurityException("Capture mission attempt mismatch for " + action.missionId);
                }
                incoming.status = MissionStatus.completed;
                state.missions.put(action.missionId, incoming);
            }else if(existing != null){
                existing.status = MissionStatus.completed;
            }
        }
    }

    /** Caller must verify the clean-stop save hash before committing this transition. */
    public static ActionStatus applyStopped(SharedCampaignState state, String identity, RuntimePayloads.ActionStopped stopped,
                                            boolean clearSectorOnLoss, long now){
        requireIdentity(identity, stopped.actionId());
        ActionState action = requireAction(state, identity);
        ActionStatus expected = stopped.completed() ? ActionStatus.completed : stopped.clean() ? ActionStatus.suspended : ActionStatus.failed;

        if(!action.status.isLive()){
            boolean sameFinal = action.status == expected
                && action.hostGeneration == stopped.generation()
                && action.runtimeIncarnation == stopped.runtimeIncarnation()
                && (!stopped.clean() || Objects.equals(action.lastSaveHash, clean(stopped.saveHash())));
            if(sameFinal) return expected;
            throw new SecurityException("Action already stopped with a different final state");
        }
        requireRuntimeIdentity(state, action, stopped.generation(), stopped.runtimeIncarnation());

        action.status = expected;
        action.failureReason = clean(stopped.reason());
        action.leaseExpiresAt = 0L;
        action.updatedAt = now;
        action.connectedPlayers = 0;
        action.connectedSpectators = 0;
        action.spectators.clear();
        action.lastSaveHash = clean(stopped.saveHash());

        SectorState sector = state.sectors.get(sectorKey(action), SectorState::new);
        sector.planetName = action.planetName;
        sector.sectorName = action.sectorName;
        sector.saveRelativePath = action.saveRelativePath;
        sector.lastSavedAt = now;
        if(stopped.summary() != null){
            action.summary = stopped.summary().strategicCopy();
            action.actionTick = stopped.summary().sampledAtTick;
            copySummaryToSector(sector, stopped.summary());
            sector.lastSummaryTick = stopped.summary().sampledAtTick;
        }
        if(stopped.produced() != null) state.discovered.addAll(stopped.produced());

        if(stopped.completed()){
            sector.captured = true;
            sector.hasBase = true;
            sector.attacked = false;
        }else if(!stopped.clean()){
            sector.hasBase = false;
            sector.captured = false;
            sector.attacked = false;
            if(clearSectorOnLoss){
                sector.saveRelativePath = "";
                sector.items.clear();
                sector.productionPerSecond.clear();
                sector.exportPerSecond.clear();
                sector.importPerSecond.clear();
                sector.lastImportedItems.clear();
                sector.summary = new SectorSummary();
                sector.summary.planetName = action.planetName;
            }
        }
        state.sectors.put(sectorKey(action), sector);

        if(!action.missionId.isEmpty()){
            MissionState durable = state.missions.get(action.missionId);
            MissionState mission = stopped.mission() != null ? stopped.mission() : durable;
            if(mission != null){
                boolean alreadyCompleted = durable != null && durable.status == MissionStatus.completed;
                if(alreadyCompleted && !stopped.completed()) mission.status = MissionStatus.completed;
                else mission.status = stopped.completed() ? MissionStatus.completed : stopped.clean() ? MissionStatus.paused : MissionStatus.failed;
                state.missions.put(action.missionId, mission);
            }
        }
        return expected;
    }

    public static void markFailed(SharedCampaignState state, String actionId, String reason, long now){
        ActionState action = state.actions.get(actionId);
        if(action == null) return;
        action.status = ActionStatus.failed;
        action.failureReason = clean(reason);
        action.leaseExpiresAt = 0L;
        action.connectedPlayers = 0;
        action.connectedSpectators = 0;
        action.spectators.clear();
        action.updatedAt = now;
        if(!action.missionId.isEmpty()){
            MissionState mission = state.missions.get(action.missionId);
            if(mission != null && mission.status != MissionStatus.completed){
                mission.status = MissionStatus.failed;
                mission.attemptId = action.attemptId;
            }
        }
    }

    public static void recoverSuspended(SharedCampaignState state, String actionId, String saveHash, String reason, long now){
        ActionState action = state.actions.get(actionId);
        if(action == null || !action.status.isLive()) return;
        action.status = ActionStatus.suspended;
        action.failureReason = clean(reason);
        action.leaseExpiresAt = 0L;
        action.connectedPlayers = 0;
        action.connectedSpectators = 0;
        action.spectators.clear();
        action.lastSaveHash = clean(saveHash);
        action.updatedAt = now;

        SectorState sector = state.sectors.get(sectorKey(action), SectorState::new);
        sector.planetName = action.planetName;
        sector.sectorName = action.sectorName;
        sector.saveRelativePath = action.saveRelativePath;
        sector.lastSavedAt = now;
        state.sectors.put(sectorKey(action), sector);

        if(!action.missionId.isEmpty()){
            MissionState mission = state.missions.get(action.missionId);
            if(mission != null && mission.status != MissionStatus.completed && mission.status != MissionStatus.incompatible){
                mission.status = MissionStatus.paused;
            }
        }
    }

    public static boolean missionIdentityMismatch(String actionMissionId, String actionAttemptId, MissionState incoming){
        if(incoming == null) return true;
        return actionMissionId == null || actionMissionId.isBlank()
            || !Objects.equals(incoming.missionId, actionMissionId)
            || !Objects.equals(incoming.attemptId, actionAttemptId == null ? "" : actionAttemptId);
    }

    public static boolean sameMembers(ObjectSet<String> left, ObjectSet<String> right){
        if(left == right) return true;
        if(left == null || right == null || left.size != right.size) return false;
        for(String member : left) if(!right.contains(member)) return false;
        return true;
    }

    public static void copySummaryToSector(SectorState sector, SectorSummary summary){
        if(sector == null || summary == null) return;
        // Sector identity is coordinator-owned. A runtime observation may omit its planet during early resume, but a
        // contradictory non-empty identity is always rejected so one Action cannot overwrite another sector's state.
        if(summary.planetName != null && !summary.planetName.isBlank() && !summary.planetName.equals(sector.planetName)){
            throw new SecurityException("Sector summary planet does not match authoritative sector: " + summary.planetName + " != " + sector.planetName);
        }
        summary.planetName = sector.planetName;
        sector.summary = summary.strategicCopy();
        sector.hasBase = summary.coreCount > 0;
        sector.items.clear(); sector.items.putAll(summary.items);
        sector.productionPerSecond.clear(); sector.productionPerSecond.putAll(summary.productionPerSecond);
        sector.exportPerSecond.clear(); sector.exportPerSecond.putAll(summary.exportPerSecond);
        sector.importPerSecond.clear(); sector.importPerSecond.putAll(summary.importPerSecond);
        sector.destinationSector = clean(summary.destinationSector);
        sector.legacyLaunchPads = summary.legacyLaunchPads;
        sector.attacked = summary.attacked;
        sector.hasSpawns = summary.hasSpawns;
        sector.displayName = clean(summary.displayName);
        sector.icon = clean(summary.icon);
        sector.contentIcon = clean(summary.contentIcon);
        sector.resources.clear(); sector.resources.addAll(summary.resources);
        sector.hasEnemyBase = summary.hasEnemyBase;
        sector.threat = summary.threat;
        sector.minutesCaptured = summary.minutesCaptured;
    }

    public static ActionState requireAction(SharedCampaignState state, String actionId){
        ActionState action = state.actions.get(actionId);
        if(action == null) throw new IllegalArgumentException("Unknown action " + actionId);
        return action;
    }

    public static String sectorKey(ActionState action){
        return SharedCampaignSectors.sectorKey(action.planetName, action.sectorName);
    }

    private static void requireIdentity(String identity, String payloadActionId){
        if(identity == null || !identity.equals(payloadActionId)) throw new SecurityException("Action identity mismatch");
    }

    private static void requireLiveIdentity(SharedCampaignState state, ActionState action, long generation, long incarnation){
        if(!action.status.isLive()) throw new SecurityException("Action runtime is no longer live: " + action.status);
        requireRuntimeIdentity(state, action, generation, incarnation);
    }

    private static void requireRuntimeIdentity(SharedCampaignState state, ActionState action, long generation, long incarnation){
        if(action.hostGeneration != generation || state.authorityGeneration != generation || action.runtimeIncarnation != incarnation){
            throw new SecurityException("Stale action runtime identity");
        }
    }

    private static void touchMember(SharedCampaignState state, String memberId, String actionId, long now){
        if(memberId == null || memberId.isBlank()) return;
        MemberState member = state.members.get(memberId);
        if(member == null) return;
        member.memberId = memberId;
        if(member.displayName.isBlank()) member.displayName = memberId;
        member.lastActionId = actionId == null ? "" : actionId;
        member.lastSeenAt = now;
        state.members.put(memberId, member);
    }

    private static String clean(String value){ return value == null ? "" : value; }
}
