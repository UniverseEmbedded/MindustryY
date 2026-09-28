package mindustry.campaign.shared.io;

import arc.struct.*;
import arc.util.*;
import mindustry.campaign.shared.SharedCampaignState;
import mindustry.campaign.shared.SharedCampaignState.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

/**
 * Replayable small-diff WAL for Shared Campaign coalesced traffic (P2).
 *
 * <p>{@code campaign.journal} remains hash-only and must never be used as a state source. This WAL stores only
 * lease/presence/strategic-summary style field updates. Missions, research, transactions, policy/schema changes,
 * or membership key changes are not WAL-eligible and must checkpoint a full snapshot
 * instead.</p>
 */
public final class SharedCampaignWal{
    public static final int magic = 0x4d594357; // MYCW
    public static final int formatVersion = 2;
    private static final int maxRecordBytes = 4 * 1024 * 1024;
    private static final int maxCollectionSize = 100_000;

    private SharedCampaignWal(){}

    /** One complete WAL record: revision edges plus entity patches. */
    public static final class Diff{
        public long fromRevision;
        public long toRevision;
        public long updatedAt;
        public long campaignTick;
        public long settlementRemainderTicks;
        public String displayName = "";
        public boolean displayNameChanged;
        public final ObjectMap<String, ActionState> actions = new ObjectMap<>();
    public final ObjectSet<String> removedActions = new ObjectSet<>();
        public final ObjectMap<String, MemberState> members = new ObjectMap<>();
        public final ObjectMap<String, SectorPatch> sectors = new ObjectMap<>();
        public final ObjectMap<String, MissionState> missions = new ObjectMap<>();
        public final ObjectSet<String> discoveredAdded = new ObjectSet<>();
    }

    /** Light clean-schema sector projection that is safe to replay independently of a full checkpoint. */
    public static final class SectorPatch{
        public String planetName = "";
        public String sectorName = "";
        public String destinationSector = "";
        public boolean legacyLaunchPads;
        public boolean hasBase;
        public boolean captured;
        public boolean attacked;
        public boolean hasSpawns;
        public boolean discovered;
        public boolean expeditionAvailable;
        public boolean waitingSettlement;
        public boolean hasEnemyBase;
        public float threat;
        public float minutesCaptured;
        public long lastSavedAt;
        public long lastOpenedAt;
        public long lastSummaryTick;
        public String logisticsWarning = "";
        public String saveRelativePath = "";
        public String displayName = "";
        public String icon = "";
        public String contentIcon = "";
        public SectorSummary summary = new SectorSummary();
        public final ObjectMap<String, Integer> items = new ObjectMap<>();
        public final ObjectMap<String, Float> productionPerSecond = new ObjectMap<>();
        public final ObjectMap<String, Float> exportPerSecond = new ObjectMap<>();
        public final ObjectMap<String, Float> importPerSecond = new ObjectMap<>();
        public final ObjectMap<String, Integer> lastImportedItems = new ObjectMap<>();
        public final ObjectSet<String> resources = new ObjectSet<>();
    }

    /**
     * Builds a WAL-eligible diff, or returns {@code null} when the mutation must become a full checkpoint.
     * Eligibility is fail-closed: the candidate is re-applied onto a copy of {@code before} and every soft field
     * must match {@code after}. Unmodeled / incomparable changes force a checkpoint instead of silent loss.
     * Callers hold the store write lock; {@code before} is the authoritative state prior to the coalesced mutation.
     */
    public static Diff tryDiff(SharedCampaignState before, SharedCampaignState after){
        if(before == null || after == null) return null;
        if(after.revision != before.revision + 1L) return null;
        if(!hardRegionsUnchanged(before, after)) return null;

        // Missions / research progress / transactions are checkpoint-only surfaces.
        if(!missionsEqual(before, after)) return null;
        if(!researchedUnchanged(before, after)) return null;
        if(!researchContentsEqual(before, after)) return null;
        if(before.researchTransactions.size != after.researchTransactions.size) return null;
        if(before.transportTransactions.size != after.transportTransactions.size) return null;
        if(before.controlRequestReceipts.size != after.controlRequestReceipts.size) return null;
        if(before.backups.size != after.backups.size) return null;
        if(before.planetPolicies.size != after.planetPolicies.size) return null;
        if(before.launchTransactions.size != after.launchTransactions.size) return null;
        if(before.producedActual.size != after.producedActual.size) return null;
        if(before.transports.size != after.transports.size) return null;
        if(before.extensionSchemas.size != after.extensionSchemas.size) return null;
        if(before.extensionData.size != after.extensionData.size) return null;
        if(before.extensionRequired.size != after.extensionRequired.size) return null;
        if(before.extensionCompatibility.size != after.extensionCompatibility.size) return null;
        if(before.members.size != after.members.size) return null;
        if(before.actions.size != after.actions.size) return null;
        if(!objectKeySetsEqual(before.members, after.members)) return null;
        if(!objectKeySetsEqual(before.actions, after.actions)) return null;
        if(!objectKeySetsEqual(before.sectors, after.sectors)) return null;

        Diff diff = new Diff();
        diff.fromRevision = before.revision;
        diff.toRevision = after.revision;
        diff.updatedAt = after.updatedAt;
        diff.campaignTick = after.campaignTick;
        diff.settlementRemainderTicks = after.settlementRemainderTicks;
        diff.displayName = after.displayName == null ? "" : after.displayName;
        diff.displayNameChanged = !Objects.equals(before.displayName, after.displayName);

        for(String key : after.members.keys()){
            MemberState previous = before.members.get(key);
            MemberState current = after.members.get(key);
            if(previous == null || current == null) return null;
            if(memberChanged(previous, current)) diff.members.put(key, copyMember(current));
        }
        for(String key : after.actions.keys()){
            ActionState previous = before.actions.get(key);
            ActionState current = after.actions.get(key);
            if(previous == null || current == null) return null;
            if(actionChanged(previous, current)) diff.actions.put(key, SharedCampaignStateCopy.copyAction(current));
        }
        for(String key : after.sectors.keys()){
            SectorState previous = before.sectors.get(key);
            SectorState current = after.sectors.get(key);
            if(previous == null || current == null) return null;
            if(sectorLightChanged(previous, current)) diff.sectors.put(key, toPatch(current));
        }
        if(!before.discovered.equals(after.discovered)){
            ObjectSet<String> added = new ObjectSet<>();
            for(String name : after.discovered){
                if(!before.discovered.contains(name)) added.add(name);
            }
            // Removals / rebuilds are not soft.
            if(before.discovered.size + added.size != after.discovered.size) return null;
            diff.discoveredAdded.addAll(added);
        }

        // Fail closed: replay must reproduce every soft difference. Incomplete field coverage becomes a checkpoint.
        SharedCampaignState replay = SharedCampaignStateCopy.copy(before);
        try{
            apply(replay, diff);
        }catch(RuntimeException error){
            return null;
        }
        // Final fail-closed authority check: the clean durable codec covers every persisted schema-26 field.
        // If replay omitted any present or future field, the encoded states differ and this mutation checkpoints.
        if(!Arrays.equals(SharedCampaignCodec.encode(replay), SharedCampaignCodec.encode(after))) return null;
        return diff;
    }

    public static void apply(SharedCampaignState state, Diff diff){
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(diff, "diff");
        if(diff.toRevision != state.revision + 1L){
            throw new IllegalStateException("WAL revision is not continuous: state=" + state.revision + " wal=" + diff.fromRevision + "->" + diff.toRevision);
        }
        if(diff.fromRevision != state.revision){
            throw new IllegalStateException("WAL base revision does not match state: state=" + state.revision + " walFrom=" + diff.fromRevision);
        }
        state.revision = diff.toRevision;
        state.updatedAt = diff.updatedAt;
        state.campaignTick = diff.campaignTick;
        state.settlementRemainderTicks = diff.settlementRemainderTicks;
        if(diff.displayNameChanged) state.displayName = diff.displayName;

        for(String actionId : diff.removedActions) state.actions.remove(actionId);
        for(ObjectMap.Entry<String, ActionState> entry : diff.actions){
            state.actions.put(entry.key, SharedCampaignStateCopy.copyAction(entry.value));
        }
        for(ObjectMap.Entry<String, MemberState> entry : diff.members){
            MemberState existing = state.members.get(entry.key);
            MemberState patched = copyMember(entry.value);
            if(existing == null) state.members.put(entry.key, patched);
            else applyMember(existing, patched);
        }
        for(ObjectMap.Entry<String, SectorPatch> entry : diff.sectors){
            SectorState sector = state.sectors.get(entry.key, SectorState::new);
            applySector(sector, entry.value);
            state.sectors.put(entry.key, sector);
        }
        // Mission payloads are never WAL-eligible; ignore any accidental field content.
        state.discovered.addAll(diff.discoveredAdded);
        state.validate();
    }

    public static byte[] encode(Diff diff){
        Objects.requireNonNull(diff, "diff");
        try{
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(buffer));
            out.writeInt(magic);
            out.writeInt(formatVersion);
            out.writeLong(diff.fromRevision);
            out.writeLong(diff.toRevision);
            out.writeLong(diff.updatedAt);
            out.writeLong(diff.campaignTick);
            out.writeLong(diff.settlementRemainderTicks);
            out.writeBoolean(diff.displayNameChanged);
            writeString(out, diff.displayName);
            writeMapSize(out, diff.actions.size);
            for(ObjectMap.Entry<String, ActionState> entry : diff.actions){
                writeString(out, entry.key);
                writeAction(out, entry.value);
            }
            writeMapSize(out, diff.members.size);
            for(ObjectMap.Entry<String, MemberState> entry : diff.members){
                writeString(out, entry.key);
                writeMember(out, entry.value);
            }
            writeMapSize(out, diff.sectors.size);
            for(ObjectMap.Entry<String, SectorPatch> entry : diff.sectors){
                writeString(out, entry.key);
                writeSectorPatch(out, entry.value);
            }
            writeMapSize(out, diff.missions.size);
            for(ObjectMap.Entry<String, MissionState> entry : diff.missions){
                writeString(out, entry.key);
                // Mission bodies stay out of WAL eligibility; keep the slot for future expansion and fail encode if used.
                throw new IllegalStateException("Mission payloads are not WAL-eligible");
            }
            writeMapSize(out, diff.discoveredAdded.size);
            for(String name : diff.discoveredAdded) writeString(out, name);
            out.flush();
            return buffer.toByteArray();
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    public static Diff decode(byte[] record){
        Objects.requireNonNull(record, "record");
        try{
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(record));
            if(in.readInt() != magic) throw new IOException("Invalid shared campaign WAL magic");
            int format = in.readInt();
            if(format != formatVersion) throw new IOException("Unsupported shared campaign WAL format: " + format);
            Diff diff = new Diff();
            diff.fromRevision = in.readLong();
            diff.toRevision = in.readLong();
            diff.updatedAt = in.readLong();
            diff.campaignTick = in.readLong();
            diff.settlementRemainderTicks = in.readLong();
            diff.displayNameChanged = in.readBoolean();
            diff.displayName = readString(in);
            int actionCount = readMapSize(in);
            for(int i = 0; i < actionCount; i++){
                String key = readString(in);
                diff.actions.put(key, readAction(in));
            }
            int memberCount = readMapSize(in);
            for(int i = 0; i < memberCount; i++){
                String key = readString(in);
                diff.members.put(key, readMember(in));
            }
            int sectorCount = readMapSize(in);
            for(int i = 0; i < sectorCount; i++){
                String key = readString(in);
                diff.sectors.put(key, readSectorPatch(in));
            }
            int missionCount = readMapSize(in);
            for(int i = 0; i < missionCount; i++) throw new IOException("WAL contains forbidden mission payload");
            int discoveredCount = readMapSize(in);
            for(int i = 0; i < discoveredCount; i++) diff.discoveredAdded.add(readString(in));
            return diff;
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    /** Result of scanning a WAL file: complete continuous prefix plus whether a torn tail was discarded. */
    public record ScanResult(java.util.List<Diff> records, boolean truncatedTornTail, long completeBytes){}

    public static ScanResult scan(byte[] fileBytes){
        java.util.List<Diff> records = new java.util.ArrayList<>();
        boolean truncated = false;
        int offset = 0;
        while(offset < fileBytes.length){
            if(offset + 16 > fileBytes.length){ truncated = true; break; }
            int recordMagic = readInt(fileBytes, offset);
            int length = readInt(fileBytes, offset + 4);
            // Frame is magic(4)+length(4)+payload(length)+crc(8) = 16+length.
            if(recordMagic != magic || length < 0 || length > maxRecordBytes || offset + 16L + length > fileBytes.length){
                truncated = true;
                break;
            }
            byte[] payload = Arrays.copyOfRange(fileBytes, offset + 8, offset + 8 + length);
            long expectedCrc = readLong(fileBytes, offset + 8 + length);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if(crc.getValue() != expectedCrc){
                truncated = true;
                break;
            }
            records.add(decode(payload));
            offset += 8 + length + 8;
        }
        return new ScanResult(records, truncated, offset);
    }

    /** Wraps an encoded diff as one framed WAL file record: magic + length + payload + crc32. */
    public static byte[] frame(Diff diff){
        byte[] payload = encode(diff);
        try{
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(payload.length + 16);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(magic);
            out.writeInt(payload.length);
            out.write(payload);
            CRC32 crc = new CRC32();
            crc.update(payload);
            out.writeLong(crc.getValue());
            out.flush();
            return buffer.toByteArray();
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    private static boolean hardRegionsUnchanged(SharedCampaignState before, SharedCampaignState after){
        if(before.origin != after.origin) return false;
        if(before.migrationPending != after.migrationPending) return false;
        if(before.authorityGeneration != after.authorityGeneration) return false;
        if(!Objects.equals(before.authorityHostId, after.authorityHostId)) return false;
        if(!Objects.equals(before.contentFingerprint, after.contentFingerprint)) return false;
        if(!Objects.equals(before.contentCompatibilityFingerprint, after.contentCompatibilityFingerprint)) return false;
        if(before.freezeWhenEmpty != after.freezeWhenEmpty) return false;
        if(before.multiFrontEnabled != after.multiFrontEnabled) return false;
        if(before.maxActiveActions != after.maxActiveActions) return false;
        if(before.invitePolicy != after.invitePolicy) return false;
        if(before.persistenceProfile != after.persistenceProfile) return false;
        if(before.schema != after.schema) return false;
        if(before.ownerId != null ? !before.ownerId.equals(after.ownerId) : after.ownerId != null) return false;
        return true;
    }

    /**
     * Fail-closed equality after WAL replay. Every authoritative region must match {@code after}: Diff-covered
     * entities plus every unmodeled collection (missions, research, transactions, …). If anything would diverge,
     * the mutation is not WAL-eligible.
     * Campaign tick / settlement remainder / updatedAt are intentionally excluded from the comparison: they are
     * stored on every Diff even when unchanged, and wall-clock updatedAt advances independently of field patches.
     */
    private static boolean softStateEquals(SharedCampaignState left, SharedCampaignState right){
        if(!Objects.equals(left.displayName, right.displayName)) return false;
        if(!left.discovered.equals(right.discovered)) return false;
        if(!left.researched.equals(right.researched)) return false;
        if(!researchContentsEqual(left, right)) return false;
        if(!objectKeySetsEqual(left.actions, right.actions)) return false;
        if(!objectKeySetsEqual(left.members, right.members)) return false;
        if(!objectKeySetsEqual(left.sectors, right.sectors)) return false;
        if(!objectKeySetsEqual(left.missions, right.missions)) return false;
        for(String key : right.actions.keys()){
            if(!actionEquals(left.actions.get(key), right.actions.get(key))) return false;
        }
        for(String key : right.members.keys()){
            if(!memberEquals(left.members.get(key), right.members.get(key))) return false;
        }
        for(String key : right.sectors.keys()){
            SectorState a = left.sectors.get(key);
            SectorState b = right.sectors.get(key);
            if(a == null || b == null) return false;
            if(!sectorLightEquals(a, b)) return false;
        }
        for(String key : right.missions.keys()){
            if(!missionEquals(left.missions.get(key), right.missions.get(key))) return false;
        }
        if(!researchTransactionsEqual(left, right)) return false;
        if(!transportTransactionsEqual(left, right)) return false;
        if(!launchTransactionsEqual(left, right)) return false;
        if(!controlReceiptsEqual(left, right)) return false;
        if(!planetPoliciesEqual(left, right)) return false;
        if(!backupsEqual(left, right)) return false;
        if(!transportsEqual(left, right)) return false;
        if(!extensionMapsEqual(left, right)) return false;
        if(left.producedActual.size != right.producedActual.size) return false;
        if(!left.producedActual.equals(right.producedActual)) return false;
        return true;
    }

    private static boolean transportsEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.transports.size != after.transports.size) return false;
        for(int i = 0; i < after.transports.size; i++){
            TransportOrder leftOrder = before.transports.get(i);
            TransportOrder rightOrder = after.transports.get(i);
            if(leftOrder == rightOrder) continue;
            if(leftOrder == null || rightOrder == null) return false;
            if(!Objects.equals(leftOrder.orderId, rightOrder.orderId)) return false;
            if(!Objects.equals(leftOrder.sourceSector, rightOrder.sourceSector)) return false;
            if(!Objects.equals(leftOrder.destinationSector, rightOrder.destinationSector)) return false;
            if(!Objects.equals(leftOrder.itemName, rightOrder.itemName)) return false;
            if(leftOrder.amount != rightOrder.amount || leftOrder.loaded != rightOrder.loaded || leftOrder.delivered != rightOrder.delivered) return false;
            if(leftOrder.createdAt != rightOrder.createdAt || leftOrder.etaCampaignTick != rightOrder.etaCampaignTick) return false;
            if(leftOrder.status != rightOrder.status) return false;
        }
        return true;
    }

    private static boolean extensionMapsEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.extensionSchemas.size != after.extensionSchemas.size) return false;
        for(ObjectMap.Entry<String, Integer> entry : after.extensionSchemas){
            Integer leftValue = before.extensionSchemas.get(entry.key);
            if(leftValue == null || !Objects.equals(leftValue, entry.value)) return false;
        }
        if(before.extensionData.size != after.extensionData.size) return false;
        for(ObjectMap.Entry<String, byte[]> entry : after.extensionData){
            byte[] leftData = before.extensionData.get(entry.key);
            if(leftData == null || !Arrays.equals(leftData, entry.value)) return false;
        }
        if(before.extensionRequired.size != after.extensionRequired.size) return false;
        for(ObjectMap.Entry<String, Boolean> entry : after.extensionRequired){
            Boolean leftValue = before.extensionRequired.get(entry.key);
            if(leftValue == null || !Objects.equals(leftValue, entry.value)) return false;
        }
        if(before.extensionCompatibility.size != after.extensionCompatibility.size) return false;
        for(ObjectMap.Entry<String, String> entry : after.extensionCompatibility){
            String leftValue = before.extensionCompatibility.get(entry.key);
            if(leftValue == null || !Objects.equals(leftValue, entry.value)) return false;
        }
        return true;
    }

    private static boolean actionChanged(ActionState before, ActionState after){
        return !actionEquals(before, after);
    }

    private static boolean actionEquals(ActionState before, ActionState after){
        if(before == after) return true;
        if(before == null || after == null) return false;
        return Objects.equals(before.actionId, after.actionId)
            && Objects.equals(before.planetName, after.planetName)
            && Objects.equals(before.sectorName, after.sectorName)
            && Objects.equals(before.missionId, after.missionId)
            && Objects.equals(before.attemptId, after.attemptId)
            && before.status == after.status
            && before.kind == after.kind
            && Objects.equals(before.objectiveSummary, after.objectiveSummary)
            && before.spectatorsAllowed == after.spectatorsAllowed
            && Objects.equals(before.hostId, after.hostId)
            && before.hostGeneration == after.hostGeneration
            && before.runtimeIncarnation == after.runtimeIncarnation
            && before.leaseExpiresAt == after.leaseExpiresAt
            && Objects.equals(before.bindAddress, after.bindAddress)
            && before.port == after.port
            && Objects.equals(before.joinSecretHash, after.joinSecretHash)
            && Objects.equals(before.saveRelativePath, after.saveRelativePath)
            && Objects.equals(before.launchOriginSector, after.launchOriginSector)
            && Objects.equals(before.launchLoadout, after.launchLoadout)
            && before.launchCommitted == after.launchCommitted
            && before.actionTick == after.actionTick
            && before.createdAt == after.createdAt
            && before.startedAt == after.startedAt
            && before.updatedAt == after.updatedAt
            && before.connectedPlayers == after.connectedPlayers
            && before.connectedSpectators == after.connectedSpectators
            && Objects.equals(before.failureReason, after.failureReason)
            && Objects.equals(before.lastSaveHash, after.lastSaveHash)
            && before.participants.equals(after.participants)
            && before.spectators.equals(after.spectators)
            && intMapEquals(before.resourceNeeds, after.resourceNeeds)
            && intMapEquals(before.launchResources, after.launchResources)
            && intMapEquals(before.launchCosts, after.launchCosts)
            && summaryEquals(before.summary, after.summary);
    }

    private static boolean memberChanged(MemberState before, MemberState after){
        return !memberEquals(before, after);
    }

    private static boolean memberEquals(MemberState before, MemberState after){
        if(before == after) return true;
        if(before == null || after == null) return false;
        return Objects.equals(before.memberId, after.memberId)
            && Objects.equals(before.displayName, after.displayName)
            && Objects.equals(before.lastActionId, after.lastActionId)
            && before.lastSeenAt == after.lastSeenAt
            && before.lastConnectedAt == after.lastConnectedAt
            && before.lastDisconnectedAt == after.lastDisconnectedAt
            && before.tutorialSteps.equals(after.tutorialSteps);
    }

    private static boolean sectorLightChanged(SectorState before, SectorState after){
        return !sectorLightEquals(before, after);
    }

    private static boolean sectorLightEquals(SectorState before, SectorState after){
        if(before == after) return true;
        if(before == null || after == null) return false;
        return Objects.equals(before.planetName, after.planetName)
            && Objects.equals(before.sectorName, after.sectorName)
            && Objects.equals(before.destinationSector, after.destinationSector)
            && before.legacyLaunchPads == after.legacyLaunchPads
            && before.hasBase == after.hasBase
            && before.captured == after.captured
            && before.attacked == after.attacked
            && before.hasSpawns == after.hasSpawns
            && before.discovered == after.discovered
            && before.expeditionAvailable == after.expeditionAvailable
            && before.waitingSettlement == after.waitingSettlement
            && before.hasEnemyBase == after.hasEnemyBase
            && Float.compare(before.threat, after.threat) == 0
            && Float.compare(before.minutesCaptured, after.minutesCaptured) == 0
            && before.lastSavedAt == after.lastSavedAt
            && before.lastOpenedAt == after.lastOpenedAt
            && before.lastSummaryTick == after.lastSummaryTick
            && Objects.equals(before.logisticsWarning, after.logisticsWarning)
            && Objects.equals(before.saveRelativePath, after.saveRelativePath)
            && Objects.equals(before.displayName, after.displayName)
            && Objects.equals(before.icon, after.icon)
            && Objects.equals(before.contentIcon, after.contentIcon)
            && before.resources.equals(after.resources)
            && intMapEquals(before.items, after.items)
            && floatMapEquals(before.productionPerSecond, after.productionPerSecond)
            && floatMapEquals(before.exportPerSecond, after.exportPerSecond)
            && floatMapEquals(before.importPerSecond, after.importPerSecond)
            && intMapEquals(before.lastImportedItems, after.lastImportedItems)
            && summaryEquals(before.summary, after.summary);
    }

    private static boolean missionsEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.missions.size != after.missions.size) return false;
        for(ObjectMap.Entry<String, MissionState> entry : after.missions){
            if(!missionEquals(before.missions.get(entry.key), entry.value)) return false;
        }
        return true;
    }

    private static boolean missionEquals(MissionState before, MissionState after){
        if(before == after) return true;
        if(before == null || after == null) return false;
        if(!Objects.equals(before.missionId, after.missionId)) return false;
        if(!Objects.equals(before.planetName, after.planetName)) return false;
        if(!Objects.equals(before.sectorName, after.sectorName)) return false;
        if(!Objects.equals(before.definitionVersion, after.definitionVersion)) return false;
        if(!Objects.equals(before.definitionFingerprint, after.definitionFingerprint)) return false;
        if(!Objects.equals(before.attemptId, after.attemptId)) return false;
        if(before.status != after.status) return false;
        if(!Objects.equals(before.phase, after.phase)) return false;
        if(before.actionTick != after.actionTick || before.eventSequence != after.eventSequence) return false;
        if(before.objectives.size != after.objectives.size) return false;
        if(!before.signals.equals(after.signals)) return false;
        if(!before.completedWorldEvents.equals(after.completedWorldEvents)) return false;
        if(before.eventJournal.size != after.eventJournal.size) return false;
        for(int i = 0; i < before.eventJournal.size; i++){
            MissionEvent leftEvent = before.eventJournal.get(i);
            MissionEvent rightEvent = after.eventJournal.get(i);
            if(leftEvent == rightEvent) continue;
            if(leftEvent == null || rightEvent == null) return false;
            if(leftEvent.sequence != rightEvent.sequence || leftEvent.actionTick != rightEvent.actionTick) return false;
            if(!Objects.equals(leftEvent.eventId, rightEvent.eventId) || !Objects.equals(leftEvent.type, rightEvent.type)) return false;
            if(!Objects.equals(leftEvent.payload, rightEvent.payload)) return false;
        }
        for(ObjectMap.Entry<String, ObjectiveState> entry : after.objectives){
            ObjectiveState leftObjective = before.objectives.get(entry.key);
            ObjectiveState rightObjective = entry.value;
            if(leftObjective == null || rightObjective == null) return false;
            if(!Objects.equals(leftObjective.objectiveId, rightObjective.objectiveId)) return false;
            if(leftObjective.status != rightObjective.status) return false;
            if(leftObjective.progress != rightObjective.progress || leftObjective.requiredProgress != rightObjective.requiredProgress) return false;
            if(leftObjective.activatedAtTick != rightObjective.activatedAtTick || leftObjective.completedAtTick != rightObjective.completedAtTick) return false;
            if(leftObjective.completionEventSequence != rightObjective.completionEventSequence) return false;
        }
        return true;
    }

    private static boolean researchTransactionsEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.researchTransactions.size != after.researchTransactions.size) return false;
        for(ObjectMap.Entry<String, ResearchTransaction> entry : after.researchTransactions){
            ResearchTransaction leftTx = before.researchTransactions.get(entry.key);
            ResearchTransaction rightTx = entry.value;
            if(leftTx == null || rightTx == null) return false;
            if(!Objects.equals(leftTx.transactionId, rightTx.transactionId)) return false;
            if(!Objects.equals(leftTx.actorId, rightTx.actorId)) return false;
            if(!Objects.equals(leftTx.contentName, rightTx.contentName)) return false;
            if(leftTx.status != rightTx.status) return false;
            if(leftTx.createdAt != rightTx.createdAt || leftTx.updatedAt != rightTx.updatedAt) return false;
            if(!Objects.equals(leftTx.failureReason, rightTx.failureReason)) return false;
            if(!leftTx.preparedActions.equals(rightTx.preparedActions)) return false;
            if(leftTx.debits.size != rightTx.debits.size) return false;
            for(ObjectMap.Entry<String, ResearchDebit> debit : rightTx.debits){
                ResearchDebit leftDebit = leftTx.debits.get(debit.key);
                if(leftDebit == null) return false;
                if(!Objects.equals(leftDebit.sectorName, debit.value.sectorName)) return false;
                if(!Objects.equals(leftDebit.actionId, debit.value.actionId)) return false;
                if(!intMapEquals(leftDebit.items, debit.value.items)) return false;
            }
        }
        return true;
    }

    private static boolean transportTransactionsEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.transportTransactions.size != after.transportTransactions.size) return false;
        for(ObjectMap.Entry<String, TransportTransaction> entry : after.transportTransactions){
            TransportTransaction leftTx = before.transportTransactions.get(entry.key);
            TransportTransaction rightTx = entry.value;
            if(leftTx == null || rightTx == null) return false;
            if(!Objects.equals(leftTx.transactionId, rightTx.transactionId)) return false;
            if(!Objects.equals(leftTx.orderId, rightTx.orderId)) return false;
            if(!Objects.equals(leftTx.actionId, rightTx.actionId)) return false;
            if(!Objects.equals(leftTx.itemName, rightTx.itemName)) return false;
            if(leftTx.requested != rightTx.requested || leftTx.applied != rightTx.applied) return false;
            if(leftTx.status != rightTx.status) return false;
            if(leftTx.createdAt != rightTx.createdAt || leftTx.updatedAt != rightTx.updatedAt) return false;
            if(!Objects.equals(leftTx.failureReason, rightTx.failureReason)) return false;
        }
        return true;
    }

    private static boolean launchTransactionsEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.launchTransactions.size != after.launchTransactions.size) return false;
        for(ObjectMap.Entry<String, LaunchTransaction> entry : after.launchTransactions){
            LaunchTransaction leftTx = before.launchTransactions.get(entry.key);
            LaunchTransaction rightTx = entry.value;
            if(leftTx == null || rightTx == null) return false;
            if(!Objects.equals(leftTx.transactionId, rightTx.transactionId)) return false;
            if(!Objects.equals(leftTx.actionId, rightTx.actionId)) return false;
            if(!Objects.equals(leftTx.actorId, rightTx.actorId)) return false;
            if(!Objects.equals(leftTx.originSector, rightTx.originSector)) return false;
            if(!Objects.equals(leftTx.sourceActionId, rightTx.sourceActionId)) return false;
            if(!intMapEquals(leftTx.costs, rightTx.costs)) return false;
            if(leftTx.status != rightTx.status) return false;
            if(leftTx.createdAt != rightTx.createdAt || leftTx.updatedAt != rightTx.updatedAt) return false;
            if(!Objects.equals(leftTx.failureReason, rightTx.failureReason)) return false;
        }
        return true;
    }

    private static boolean controlReceiptsEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.controlRequestReceipts.size != after.controlRequestReceipts.size) return false;
        for(ObjectMap.Entry<String, ControlRequestReceipt> entry : after.controlRequestReceipts){
            ControlRequestReceipt leftReceipt = before.controlRequestReceipts.get(entry.key);
            ControlRequestReceipt rightReceipt = entry.value;
            if(leftReceipt == null || rightReceipt == null) return false;
            if(!Objects.equals(leftReceipt.memberId, rightReceipt.memberId)) return false;
            if(leftReceipt.requestId != rightReceipt.requestId) return false;
            if(!Objects.equals(leftReceipt.requestType, rightReceipt.requestType)) return false;
            if(!Objects.equals(leftReceipt.payloadHash, rightReceipt.payloadHash)) return false;
            if(!Objects.equals(leftReceipt.responseType, rightReceipt.responseType)) return false;
            if(leftReceipt.completedRevision != rightReceipt.completedRevision || leftReceipt.completedAt != rightReceipt.completedAt) return false;
            if(!Objects.equals(leftReceipt.resultReference, rightReceipt.resultReference)) return false;
        }
        return true;
    }

    private static boolean planetPoliciesEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.planetPolicies.size != after.planetPolicies.size) return false;
        for(ObjectMap.Entry<String, PlanetPolicyState> entry : after.planetPolicies){
            PlanetPolicyState leftPolicy = before.planetPolicies.get(entry.key);
            PlanetPolicyState rightPolicy = entry.value;
            if(leftPolicy == null || rightPolicy == null) return false;
            if(!Objects.equals(leftPolicy.planetName, rightPolicy.planetName)) return false;
            if(!Objects.equals(leftPolicy.policyId, rightPolicy.policyId)) return false;
            if(leftPolicy.policyVersion != rightPolicy.policyVersion) return false;
            if(!Objects.equals(leftPolicy.compatibilityId, rightPolicy.compatibilityId)) return false;
            if(!Objects.equals(leftPolicy.mode, rightPolicy.mode)) return false;
            if(leftPolicy.recommendedActiveActions != rightPolicy.recommendedActiveActions) return false;
            if(!Objects.equals(leftPolicy.researchSharing, rightPolicy.researchSharing)) return false;
            if(!Objects.equals(leftPolicy.resourceOwnership, rightPolicy.resourceOwnership)) return false;
            if(!Objects.equals(leftPolicy.failureRule, rightPolicy.failureRule)) return false;
            if(!Objects.equals(leftPolicy.invasionRule, rightPolicy.invasionRule)) return false;
        }
        return true;
    }

    private static boolean backupsEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.backups.size != after.backups.size) return false;
        for(int i = 0; i < after.backups.size; i++){
            BackupState leftBackup = before.backups.get(i);
            BackupState rightBackup = after.backups.get(i);
            if(leftBackup == rightBackup) continue;
            if(leftBackup == null || rightBackup == null) return false;
            if(!Objects.equals(leftBackup.backupId, rightBackup.backupId)) return false;
            if(!Objects.equals(leftBackup.displayName, rightBackup.displayName)) return false;
            if(!Objects.equals(leftBackup.reason, rightBackup.reason)) return false;
            if(!Objects.equals(leftBackup.relativePath, rightBackup.relativePath)) return false;
            if(!Objects.equals(leftBackup.sha256, rightBackup.sha256)) return false;
            if(leftBackup.createdAt != rightBackup.createdAt || leftBackup.campaignRevision != rightBackup.campaignRevision) return false;
            if(leftBackup.sizeBytes != rightBackup.sizeBytes || leftBackup.complete != rightBackup.complete) return false;
        }
        return true;
    }

    private static boolean researchedUnchanged(SharedCampaignState before, SharedCampaignState after){
        return before.researched.equals(after.researched);
    }

    private static boolean researchContentsEqual(SharedCampaignState before, SharedCampaignState after){
        if(before.research.size != after.research.size) return false;
        for(ObjectMap.Entry<String, ResearchState> entry : after.research){
            ResearchState previous = before.research.get(entry.key);
            if(previous == null) return false;
            if(!Objects.equals(previous.contentName, entry.value.contentName)) return false;
            if(previous.startedAtRevision != entry.value.startedAtRevision || previous.completedAtRevision != entry.value.completedAtRevision) return false;
            if(!intMapEquals(previous.required, entry.value.required)) return false;
            if(!intMapEquals(previous.contributed, entry.value.contributed)) return false;
        }
        return true;
    }

    private static boolean summaryEquals(SectorSummary before, SectorSummary after){
        if(before == after) return true;
        if(before == null || after == null) return false;
        return before.wave == after.wave
            && before.winWave == after.winWave
            && before.waves == after.waves
            && before.attackMode == after.attackMode
            && before.coreCount == after.coreCount
            && before.unitCount == after.unitCount
            && before.enemyCount == after.enemyCount
            && before.storageCapacity == after.storageCapacity
            && Objects.equals(before.coreType, after.coreType)
            && Float.compare(before.powerProduced, after.powerProduced) == 0
            && Float.compare(before.powerConsumed, after.powerConsumed) == 0
            && Float.compare(before.defenseScore, after.defenseScore) == 0
            && before.capturedAtTick == after.capturedAtTick
            && before.sampledAtTick == after.sampledAtTick
            && Objects.equals(before.phase, after.phase)
            && Objects.equals(before.planetName, after.planetName)
            && Objects.equals(before.destinationSector, after.destinationSector)
            && before.legacyLaunchPads == after.legacyLaunchPads
            && before.attacked == after.attacked
            && before.hasSpawns == after.hasSpawns
            && Objects.equals(before.displayName, after.displayName)
            && Objects.equals(before.icon, after.icon)
            && Objects.equals(before.contentIcon, after.contentIcon)
            && before.resources.equals(after.resources)
            && before.hasEnemyBase == after.hasEnemyBase
            && Float.compare(before.threat, after.threat) == 0
            && Float.compare(before.minutesCaptured, after.minutesCaptured) == 0
            && intMapEquals(before.items, after.items)
            && floatMapEquals(before.productionPerSecond, after.productionPerSecond)
            && floatMapEquals(before.exportPerSecond, after.exportPerSecond)
            && floatMapEquals(before.importPerSecond, after.importPerSecond);
    }

    private static boolean intMapEquals(ObjectMap<String, Integer> before, ObjectMap<String, Integer> after){
        if(before == after) return true;
        if(before == null || after == null) return false;
        if(before.size != after.size) return false;
        for(ObjectMap.Entry<String, Integer> entry : before){
            if(!Objects.equals(entry.value, after.get(entry.key))) return false;
        }
        return true;
    }

    private static boolean floatMapEquals(ObjectMap<String, Float> before, ObjectMap<String, Float> after){
        if(before == after) return true;
        if(before == null || after == null) return false;
        if(before.size != after.size) return false;
        for(ObjectMap.Entry<String, Float> entry : before){
            Float other = after.get(entry.key);
            if(other == null || Float.compare(entry.value, other) != 0) return false;
        }
        return true;
    }

    private static boolean objectKeySetsEqual(ObjectMap<String, ?> left, ObjectMap<String, ?> right){
        if(left == right) return true;
        if(left == null || right == null) return false;
        if(left.size != right.size) return false;
        for(String key : right.keys()){
            if(!left.containsKey(key)) return false;
        }
        return true;
    }

    private static boolean objectKeySetsEqual(ObjectSet<String> leftKeys, ObjectSet<String> rightKeys){
        if(leftKeys.size != rightKeys.size) return false;
        for(String key : rightKeys){
            if(!leftKeys.contains(key)) return false;
        }
        return true;
    }

    private static ObjectSet<String> sectorKeys(SharedCampaignState before, SharedCampaignState after){
        ObjectSet<String> keys = new ObjectSet<>();
        for(String key : before.sectors.keys()) keys.add(key);
        for(String key : after.sectors.keys()) keys.add(key);
        return keys;
    }

    private static MemberState copyMember(MemberState source){
        MemberState copy = new MemberState();
        applyMember(copy, source);
        copy.tutorialSteps.clear();
        copy.tutorialSteps.addAll(source.tutorialSteps);
        return copy;
    }

    private static void applyMember(MemberState target, MemberState patch){
        target.memberId = patch.memberId;
        target.displayName = patch.displayName;
        target.lastActionId = patch.lastActionId;
        target.lastSeenAt = patch.lastSeenAt;
        target.lastConnectedAt = patch.lastConnectedAt;
        target.lastDisconnectedAt = patch.lastDisconnectedAt;
        target.tutorialSteps.clear();
        target.tutorialSteps.addAll(patch.tutorialSteps);
    }

    private static SectorPatch toPatch(SectorState sector){
        SectorPatch patch = new SectorPatch();
        patch.planetName = sector.planetName;
        patch.sectorName = sector.sectorName;
        patch.destinationSector = sector.destinationSector == null ? "" : sector.destinationSector;
        patch.legacyLaunchPads = sector.legacyLaunchPads;
        patch.hasBase = sector.hasBase;
        patch.captured = sector.captured;
        patch.attacked = sector.attacked;
        patch.hasSpawns = sector.hasSpawns;
        patch.discovered = sector.discovered;
        patch.expeditionAvailable = sector.expeditionAvailable;
        patch.waitingSettlement = sector.waitingSettlement;
        patch.hasEnemyBase = sector.hasEnemyBase;
        patch.threat = sector.threat;
        patch.minutesCaptured = sector.minutesCaptured;
        patch.lastSavedAt = sector.lastSavedAt;
        patch.lastOpenedAt = sector.lastOpenedAt;
        patch.lastSummaryTick = sector.lastSummaryTick;
        patch.logisticsWarning = sector.logisticsWarning == null ? "" : sector.logisticsWarning;
        patch.saveRelativePath = sector.saveRelativePath == null ? "" : sector.saveRelativePath;
        patch.displayName = sector.displayName == null ? "" : sector.displayName;
        patch.icon = sector.icon == null ? "" : sector.icon;
        patch.contentIcon = sector.contentIcon == null ? "" : sector.contentIcon;
        patch.summary = sector.summary == null ? new SectorSummary() : sector.summary.strategicCopy();
        patch.items.clear(); patch.items.putAll(sector.items);
        patch.productionPerSecond.clear(); patch.productionPerSecond.putAll(sector.productionPerSecond);
        patch.exportPerSecond.clear(); patch.exportPerSecond.putAll(sector.exportPerSecond);
        patch.importPerSecond.clear(); patch.importPerSecond.putAll(sector.importPerSecond);
        patch.lastImportedItems.clear(); patch.lastImportedItems.putAll(sector.lastImportedItems);
        patch.resources.clear(); patch.resources.addAll(sector.resources);
        return patch;
    }

    private static void applySector(SectorState sector, SectorPatch patch){
        sector.planetName = patch.planetName;
        sector.sectorName = patch.sectorName;
        sector.destinationSector = patch.destinationSector;
        sector.legacyLaunchPads = patch.legacyLaunchPads;
        sector.hasBase = patch.hasBase;
        sector.captured = patch.captured;
        sector.attacked = patch.attacked;
        sector.hasSpawns = patch.hasSpawns;
        sector.discovered = patch.discovered;
        sector.expeditionAvailable = patch.expeditionAvailable;
        sector.waitingSettlement = patch.waitingSettlement;
        sector.hasEnemyBase = patch.hasEnemyBase;
        sector.threat = patch.threat;
        sector.minutesCaptured = patch.minutesCaptured;
        sector.lastSavedAt = patch.lastSavedAt;
        sector.lastOpenedAt = patch.lastOpenedAt;
        sector.lastSummaryTick = patch.lastSummaryTick;
        sector.logisticsWarning = patch.logisticsWarning;
        sector.saveRelativePath = patch.saveRelativePath;
        sector.displayName = patch.displayName;
        sector.icon = patch.icon;
        sector.contentIcon = patch.contentIcon;
        // Summary and live/offline sector inventory are distinct clean-schema projections. Preserve the summary
        // exactly as serialized instead of forcing it to mirror the top-level sector maps.
        sector.summary = patch.summary == null ? new SectorSummary() : patch.summary.strategicCopy();
        sector.items.clear(); sector.items.putAll(patch.items);
        sector.productionPerSecond.clear(); sector.productionPerSecond.putAll(patch.productionPerSecond);
        sector.exportPerSecond.clear(); sector.exportPerSecond.putAll(patch.exportPerSecond);
        sector.importPerSecond.clear(); sector.importPerSecond.putAll(patch.importPerSecond);
        sector.lastImportedItems.clear(); sector.lastImportedItems.putAll(patch.lastImportedItems);
        sector.resources.clear(); sector.resources.addAll(patch.resources);
    }

    /** Encodes every field {@link #actionEquals(ActionState, ActionState)} compares so WAL replay cannot drop non-default payloads. */
    private static void writeAction(DataOutputStream out, ActionState action) throws IOException{
        writeString(out, action.actionId);
        writeString(out, action.planetName);
        writeString(out, action.sectorName);
        writeString(out, action.missionId);
        writeString(out, action.attemptId);
        writeString(out, action.status.name());
        writeString(out, action.kind == null ? ActionKind.expedition.name() : action.kind.name());
        writeString(out, action.objectiveSummary == null ? "" : action.objectiveSummary);
        out.writeBoolean(action.spectatorsAllowed);
        writeString(out, action.hostId);
        writeString(out, action.bindAddress == null ? "" : action.bindAddress);
        writeString(out, action.joinSecretHash == null ? "" : action.joinSecretHash);
        writeString(out, action.saveRelativePath);
        writeString(out, action.launchOriginSector == null ? "" : action.launchOriginSector);
        writeString(out, action.launchLoadout == null ? "" : action.launchLoadout);
        out.writeBoolean(action.launchCommitted);
        writeString(out, action.lastSaveHash);
        writeString(out, action.failureReason);
        out.writeLong(action.hostGeneration);
        out.writeLong(action.runtimeIncarnation);
        out.writeLong(action.leaseExpiresAt);
        out.writeLong(action.actionTick);
        out.writeLong(action.createdAt);
        out.writeLong(action.startedAt);
        out.writeLong(action.updatedAt);
        out.writeInt(action.port);
        out.writeInt(action.connectedPlayers);
        out.writeInt(action.connectedSpectators);
        writeStringSet(out, action.participants);
        writeStringSet(out, action.spectators);
        writeIntMap(out, action.resourceNeeds);
        writeIntMap(out, action.launchResources);
        writeIntMap(out, action.launchCosts);
        writeSummary(out, action.summary);
    }

    private static ActionState readAction(DataInputStream in) throws IOException{
        ActionState action = new ActionState();
        action.actionId = readString(in);
        action.planetName = readString(in);
        action.sectorName = readString(in);
        action.missionId = readString(in);
        action.attemptId = readString(in);
        action.status = ActionStatus.valueOf(readString(in));
        action.kind = ActionKind.valueOf(readString(in));
        action.objectiveSummary = readString(in);
        action.spectatorsAllowed = in.readBoolean();
        action.hostId = readString(in);
        action.bindAddress = readString(in);
        action.joinSecretHash = readString(in);
        action.saveRelativePath = readString(in);
        action.launchOriginSector = readString(in);
        action.launchLoadout = readString(in);
        action.launchCommitted = in.readBoolean();
        action.lastSaveHash = readString(in);
        action.failureReason = readString(in);
        action.hostGeneration = in.readLong();
        action.runtimeIncarnation = in.readLong();
        action.leaseExpiresAt = in.readLong();
        action.actionTick = in.readLong();
        action.createdAt = in.readLong();
        action.startedAt = in.readLong();
        action.updatedAt = in.readLong();
        action.port = in.readInt();
        action.connectedPlayers = in.readInt();
        action.connectedSpectators = in.readInt();
        action.participants.clear();
        action.participants.addAll(readStringSet(in));
        action.spectators.clear();
        action.spectators.addAll(readStringSet(in));
        action.resourceNeeds.clear();
        action.resourceNeeds.putAll(readIntMap(in));
        action.launchResources.clear();
        action.launchResources.putAll(readIntMap(in));
        action.launchCosts.clear();
        action.launchCosts.putAll(readIntMap(in));
        action.summary = readSummary(in);
        return action;
    }

    private static void writeMember(DataOutputStream out, MemberState member) throws IOException{
        writeString(out, member.memberId);
        writeString(out, member.displayName);
        writeString(out, member.lastActionId);
        out.writeLong(member.lastSeenAt);
        out.writeLong(member.lastConnectedAt);
        out.writeLong(member.lastDisconnectedAt);
        writeStringSet(out, member.tutorialSteps);
    }

    private static MemberState readMember(DataInputStream in) throws IOException{
        MemberState member = new MemberState();
        member.memberId = readString(in);
        member.displayName = readString(in);
        member.lastActionId = readString(in);
        member.lastSeenAt = in.readLong();
        member.lastConnectedAt = in.readLong();
        member.lastDisconnectedAt = in.readLong();
        member.tutorialSteps.clear();
        member.tutorialSteps.addAll(readStringSet(in));
        return member;
    }

    private static void writeSectorPatch(DataOutputStream out, SectorPatch patch) throws IOException{
        writeString(out, patch.planetName);
        writeString(out, patch.sectorName);
        writeString(out, patch.destinationSector);
        out.writeBoolean(patch.legacyLaunchPads);
        out.writeBoolean(patch.hasBase);
        out.writeBoolean(patch.captured);
        out.writeBoolean(patch.attacked);
        out.writeBoolean(patch.hasSpawns);
        out.writeBoolean(patch.discovered);
        out.writeBoolean(patch.expeditionAvailable);
        out.writeBoolean(patch.waitingSettlement);
        out.writeBoolean(patch.hasEnemyBase);
        out.writeFloat(patch.threat);
        out.writeFloat(patch.minutesCaptured);
        out.writeLong(patch.lastSavedAt);
        out.writeLong(patch.lastOpenedAt);
        out.writeLong(patch.lastSummaryTick);
        writeString(out, patch.logisticsWarning);
        writeString(out, patch.saveRelativePath);
        writeString(out, patch.displayName);
        writeString(out, patch.icon);
        writeString(out, patch.contentIcon);
        writeSummary(out, patch.summary);
        writeIntMap(out, patch.items);
        writeFloatMap(out, patch.productionPerSecond);
        writeFloatMap(out, patch.exportPerSecond);
        writeFloatMap(out, patch.importPerSecond);
        writeIntMap(out, patch.lastImportedItems);
        writeStringSet(out, patch.resources);
    }

    private static SectorPatch readSectorPatch(DataInputStream in) throws IOException{
        SectorPatch patch = new SectorPatch();
        patch.planetName = readString(in);
        patch.sectorName = readString(in);
        patch.destinationSector = readString(in);
        patch.legacyLaunchPads = in.readBoolean();
        patch.hasBase = in.readBoolean();
        patch.captured = in.readBoolean();
        patch.attacked = in.readBoolean();
        patch.hasSpawns = in.readBoolean();
        patch.discovered = in.readBoolean();
        patch.expeditionAvailable = in.readBoolean();
        patch.waitingSettlement = in.readBoolean();
        patch.hasEnemyBase = in.readBoolean();
        patch.threat = in.readFloat();
        patch.minutesCaptured = in.readFloat();
        patch.lastSavedAt = in.readLong();
        patch.lastOpenedAt = in.readLong();
        patch.lastSummaryTick = in.readLong();
        patch.logisticsWarning = readString(in);
        patch.saveRelativePath = readString(in);
        patch.displayName = readString(in);
        patch.icon = readString(in);
        patch.contentIcon = readString(in);
        patch.summary = readSummary(in);
        patch.items.clear(); patch.items.putAll(readIntMap(in));
        patch.productionPerSecond.clear(); patch.productionPerSecond.putAll(readFloatMap(in));
        patch.exportPerSecond.clear(); patch.exportPerSecond.putAll(readFloatMap(in));
        patch.importPerSecond.clear(); patch.importPerSecond.putAll(readFloatMap(in));
        patch.lastImportedItems.clear(); patch.lastImportedItems.putAll(readIntMap(in));
        patch.resources.clear(); patch.resources.addAll(readStringSet(in));
        return patch;
    }

    private static void writeIntMap(DataOutputStream out, ObjectMap<String, Integer> values) throws IOException{
        writeMapSize(out, values.size);
        for(ObjectMap.Entry<String, Integer> entry : values){
            writeString(out, entry.key);
            out.writeInt(entry.value == null ? 0 : entry.value);
        }
    }

    private static ObjectMap<String, Integer> readIntMap(DataInputStream in) throws IOException{
        int size = readMapSize(in);
        ObjectMap<String, Integer> values = new ObjectMap<>(Math.max(1, size));
        for(int i = 0; i < size; i++){
            String key = readString(in);
            values.put(key, in.readInt());
        }
        return values;
    }

    private static void writeFloatMap(DataOutputStream out, ObjectMap<String, Float> values) throws IOException{
        writeMapSize(out, values.size);
        for(ObjectMap.Entry<String, Float> entry : values){
            writeString(out, entry.key);
            out.writeFloat(entry.value);
        }
    }

    private static ObjectMap<String, Float> readFloatMap(DataInputStream in) throws IOException{
        int size = readMapSize(in);
        ObjectMap<String, Float> values = new ObjectMap<>(Math.max(1, size));
        for(int i = 0; i < size; i++){
            String key = readString(in);
            values.put(key, in.readFloat());
        }
        return values;
    }

    private static void writeSummary(DataOutputStream out, SectorSummary summary) throws IOException{
        SectorSummary value = summary == null ? new SectorSummary() : summary;
        out.writeInt(value.wave);
        out.writeInt(value.winWave);
        out.writeBoolean(value.waves);
        out.writeBoolean(value.attackMode);
        out.writeInt(value.coreCount);
        out.writeInt(value.unitCount);
        out.writeInt(value.enemyCount);
        out.writeInt(value.storageCapacity);
        writeString(out, value.coreType);
        out.writeFloat(value.powerProduced);
        out.writeFloat(value.powerConsumed);
        out.writeFloat(value.defenseScore);
        out.writeLong(value.capturedAtTick);
        out.writeLong(value.sampledAtTick);
        writeString(out, value.phase);
        writeString(out, value.planetName);
        writeString(out, value.destinationSector);
        out.writeBoolean(value.legacyLaunchPads);
        out.writeBoolean(value.attacked);
        out.writeBoolean(value.hasSpawns);
        writeString(out, value.displayName);
        writeString(out, value.icon);
        writeString(out, value.contentIcon);
        writeStringSet(out, value.resources);
        out.writeBoolean(value.hasEnemyBase);
        out.writeFloat(value.threat);
        out.writeFloat(value.minutesCaptured);
        writeIntMap(out, value.items);
        writeFloatMap(out, value.productionPerSecond);
        writeFloatMap(out, value.exportPerSecond);
        writeFloatMap(out, value.importPerSecond);
    }

    private static SectorSummary readSummary(DataInputStream in) throws IOException{
        SectorSummary summary = new SectorSummary();
        summary.wave = in.readInt();
        summary.winWave = in.readInt();
        summary.waves = in.readBoolean();
        summary.attackMode = in.readBoolean();
        summary.coreCount = in.readInt();
        summary.unitCount = in.readInt();
        summary.enemyCount = in.readInt();
        summary.storageCapacity = in.readInt();
        summary.coreType = readString(in);
        summary.powerProduced = in.readFloat();
        summary.powerConsumed = in.readFloat();
        summary.defenseScore = in.readFloat();
        summary.capturedAtTick = in.readLong();
        summary.sampledAtTick = in.readLong();
        summary.phase = readString(in);
        summary.planetName = readString(in);
        summary.destinationSector = readString(in);
        summary.legacyLaunchPads = in.readBoolean();
        summary.attacked = in.readBoolean();
        summary.hasSpawns = in.readBoolean();
        summary.displayName = readString(in);
        summary.icon = readString(in);
        summary.contentIcon = readString(in);
        summary.resources.clear();
        summary.resources.addAll(readStringSet(in));
        summary.hasEnemyBase = in.readBoolean();
        summary.threat = in.readFloat();
        summary.minutesCaptured = in.readFloat();
        summary.items.clear(); summary.items.putAll(readIntMap(in));
        summary.productionPerSecond.clear(); summary.productionPerSecond.putAll(readFloatMap(in));
        summary.exportPerSecond.clear(); summary.exportPerSecond.putAll(readFloatMap(in));
        summary.importPerSecond.clear(); summary.importPerSecond.putAll(readFloatMap(in));
        return summary;
    }

    private static void writeStringSet(DataOutputStream out, ObjectSet<String> values) throws IOException{
        writeMapSize(out, values.size);
        for(String value : values) writeString(out, value);
    }

    private static ObjectSet<String> readStringSet(DataInputStream in) throws IOException{
        int size = readMapSize(in);
        ObjectSet<String> values = new ObjectSet<>();
        for(int i = 0; i < size; i++) values.add(readString(in));
        return values;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException{
        out.writeUTF(value == null ? "" : value);
    }

    private static String readString(DataInputStream in) throws IOException{
        String value = in.readUTF();
        return value == null ? "" : value;
    }

    private static void writeMapSize(DataOutputStream out, int size) throws IOException{
        if(size < 0 || size > maxCollectionSize) throw new IllegalStateException("WAL collection size out of range: " + size);
        out.writeInt(size);
    }

    private static int readMapSize(DataInputStream in) throws IOException{
        int size = in.readInt();
        if(size < 0 || size > maxCollectionSize) throw new IOException("Invalid WAL collection size: " + size);
        return size;
    }

    private static int readInt(byte[] bytes, int offset){
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static long readLong(byte[] bytes, int offset){
        long value = 0;
        for(int i = 0; i < 8; i++) value = (value << 8) | (bytes[offset + i] & 0xff);
        return value;
    }
}
