package mindustry.campaign.shared.runtime;

import arc.struct.*;
import mindustry.campaign.shared.SharedCampaignState.*;

import java.io.*;
import java.nio.charset.*;

/** Compact payload codecs for the control plane. */
public final class RuntimePayloads{
    private RuntimePayloads(){}

    public record ActionHello(String actionId, long generation, long runtimeIncarnation, int gamePort, String contentFingerprint){}
    public record ActionHeartbeat(String actionId, long generation, long runtimeIncarnation, long actionTick, ObjectSet<String> participants, ObjectSet<String> spectators){}
    public record ActionStateUpdate(String actionId, long generation, long runtimeIncarnation, long actionTick, boolean includesExtendedState, SectorSummary summary, MissionState mission, ObjectSet<String> produced, ObjectMap<String, ObjectSet<String>> tutorialSteps){}
    public record ActionStopped(String actionId, long generation, long runtimeIncarnation, boolean clean, boolean completed, String saveHash, String reason, SectorSummary summary, MissionState mission, ObjectSet<String> produced){}
    public record LaunchPlan(String originSector, String loadout, ObjectMap<String, Integer> resources){
        public LaunchPlan{
            originSector = originSector == null ? "" : originSector;
            loadout = loadout == null ? "" : loadout;
            resources = resources == null ? new ObjectMap<>() : resources;
        }
        public static LaunchPlan empty(){ return new LaunchPlan("", "", new ObjectMap<>()); }
    }
    public record StartAction(String planetName, String sectorName, String missionId, LaunchPlan launch){
        public StartAction{ launch = launch == null ? LaunchPlan.empty() : launch; }
    }
    public record StartResult(String actionId, String host, int port, String joinToken, String error){}
    public record JoinResult(String actionId, String host, int port, String joinToken, String error){}
    /** Same-TCP hot-switch outcome: when ok, the client rebinds its detached entry channel instead of dialing a new TCP. */
    public record HotSwitchResult(String sessionId, String actionId, String host, int port, String joinToken, boolean sameTcp, String error){}
    public record JoinAction(String actionId, boolean spectator, String networkUuid){
        public JoinAction{
            actionId = actionId == null ? "" : actionId;
            networkUuid = networkUuid == null ? "" : networkUuid;
        }
    }
    public record HotSwitchRequest(String sessionId, String fromActionId, String toActionId, boolean spectator, String networkUuid){
        public HotSwitchRequest{
            sessionId = sessionId == null ? "" : sessionId;
            fromActionId = fromActionId == null ? "" : fromActionId;
            toActionId = toActionId == null ? "" : toActionId;
            networkUuid = networkUuid == null ? "" : networkUuid;
        }
    }
    /** Source-Action request for a direct vanilla reconnect to another already-running Action. */
    public record VanillaTransferRequest(String fromActionId, String target, String memberId, String networkUuid, String remoteAddress, boolean spectator){
        public VanillaTransferRequest{
            fromActionId = fromActionId == null ? "" : fromActionId;
            target = target == null ? "" : target;
            memberId = memberId == null ? "" : memberId;
            networkUuid = networkUuid == null ? "" : networkUuid;
            remoteAddress = remoteAddress == null ? "" : remoteAddress;
        }
    }
    public record VanillaTransferResult(String actionId, String host, int port, long expiresAt, String error){}
    /** Coordinator-authenticated one-shot admission installed on the destination Action before Call.connect. */
    public record VanillaAdmissionPrepare(String actionId, String grantId, String memberId, String networkUuid, String remoteAddress, boolean spectator, long expiresAt){
        public VanillaAdmissionPrepare{
            actionId = actionId == null ? "" : actionId;
            grantId = grantId == null ? "" : grantId;
            memberId = memberId == null ? "" : memberId;
            networkUuid = networkUuid == null ? "" : networkUuid;
            remoteAddress = remoteAddress == null ? "" : remoteAddress;
        }
    }
    public record VanillaAdmissionPrepareResult(String grantId, boolean accepted, String error){}
    /** Idempotent cleanup for a prepared vanilla grant when coordinator durable commit fails. */
    public record VanillaAdmissionRevoke(String actionId, String grantId, String networkUuid){
        public VanillaAdmissionRevoke{
            actionId = actionId == null ? "" : actionId;
            grantId = grantId == null ? "" : grantId;
            networkUuid = networkUuid == null ? "" : networkUuid;
        }
    }
    public record CampaignSettings(int maxActiveActions, boolean freezeWhenEmpty, boolean multiFrontEnabled, InvitePolicy invitePolicy, PersistenceProfile persistenceProfile){}
    /** Exact research identity: content plus the planet/technology-tree context chosen by the player. */
    public record ResearchRequest(String contentName, String planetName){
        public ResearchRequest{
            contentName = contentName == null ? "" : contentName;
            planetName = planetName == null ? "" : planetName;
        }
    }
    public record SectorLogistics(String sourceSector, String destinationSector){}
    public record ActionLogistics(String actionId, String sourceSector, String destinationSector){}
    public record ResearchPrepare(String transactionId, String actionId, ObjectMap<String, Integer> items){}
    public record ResearchPrepareResult(String transactionId, boolean success, boolean retryable, String error, SectorSummary summary){
        public ResearchPrepareResult(String transactionId, boolean success, String error, SectorSummary summary){
            this(transactionId, success, false, error, summary);
        }
    }
    public record ResearchDecision(String transactionId, boolean commit){}
    public record ResearchDecisionResult(String transactionId, boolean success, String error){}
    public record SnapshotApplied(long revision){}
    public record TransportPrepare(String transactionId, String actionId, String itemName, int amount){}
    public record TransportPrepareResult(String transactionId, boolean success, String error, int applied, SectorSummary summary){}
    public record TransportDecision(String transactionId, boolean commit){}
    public record TransportDecisionResult(String transactionId, boolean success, String error){}
    /** Idempotent Action-originated LaunchPad dispatch; source inventory has already been removed by gameplay. */
    public record TransportDispatch(String actionId, String dispatchId, String sourceSector, String destinationSector, String itemName, int amount, long travelTurns){}
    public record TransportDispatchResult(String dispatchId, boolean accepted, String error){}

    public static byte[] encode(ActionHello value){ return write(out -> { string(out, value.actionId); out.writeLong(value.generation); out.writeLong(value.runtimeIncarnation); out.writeInt(value.gamePort); string(out, value.contentFingerprint); }); }
    public static ActionHello actionHello(byte[] data){ return read(data, in -> new ActionHello(string(in), in.readLong(), in.readLong(), in.readInt(), string(in))); }

    public static byte[] encode(ActionHeartbeat value){ return write(out -> {
        string(out, value.actionId); out.writeLong(value.generation); out.writeLong(value.runtimeIncarnation); out.writeLong(value.actionTick);
        out.writeInt(value.participants.size); for(String member : value.participants.toSeq().sort()) string(out, member);
        out.writeInt(value.spectators.size); for(String member : value.spectators.toSeq().sort()) string(out, member);
    }); }
    public static ActionHeartbeat heartbeat(byte[] data){ return read(data, in -> {
        String actionId = string(in); long generation = in.readLong(), runtimeIncarnation = in.readLong(), actionTick = in.readLong();
        ObjectSet<String> participants = new ObjectSet<>(); int playerCount = size(in.readInt()); for(int i = 0; i < playerCount; i++) participants.add(string(in));
        ObjectSet<String> spectators = new ObjectSet<>(); int spectatorCount = size(in.readInt()); for(int i = 0; i < spectatorCount; i++) spectators.add(string(in));
        return new ActionHeartbeat(actionId, generation, runtimeIncarnation, actionTick, participants, spectators);
    }); }

    public static byte[] encode(ActionStateUpdate value){ return write(out -> {
        string(out, value.actionId); out.writeLong(value.generation); out.writeLong(value.runtimeIncarnation); out.writeLong(value.actionTick); out.writeBoolean(value.includesExtendedState);
        summary(out, value.summary); out.writeBoolean(value.mission != null); if(value.mission != null) mission(out, value.mission);
        out.writeInt(value.produced.size); for(String name : value.produced.toSeq().sort()) string(out, name);
        out.writeInt(value.tutorialSteps.size);
        for(String member : value.tutorialSteps.keys().toSeq().sort()){
            string(out, member);
            ObjectSet<String> steps = value.tutorialSteps.get(member);
            out.writeInt(steps.size); for(String step : steps.toSeq().sort()) string(out, step);
        }
    }); }
    public static ActionStateUpdate stateUpdate(byte[] data){ return read(data, in -> {
        String actionId = string(in); long generation = in.readLong(), runtimeIncarnation = in.readLong(), actionTick = in.readLong(); boolean includesExtendedState = in.readBoolean();
        SectorSummary summary = summary(in); MissionState mission = in.readBoolean() ? mission(in) : null;
        ObjectSet<String> produced = new ObjectSet<>(); int amount = size(in.readInt()); for(int i = 0; i < amount; i++) produced.add(string(in));
        ObjectMap<String, ObjectSet<String>> tutorialSteps = new ObjectMap<>();
        int members = size(in.readInt());
        for(int i = 0; i < members; i++){
            String member = string(in); ObjectSet<String> steps = new ObjectSet<>();
            int stepCount = size(in.readInt()); for(int j = 0; j < stepCount; j++) steps.add(string(in));
            tutorialSteps.put(member, steps);
        }
        return new ActionStateUpdate(actionId, generation, runtimeIncarnation, actionTick, includesExtendedState, summary, mission, produced, tutorialSteps);
    }); }

    public static byte[] encode(ActionStopped value){ return write(out -> {
        string(out, value.actionId); out.writeLong(value.generation); out.writeLong(value.runtimeIncarnation); out.writeBoolean(value.clean); out.writeBoolean(value.completed); string(out, value.saveHash); string(out, value.reason);
        out.writeBoolean(value.summary != null); if(value.summary != null) summary(out, value.summary);
        out.writeBoolean(value.mission != null); if(value.mission != null) mission(out, value.mission);
        out.writeInt(value.produced.size); for(String name : value.produced.toSeq().sort()) string(out, name);
    }); }
    public static ActionStopped stopped(byte[] data){ return read(data, in -> {
        String actionId = string(in); long generation = in.readLong(), runtimeIncarnation = in.readLong(); boolean clean = in.readBoolean(), completed = in.readBoolean(); String hash = string(in), reason = string(in);
        SectorSummary summary = in.readBoolean() ? summary(in) : null; MissionState mission = in.readBoolean() ? mission(in) : null;
        ObjectSet<String> produced = new ObjectSet<>(); int count = size(in.readInt()); for(int i = 0; i < count; i++) produced.add(string(in));
        return new ActionStopped(actionId, generation, runtimeIncarnation, clean, completed, hash, reason, summary, mission, produced);
    }); }

    public static byte[] encode(StartAction value){ return write(out -> {
        string(out, value.planetName); string(out, value.sectorName); string(out, value.missionId);
        string(out, value.launch.originSector); string(out, value.launch.loadout); writeIntMap(out, value.launch.resources);
    }); }
    public static StartAction startAction(byte[] data){ return read(data, in -> new StartAction(string(in), string(in), string(in), new LaunchPlan(string(in), string(in), readIntMap(in)))); }

    public static byte[] encode(LaunchPlan value){ return write(out -> { string(out, value.originSector); string(out, value.loadout); writeIntMap(out, value.resources); }); }
    public static LaunchPlan launchPlan(byte[] data){ return read(data, in -> new LaunchPlan(string(in), string(in), readIntMap(in))); }

    public static byte[] encode(StartResult value){ return write(out -> { string(out, value.actionId); string(out, value.host); out.writeInt(value.port); string(out, value.joinToken); string(out, value.error); }); }
    public static StartResult startResult(byte[] data){ return read(data, in -> new StartResult(string(in), string(in), in.readInt(), string(in), string(in))); }

    public static byte[] encode(JoinResult value){ return write(out -> { string(out, value.actionId); string(out, value.host); out.writeInt(value.port); string(out, value.joinToken); string(out, value.error); }); }
    public static JoinResult joinResult(byte[] data){ return read(data, in -> new JoinResult(string(in), string(in), in.readInt(), string(in), string(in))); }

    public static byte[] encode(HotSwitchResult value){ return write(out -> {
        string(out, value.sessionId); string(out, value.actionId); string(out, value.host); out.writeInt(value.port);
        string(out, value.joinToken); out.writeBoolean(value.sameTcp); string(out, value.error);
    }); }
    public static HotSwitchResult hotSwitchResult(byte[] data){ return read(data, in ->
        new HotSwitchResult(string(in), string(in), string(in), in.readInt(), string(in), in.readBoolean(), string(in))); }

    public static byte[] encode(HotSwitchRequest value){ return write(out -> {
        string(out, value.sessionId); string(out, value.fromActionId); string(out, value.toActionId); out.writeBoolean(value.spectator); string(out, value.networkUuid);
    }); }
    public static HotSwitchRequest hotSwitchRequest(byte[] data){ return read(data, in ->
        new HotSwitchRequest(string(in), string(in), string(in), in.readBoolean(), string(in))); }

    public static byte[] encode(VanillaTransferRequest value){ return write(out -> {
        string(out, value.fromActionId); string(out, value.target); string(out, value.memberId); string(out, value.networkUuid); string(out, value.remoteAddress); out.writeBoolean(value.spectator);
    }); }
    public static VanillaTransferRequest vanillaTransferRequest(byte[] data){ return read(data, in ->
        new VanillaTransferRequest(string(in), string(in), string(in), string(in), string(in), in.readBoolean())); }

    public static byte[] encode(VanillaTransferResult value){ return write(out -> {
        string(out, value.actionId); string(out, value.host); out.writeInt(value.port); out.writeLong(value.expiresAt); string(out, value.error);
    }); }
    public static VanillaTransferResult vanillaTransferResult(byte[] data){ return read(data, in ->
        new VanillaTransferResult(string(in), string(in), in.readInt(), in.readLong(), string(in))); }

    public static byte[] encode(VanillaAdmissionPrepare value){ return write(out -> {
        string(out, value.actionId); string(out, value.grantId); string(out, value.memberId); string(out, value.networkUuid); string(out, value.remoteAddress); out.writeBoolean(value.spectator); out.writeLong(value.expiresAt);
    }); }
    public static VanillaAdmissionPrepare vanillaAdmissionPrepare(byte[] data){ return read(data, in ->
        new VanillaAdmissionPrepare(string(in), string(in), string(in), string(in), string(in), in.readBoolean(), in.readLong())); }

    public static byte[] encode(VanillaAdmissionPrepareResult value){ return write(out -> { string(out, value.grantId); out.writeBoolean(value.accepted); string(out, value.error); }); }
    public static VanillaAdmissionPrepareResult vanillaAdmissionPrepareResult(byte[] data){ return read(data, in ->
        new VanillaAdmissionPrepareResult(string(in), in.readBoolean(), string(in))); }

    public static byte[] encode(VanillaAdmissionRevoke value){ return write(out -> {
        string(out, value.actionId); string(out, value.grantId); string(out, value.networkUuid);
    }); }
    public static VanillaAdmissionRevoke vanillaAdmissionRevoke(byte[] data){ return read(data, in ->
        new VanillaAdmissionRevoke(string(in), string(in), string(in))); }

    public static byte[] encodeBoolean(boolean value){ return write(out -> out.writeBoolean(value)); }
    public static boolean decodeBoolean(byte[] data){ return read(data, DataInputStream::readBoolean); }

    public static byte[] encode(JoinAction value){ return write(out -> { string(out, value.actionId); out.writeBoolean(value.spectator); string(out, value.networkUuid); }); }
    public static JoinAction joinAction(byte[] data){ return read(data, in -> new JoinAction(string(in), in.readBoolean(), string(in))); }

    public static byte[] encode(CampaignSettings value){ return write(out -> {
        out.writeInt(value.maxActiveActions); out.writeBoolean(value.freezeWhenEmpty); out.writeBoolean(value.multiFrontEnabled);
        string(out, value.invitePolicy.name()); string(out, value.persistenceProfile.name());
    }); }
    public static CampaignSettings campaignSettings(byte[] data){ return read(data, in -> new CampaignSettings(
        in.readInt(), in.readBoolean(), in.readBoolean(), InvitePolicy.valueOf(string(in)), PersistenceProfile.valueOf(string(in)))); }

    public static byte[] encode(ResearchRequest value){ return write(out -> { string(out, value.contentName); string(out, value.planetName); }); }
    public static ResearchRequest researchRequest(byte[] data){ return read(data, in -> new ResearchRequest(string(in), string(in))); }

    public static byte[] encode(SectorLogistics value){ return write(out -> { string(out, value.sourceSector); string(out, value.destinationSector); }); }
    public static SectorLogistics sectorLogistics(byte[] data){ return read(data, in -> new SectorLogistics(string(in), string(in))); }

    public static byte[] encode(ActionLogistics value){ return write(out -> { string(out, value.actionId); string(out, value.sourceSector); string(out, value.destinationSector); }); }
    public static ActionLogistics actionLogistics(byte[] data){ return read(data, in -> new ActionLogistics(string(in), string(in), string(in))); }

    public static byte[] encode(ResearchPrepare value){ return write(out -> {
        string(out, value.transactionId); string(out, value.actionId); writeIntMap(out, value.items);
    }); }
    public static ResearchPrepare researchPrepare(byte[] data){ return read(data, in -> new ResearchPrepare(string(in), string(in), readIntMap(in))); }

    public static byte[] encode(ResearchPrepareResult value){ return write(out -> {
        string(out, value.transactionId); out.writeBoolean(value.success); out.writeBoolean(value.retryable); string(out, value.error);
        out.writeBoolean(value.summary != null); if(value.summary != null) summary(out, value.summary);
    }); }
    public static ResearchPrepareResult researchPrepareResult(byte[] data){ return read(data, in -> new ResearchPrepareResult(string(in), in.readBoolean(), in.readBoolean(), string(in), in.readBoolean() ? summary(in) : null)); }

    public static byte[] encode(ResearchDecision value){ return write(out -> { string(out, value.transactionId); out.writeBoolean(value.commit); }); }
    public static ResearchDecision researchDecision(byte[] data){ return read(data, in -> new ResearchDecision(string(in), in.readBoolean())); }

    public static byte[] encode(ResearchDecisionResult value){ return write(out -> { string(out, value.transactionId); out.writeBoolean(value.success); string(out, value.error); }); }
    public static ResearchDecisionResult researchDecisionResult(byte[] data){ return read(data, in -> new ResearchDecisionResult(string(in), in.readBoolean(), string(in))); }

    public static byte[] encode(SnapshotApplied value){ return write(out -> out.writeLong(value.revision)); }
    public static SnapshotApplied snapshotApplied(byte[] data){ return read(data, in -> new SnapshotApplied(in.readLong())); }

    public static byte[] encode(TransportPrepare value){ return write(out -> { string(out, value.transactionId); string(out, value.actionId); string(out, value.itemName); out.writeInt(value.amount); }); }
    public static TransportPrepare transportPrepare(byte[] data){ return read(data, in -> new TransportPrepare(string(in), string(in), string(in), in.readInt())); }

    public static byte[] encode(TransportPrepareResult value){ return write(out -> {
        string(out, value.transactionId); out.writeBoolean(value.success); string(out, value.error); out.writeInt(value.applied);
        out.writeBoolean(value.summary != null); if(value.summary != null) summary(out, value.summary);
    }); }
    public static TransportPrepareResult transportPrepareResult(byte[] data){ return read(data, in -> new TransportPrepareResult(string(in), in.readBoolean(), string(in), in.readInt(), in.readBoolean() ? summary(in) : null)); }

    public static byte[] encode(TransportDecision value){ return write(out -> { string(out, value.transactionId); out.writeBoolean(value.commit); }); }
    public static TransportDecision transportDecision(byte[] data){ return read(data, in -> new TransportDecision(string(in), in.readBoolean())); }

    public static byte[] encode(TransportDecisionResult value){ return write(out -> { string(out, value.transactionId); out.writeBoolean(value.success); string(out, value.error); }); }
    public static TransportDecisionResult transportDecisionResult(byte[] data){ return read(data, in -> new TransportDecisionResult(string(in), in.readBoolean(), string(in))); }

    public static byte[] encode(TransportDispatch value){ return write(out -> {
        string(out, value.actionId); string(out, value.dispatchId); string(out, value.sourceSector); string(out, value.destinationSector);
        string(out, value.itemName); out.writeInt(value.amount); out.writeLong(value.travelTurns);
    }); }
    public static TransportDispatch transportDispatch(byte[] data){ return read(data, in ->
        new TransportDispatch(string(in), string(in), string(in), string(in), string(in), in.readInt(), in.readLong())); }

    public static byte[] encode(TransportDispatchResult value){ return write(out -> { string(out, value.dispatchId); out.writeBoolean(value.accepted); string(out, value.error); }); }
    public static TransportDispatchResult transportDispatchResult(byte[] data){ return read(data, in -> new TransportDispatchResult(string(in), in.readBoolean(), string(in))); }


    public static byte[] encodeSummary(SectorSummary value){ return write(out -> summary(out, value)); }
    public static SectorSummary decodeSummary(byte[] data){ return read(data, RuntimePayloads::summary); }

    public static byte[] encodeString(String value){ return write(out -> string(out, value)); }
    public static String decodeString(byte[] data){ return read(data, RuntimePayloads::string); }

    public static byte[] encodeStrings(String... values){ return write(out -> { out.writeInt(values.length); for(String value : values) string(out, value); }); }
    public static String[] decodeStrings(byte[] data){ return read(data, in -> { int size = in.readInt(); if(size < 0 || size > 128) throw new IOException("Invalid string vector"); String[] values = new String[size]; for(int i = 0; i < size; i++) values[i] = string(in); return values; }); }

    private static void mission(DataOutput out, MissionState m) throws IOException{
        string(out, m.missionId); string(out, m.planetName); string(out, m.sectorName); string(out, m.definitionVersion); string(out, m.definitionFingerprint); string(out, m.attemptId);
        out.writeInt(m.status.ordinal()); string(out, m.phase); out.writeLong(m.actionTick); out.writeLong(m.eventSequence);
        out.writeInt(m.objectives.size);
        for(var entry : m.objectives){
            string(out, entry.key); ObjectiveState o = entry.value; string(out, o.objectiveId); out.writeInt(o.status.ordinal());
            out.writeLong(o.progress); out.writeLong(o.requiredProgress); out.writeLong(o.activatedAtTick); out.writeLong(o.completedAtTick); out.writeLong(o.completionEventSequence);
        }
        out.writeInt(m.signals.size); for(String signal : m.signals) string(out, signal);
        out.writeInt(m.completedWorldEvents.size); for(String event : m.completedWorldEvents) string(out, event);
        out.writeInt(m.eventJournal.size); for(MissionEvent e : m.eventJournal){ out.writeLong(e.sequence); string(out, e.eventId); string(out, e.type); out.writeLong(e.actionTick); string(out, e.payload); }
    }

    private static MissionState mission(DataInput in) throws IOException{
        MissionState m = new MissionState(); m.missionId = string(in); m.planetName = string(in); m.sectorName = string(in); m.definitionVersion = string(in); m.definitionFingerprint = string(in); m.attemptId = string(in);
        int status = in.readInt(); if(status < 0 || status >= MissionStatus.values().length) throw new IOException("Invalid mission status"); m.status = MissionStatus.values()[status];
        m.phase = string(in); m.actionTick = in.readLong(); m.eventSequence = in.readLong();
        int objectives = size(in.readInt()); for(int i = 0; i < objectives; i++){
            String key = string(in); ObjectiveState o = new ObjectiveState(); o.objectiveId = string(in); int os = in.readInt();
            if(os < 0 || os >= ObjectiveStatus.values().length) throw new IOException("Invalid objective status"); o.status = ObjectiveStatus.values()[os];
            o.progress = in.readLong(); o.requiredProgress = in.readLong(); o.activatedAtTick = in.readLong(); o.completedAtTick = in.readLong(); o.completionEventSequence = in.readLong(); m.objectives.put(key, o);
        }
        int signals = size(in.readInt()); for(int i = 0; i < signals; i++) m.signals.add(string(in));
        int worldEvents = size(in.readInt()); for(int i = 0; i < worldEvents; i++) m.completedWorldEvents.add(string(in));
        int events = size(in.readInt()); for(int i = 0; i < events; i++){ MissionEvent e = new MissionEvent(); e.sequence = in.readLong(); e.eventId = string(in); e.type = string(in); e.actionTick = in.readLong(); e.payload = string(in); m.eventJournal.add(e); }
        return m;
    }

    private static void summary(DataOutput out, SectorSummary s) throws IOException{
        out.writeInt(s.wave); out.writeInt(s.winWave); out.writeBoolean(s.waves); out.writeBoolean(s.attackMode);
        out.writeInt(s.coreCount); out.writeInt(s.unitCount); out.writeInt(s.enemyCount); out.writeInt(s.storageCapacity); string(out, s.coreType);
        out.writeFloat(s.powerProduced); out.writeFloat(s.powerConsumed); out.writeFloat(s.defenseScore);
        out.writeLong(s.capturedAtTick); out.writeLong(s.sampledAtTick); string(out, s.phase);
        string(out, s.planetName); string(out, s.destinationSector); out.writeBoolean(s.legacyLaunchPads);
        out.writeBoolean(s.attacked); out.writeBoolean(s.hasSpawns); out.writeFloat(s.minutesCaptured);
        writeIntMap(out, s.items);
        writeFloatMap(out, s.productionPerSecond);
        writeFloatMap(out, s.exportPerSecond);
        writeFloatMap(out, s.importPerSecond);
        string(out, s.displayName); string(out, s.icon); string(out, s.contentIcon); writeStringSet(out, s.resources);
        out.writeBoolean(s.hasEnemyBase); out.writeFloat(s.threat);
    }

    private static SectorSummary summary(DataInput in) throws IOException{
        SectorSummary s = new SectorSummary(); s.wave = in.readInt(); s.winWave = in.readInt(); s.waves = in.readBoolean(); s.attackMode = in.readBoolean();
        s.coreCount = in.readInt(); s.unitCount = in.readInt(); s.enemyCount = in.readInt(); s.storageCapacity = in.readInt(); s.coreType = string(in);
        s.powerProduced = in.readFloat(); s.powerConsumed = in.readFloat(); s.defenseScore = in.readFloat();
        s.capturedAtTick = in.readLong(); s.sampledAtTick = in.readLong(); s.phase = string(in);
        s.planetName = string(in); s.destinationSector = string(in); s.legacyLaunchPads = in.readBoolean();
        s.attacked = in.readBoolean(); s.hasSpawns = in.readBoolean(); s.minutesCaptured = in.readFloat();
        s.items = readIntMap(in);
        s.productionPerSecond = readFloatMap(in);
        s.exportPerSecond = readFloatMap(in);
        s.importPerSecond = readFloatMap(in);
        s.displayName = string(in); s.icon = string(in); s.contentIcon = string(in); s.resources = readStringSet(in);
        s.hasEnemyBase = in.readBoolean(); s.threat = in.readFloat();
        return s;
    }


    private static void writeStringSet(DataOutput out, ObjectSet<String> values) throws IOException{
        Seq<String> ordered = values == null ? new Seq<>() : values.toSeq().sort();
        out.writeInt(ordered.size);
        for(String value : ordered) string(out, value);
    }
    private static ObjectSet<String> readStringSet(DataInput in) throws IOException{
        int count = size(in.readInt());
        ObjectSet<String> values = new ObjectSet<>(count);
        for(int i = 0; i < count; i++) values.add(string(in));
        return values;
    }

    private static void writeIntMap(DataOutput out, ObjectMap<String, Integer> values) throws IOException{
        Seq<String> keys = values.keys().toSeq().sort(); out.writeInt(keys.size);
        for(String key : keys){ string(out, key); out.writeInt(values.get(key)); }
    }
    private static ObjectMap<String, Integer> readIntMap(DataInput in) throws IOException{
        int count = size(in.readInt()); ObjectMap<String, Integer> values = new ObjectMap<>(count);
        for(int i = 0; i < count; i++) values.put(string(in), in.readInt()); return values;
    }
    private static void writeFloatMap(DataOutput out, ObjectMap<String, Float> values) throws IOException{
        Seq<String> keys = values.keys().toSeq().sort(); out.writeInt(keys.size);
        for(String key : keys){ string(out, key); out.writeFloat(values.get(key)); }
    }
    private static ObjectMap<String, Float> readFloatMap(DataInput in) throws IOException{
        int count = size(in.readInt()); ObjectMap<String, Float> values = new ObjectMap<>(count);
        for(int i = 0; i < count; i++) values.put(string(in), in.readFloat()); return values;
    }

    private static int size(int size) throws IOException{ if(size < 0 || size > 1_000_000) throw new IOException("Invalid collection size"); return size; }
    private static void string(DataOutput out, String value) throws IOException{ byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8); if(bytes.length > 16 * 1024 * 1024) throw new IOException("String too long"); out.writeInt(bytes.length); out.write(bytes); }
    private static String string(DataInput in) throws IOException{ int length = in.readInt(); if(length < 0 || length > 16 * 1024 * 1024) throw new IOException("Invalid string length"); byte[] bytes = new byte[length]; in.readFully(bytes); return new String(bytes, StandardCharsets.UTF_8); }

    private static byte[] write(IoConsumer<DataOutputStream> writer){
        try{ ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try(DataOutputStream out = new DataOutputStream(bytes)){ writer.accept(out); } return bytes.toByteArray(); }
        catch(IOException e){ throw new UncheckedIOException(e); }
    }
    private static <T> T read(byte[] data, IoFunction<DataInputStream, T> reader){
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){ T value = reader.apply(in); if(in.read() != -1) throw new IOException("Trailing payload data"); return value; }
        catch(IOException e){ throw new UncheckedIOException(e); }
    }
    @FunctionalInterface private interface IoConsumer<T>{ void accept(T value) throws IOException; }
    @FunctionalInterface private interface IoFunction<T, R>{ R apply(T value) throws IOException; }
}
