package mindustry.campaign.shared.mission;

import arc.*;
import arc.struct.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.net.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.game.MapObjectives.*;
import mindustry.io.*;
import mindustry.runtime.*;

import java.nio.charset.*;
import java.security.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Server-authoritative bridge between the objective graph and world-processor signals embedded in vanilla Erekir maps
 * and the durable shared-campaign mission model. This class intentionally keeps the vanilla objective executor alive:
 * map logic and markers still work, while stable identities, progress, phases and one-shot events are persisted outside
 * the map save and can be restored after reconnects, action suspension or host migration.
 */
public class ErekirMissionRuntime{
    private MissionState mission;
    private final ObjectMap<MapObjective, String> ids = new ObjectMap<>();
    private final ObjectMap<String, MapObjective> objectivesById = new ObjectMap<>();
    private final ObjectSet<String> lastSignals = new ObjectSet<>();
    private boolean commandObserved;
    private final ObjectMap<String, ObjectSet<String>> tutorialSteps = new ObjectMap<>();

    /**
     * Resolves the action attempt identity this runtime belongs to. An embedded (in-process) Sector carries its attempt
     * on the bound GameContext's explicit action runtime; an isolated JVM worker still reads the process bootstrap
     * property. Reading only the system property caused the embedded coordinator to receive a random attempt id that
     * mismatched its reservation ("Mission heartbeat identity does not match its action").
     */
    private String attemptId(){
        GameContext context = RuntimeContexts.requireCurrent();
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.find(context);
        ActionRuntimeConfig runtime = shared == null ? ActionRuntimeConfig.disabled() : shared.actionRuntime();
        if(runtime.enabled()){
            ActionRuntimeDescriptor descriptor = runtime.descriptor();
            if(descriptor != null && descriptor.attemptId() != null && !descriptor.attemptId().isBlank()) return descriptor.attemptId();
        }
        return System.getProperty(ActionRuntimeConfig.actionAttemptProperty, "");
    }

    public ErekirMissionRuntime(){
        Events.on(WorldLoadEvent.class, e -> bindWorld());
        Events.on(ResetEvent.class, e -> clear());
        Events.on(UnitCommandIssuedEvent.class, e -> {
            if(active() && e.player != null && e.player.team() == mindustry.Vars.game().state.rules.defaultTeam){
                commandObserved = true;
                String memberId = campaignMemberId(e.player);
                if(!memberId.isBlank()) markTutorial(memberId, mission.missionId + "/tutorial/unit-command");
                appendEventOnce(mission.missionId + "/team-event/unit-command", "team-event", "unit-command", memberId);
            }
        });
        Events.on(PickupEvent.class, e -> {
            if(active() && e.carrier != null && e.carrier.isPlayer()){ String memberId = campaignMemberId(e.carrier.getPlayer()); if(!memberId.isBlank()) markTutorial(memberId, mission.missionId + "/tutorial/payload-pickup"); }
        });
        Events.on(PayloadDropEvent.class, e -> {
            if(active() && e.carrier != null && e.carrier.isPlayer()){ String memberId = campaignMemberId(e.carrier.getPlayer()); if(!memberId.isBlank()) markTutorial(memberId, mission.missionId + "/tutorial/payload-drop"); }
        });
    }


    /** Resolves the durable Shared Campaign identity bound to this Action connection. */
    private String campaignMemberId(mindustry.gen.Player player){
        if(player == null) return "";
        GameContext context = RuntimeContexts.requireCurrent();
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.find(context);
        if(shared != null && shared.actionEnabled()){
            SharedCampaignNet net = SharedCampaignNet.find(context);
            return net == null ? "" : net.authenticatedMemberId(player.con);
        }
        // Outside an isolated Shared Action there is no campaign admission identity to preserve.
        return player.uuid() == null ? "" : player.uuid();
    }

    public boolean active(){
        return mission != null && mindustry.Vars.game().state.isCampaign() && mindustry.Vars.game().state.getSector() != null && mindustry.Vars.game().state.getSector().planet == Planets.erekir;
    }

    public MissionState snapshot(){ return mission == null ? null : copyMission(mission); }
    public boolean commandObserved(){ return commandObserved; }
    public ObjectMap<String, ObjectSet<String>> tutorialSnapshot(){
        ObjectMap<String, ObjectSet<String>> copy = new ObjectMap<>();
        for(var entry : tutorialSteps){ ObjectSet<String> steps = new ObjectSet<>(); steps.addAll(entry.value); copy.put(entry.key, steps); }
        return copy;
    }

    /** Updates durable progress from the authoritative world. Clients only render snapshots supplied by the authority. */
    public void update(){
        if(!active() || mindustry.Vars.game().net.client()) return;
        mission.actionTick = Math.max(mission.actionTick, (long)mindustry.Vars.game().state.tick);
        updateSignals();
        updateObjectives();

        String phase = ErekirMissionCatalog.phase(mission.sectorName, mission.actionTick, mission.signals, mission.completedWorldEvents, mindustry.Vars.game().state.rules);
        if(!Objects.equals(phase, mission.phase)){
            String previous = mission.phase;
            mission.phase = phase;
            appendEventOnce(mission.missionId + "/phase/" + phase, "phase-changed", phase, previous);
        }
    }

    public void onObjectiveDone(MapObjective objective){
        if(!active() || mindustry.Vars.game().net.client()) return;
        String id = ids.get(objective);
        if(id == null) return;
        ObjectiveState value = mission.objectives.get(id, ObjectiveState::new);
        value.objectiveId = id;
        value.requiredProgress = Math.max(1L, requiredProgress(objective));
        value.progress = Math.max(value.progress, value.requiredProgress);
        value.status = ObjectiveStatus.completed;
        value.completedAtTick = mission.actionTick;
        if(value.activatedAtTick < 0) value.activatedAtTick = mission.actionTick;
        if(value.completionEventSequence == 0){
            value.completionEventSequence = mission.eventSequence + 1;
            appendEventOnce(id + "/completed", "objective-completed", id, "");
        }
        mission.objectives.put(id, value);
    }

    /**
     * Restores a coordinator snapshot without replaying map scripts. World state remains sourced from the .msav; this
     * only restores completion flags, mission metadata and the compatibility objective-flag mirror.
     */
    public void applyAuthoritative(MissionState authoritative){
        if(authoritative == null || mission == null || !mission.missionId.equals(authoritative.missionId)) return;
        if(!authoritative.attemptId.isEmpty() && !mission.attemptId.equals(authoritative.attemptId)) return;

        mission.status = authoritative.status;
        mission.definitionVersion = authoritative.definitionVersion;
        mission.definitionFingerprint = authoritative.definitionFingerprint;
        mission.phase = authoritative.phase;
        mission.actionTick = Math.max(mission.actionTick, authoritative.actionTick);
        mission.eventSequence = Math.max(mission.eventSequence, authoritative.eventSequence);
        mission.completedWorldEvents.addAll(authoritative.completedWorldEvents);
        mergeJournal(authoritative.eventJournal);

        mission.signals.clear();
        mission.signals.addAll(authoritative.signals);
        mindustry.Vars.game().state.rules.objectiveFlags.clear();
        mindustry.Vars.game().state.rules.objectiveFlags.addAll(authoritative.signals);

        for(var entry : authoritative.objectives){
            ObjectiveState local = mission.objectives.get(entry.key);
            if(local == null) continue;
            copyObjective(entry.value, local);
            MapObjective objective = objectivesById.get(entry.key);
            if(objective != null && entry.value.status == ObjectiveStatus.completed && !objective.isCompleted()) objective.restoreCompleted();
        }
        lastSignals.clear();
        lastSignals.addAll(mindustry.Vars.game().state.rules.objectiveFlags);
    }

    private void bindWorld(){
        clear();
        if(!mindustry.Vars.game().state.isCampaign() || mindustry.Vars.game().state.getSector() == null || mindustry.Vars.game().state.getSector().planet != Planets.erekir || mindustry.Vars.game().state.getSector().preset == null) return;

        mission = new MissionState();
        mission.planetName = mindustry.Vars.game().state.getSector().planet.name;
        mission.sectorName = mindustry.Vars.game().state.getSector().preset.name;
        mission.missionId = "vanilla-erekir:" + mission.sectorName;
        mission.attemptId = attemptId().isBlank() ? UUID.randomUUID().toString() : attemptId();
        mission.status = MissionStatus.running;
        mission.actionTick = (long)mindustry.Vars.game().state.tick;
        SharedCampaignMissionRegistry.MissionSpec definition = new ErekirMissionCatalog.Definition(mission.sectorName, mindustry.Vars.game().state.getSector().id);
        mission.definitionVersion = "1";
        mission.definitionFingerprint = SharedCampaignMissionRegistry.fingerprint("mindustry:core", mission.missionId, 1, definition);

        ObjectMap<String, Integer> occurrences = new ObjectMap<>();
        for(MapObjective objective : mindustry.Vars.game().state.rules.objectives.all){
            String semantic = semanticHash(objective);
            int occurrence = occurrences.get(semantic, 0);
            occurrences.put(semantic, occurrence + 1);
            String id = mission.missionId + "/objective/" + semantic + (occurrence == 0 ? "" : "-" + occurrence);
            objective.stableId = id;
            ids.put(objective, id);
            objectivesById.put(id, objective);

            ObjectiveState value = new ObjectiveState();
            value.objectiveId = id;
            value.requiredProgress = Math.max(1L, requiredProgress(objective));
            value.progress = Math.min(value.requiredProgress, Math.max(0L, progress(objective)));
            value.status = objective.isCompleted() ? ObjectiveStatus.completed : objective.qualified() ? ObjectiveStatus.active : ObjectiveStatus.locked;
            if(value.status == ObjectiveStatus.active) value.activatedAtTick = mission.actionTick;
            if(value.status == ObjectiveStatus.completed){
                value.activatedAtTick = mission.actionTick;
                value.completedAtTick = mission.actionTick;
            }
            mission.objectives.put(id, value);
        }

        mission.signals.addAll(mindustry.Vars.game().state.rules.objectiveFlags);
        lastSignals.addAll(mindustry.Vars.game().state.rules.objectiveFlags);
        for(String signal : mission.signals) mission.completedWorldEvents.add(ErekirMissionCatalog.eventId(mission.sectorName, signal));
        mission.phase = ErekirMissionCatalog.phase(mission.sectorName, mission.actionTick, mission.signals, mission.completedWorldEvents, mindustry.Vars.game().state.rules);
        MissionState authoritative = authoritativeMission(mission.missionId);
        if(authoritative != null) applyAuthoritative(authoritative);
    }

    private void updateSignals(){
        if(lastSignals.equals(mindustry.Vars.game().state.rules.objectiveFlags)) return;
        ObjectSet<String> added = new ObjectSet<>();
        added.addAll(mindustry.Vars.game().state.rules.objectiveFlags);
        for(String signal : lastSignals) added.remove(signal);
        ObjectSet<String> removed = new ObjectSet<>();
        removed.addAll(lastSignals);
        for(String signal : mindustry.Vars.game().state.rules.objectiveFlags) removed.remove(signal);

        for(String signal : added){
            String eventId = ErekirMissionCatalog.eventId(mission.sectorName, signal);
            mission.completedWorldEvents.add(eventId);
            appendEventOnce(eventId, "world-signal-added", signal, "");
        }
        for(String signal : removed){
            appendEventOnce(ErekirMissionCatalog.eventId(mission.sectorName, signal) + "/removed/" + mission.actionTick,
                "world-signal-removed", signal, "");
        }
        mission.signals.clear();
        mission.signals.addAll(mindustry.Vars.game().state.rules.objectiveFlags);
        lastSignals.clear();
        lastSignals.addAll(mindustry.Vars.game().state.rules.objectiveFlags);
    }

    private void updateObjectives(){
        for(var entry : ids){
            MapObjective objective = entry.key;
            String id = entry.value;
            ObjectiveState value = mission.objectives.get(id);
            if(value == null) continue;

            long required = Math.max(1L, requiredProgress(objective));
            long current = Math.min(required, Math.max(0L, progress(objective)));
            boolean completed = objective.isCompleted();
            boolean qualified = objective.qualified();

            value.requiredProgress = required;
            value.progress = completed ? required : current;
            if(completed){
                value.status = ObjectiveStatus.completed;
                if(value.completedAtTick < 0) value.completedAtTick = mission.actionTick;
                if(value.activatedAtTick < 0) value.activatedAtTick = value.completedAtTick;
            }else if(qualified){
                if(value.status != ObjectiveStatus.active){
                    value.status = ObjectiveStatus.active;
                    value.activatedAtTick = mission.actionTick;
                    appendEventOnce(id + "/activated", "objective-activated", id, "");
                }
            }else{
                value.status = ObjectiveStatus.locked;
            }
        }
    }

    private long progress(MapObjective objective){
        if(objective instanceof BuildCountObjective o) return o.current();
        if(objective instanceof UnitCountObjective o) return o.current();
        if(objective instanceof DestroyUnitsObjective o) return o.current();
        if(objective instanceof DestroyBlocksObjective o) return o.progress();
        if(objective instanceof DestroyBlockObjective o) return o.update() ? 1 : 0;
        if(objective instanceof DestroyCoreObjective o) return o.update() ? 1 : 0;
        if(objective instanceof CoreItemObjective o) return o.current();
        if(objective instanceof ItemObjective o) return o.current();
        if(objective instanceof ResearchObjective o){ ActionCampaignSnapshot action = actionCampaign(); SharedCampaignState state = action == null ? authoritativeCampaign() : null; return action != null ? (action.researched(o.content.name) ? 1 : 0) : state != null ? (state.researched.contains(o.content.name) ? 1 : 0) : o.content.unlocked() ? 1 : 0; }
        if(objective instanceof ProduceObjective o){ ActionCampaignSnapshot action = actionCampaign(); SharedCampaignState state = action == null ? authoritativeCampaign() : null; return action != null ? (action.unlocked(o.content.name) ? 1 : 0) : state != null ? (SharedCampaignState.effectiveUnlocked(state.researched, state.discovered, o.content.name) ? 1 : 0) : o.content.unlocked() ? 1 : 0; }
        if(objective instanceof FlagObjective o) return mindustry.Vars.game().state.rules.objectiveFlags.contains(o.flag) ? 1 : 0;
        if(objective instanceof CommandModeObjective) return commandObserved ? 1 : 0;
        if(objective instanceof TimerObjective o){
            return Math.min(requiredProgress(o), Math.max(0L, Math.round(o.sharedCountup())));
        }
        return objective.isCompleted() ? 1 : 0;
    }

    private long requiredProgress(MapObjective objective){
        if(objective instanceof BuildCountObjective o) return o.count;
        if(objective instanceof UnitCountObjective o) return o.count;
        if(objective instanceof DestroyUnitsObjective o) return o.count;
        if(objective instanceof DestroyBlocksObjective o) return o.positions.length;
        if(objective instanceof CoreItemObjective o) return o.amount;
        if(objective instanceof ItemObjective o) return o.amount;
        if(objective instanceof TimerObjective o) return Math.max(1L, Math.round(o.duration * mindustry.Vars.game().state.rules.objectiveTimerMultiplier));
        return 1;
    }

    private String semanticHash(MapObjective objective){
        try{
            String json = JsonIO.current().toJson(objective, MapObjective.class);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((objective.getClass().getName() + "\n" + json).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for(int i = 0; i < 10; i++) out.append(String.format(Locale.ROOT, "%02x", hash[i] & 0xff));
            return out.toString();
        }catch(Exception e){
            throw new RuntimeException("Failed to create stable objective ID", e);
        }
    }

    private void appendEventOnce(String eventId, String type, String subject, String payload){
        for(MissionEvent existing : mission.eventJournal) if(existing.eventId.equals(eventId)) return;
        MissionEvent event = new MissionEvent();
        event.sequence = ++mission.eventSequence;
        event.eventId = eventId;
        event.type = type;
        event.actionTick = mission.actionTick;
        event.payload = subject + (payload == null || payload.isEmpty() ? "" : "\n" + payload);
        mission.eventJournal.add(event);
        trimJournal();
    }

    private void mergeJournal(Seq<MissionEvent> authoritative){
        ObjectSet<String> known = new ObjectSet<>();
        for(MissionEvent event : mission.eventJournal) known.add(event.eventId);
        for(MissionEvent event : authoritative){
            if(known.add(event.eventId)) mission.eventJournal.add(event);
        }
        mission.eventJournal.sort(event -> event.sequence);
        trimJournal();
    }

    private void trimJournal(){ while(mission.eventJournal.size > 512) mission.eventJournal.remove(0); }

    private void markTutorial(String memberId, String step){
        if(memberId == null || memberId.isBlank() || step == null || step.isBlank()) return;
        tutorialSteps.get(memberId, ObjectSet::new).add(step);
    }

    private void clear(){
        mission = null;
        ids.clear();
        objectivesById.clear();
        lastSignals.clear();
        commandObserved = false;
    }

    private ActionCampaignSnapshot actionCampaign(){
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.find(RuntimeContexts.requireCurrent());
        return shared == null ? null : shared.component(ActionCampaignSnapshot.class);
    }

    private SharedCampaignState authoritativeCampaign(){
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.find(RuntimeContexts.requireCurrent());
        SharedCampaignNet.CampaignStateSource source = shared == null ? null : shared.component(SharedCampaignNet.CampaignStateSource.class);
        return source == null ? null : source.state();
    }

    private MissionState authoritativeMission(String missionId){
        ActionCampaignSnapshot action = actionCampaign();
        if(action != null) return action.mission(missionId);
        SharedCampaignState state = authoritativeCampaign();
        if(state == null || missionId == null || missionId.isBlank()) return null;
        MissionState value = state.missions.get(missionId);
        return value == null ? null : copyMission(value);
    }

    private static void copyObjective(ObjectiveState source, ObjectiveState target){
        target.status = source.status;
        target.progress = source.progress;
        target.requiredProgress = source.requiredProgress;
        target.activatedAtTick = source.activatedAtTick;
        target.completedAtTick = source.completedAtTick;
        target.completionEventSequence = source.completionEventSequence;
    }

    private static MissionState copyMission(MissionState source){
        SharedCampaignState wrapper = new SharedCampaignState();
        wrapper.missions.put(source.missionId, source);
        return mindustry.campaign.shared.io.SharedCampaignCodec.copy(wrapper).missions.get(source.missionId);
    }
}
