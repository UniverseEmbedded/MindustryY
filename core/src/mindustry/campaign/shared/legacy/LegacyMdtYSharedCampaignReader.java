package mindustry.campaign.shared.legacy;

import arc.struct.*;
import mindustry.campaign.shared.SharedCampaignState;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.SharedCampaignCodec;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * Read-only compatibility decoder for historical MindustryY Shared Campaign containers (formats 1..25).
 *
 * <p>Product-specific legacy payloads are consumed only to preserve stream alignment and are deliberately discarded.
 * Returned states retain their original schema number; a separate migration step must upgrade them to clean schema 26
 * before {@link SharedCampaignCodec} can persist them again.</p>
 */
public final class LegacyMdtYSharedCampaignReader{
    public static final int maxLegacyFormat = 25;
    private static final int maxStringBytes = 16 * 1024 * 1024;
    private static final int maxCollectionSize = 1_000_000;

    private LegacyMdtYSharedCampaignReader(){}

    public static SharedCampaignState decode(byte[] data){
        Objects.requireNonNull(data, "data");
        return decode(new ByteArrayInputStream(data));
    }

    public static SharedCampaignState decode(InputStream input){
        Objects.requireNonNull(input, "input");
        try{
            DataInputStream in = new DataInputStream(input instanceof BufferedInputStream ? input : new BufferedInputStream(input));
            if(in.readInt() != SharedCampaignCodec.magic) throw new IOException("Not a Shared Campaign file");
            int format = in.readInt();
            if(format < 1 || format > maxLegacyFormat) throw new IOException("Unsupported legacy MDT-Y shared campaign format: " + format);
            SharedCampaignState state = readState(in, format);
            if(in.read() != -1) throw new IOException("Trailing data in legacy shared campaign file");
            state.validate();
            return state;
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    @FunctionalInterface
    private interface IoReader<T>{ T read(DataInput input) throws IOException; }

    private static SharedCampaignState readState(DataInput in, int format) throws IOException{
        SharedCampaignState s = new SharedCampaignState();
        s.schema = in.readInt();
        s.campaignId = readString(in);
        s.displayName = readString(in);
        s.ownerId = readString(in);
        s.revision = in.readLong();
        s.createdAt = in.readLong();
        s.updatedAt = in.readLong();
        s.maxActiveActions = in.readInt();
        s.freezeWhenEmpty = in.readBoolean();
        if(format >= 11){
            s.primaryPlanetName = readString(in);
            s.multiFrontEnabled = in.readBoolean();
            s.invitePolicy = readEnum(in, InvitePolicy.class);
            s.origin = readEnum(in, CampaignOrigin.class);
            s.originDescription = readString(in);
        }
        if(format >= 2) s.campaignTick = in.readLong();
        if(format >= 6) s.settlementRemainderTicks = in.readLong();
        s.contentFingerprint = readString(in);
        if(format >= 20) s.contentCompatibilityFingerprint = readString(in);
        s.authorityGeneration = in.readLong();
        if(format >= 3){
            s.authorityHostId = readString(in);
            s.migrationPending = in.readBoolean();
            s.migrationTargetHostId = readString(in);
            s.migrationNonce = readString(in);
            s.migrationExpiresAt = in.readLong();
        }else{
            s.authorityHostId = s.ownerId;
        }

        s.members = readMap(in, LegacyMdtYSharedCampaignReader::readString, input -> readMember(input, format));
        if(format >= 11) s.planetPolicies = readMap(in, LegacyMdtYSharedCampaignReader::readString, input -> readPlanetPolicy(input, format));
        s.actions = readMap(in, LegacyMdtYSharedCampaignReader::readString, input -> readAction(input, format));
        s.sectors = readMap(in, LegacyMdtYSharedCampaignReader::readString, input -> readSector(input, format));
        s.missions = readMap(in, LegacyMdtYSharedCampaignReader::readString, input -> readMission(input, format));
        s.researched = readSet(in, LegacyMdtYSharedCampaignReader::readString);
        if(format >= 2){
            s.discovered = readSet(in, LegacyMdtYSharedCampaignReader::readString);
            if(format >= 23) s.producedActual = readSet(in, LegacyMdtYSharedCampaignReader::readString);
            s.research = readMap(in, LegacyMdtYSharedCampaignReader::readString, LegacyMdtYSharedCampaignReader::readResearch);
            if(format >= 4) s.researchTransactions = readMap(in, LegacyMdtYSharedCampaignReader::readString, LegacyMdtYSharedCampaignReader::readResearchTransaction);
            if(format >= 7) s.transportTransactions = readMap(in, LegacyMdtYSharedCampaignReader::readString, LegacyMdtYSharedCampaignReader::readTransportTransaction);
            if(format >= 16) skipLegacyTantrosRelayBatches(in);
            if(format >= 10) s.launchTransactions = readMap(in, LegacyMdtYSharedCampaignReader::readString, LegacyMdtYSharedCampaignReader::readLaunchTransaction);
            s.extensionSchemas = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt);
            s.extensionData = readMap(in, LegacyMdtYSharedCampaignReader::readString, LegacyMdtYSharedCampaignReader::readBytes);
            if(format >= 5){
                s.extensionRequired = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readBoolean);
                s.extensionCompatibility = readMap(in, LegacyMdtYSharedCampaignReader::readString, LegacyMdtYSharedCampaignReader::readString);
            }else{
                for(String id : s.extensionSchemas.keys()){
                    s.extensionRequired.put(id, true);
                    s.extensionCompatibility.put(id, "");
                }
            }
        }
        s.transports = readSeq(in, input -> readTransport(input, format));
        s.recentEvents = readSeq(in, LegacyMdtYSharedCampaignReader::readCampaignEvent);
        if(format >= 11) s.backups = readSeq(in, LegacyMdtYSharedCampaignReader::readBackup);
        if(format >= 17) s.controlRequestReceipts = readMap(in, LegacyMdtYSharedCampaignReader::readString, input -> readControlRequestReceipt(input, format));
        return s;
    }

    private static SectorState readSector(DataInput in, int format) throws IOException{
        SectorState s = new SectorState();
        s.sectorName = readString(in);
        if(format >= 6){ s.planetName = readString(in); s.destinationSector = readString(in); s.legacyLaunchPads = in.readBoolean(); }
        s.hasBase = in.readBoolean(); s.captured = in.readBoolean(); s.attacked = in.readBoolean();
        if(format >= 6){
            s.hasSpawns = in.readBoolean();
            if(format >= 16){ in.readBoolean(); in.readBoolean(); } // legacy Tantros relay sender/receiver flags
        }
        if(format >= 11){ s.discovered = in.readBoolean(); s.expeditionAvailable = in.readBoolean(); s.waitingSettlement = in.readBoolean(); s.logisticsWarning = readString(in); }
        if(format >= 6) s.minutesCaptured = in.readFloat();
        s.lastSavedAt = in.readLong(); if(format >= 11) s.lastOpenedAt = in.readLong(); s.lastSummaryTick = in.readLong(); s.saveRelativePath = readString(in); s.summary = readSummary(in, format).strategicCopy();
        s.items = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt);
        s.productionPerSecond = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readFloat);
        s.exportPerSecond = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readFloat);
        if(format >= 6){
            s.importPerSecond = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readFloat);
            if(format >= 14){ readString(in); in.readBoolean(); in.readLong(); in.readFloat(); } // legacy Industrial model
            if(format >= 15){ readString(in); in.readBoolean(); in.readBoolean(); in.readBoolean(); in.readBoolean(); in.readLong(); } // legacy simulation model
            if(format >= 25){ readString(in); readString(in); } // legacy model sidecar hashes
            s.lastImportedItems = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt);
        }
        if(format >= 21){
            s.displayName = readString(in); s.icon = readString(in); s.contentIcon = readString(in);
            s.resources = readSet(in, LegacyMdtYSharedCampaignReader::readString); s.hasEnemyBase = in.readBoolean(); s.threat = in.readFloat();
        }
        return s;
    }

    private static SectorSummary readSummary(DataInput in, int format) throws IOException{
        SectorSummary s = new SectorSummary(); s.wave = in.readInt(); s.winWave = in.readInt(); s.waves = in.readBoolean(); s.attackMode = in.readBoolean();
        s.coreCount = in.readInt(); s.unitCount = in.readInt(); s.enemyCount = in.readInt(); s.storageCapacity = in.readInt(); if(format >= 10) s.coreType = readString(in);
        s.powerProduced = in.readFloat(); s.powerConsumed = in.readFloat(); s.defenseScore = in.readFloat();
        if(format >= 19){
            in.readInt(); // legacy expected enemy domains
            in.readFloat(); in.readFloat(); in.readFloat(); in.readFloat(); in.readFloat(); // legacy Y defense-domain values
        }
        s.capturedAtTick = in.readLong(); s.sampledAtTick = in.readLong(); s.phase = readString(in);
        if(format >= 6){
            s.planetName = readString(in); s.destinationSector = readString(in); s.legacyLaunchPads = in.readBoolean();
            s.attacked = in.readBoolean(); s.hasSpawns = in.readBoolean(); if(format >= 16){ in.readBoolean(); in.readBoolean(); } s.minutesCaptured = in.readFloat();
        }
        s.items = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt);
        s.productionPerSecond = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readFloat);
        s.exportPerSecond = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readFloat);
        if(format >= 6) s.importPerSecond = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readFloat);
        if(format >= 14){ readString(in); in.readBoolean(); in.readLong(); in.readFloat(); }
        if(format >= 15){ readString(in); in.readBoolean(); in.readBoolean(); in.readBoolean(); in.readBoolean(); in.readLong(); }
        if(format >= 21){
            s.displayName = readString(in); s.icon = readString(in); s.contentIcon = readString(in);
            s.resources = readSet(in, LegacyMdtYSharedCampaignReader::readString); s.hasEnemyBase = in.readBoolean(); s.threat = in.readFloat();
        }
        return s;
    }

    private static void skipLegacyTantrosRelayBatches(DataInput in) throws IOException{
        int count = checkedSize(in.readInt(), maxCollectionSize, "legacy Tantros relay map");
        for(int i = 0; i < count; i++){
            readString(in); // map key
            readString(in); readString(in); readString(in); // batch/source-action/destination-action IDs
            readString(in); readString(in); // source/destination sectors
            readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt); // cargo
            in.readLong(); in.readLong(); in.readLong(); in.readLong(); // departure/eta/create/update
            in.readInt(); // legacy TantrosRelayStatus ordinal
            in.readBoolean(); in.readBoolean();
            readString(in); // failure reason
        }
    }

    private static ControlRequestReceipt readControlRequestReceipt(DataInput in, int format) throws IOException{
        ControlRequestReceipt value = new ControlRequestReceipt(); value.memberId = readString(in); value.requestId = in.readLong();
        value.requestType = readString(in); value.payloadHash = readString(in); value.responseType = readString(in);
        if(format >= 18) value.resultReference = readString(in);
        value.completedRevision = in.readLong(); value.completedAt = in.readLong(); return value;
    }

    private static PlanetPolicyState readPlanetPolicy(DataInput in, int format) throws IOException{
        PlanetPolicyState value = new PlanetPolicyState(); value.planetName = readString(in); value.policyId = readString(in);
        value.policyVersion = in.readInt(); value.compatibilityId = readString(in); value.mode = readString(in);
        value.recommendedActiveActions = in.readInt();
        if(format >= 13){
            value.researchSharing = readString(in); value.resourceOwnership = readString(in);
            value.failureRule = readString(in); value.invasionRule = readString(in);
        }
        return value;
    }

    private static BackupState readBackup(DataInput in) throws IOException{
        BackupState value = new BackupState(); value.backupId = readString(in); value.displayName = readString(in);
        value.reason = readString(in); value.relativePath = readString(in); value.sha256 = readString(in);
        value.createdAt = in.readLong(); value.campaignRevision = in.readLong(); value.sizeBytes = in.readLong();
        value.complete = in.readBoolean(); return value;
    }

    private static MemberState readMember(DataInput in, int format) throws IOException{
        MemberState m = new MemberState();
        m.memberId = readString(in); m.displayName = readString(in); m.lastActionId = readString(in);
        m.lastSeenAt = in.readLong();
        if(format >= 11){ m.lastConnectedAt = in.readLong(); m.lastDisconnectedAt = in.readLong(); }
        m.tutorialSteps = readSet(in, LegacyMdtYSharedCampaignReader::readString); return m;
    }

    private static ActionState readAction(DataInput in, int format) throws IOException{
        ActionState a = new ActionState();
        a.actionId = readString(in); if(format >= 9) a.planetName = readString(in); a.sectorName = readString(in); a.missionId = readString(in); a.attemptId = readString(in);
        a.status = readEnum(in, ActionStatus.class);
        if(format >= 11){
            a.kind = readEnum(in, ActionKind.class); a.objectiveSummary = readString(in);
            a.resourceNeeds = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt); a.spectatorsAllowed = in.readBoolean();
        }
        a.hostId = readString(in); a.hostGeneration = in.readLong(); a.runtimeIncarnation = format >= 24 ? in.readLong() : 1L; a.leaseExpiresAt = in.readLong();
        a.bindAddress = readString(in); a.port = in.readInt(); a.joinSecretHash = readString(in); a.saveRelativePath = readString(in);
        if(format >= 10){
            a.launchOriginSector = readString(in); a.launchLoadout = readString(in);
            a.launchResources = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt);
            a.launchCosts = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt); a.launchCommitted = in.readBoolean();
        }
        a.actionTick = in.readLong(); a.createdAt = in.readLong(); if(format >= 11) a.startedAt = in.readLong(); a.updatedAt = in.readLong();
        a.participants = readSet(in, LegacyMdtYSharedCampaignReader::readString);
        if(format >= 12) a.spectators = readSet(in, LegacyMdtYSharedCampaignReader::readString);
        a.summary = readSummary(in, format).strategicCopy(); a.failureReason = readString(in);
        if(format >= 2){
            a.connectedPlayers = in.readInt();
            if(format >= 12) a.connectedSpectators = in.readInt();
            a.lastSaveHash = readString(in);
        }
        return a;
    }

    private static MissionState readMission(DataInput in, int format) throws IOException{
        MissionState m = new MissionState(); m.missionId = readString(in); if(format >= 9) m.planetName = readString(in); m.sectorName = readString(in); m.definitionVersion = readString(in); if(format >= 8) m.definitionFingerprint = readString(in); m.attemptId = readString(in);
        m.status = readEnum(in, MissionStatus.class); m.phase = readString(in); m.actionTick = in.readLong(); m.eventSequence = in.readLong();
        m.objectives = readMap(in, LegacyMdtYSharedCampaignReader::readString, LegacyMdtYSharedCampaignReader::readObjective);
        m.signals = readSet(in, LegacyMdtYSharedCampaignReader::readString); m.completedWorldEvents = readSet(in, LegacyMdtYSharedCampaignReader::readString);
        m.eventJournal = readSeq(in, LegacyMdtYSharedCampaignReader::readMissionEvent); return m;
    }

    private static ObjectiveState readObjective(DataInput in) throws IOException{
        ObjectiveState o = new ObjectiveState(); o.objectiveId = readString(in); o.status = readEnum(in, ObjectiveStatus.class);
        o.progress = in.readLong(); o.requiredProgress = in.readLong(); o.activatedAtTick = in.readLong();
        o.completedAtTick = in.readLong(); o.completionEventSequence = in.readLong(); return o;
    }

    private static MissionEvent readMissionEvent(DataInput in) throws IOException{
        MissionEvent e = new MissionEvent(); e.sequence = in.readLong(); e.eventId = readString(in); e.type = readString(in); e.actionTick = in.readLong(); e.payload = readString(in); return e;
    }

    private static ResearchState readResearch(DataInput in) throws IOException{
        ResearchState value = new ResearchState();
        value.contentName = readString(in);
        value.required = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt);
        value.contributed = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt);
        value.startedAtRevision = in.readLong();
        value.completedAtRevision = in.readLong();
        return value;
    }

    private static ResearchTransaction readResearchTransaction(DataInput in) throws IOException{
        ResearchTransaction value = new ResearchTransaction();
        value.transactionId = readString(in);
        value.actorId = readString(in);
        value.contentName = readString(in);
        value.status = readEnum(in, ResearchTransactionStatus.class);
        value.createdAt = in.readLong();
        value.updatedAt = in.readLong();
        value.debits = readMap(in, LegacyMdtYSharedCampaignReader::readString, LegacyMdtYSharedCampaignReader::readResearchDebit);
        value.preparedActions = readSet(in, LegacyMdtYSharedCampaignReader::readString);
        value.failureReason = readString(in);
        return value;
    }

    private static ResearchDebit readResearchDebit(DataInput in) throws IOException{
        ResearchDebit value = new ResearchDebit();
        value.sectorName = readString(in);
        value.actionId = readString(in);
        value.items = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt);
        return value;
    }

    private static LaunchTransaction readLaunchTransaction(DataInput in) throws IOException{
        LaunchTransaction value = new LaunchTransaction();
        value.transactionId = readString(in); value.actionId = readString(in); value.actorId = readString(in);
        value.originSector = readString(in); value.sourceActionId = readString(in);
        value.costs = readMap(in, LegacyMdtYSharedCampaignReader::readString, DataInput::readInt); value.status = readEnum(in, LaunchTransactionStatus.class);
        value.createdAt = in.readLong(); value.updatedAt = in.readLong(); value.failureReason = readString(in); return value;
    }

    private static TransportTransaction readTransportTransaction(DataInput in) throws IOException{
        TransportTransaction value = new TransportTransaction();
        value.transactionId = readString(in); value.orderId = readString(in); value.actionId = readString(in); value.itemName = readString(in);
        value.requested = in.readInt(); value.applied = in.readInt(); value.status = readEnum(in, TransportTransactionStatus.class);
        value.createdAt = in.readLong(); value.updatedAt = in.readLong(); value.failureReason = readString(in);
        return value;
    }

    private static TransportOrder readTransport(DataInput in, int format) throws IOException{
        TransportOrder t = new TransportOrder(); t.orderId = readString(in); t.sourceSector = readString(in); t.destinationSector = readString(in); t.itemName = readString(in);
        t.amount = in.readInt(); if(format >= 2) t.loaded = in.readInt(); t.delivered = in.readInt();
        t.createdAt = in.readLong(); t.etaCampaignTick = in.readLong(); t.status = readEnum(in, TransportStatus.class);
        if(format < 2) t.loaded = t.status == TransportStatus.queued ? 0 : t.amount;
        return t;
    }

    private static CampaignEvent readCampaignEvent(DataInput in) throws IOException{
        CampaignEvent e = new CampaignEvent(); e.sequence = in.readLong(); e.timestamp = in.readLong(); e.type = readString(in); e.subjectId = readString(in); e.payload = readString(in); return e;
    }

    private static String readString(DataInput in) throws IOException{
        int length = checkedSize(in.readInt(), maxStringBytes, "string");
        byte[] data = new byte[length]; in.readFully(data); return new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(DataInput in) throws IOException{
        int length = checkedSize(in.readInt(), maxStringBytes, "byte vector");
        byte[] data = new byte[length]; in.readFully(data); return data;
    }

    private static <E extends Enum<E>> E readEnum(DataInput in, Class<E> type) throws IOException{
        E[] values = type.getEnumConstants(); int ordinal = in.readInt();
        if(ordinal < 0 || ordinal >= values.length) throw new IOException("Invalid " + type.getSimpleName() + " ordinal: " + ordinal);
        return values[ordinal];
    }

    private static <T> Seq<T> readSeq(DataInput in, IoReader<T> reader) throws IOException{
        int size = checkedSize(in.readInt(), maxCollectionSize, "sequence"); Seq<T> seq = new Seq<>(size);
        for(int i = 0; i < size; i++) seq.add(reader.read(in)); return seq;
    }

    private static <T> ObjectSet<T> readSet(DataInput in, IoReader<T> reader) throws IOException{
        int size = checkedSize(in.readInt(), maxCollectionSize, "set"); ObjectSet<T> set = new ObjectSet<>(size);
        for(int i = 0; i < size; i++) set.add(reader.read(in)); return set;
    }

    private static <K, V> ObjectMap<K, V> readMap(DataInput in, IoReader<K> keyReader, IoReader<V> valueReader) throws IOException{
        int size = checkedSize(in.readInt(), maxCollectionSize, "map"); ObjectMap<K, V> map = new ObjectMap<>(size);
        for(int i = 0; i < size; i++) map.put(keyReader.read(in), valueReader.read(in)); return map;
    }

    private static int checkedSize(int value, int max, String kind) throws IOException{
        if(value < 0 || value > max) throw new IOException("Invalid " + kind + " size: " + value); return value;
    }
}
