package mindustry.campaign.shared.io;


import arc.struct.*;
import mindustry.campaign.shared.SharedCampaignState;
import mindustry.campaign.shared.SharedCampaignState.*;

import java.io.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.function.*;

/** Deterministic binary codec for shared campaign state. */
public final class SharedCampaignCodec{
    public static final int magic = 0x4d594350; // MYCP
    public static final int formatVersion = 27;
    private static final int maxStringBytes = 16 * 1024 * 1024;
    private static final int maxCollectionSize = 1_000_000;

    private SharedCampaignCodec(){}

    public static byte[] encode(SharedCampaignState state){
        return encode(state, false);
    }

    /** Encodes the bounded client/action control-plane view. Clean schema 26 keeps large world payloads outside this state graph. */
    public static byte[] encodeStrategic(SharedCampaignState state){
        return encode(state, true);
    }

    private static byte[] encode(SharedCampaignState state, boolean strategic){
        try{
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            write(state, strategic, bytes);
            return bytes.toByteArray();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /** Streams the durable binary representation without allocating a second full-size snapshot byte array. */
    public static void write(SharedCampaignState state, OutputStream output) throws IOException{
        write(state, false, output);
    }

    /** Streams the bounded strategic representation without taking ownership of {@code output}. */
    public static void writeStrategic(SharedCampaignState state, OutputStream output) throws IOException{
        write(state, true, output);
    }

    private static void write(SharedCampaignState state, boolean strategic, OutputStream output) throws IOException{
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(output, "output");
        state.validate();
        if(state.schema != SharedCampaignState.currentSchema) throw new IOException("State must be migrated to clean schema " + SharedCampaignState.currentSchema + " before encoding: " + state.schema);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output));
        out.writeInt(magic);
        out.writeInt(formatVersion);
        writeState(out, state, strategic);
        out.flush();
    }

    /** Standalone ActionState envelope used by platform runtime bridges (for example Android :sharedhost). */
    public static byte[] encodeAction(ActionState action){
        Objects.requireNonNull(action, "action");
        try{
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(bytes));
            out.writeInt(magic);
            out.writeInt(formatVersion);
            out.writeInt(0x4143544e); // ACTN
            writeAction(out, action, false);
            out.flush();
            return bytes.toByteArray();
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    public static ActionState decodeAction(byte[] data){
        Objects.requireNonNull(data, "data");
        try{
            DataInputStream in = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(data)));
            if(in.readInt() != magic) throw new IOException("Invalid shared-campaign action magic");
            int format = in.readInt();
            if(format < 26 || format > formatVersion) throw new IOException("Unsupported clean shared-campaign action format " + format + "; legacy formats require the legacy reader");
            if(in.readInt() != 0x4143544e) throw new IOException("Invalid shared-campaign action envelope");
            return readAction(in, format);
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    /** Hashes the durable representation without materializing it as a byte array. */
    public static String sha256(SharedCampaignState state){
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try(OutputStream sink = new DigestOutputStream(OutputStream.nullOutputStream(), digest)){
                write(state, sink);
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(IOException | GeneralSecurityException error){
            throw new IllegalStateException(error);
        }
    }

    public static SharedCampaignState decode(byte[] data){
        Objects.requireNonNull(data, "data");
        return decode(new ByteArrayInputStream(data));
    }

    /** Decodes a campaign without first materializing the whole file as one heap byte array. Does not close {@code input}. */
    public static SharedCampaignState decode(InputStream input){
        Objects.requireNonNull(input, "input");
        try{
            DataInputStream in = new DataInputStream(input instanceof BufferedInputStream ? input : new BufferedInputStream(input));
            if(in.readInt() != magic) throw new IOException("Not a Shared Campaign file");
            int format = in.readInt();
            if(format < 26 || format > formatVersion) throw new IOException("Unsupported clean shared campaign container version: " + format + "; legacy formats require the legacy reader");
            SharedCampaignState state = readState(in, format);
            if(in.read() != -1) throw new IOException("Trailing data in shared campaign file");
            state.validate();
            return state;
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    public static SharedCampaignState copy(SharedCampaignState state){
        return SharedCampaignStateCopy.copy(state);
    }

    /** Deep-copies a sector without exposing the mutable state stored by the campaign authority. */
    public static SectorState copySector(SectorState sector){
        Objects.requireNonNull(sector, "sector");
        return SharedCampaignStateCopy.copySector(sector);
    }

    /** Deep-copies an action without exposing the mutable state stored by the campaign authority. */
    public static ActionState copyAction(ActionState action){
        Objects.requireNonNull(action, "action");
        return SharedCampaignStateCopy.copyAction(action);
    }

    private static <T> T roundTrip(T value, IoWriter<T> writer, IoReader<T> reader){
        try{
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try(DataOutputStream out = new DataOutputStream(bytes)){
                writer.write(out, value);
            }
            try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))){
                T copy = reader.read(in);
                if(in.read() != -1) throw new IOException("Trailing data in shared campaign object copy");
                return copy;
            }
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    @FunctionalInterface
    private interface IoWriter<T>{
        void write(DataOutput output, T value) throws IOException;
    }

    @FunctionalInterface
    private interface IoReader<T>{
        T read(DataInput input) throws IOException;
    }

    public static String sha256(byte[] data){
        try{
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for(byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        }catch(NoSuchAlgorithmException e){
            throw new AssertionError(e);
        }
    }

    private static void writeState(DataOutput out, SharedCampaignState s) throws IOException{ writeState(out, s, false); }

    private static void writeState(DataOutput out, SharedCampaignState s, boolean strategic) throws IOException{
        out.writeInt(s.schema);
        writeString(out, s.campaignId);
        writeString(out, s.displayName);
        writeString(out, s.ownerId);
        out.writeLong(s.revision);
        out.writeLong(s.createdAt);
        out.writeLong(s.updatedAt);
        out.writeInt(s.maxActiveActions);
        out.writeBoolean(s.freezeWhenEmpty);
        writeEnum(out, s.persistenceProfile);
        writeString(out, s.primaryPlanetName);
        out.writeBoolean(s.multiFrontEnabled);
        writeEnum(out, s.invitePolicy);
        writeEnum(out, s.origin);
        writeString(out, s.originDescription);
        out.writeLong(s.campaignTick);
        out.writeLong(s.settlementRemainderTicks);
        writeString(out, s.contentFingerprint);
        writeString(out, s.contentCompatibilityFingerprint);
        out.writeLong(s.authorityGeneration);
        writeString(out, s.authorityHostId);
        out.writeBoolean(s.migrationPending);
        writeString(out, s.migrationTargetHostId);
        writeString(out, s.migrationNonce);
        out.writeLong(s.migrationExpiresAt);

        writeMap(out, s.members, SharedCampaignCodec::writeString, SharedCampaignCodec::writeMember);
        writeMap(out, s.planetPolicies, SharedCampaignCodec::writeString, SharedCampaignCodec::writePlanetPolicy);
        writeMap(out, s.actions, SharedCampaignCodec::writeString, (output, value) -> writeAction(output, value, strategic));
        writeMap(out, s.sectors, SharedCampaignCodec::writeString, (output, value) -> writeSector(output, value, strategic));
        writeMap(out, s.missions, SharedCampaignCodec::writeString, SharedCampaignCodec::writeMission);
        writeSet(out, s.researched, SharedCampaignCodec::writeString);
        writeSet(out, s.discovered, SharedCampaignCodec::writeString);
        writeSet(out, s.producedActual, SharedCampaignCodec::writeString);
        writeMap(out, s.research, SharedCampaignCodec::writeString, SharedCampaignCodec::writeResearch);
        writeMap(out, s.researchTransactions, SharedCampaignCodec::writeString, SharedCampaignCodec::writeResearchTransaction);
        writeMap(out, s.transportTransactions, SharedCampaignCodec::writeString, SharedCampaignCodec::writeTransportTransaction);
        writeMap(out, s.launchTransactions, SharedCampaignCodec::writeString, SharedCampaignCodec::writeLaunchTransaction);
        writeMap(out, s.extensionSchemas, SharedCampaignCodec::writeString, DataOutput::writeInt);
        writeMap(out, s.extensionData, SharedCampaignCodec::writeString, SharedCampaignCodec::writeBytes);
        writeMap(out, s.extensionRequired, SharedCampaignCodec::writeString, DataOutput::writeBoolean);
        writeMap(out, s.extensionCompatibility, SharedCampaignCodec::writeString, SharedCampaignCodec::writeString);
        writeSeq(out, s.transports, SharedCampaignCodec::writeTransport);
        writeSeq(out, s.recentEvents, SharedCampaignCodec::writeCampaignEvent);
        writeSeq(out, s.backups, SharedCampaignCodec::writeBackup);
        writeMap(out, s.controlRequestReceipts, SharedCampaignCodec::writeString, SharedCampaignCodec::writeControlRequestReceipt);
    }

    private static SharedCampaignState readState(DataInput in, int format) throws IOException{
        SharedCampaignState s = new SharedCampaignState();
        s.schema = in.readInt();
        if(s.schema < 26 || s.schema > SharedCampaignState.currentSchema) throw new IOException("Clean container carries unexpected state schema: " + s.schema);
        s.campaignId = readString(in);
        s.displayName = readString(in);
        s.ownerId = readString(in);
        s.revision = in.readLong();
        s.createdAt = in.readLong();
        s.updatedAt = in.readLong();
        s.maxActiveActions = in.readInt();
        s.freezeWhenEmpty = in.readBoolean();
        s.persistenceProfile = format >= 27 ? readEnum(in, PersistenceProfile.class) : PersistenceProfile.highFrequencyWal;
        s.primaryPlanetName = readString(in);
        s.multiFrontEnabled = in.readBoolean();
        s.invitePolicy = readEnum(in, InvitePolicy.class);
        s.origin = readEnum(in, CampaignOrigin.class);
        s.originDescription = readString(in);
        s.campaignTick = in.readLong();
        s.settlementRemainderTicks = in.readLong();
        s.contentFingerprint = readString(in);
        s.contentCompatibilityFingerprint = readString(in);
        s.authorityGeneration = in.readLong();
        s.authorityHostId = readString(in);
        s.migrationPending = in.readBoolean();
        s.migrationTargetHostId = readString(in);
        s.migrationNonce = readString(in);
        s.migrationExpiresAt = in.readLong();

        s.members = readMap(in, SharedCampaignCodec::readString, input -> readMember(input, format));
        s.planetPolicies = readMap(in, SharedCampaignCodec::readString, input -> readPlanetPolicy(input, format));
        s.actions = readMap(in, SharedCampaignCodec::readString, input -> readAction(input, format));
        s.sectors = readMap(in, SharedCampaignCodec::readString, input -> readSector(input, format));
        s.missions = readMap(in, SharedCampaignCodec::readString, input -> readMission(input, format));
        s.researched = readSet(in, SharedCampaignCodec::readString);
        s.discovered = readSet(in, SharedCampaignCodec::readString);
        s.producedActual = readSet(in, SharedCampaignCodec::readString);
        s.research = readMap(in, SharedCampaignCodec::readString, SharedCampaignCodec::readResearch);
        s.researchTransactions = readMap(in, SharedCampaignCodec::readString, SharedCampaignCodec::readResearchTransaction);
        s.transportTransactions = readMap(in, SharedCampaignCodec::readString, SharedCampaignCodec::readTransportTransaction);
        s.launchTransactions = readMap(in, SharedCampaignCodec::readString, SharedCampaignCodec::readLaunchTransaction);
        s.extensionSchemas = readMap(in, SharedCampaignCodec::readString, DataInput::readInt);
        s.extensionData = readMap(in, SharedCampaignCodec::readString, SharedCampaignCodec::readBytes);
        s.extensionRequired = readMap(in, SharedCampaignCodec::readString, DataInput::readBoolean);
        s.extensionCompatibility = readMap(in, SharedCampaignCodec::readString, SharedCampaignCodec::readString);
        s.transports = readSeq(in, input -> readTransport(input, format));
        s.recentEvents = readSeq(in, SharedCampaignCodec::readCampaignEvent);
        s.backups = readSeq(in, SharedCampaignCodec::readBackup);
        s.controlRequestReceipts = readMap(in, SharedCampaignCodec::readString, input -> readControlRequestReceipt(input, format));
        return s;
    }

    private static void writeControlRequestReceipt(DataOutput out, ControlRequestReceipt value) throws IOException{
        writeString(out, value.memberId); out.writeLong(value.requestId); writeString(out, value.requestType);
        writeString(out, value.payloadHash); writeString(out, value.responseType); writeString(out, value.resultReference); out.writeLong(value.completedRevision);
        out.writeLong(value.completedAt);
    }

    private static ControlRequestReceipt readControlRequestReceipt(DataInput in, int format) throws IOException{
        ControlRequestReceipt value = new ControlRequestReceipt(); value.memberId = readString(in); value.requestId = in.readLong();
        value.requestType = readString(in); value.payloadHash = readString(in); value.responseType = readString(in);
        if(format >= 18) value.resultReference = readString(in);
        value.completedRevision = in.readLong(); value.completedAt = in.readLong(); return value;
    }

    private static void writePlanetPolicy(DataOutput out, PlanetPolicyState value) throws IOException{
        writeString(out, value.planetName); writeString(out, value.policyId); out.writeInt(value.policyVersion);
        writeString(out, value.compatibilityId); writeString(out, value.mode); out.writeInt(value.recommendedActiveActions);
        writeString(out, value.researchSharing); writeString(out, value.resourceOwnership);
        writeString(out, value.failureRule); writeString(out, value.invasionRule);
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

    private static void writeBackup(DataOutput out, BackupState value) throws IOException{
        writeString(out, value.backupId); writeString(out, value.displayName); writeString(out, value.reason);
        writeString(out, value.relativePath); writeString(out, value.sha256); out.writeLong(value.createdAt);
        out.writeLong(value.campaignRevision); out.writeLong(value.sizeBytes); out.writeBoolean(value.complete);
    }

    private static BackupState readBackup(DataInput in) throws IOException{
        BackupState value = new BackupState(); value.backupId = readString(in); value.displayName = readString(in);
        value.reason = readString(in); value.relativePath = readString(in); value.sha256 = readString(in);
        value.createdAt = in.readLong(); value.campaignRevision = in.readLong(); value.sizeBytes = in.readLong();
        value.complete = in.readBoolean(); return value;
    }

    private static void writeMember(DataOutput out, MemberState m) throws IOException{
        writeString(out, m.memberId); writeString(out, m.displayName); writeString(out, m.lastActionId);
        out.writeLong(m.lastSeenAt); out.writeLong(m.lastConnectedAt); out.writeLong(m.lastDisconnectedAt);
        writeSet(out, m.tutorialSteps, SharedCampaignCodec::writeString);
    }

    private static MemberState readMember(DataInput in, int format) throws IOException{
        MemberState m = new MemberState();
        m.memberId = readString(in); m.displayName = readString(in); m.lastActionId = readString(in);
        m.lastSeenAt = in.readLong();
        if(format >= 11){ m.lastConnectedAt = in.readLong(); m.lastDisconnectedAt = in.readLong(); }
        m.tutorialSteps = readSet(in, SharedCampaignCodec::readString); return m;
    }

    private static void writeAction(DataOutput out, ActionState a) throws IOException{ writeAction(out, a, false); }

    private static void writeAction(DataOutput out, ActionState a, boolean strategic) throws IOException{
        writeString(out, a.actionId); writeString(out, a.planetName); writeString(out, a.sectorName); writeString(out, a.missionId); writeString(out, a.attemptId);
        writeEnum(out, a.status); writeEnum(out, a.kind); writeString(out, a.objectiveSummary);
        writeMap(out, a.resourceNeeds, SharedCampaignCodec::writeString, DataOutput::writeInt); out.writeBoolean(a.spectatorsAllowed);
        writeString(out, a.hostId); out.writeLong(a.hostGeneration); out.writeLong(a.runtimeIncarnation); out.writeLong(a.leaseExpiresAt);
        writeString(out, a.bindAddress); out.writeInt(a.port); writeString(out, a.joinSecretHash); writeString(out, a.saveRelativePath);
        writeString(out, a.launchOriginSector); writeString(out, a.launchLoadout);
        writeMap(out, a.launchResources, SharedCampaignCodec::writeString, DataOutput::writeInt);
        writeMap(out, a.launchCosts, SharedCampaignCodec::writeString, DataOutput::writeInt); out.writeBoolean(a.launchCommitted);
        out.writeLong(a.actionTick); out.writeLong(a.createdAt); out.writeLong(a.startedAt); out.writeLong(a.updatedAt);
        writeSet(out, a.participants, SharedCampaignCodec::writeString); writeSet(out, a.spectators, SharedCampaignCodec::writeString);
        writeSummary(out, a.summary, strategic); writeString(out, a.failureReason);
        out.writeInt(a.connectedPlayers); out.writeInt(a.connectedSpectators); writeString(out, a.lastSaveHash);
    }

    private static ActionState readAction(DataInput in, int format) throws IOException{
        ActionState a = new ActionState();
        a.actionId = readString(in); if(format >= 9) a.planetName = readString(in); a.sectorName = readString(in); a.missionId = readString(in); a.attemptId = readString(in);
        a.status = readEnum(in, ActionStatus.class);
        if(format >= 11){
            a.kind = readEnum(in, ActionKind.class); a.objectiveSummary = readString(in);
            a.resourceNeeds = readMap(in, SharedCampaignCodec::readString, DataInput::readInt); a.spectatorsAllowed = in.readBoolean();
        }
        a.hostId = readString(in); a.hostGeneration = in.readLong(); a.runtimeIncarnation = format >= 24 ? in.readLong() : 1L; a.leaseExpiresAt = in.readLong();
        a.bindAddress = readString(in); a.port = in.readInt(); a.joinSecretHash = readString(in); a.saveRelativePath = readString(in);
        if(format >= 10){
            a.launchOriginSector = readString(in); a.launchLoadout = readString(in);
            a.launchResources = readMap(in, SharedCampaignCodec::readString, DataInput::readInt);
            a.launchCosts = readMap(in, SharedCampaignCodec::readString, DataInput::readInt); a.launchCommitted = in.readBoolean();
        }
        a.actionTick = in.readLong(); a.createdAt = in.readLong(); if(format >= 11) a.startedAt = in.readLong(); a.updatedAt = in.readLong();
        a.participants = readSet(in, SharedCampaignCodec::readString);
        if(format >= 12) a.spectators = readSet(in, SharedCampaignCodec::readString);
        a.summary = readSummary(in, format).strategicCopy(); a.failureReason = readString(in);
        if(format >= 2){
            a.connectedPlayers = in.readInt();
            if(format >= 12) a.connectedSpectators = in.readInt();
            a.lastSaveHash = readString(in);
        }
        return a;
    }

    private static void writeSector(DataOutput out, SectorState s) throws IOException{ writeSector(out, s, false); }

    private static void writeSector(DataOutput out, SectorState s, boolean strategic) throws IOException{
        writeString(out, s.sectorName); writeString(out, s.planetName); writeString(out, s.destinationSector); out.writeBoolean(s.legacyLaunchPads);
        out.writeBoolean(s.hasBase); out.writeBoolean(s.captured); out.writeBoolean(s.attacked); out.writeBoolean(s.hasSpawns);
        out.writeBoolean(s.discovered); out.writeBoolean(s.expeditionAvailable); out.writeBoolean(s.waitingSettlement); writeString(out, s.logisticsWarning);
        out.writeFloat(s.minutesCaptured);
        out.writeLong(s.lastSavedAt); out.writeLong(s.lastOpenedAt); out.writeLong(s.lastSummaryTick); writeString(out, s.saveRelativePath); writeSummary(out, s.summary, strategic);
        writeMap(out, s.items, SharedCampaignCodec::writeString, DataOutput::writeInt);
        writeMap(out, s.productionPerSecond, SharedCampaignCodec::writeString, DataOutput::writeFloat);
        writeMap(out, s.exportPerSecond, SharedCampaignCodec::writeString, DataOutput::writeFloat);
        writeMap(out, s.importPerSecond, SharedCampaignCodec::writeString, DataOutput::writeFloat);
        writeMap(out, s.lastImportedItems, SharedCampaignCodec::writeString, DataOutput::writeInt);
        writeString(out, s.displayName); writeString(out, s.icon); writeString(out, s.contentIcon);
        writeSet(out, s.resources, SharedCampaignCodec::writeString); out.writeBoolean(s.hasEnemyBase); out.writeFloat(s.threat);
    }

    private static SectorState readSector(DataInput in, int format) throws IOException{
        SectorState s = new SectorState();
        s.sectorName = readString(in); s.planetName = readString(in); s.destinationSector = readString(in); s.legacyLaunchPads = in.readBoolean();
        s.hasBase = in.readBoolean(); s.captured = in.readBoolean(); s.attacked = in.readBoolean(); s.hasSpawns = in.readBoolean();
        s.discovered = in.readBoolean(); s.expeditionAvailable = in.readBoolean(); s.waitingSettlement = in.readBoolean(); s.logisticsWarning = readString(in);
        s.minutesCaptured = in.readFloat();
        s.lastSavedAt = in.readLong(); s.lastOpenedAt = in.readLong(); s.lastSummaryTick = in.readLong(); s.saveRelativePath = readString(in); s.summary = readSummary(in, format).strategicCopy();
        s.items = readMap(in, SharedCampaignCodec::readString, DataInput::readInt);
        s.productionPerSecond = readMap(in, SharedCampaignCodec::readString, DataInput::readFloat);
        s.exportPerSecond = readMap(in, SharedCampaignCodec::readString, DataInput::readFloat);
        s.importPerSecond = readMap(in, SharedCampaignCodec::readString, DataInput::readFloat);
        s.lastImportedItems = readMap(in, SharedCampaignCodec::readString, DataInput::readInt);
        s.displayName = readString(in); s.icon = readString(in); s.contentIcon = readString(in);
        s.resources = readSet(in, SharedCampaignCodec::readString); s.hasEnemyBase = in.readBoolean(); s.threat = in.readFloat();
        return s;
    }

    private static void writeSummary(DataOutput out, SectorSummary s) throws IOException{ writeSummary(out, s, false); }

    private static void writeSummary(DataOutput out, SectorSummary s, boolean strategic) throws IOException{
        out.writeInt(s.wave); out.writeInt(s.winWave); out.writeBoolean(s.waves); out.writeBoolean(s.attackMode);
        out.writeInt(s.coreCount); out.writeInt(s.unitCount); out.writeInt(s.enemyCount); out.writeInt(s.storageCapacity); writeString(out, s.coreType);
        out.writeFloat(s.powerProduced); out.writeFloat(s.powerConsumed); out.writeFloat(s.defenseScore);
        out.writeLong(s.capturedAtTick); out.writeLong(s.sampledAtTick); writeString(out, s.phase);
        writeString(out, s.planetName); writeString(out, s.destinationSector); out.writeBoolean(s.legacyLaunchPads);
        out.writeBoolean(s.attacked); out.writeBoolean(s.hasSpawns); out.writeFloat(s.minutesCaptured);
        writeMap(out, s.items, SharedCampaignCodec::writeString, DataOutput::writeInt);
        writeMap(out, s.productionPerSecond, SharedCampaignCodec::writeString, DataOutput::writeFloat);
        writeMap(out, s.exportPerSecond, SharedCampaignCodec::writeString, DataOutput::writeFloat);
        writeMap(out, s.importPerSecond, SharedCampaignCodec::writeString, DataOutput::writeFloat);
        writeString(out, s.displayName); writeString(out, s.icon); writeString(out, s.contentIcon);
        writeSet(out, s.resources, SharedCampaignCodec::writeString); out.writeBoolean(s.hasEnemyBase); out.writeFloat(s.threat);
    }

    private static SectorSummary readSummary(DataInput in, int format) throws IOException{
        SectorSummary s = new SectorSummary(); s.wave = in.readInt(); s.winWave = in.readInt(); s.waves = in.readBoolean(); s.attackMode = in.readBoolean();
        s.coreCount = in.readInt(); s.unitCount = in.readInt(); s.enemyCount = in.readInt(); s.storageCapacity = in.readInt(); s.coreType = readString(in);
        s.powerProduced = in.readFloat(); s.powerConsumed = in.readFloat(); s.defenseScore = in.readFloat();
        s.capturedAtTick = in.readLong(); s.sampledAtTick = in.readLong(); s.phase = readString(in);
        s.planetName = readString(in); s.destinationSector = readString(in); s.legacyLaunchPads = in.readBoolean();
        s.attacked = in.readBoolean(); s.hasSpawns = in.readBoolean(); s.minutesCaptured = in.readFloat();
        s.items = readMap(in, SharedCampaignCodec::readString, DataInput::readInt);
        s.productionPerSecond = readMap(in, SharedCampaignCodec::readString, DataInput::readFloat);
        s.exportPerSecond = readMap(in, SharedCampaignCodec::readString, DataInput::readFloat);
        s.importPerSecond = readMap(in, SharedCampaignCodec::readString, DataInput::readFloat);
        s.displayName = readString(in); s.icon = readString(in); s.contentIcon = readString(in);
        s.resources = readSet(in, SharedCampaignCodec::readString); s.hasEnemyBase = in.readBoolean(); s.threat = in.readFloat();
        return s;
    }

    private static void writeMission(DataOutput out, MissionState m) throws IOException{
        writeString(out, m.missionId); writeString(out, m.planetName); writeString(out, m.sectorName); writeString(out, m.definitionVersion); writeString(out, m.definitionFingerprint); writeString(out, m.attemptId);
        writeEnum(out, m.status); writeString(out, m.phase); out.writeLong(m.actionTick); out.writeLong(m.eventSequence);
        writeMap(out, m.objectives, SharedCampaignCodec::writeString, SharedCampaignCodec::writeObjective);
        writeSet(out, m.signals, SharedCampaignCodec::writeString); writeSet(out, m.completedWorldEvents, SharedCampaignCodec::writeString);
        writeSeq(out, m.eventJournal, SharedCampaignCodec::writeMissionEvent);
    }

    private static MissionState readMission(DataInput in, int format) throws IOException{
        MissionState m = new MissionState(); m.missionId = readString(in); if(format >= 9) m.planetName = readString(in); m.sectorName = readString(in); m.definitionVersion = readString(in); if(format >= 8) m.definitionFingerprint = readString(in); m.attemptId = readString(in);
        m.status = readEnum(in, MissionStatus.class); m.phase = readString(in); m.actionTick = in.readLong(); m.eventSequence = in.readLong();
        m.objectives = readMap(in, SharedCampaignCodec::readString, SharedCampaignCodec::readObjective);
        m.signals = readSet(in, SharedCampaignCodec::readString); m.completedWorldEvents = readSet(in, SharedCampaignCodec::readString);
        m.eventJournal = readSeq(in, SharedCampaignCodec::readMissionEvent); return m;
    }

    private static void writeObjective(DataOutput out, ObjectiveState o) throws IOException{
        writeString(out, o.objectiveId); writeEnum(out, o.status); out.writeLong(o.progress); out.writeLong(o.requiredProgress);
        out.writeLong(o.activatedAtTick); out.writeLong(o.completedAtTick); out.writeLong(o.completionEventSequence);
    }

    private static ObjectiveState readObjective(DataInput in) throws IOException{
        ObjectiveState o = new ObjectiveState(); o.objectiveId = readString(in); o.status = readEnum(in, ObjectiveStatus.class);
        o.progress = in.readLong(); o.requiredProgress = in.readLong(); o.activatedAtTick = in.readLong();
        o.completedAtTick = in.readLong(); o.completionEventSequence = in.readLong(); return o;
    }

    private static void writeMissionEvent(DataOutput out, MissionEvent e) throws IOException{
        out.writeLong(e.sequence); writeString(out, e.eventId); writeString(out, e.type); out.writeLong(e.actionTick); writeString(out, e.payload);
    }

    private static MissionEvent readMissionEvent(DataInput in) throws IOException{
        MissionEvent e = new MissionEvent(); e.sequence = in.readLong(); e.eventId = readString(in); e.type = readString(in); e.actionTick = in.readLong(); e.payload = readString(in); return e;
    }

    private static void writeResearch(DataOutput out, ResearchState value) throws IOException{
        writeString(out, value.contentName);
        writeMap(out, value.required, SharedCampaignCodec::writeString, DataOutput::writeInt);
        writeMap(out, value.contributed, SharedCampaignCodec::writeString, DataOutput::writeInt);
        out.writeLong(value.startedAtRevision);
        out.writeLong(value.completedAtRevision);
    }

    private static ResearchState readResearch(DataInput in) throws IOException{
        ResearchState value = new ResearchState();
        value.contentName = readString(in);
        value.required = readMap(in, SharedCampaignCodec::readString, DataInput::readInt);
        value.contributed = readMap(in, SharedCampaignCodec::readString, DataInput::readInt);
        value.startedAtRevision = in.readLong();
        value.completedAtRevision = in.readLong();
        return value;
    }


    private static void writeResearchTransaction(DataOutput out, ResearchTransaction value) throws IOException{
        writeString(out, value.transactionId);
        writeString(out, value.actorId);
        writeString(out, value.contentName);
        writeEnum(out, value.status);
        out.writeLong(value.createdAt);
        out.writeLong(value.updatedAt);
        writeMap(out, value.debits, SharedCampaignCodec::writeString, SharedCampaignCodec::writeResearchDebit);
        writeSet(out, value.preparedActions, SharedCampaignCodec::writeString);
        writeString(out, value.failureReason);
    }

    private static ResearchTransaction readResearchTransaction(DataInput in) throws IOException{
        ResearchTransaction value = new ResearchTransaction();
        value.transactionId = readString(in);
        value.actorId = readString(in);
        value.contentName = readString(in);
        value.status = readEnum(in, ResearchTransactionStatus.class);
        value.createdAt = in.readLong();
        value.updatedAt = in.readLong();
        value.debits = readMap(in, SharedCampaignCodec::readString, SharedCampaignCodec::readResearchDebit);
        value.preparedActions = readSet(in, SharedCampaignCodec::readString);
        value.failureReason = readString(in);
        return value;
    }

    private static void writeResearchDebit(DataOutput out, ResearchDebit value) throws IOException{
        writeString(out, value.sectorName);
        writeString(out, value.actionId);
        writeMap(out, value.items, SharedCampaignCodec::writeString, DataOutput::writeInt);
    }

    private static ResearchDebit readResearchDebit(DataInput in) throws IOException{
        ResearchDebit value = new ResearchDebit();
        value.sectorName = readString(in);
        value.actionId = readString(in);
        value.items = readMap(in, SharedCampaignCodec::readString, DataInput::readInt);
        return value;
    }

    private static void writeLaunchTransaction(DataOutput out, LaunchTransaction value) throws IOException{
        writeString(out, value.transactionId); writeString(out, value.actionId); writeString(out, value.actorId);
        writeString(out, value.originSector); writeString(out, value.sourceActionId);
        writeMap(out, value.costs, SharedCampaignCodec::writeString, DataOutput::writeInt); writeEnum(out, value.status);
        out.writeLong(value.createdAt); out.writeLong(value.updatedAt); writeString(out, value.failureReason);
    }

    private static LaunchTransaction readLaunchTransaction(DataInput in) throws IOException{
        LaunchTransaction value = new LaunchTransaction();
        value.transactionId = readString(in); value.actionId = readString(in); value.actorId = readString(in);
        value.originSector = readString(in); value.sourceActionId = readString(in);
        value.costs = readMap(in, SharedCampaignCodec::readString, DataInput::readInt); value.status = readEnum(in, LaunchTransactionStatus.class);
        value.createdAt = in.readLong(); value.updatedAt = in.readLong(); value.failureReason = readString(in); return value;
    }





    private static void writeTransportTransaction(DataOutput out, TransportTransaction value) throws IOException{
        writeString(out, value.transactionId); writeString(out, value.orderId); writeString(out, value.actionId); writeString(out, value.itemName);
        out.writeInt(value.requested); out.writeInt(value.applied); writeEnum(out, value.status); out.writeLong(value.createdAt); out.writeLong(value.updatedAt); writeString(out, value.failureReason);
    }

    private static TransportTransaction readTransportTransaction(DataInput in) throws IOException{
        TransportTransaction value = new TransportTransaction();
        value.transactionId = readString(in); value.orderId = readString(in); value.actionId = readString(in); value.itemName = readString(in);
        value.requested = in.readInt(); value.applied = in.readInt(); value.status = readEnum(in, TransportTransactionStatus.class);
        value.createdAt = in.readLong(); value.updatedAt = in.readLong(); value.failureReason = readString(in);
        return value;
    }

    private static void writeTransport(DataOutput out, TransportOrder t) throws IOException{
        writeString(out, t.orderId); writeString(out, t.sourceSector); writeString(out, t.destinationSector); writeString(out, t.itemName);
        out.writeInt(t.amount); out.writeInt(t.loaded); out.writeInt(t.delivered); out.writeLong(t.createdAt); out.writeLong(t.etaCampaignTick); writeEnum(out, t.status);
    }

    private static TransportOrder readTransport(DataInput in, int format) throws IOException{
        TransportOrder t = new TransportOrder(); t.orderId = readString(in); t.sourceSector = readString(in); t.destinationSector = readString(in); t.itemName = readString(in);
        t.amount = in.readInt(); if(format >= 2) t.loaded = in.readInt(); t.delivered = in.readInt();
        t.createdAt = in.readLong(); t.etaCampaignTick = in.readLong(); t.status = readEnum(in, TransportStatus.class);
        if(format < 2) t.loaded = t.status == TransportStatus.queued ? 0 : t.amount;
        return t;
    }

    private static void writeCampaignEvent(DataOutput out, CampaignEvent e) throws IOException{
        out.writeLong(e.sequence); out.writeLong(e.timestamp); writeString(out, e.type); writeString(out, e.subjectId); writeString(out, e.payload);
    }

    private static CampaignEvent readCampaignEvent(DataInput in) throws IOException{
        CampaignEvent e = new CampaignEvent(); e.sequence = in.readLong(); e.timestamp = in.readLong(); e.type = readString(in); e.subjectId = readString(in); e.payload = readString(in); return e;
    }

    private static void writeString(DataOutput out, String value) throws IOException{
        byte[] data = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if(data.length > maxStringBytes) throw new IOException("String too large: " + data.length);
        out.writeInt(data.length); out.write(data);
    }

    private static String readString(DataInput in) throws IOException{
        int length = checkedSize(in.readInt(), maxStringBytes, "string");
        byte[] data = new byte[length]; in.readFully(data); return new String(data, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutput out, byte[] value) throws IOException{
        byte[] data = value == null ? new byte[0] : value;
        if(data.length > maxStringBytes) throw new IOException("Byte vector too large: " + data.length);
        out.writeInt(data.length); out.write(data);
    }

    private static byte[] readBytes(DataInput in) throws IOException{
        int length = checkedSize(in.readInt(), maxStringBytes, "byte vector");
        byte[] data = new byte[length]; in.readFully(data); return data;
    }

    private static <E extends Enum<E>> void writeEnum(DataOutput out, E value) throws IOException{
        out.writeInt(value.ordinal());
    }

    private static <E extends Enum<E>> E readEnum(DataInput in, Class<E> type) throws IOException{
        E[] values = type.getEnumConstants(); int ordinal = in.readInt();
        if(ordinal < 0 || ordinal >= values.length) throw new IOException("Invalid " + type.getSimpleName() + " ordinal: " + ordinal);
        return values[ordinal];
    }

    private static <T> void writeSeq(DataOutput out, Seq<T> seq, IoWriter<T> writer) throws IOException{
        out.writeInt(seq.size); for(T value : seq) writer.write(out, value);
    }

    private static <T> Seq<T> readSeq(DataInput in, IoReader<T> reader) throws IOException{
        int size = checkedSize(in.readInt(), maxCollectionSize, "sequence"); Seq<T> seq = new Seq<>(size);
        for(int i = 0; i < size; i++) seq.add(reader.read(in)); return seq;
    }

    private static <T> void writeSet(DataOutput out, ObjectSet<T> set, IoWriter<T> writer) throws IOException{
        Seq<T> values = set.toSeq(); values.sort(Comparator.comparing(String::valueOf));
        out.writeInt(values.size); for(T value : values) writer.write(out, value);
    }

    private static <T> ObjectSet<T> readSet(DataInput in, IoReader<T> reader) throws IOException{
        int size = checkedSize(in.readInt(), maxCollectionSize, "set"); ObjectSet<T> set = new ObjectSet<>(size);
        for(int i = 0; i < size; i++) set.add(reader.read(in)); return set;
    }

    private static <K, V> void writeMap(DataOutput out, ObjectMap<K, V> map, IoWriter<K> keyWriter, IoWriter<V> valueWriter) throws IOException{
        // ObjectMap's entry iterator reuses one mutable Entry instance. Never retain those Entry references for
        // sorting: doing so aliases every collected element to the final entry and silently collapses multi-key maps.
        Seq<K> keys = new Seq<>(map.size);
        for(K key : map.keys()) keys.add(key);
        keys.sort(Comparator.comparing(String::valueOf));
        out.writeInt(keys.size);
        for(K key : keys){
            keyWriter.write(out, key);
            valueWriter.write(out, map.get(key));
        }
    }

    private static <K, V> ObjectMap<K, V> readMap(DataInput in, IoReader<K> keyReader, IoReader<V> valueReader) throws IOException{
        int size = checkedSize(in.readInt(), maxCollectionSize, "map"); ObjectMap<K, V> map = new ObjectMap<>(size);
        for(int i = 0; i < size; i++) map.put(keyReader.read(in), valueReader.read(in)); return map;
    }

    private static int checkedSize(int value, int max, String kind) throws IOException{
        if(value < 0 || value > max) throw new IOException("Invalid " + kind + " size: " + value); return value;
    }

}
