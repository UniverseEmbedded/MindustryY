package mindustry.campaign.shared;

import arc.struct.*;
import arc.util.*;

import java.util.*;

/**
 * Authoritative, engine-independent state of a shared campaign.
 *
 * <p>This object deliberately contains no live world/entity references. Every mutation is committed through
 * the coordinator, action runtimes and future hosts may exchange the same durable state without depending on
 * a process-global {@code Vars.state}. Persistence/legacy codecs are deliberately layered outside this domain type.</p>
 */
public class SharedCampaignState{
    public static final int currentSchema = 27;

    public int schema = currentSchema;
    public String campaignId = UUID.randomUUID().toString();
    public String displayName = "Shared Campaign";
    public String ownerId = "";
    public long revision;
    public long createdAt = Time.millis();
    public long updatedAt = createdAt;
    public int maxActiveActions = 2;
    public boolean freezeWhenEmpty = true;
    /** Durable persistence policy. Existing schema-26 campaigns migrate to high-frequency WAL to preserve semantics. */
    public PersistenceProfile persistenceProfile = PersistenceProfile.lowFrequencyWal;
    /** Stable primary strategic planet displayed when the lobby opens. */
    public String primaryPlanetName = "serpulo";
    /** When false, the coordinator enforces one live action regardless of maxActiveActions. */
    public boolean multiFrontEnabled = true;
    /** Who may retrieve the current invite code after authenticating as a campaign member. */
    public InvitePolicy invitePolicy = InvitePolicy.members;
    /** How this campaign was created; used for diagnostics and restore UX, never for authority decisions. */
    public CampaignOrigin origin = CampaignOrigin.newCampaign;
    public String originDescription = "";
    /** Monotonic campaign clock. It advances only while the campaign authority decides time is running. */
    public long campaignTick;
    /** Uncommitted ticks toward the next vanilla campaign turn for suspended-sector settlement. */
    public long settlementRemainderTicks;

    /** Strict current-installation runtime fingerprint required by every live Action host. */
    public String contentFingerprint = "";
    /** Build-independent stable content/Mod identity used to decide whether a durable campaign may cross an update. */
    public String contentCompatibilityFingerprint = "";
    /** Monotonically increasing generation used to reject stale hosts and migration bundles. */
    public long authorityGeneration = 1L;
    /** Stable identity of the only host currently allowed to open the authoritative store. */
    public String authorityHostId = "";
    /** A cold migration fences the old host before the archive leaves the machine. */
    public boolean migrationPending;
    public String migrationTargetHostId = "";
    public String migrationNonce = "";
    public long migrationExpiresAt;

    public ObjectMap<String, MemberState> members = new ObjectMap<>();
    /** Persisted planet policy identity and compatibility data. */
    public ObjectMap<String, PlanetPolicyState> planetPolicies = new ObjectMap<>();
    public ObjectMap<String, ActionState> actions = new ObjectMap<>();
    public ObjectMap<String, SectorState> sectors = new ObjectMap<>();
    public ObjectMap<String, MissionState> missions = new ObjectMap<>();
    public ObjectSet<String> researched = new ObjectSet<>();
    /** Durable authoritative non-research unlock/discovery history. */
    public ObjectSet<String> discovered = new ObjectSet<>();
    /** Literal factory-production history. Legacy importers may leave this empty when production history cannot be proven. */
    public ObjectSet<String> producedActual = new ObjectSet<>();

    /** Effective Shared Campaign unlock, matching vanilla {@code UnlockableContent.unlockedHost()}: explicit research
     * OR any authoritative discovery/automatic unlock. Literal production telemetry is deliberately not authority. */
    public static boolean effectiveUnlocked(ObjectSet<String> researched, ObjectSet<String> discovered, String name){
        return (researched != null && researched.contains(name)) || (discovered != null && discovered.contains(name));
    }
    /** Partial research contributions, preserving vanilla's incremental research behavior. */
    public ObjectMap<String, ResearchState> research = new ObjectMap<>();
    /** Durable two-phase transactions used when research consumes resources from live action processes. */
    public ObjectMap<String, ResearchTransaction> researchTransactions = new ObjectMap<>();
    /** Durable delivery transactions for imports into live action processes. */
    public ObjectMap<String, TransportTransaction> transportTransactions = new ObjectMap<>();
    /** Durable launch debits, including reservations against a source sector that is itself running live. */
    public ObjectMap<String, LaunchTransaction> launchTransactions = new ObjectMap<>();
    /** Installed shared-campaign extension schemas and their opaque, versioned state. */
    public ObjectMap<String, Integer> extensionSchemas = new ObjectMap<>();
    public ObjectMap<String, byte[]> extensionData = new ObjectMap<>();
    /** Whether a campaign must refuse to open when an extension is unavailable. Persisted at creation time. */
    public ObjectMap<String, Boolean> extensionRequired = new ObjectMap<>();
    /** Extension-defined compatibility identity, separate from its mutable data schema. */
    public ObjectMap<String, String> extensionCompatibility = new ObjectMap<>();
    public Seq<TransportOrder> transports = new Seq<>();
    public Seq<CampaignEvent> recentEvents = new Seq<>();
    /** Bounded restore-point metadata; backup payloads live next to the campaign store. */
    public Seq<BackupState> backups = new Seq<>();
    /**
     * Durable receipts for authenticated control-plane mutations whose business state and replay identity must
     * survive a coordinator process crash atomically. The coordinator may keep a richer in-memory response cache,
     * but these receipts are the authority for deciding whether a request ID may execute again after restart.
     */
    public ObjectMap<String, ControlRequestReceipt> controlRequestReceipts = new ObjectMap<>();


    public ActionState action(String id){
        return actions.get(id);
    }

    public static String sectorKey(String planetName, String sectorName){
        String planet = planetName == null ? "" : planetName.trim();
        String sector = sectorName == null ? "" : sectorName.trim();
        if(planet.isBlank() || sector.isBlank()) throw new IllegalArgumentException("Planet and sector are required");
        return planet + ":" + sector;
    }


    public int runningActions(){
        int count = 0;
        for(ActionState action : actions.values()){
            if(action.status == ActionStatus.starting || action.status == ActionStatus.running || action.status == ActionStatus.suspending) count++;
        }
        return count;
    }

    /**
     * Bounds durable terminal transaction history before every snapshot commit. Active/in-doubt records are never
     * removed: recovery and idempotency always take priority over compaction. Without this, a long-running campaign
     * (especially a full LaunchPad destination producing repeated zero-apply deliveries) grows every future snapshot
     * without bound and can eventually cross the control protocol payload ceiling.
     */
    public void compactTerminalTransactionHistory(){
        trimResearchTransactions(2048);
        trimTransportTransactions(2048, 256);
        trimLaunchTransactions(2048);
    }

    private void trimResearchTransactions(int retainedTerminal){
        Seq<ResearchTransaction> terminal = researchTransactions.values().toSeq().select(value -> value.status == ResearchTransactionStatus.committed || value.status == ResearchTransactionStatus.aborted);
        terminal.sort((a, b) -> { int time = Long.compare(a.updatedAt, b.updatedAt); return time != 0 ? time : a.transactionId.compareTo(b.transactionId); });
        while(terminal.size > retainedTerminal){
            ResearchTransaction oldest = terminal.remove(0);
            researchTransactions.remove(oldest.transactionId);
        }
    }

    private void trimTransportTransactions(int retainedTerminal, int retainedZeroApply){
        Seq<TransportTransaction> terminal = transportTransactions.values().toSeq().select(value -> value.status == TransportTransactionStatus.acknowledged || value.status == TransportTransactionStatus.aborted);
        terminal.sort((a, b) -> { int time = Long.compare(a.updatedAt, b.updatedAt); return time != 0 ? time : a.transactionId.compareTo(b.transactionId); });
        int zeroApply = 0;
        for(TransportTransaction value : terminal) if(value.status == TransportTransactionStatus.acknowledged && value.applied == 0) zeroApply++;
        for(int i = 0; i < terminal.size && zeroApply > retainedZeroApply; ){
            TransportTransaction value = terminal.get(i);
            if(value.status == TransportTransactionStatus.acknowledged && value.applied == 0){
                transportTransactions.remove(value.transactionId);
                terminal.remove(i);
                zeroApply--;
            }else i++;
        }
        while(terminal.size > retainedTerminal){
            TransportTransaction oldest = terminal.remove(0);
            transportTransactions.remove(oldest.transactionId);
        }
    }

    private void trimLaunchTransactions(int retainedTerminal){
        Seq<LaunchTransaction> terminal = launchTransactions.values().toSeq().select(value -> value.status == LaunchTransactionStatus.committed || value.status == LaunchTransactionStatus.aborted);
        terminal.sort((a, b) -> { int time = Long.compare(a.updatedAt, b.updatedAt); return time != 0 ? time : a.transactionId.compareTo(b.transactionId); });
        while(terminal.size > retainedTerminal){
            LaunchTransaction oldest = terminal.remove(0);
            launchTransactions.remove(oldest.transactionId);
        }
    }


    public void validate(){
        if(schema <= 0 || schema > currentSchema) throw new IllegalStateException("Unsupported shared campaign schema: " + schema);
        if(campaignId == null || campaignId.isBlank()) throw new IllegalStateException("campaignId is required");
        if(maxActiveActions < 1 || maxActiveActions > 32) throw new IllegalStateException("maxActiveActions out of range");
        if(primaryPlanetName == null || primaryPlanetName.isBlank()) throw new IllegalStateException("primaryPlanetName is required");
        if(invitePolicy == null) throw new IllegalStateException("invitePolicy is required");
        if(persistenceProfile == null) throw new IllegalStateException("persistenceProfile is required");
        if(origin == null) throw new IllegalStateException("campaign origin is required");
        if(!multiFrontEnabled && maxActiveActions != 1) throw new IllegalStateException("Single-front campaigns must have exactly one active action");
        if(authorityGeneration < 1) throw new IllegalStateException("authorityGeneration must be positive");
        if(authorityHostId == null) throw new IllegalStateException("authorityHostId may not be null");
        boolean initialized = revision > 0L || (ownerId != null && !ownerId.isBlank()) || !members.isEmpty();
        if(initialized){
            if(ownerId == null || ownerId.isBlank()) throw new IllegalStateException("Initialized campaign has no ownerId");
            if(authorityHostId.isBlank()) throw new IllegalStateException("Initialized campaign has no authorityHostId");
            if(!members.containsKey(ownerId)) throw new IllegalStateException("Campaign owner is not a durable member: " + ownerId);
        }
        if(migrationPending){
            if(migrationTargetHostId == null || migrationTargetHostId.isBlank()) throw new IllegalStateException("Pending migration has no target host");
            if(!migrationTargetHostId.equals(authorityHostId)) throw new IllegalStateException("Pending migration target is not the fenced authority host");
            if(migrationNonce == null || migrationNonce.isBlank()) throw new IllegalStateException("Pending migration has no nonce");
            if(migrationExpiresAt <= 0) throw new IllegalStateException("Pending migration has no expiry");
        }

        if(schema >= 11 && !(revision == 0L && (ownerId == null || ownerId.isBlank()) && planetPolicies.isEmpty())){
            for(ObjectMap.Entry<String, PlanetPolicyState> entry : planetPolicies){
                if(entry.key == null || entry.key.isBlank() || entry.value == null || !entry.key.equals(entry.value.planetName)) throw new IllegalStateException("Invalid planet policy key");
                entry.value.validate();
            }
            if(!planetPolicies.containsKey(primaryPlanetName)) throw new IllegalStateException("Primary planet has no persisted multiplayer policy: " + primaryPlanetName);
            for(BackupState backup : backups) backup.validate();
            if(backups.size > 128) throw new IllegalStateException("Too many retained backup records");
        }
        if(schema >= 17){
            if(controlRequestReceipts.size > 4096) throw new IllegalStateException("Too many retained control request receipts");
            for(ObjectMap.Entry<String, ControlRequestReceipt> entry : controlRequestReceipts){
                if(entry.key == null || entry.key.isBlank() || entry.value == null || !entry.key.equals(entry.value.key())){
                    throw new IllegalStateException("Invalid durable control request receipt key");
                }
                entry.value.validate();
            }
        }

        ObjectSet<Integer> ports = new ObjectSet<>();
        ObjectSet<String> liveMissionIds = new ObjectSet<>();
        ObjectSet<String> liveSectorKeys = new ObjectSet<>();
        for(ObjectMap.Entry<String, MemberState> entry : members){
            if(entry.key == null || entry.key.isBlank() || entry.value == null || !entry.key.equals(entry.value.memberId)){
                throw new IllegalStateException("Invalid shared campaign member key");
            }
        }
        for(ObjectMap.Entry<String, SectorState> entry : sectors){
            if(entry.key == null || entry.key.isBlank() || entry.value == null){
                throw new IllegalStateException("Invalid shared campaign sector key");
            }
            if(schema >= 9 && !entry.key.equals(sectorKey(entry.value.planetName, entry.value.sectorName))){
                throw new IllegalStateException("Shared campaign sector key is not globally qualified: " + entry.key);
            }
        }
        for(ObjectMap.Entry<String, MissionState> entry : missions){
            MissionState mission = entry.value;
            if(entry.key == null || entry.key.isBlank() || mission == null || !entry.key.equals(mission.missionId)){
                throw new IllegalStateException("Invalid shared campaign mission key");
            }
            if(mission.sectorName == null || mission.sectorName.isBlank()) throw new IllegalStateException("Mission sector is required: " + entry.key);
            if(schema >= 9 && (mission.planetName == null || mission.planetName.isBlank())) throw new IllegalStateException("Mission planet is required: " + entry.key);
            if(mission.status == MissionStatus.preparing || mission.status == MissionStatus.running || mission.status == MissionStatus.paused){
                if(mission.attemptId == null || mission.attemptId.isBlank()) throw new IllegalStateException("Active mission has no attempt ID: " + entry.key);
                if(mission.definitionVersion == null || mission.definitionVersion.isBlank() || mission.definitionFingerprint == null){
                    throw new IllegalStateException("Active mission has no definition identity field: " + entry.key);
                }
                //A schema migration may temporarily leave the semantic fingerprint empty. The mission registry fills
                //or rejects it before any action can resume; the engine-independent codec cannot know installed Mods.
            }
            for(ObjectMap.Entry<String, ObjectiveState> objectiveEntry : mission.objectives){
                if(objectiveEntry.key == null || objectiveEntry.key.isBlank() || objectiveEntry.value == null || !objectiveEntry.key.equals(objectiveEntry.value.objectiveId)){
                    throw new IllegalStateException("Invalid objective key in mission " + entry.key);
                }
            }
        }
        for(ObjectMap.Entry<String, ActionState> entry : actions){
            ActionState action = entry.value;
            if(entry.key == null || entry.key.isBlank() || action == null || !entry.key.equals(action.actionId)){
                throw new IllegalStateException("Invalid shared campaign action key");
            }
            action.validate();
            for(String memberId : action.participants){
                if(!members.containsKey(memberId)) throw new IllegalStateException("Action participant is not a campaign member: " + action.actionId + " / " + memberId);
            }
            if(schema >= 9 && (action.planetName == null || action.planetName.isBlank())) throw new IllegalStateException("Action planet is required: " + action.actionId);
            if(schema >= 10 && !action.launchOriginSector.isBlank() && !sectors.containsKey(action.launchOriginSector)) throw new IllegalStateException("Action launch origin is unknown: " + action.actionId + " -> " + action.launchOriginSector);
            if(action.status.isLive() && action.port > 0 && !ports.add(action.port)){
                throw new IllegalStateException("Duplicate live action port: " + action.port);
            }
            if(action.status.isLive() && schema >= 9 && !liveSectorKeys.add(sectorKey(action.planetName, action.sectorName))){
                throw new IllegalStateException("More than one live action exists for sector: " + sectorKey(action.planetName, action.sectorName));
            }
            if(action.missionId != null && !action.missionId.isBlank()){
                MissionState mission = missions.get(action.missionId);
                if(mission == null) throw new IllegalStateException("Action references unknown mission: " + action.missionId);
                boolean authoritativeAttempt = action.status.isLive() || action.status == ActionStatus.suspended || action.status == ActionStatus.incompatible;
                if(action.attemptId == null || action.attemptId.isBlank() || authoritativeAttempt && !action.attemptId.equals(mission.attemptId)){
                    throw new IllegalStateException("Action/mission attempt mismatch: " + action.actionId);
                }
                if(schema >= 9 && (!Objects.equals(action.planetName, mission.planetName) || !Objects.equals(action.sectorName, mission.sectorName))){
                    throw new IllegalStateException("Action/mission sector mismatch: " + action.actionId);
                }
                //Failed attempts remain as immutable history after a retry gives the mission a new attempt ID.
                if(action.status.isLive() && !liveMissionIds.add(action.missionId)){
                    throw new IllegalStateException("More than one live authoritative instance exists for mission: " + action.missionId);
                }
            }
        }

        for(TransportOrder order : transports){
            order.validate();
            if(schema >= 9 && (!sectors.containsKey(order.sourceSector) || !sectors.containsKey(order.destinationSector))){
                throw new IllegalStateException("Transport references an unknown globally-qualified sector: " + order.orderId);
            }
        }

        for(ObjectMap.Entry<String, ResearchState> entry : research){
            if(entry.key == null || entry.key.isBlank()) throw new IllegalStateException("Research content ID is required");
            entry.value.validate();
        }
        for(ObjectMap.Entry<String, ResearchTransaction> entry : researchTransactions){
            if(entry.key == null || !entry.key.equals(entry.value.transactionId)) throw new IllegalStateException("Invalid research transaction key");
            entry.value.validate();
            if(schema >= 9) for(String sectorKey : entry.value.debits.keys()) if(!sectors.containsKey(sectorKey)) throw new IllegalStateException("Research transaction references unknown sector: " + sectorKey);
        }
        for(ObjectMap.Entry<String, TransportTransaction> entry : transportTransactions){
            if(entry.key == null || !entry.key.equals(entry.value.transactionId)) throw new IllegalStateException("Invalid transport transaction key");
            entry.value.validate();
        }
        for(ObjectMap.Entry<String, LaunchTransaction> entry : launchTransactions){
            if(entry.key == null || !entry.key.equals(entry.value.transactionId)) throw new IllegalStateException("Invalid launch transaction key");
            entry.value.validate();
            if(!entry.value.originSector.isBlank() && !sectors.containsKey(entry.value.originSector)) throw new IllegalStateException("Launch transaction references unknown origin: " + entry.value.originSector);
            if(!entry.value.actionId.isBlank() && !actions.containsKey(entry.value.actionId) && entry.value.status != LaunchTransactionStatus.preparing && entry.value.status != LaunchTransactionStatus.aborted) throw new IllegalStateException("Launch transaction references unknown action: " + entry.value.actionId);
        }

        for(ObjectMap.Entry<String, Integer> entry : extensionSchemas){
            if(entry.key == null || entry.key.isBlank() || entry.value == null || entry.value < 1){
                throw new IllegalStateException("Invalid extension schema entry");
            }
            if(!extensionRequired.containsKey(entry.key)) throw new IllegalStateException("Missing extension requirement policy: " + entry.key);
            if(extensionCompatibility.get(entry.key) == null) throw new IllegalStateException("Missing extension compatibility identity: " + entry.key);
            //Legacy schema migration intentionally writes an empty identity; installed extensions resolve it before
            //the campaign authority begins accepting action or Mod mutations.
        }
        for(String id : extensionRequired.keys()) if(!extensionSchemas.containsKey(id)) throw new IllegalStateException("Orphan extension requirement policy: " + id);
        for(String id : extensionCompatibility.keys()) if(!extensionSchemas.containsKey(id)) throw new IllegalStateException("Orphan extension compatibility identity: " + id);
        for(String id : extensionData.keys()) if(!extensionSchemas.containsKey(id)) throw new IllegalStateException("Orphan extension data: " + id);
    }

    private static boolean oneOf(String value, String... allowed){
        if(value == null) return false;
        for(String candidate : allowed) if(candidate.equals(value)) return true;
        return false;
    }

    public enum PersistenceProfile{highFrequencyWal, lowFrequencyWal, traditional}
    public enum InvitePolicy{ownerOnly, members}
    public enum CampaignOrigin{newCampaign, singlePlayerImport, backupRestore}

    /** Minimal, engine-independent exactly-once identity persisted with the mutation it protects. */
    public static class ControlRequestReceipt{
        public String memberId = "";
        public long requestId;
        public String requestType = "";
        public String payloadHash = "";
        public String responseType = "";
        /** Optional durable identity of the response target, e.g. the authoritative actionId created by startAction. */
        public String resultReference = "";
        public long completedRevision;
        public long completedAt;

        public String key(){
            return durableKey(memberId, requestId);
        }

        public void validate(){
            if(memberId == null || memberId.isBlank()) throw new IllegalStateException("Control request receipt member is required");
            if(requestId <= 0L) throw new IllegalStateException("Control request receipt ID must be positive");
            if(requestType == null || requestType.isBlank()) throw new IllegalStateException("Control request receipt type is required");
            if(payloadHash == null || payloadHash.length() != 64) throw new IllegalStateException("Control request receipt payload hash is invalid");
            if(responseType == null || responseType.isBlank()) throw new IllegalStateException("Control request receipt response type is required");
            if(resultReference == null) throw new IllegalStateException("Control request receipt result reference may not be null");
            if(completedRevision <= 0L || completedAt <= 0L) throw new IllegalStateException("Control request receipt completion metadata is invalid");
        }

        public static String durableKey(String memberId, long requestId){
            String identity = memberId == null ? "" : memberId;
            return identity.length() + ":" + identity + ":" + Long.toUnsignedString(requestId);
        }
    }

    public static class PlanetPolicyState{
        public String planetName = "";
        public String policyId = "";
        public int policyVersion = 1;
        public String compatibilityId = "";
        public String mode = "unsupported";
        public int recommendedActiveActions = 1;
        public String researchSharing = "shared";
        public String resourceOwnership = "perSector";
        public String failureRule = "sectorOnly";
        public String invasionRule = "onlineProtected";

        public void validate(){
            if(planetName == null || planetName.isBlank()) throw new IllegalStateException("Planet policy planet is required");
            if(policyId == null || policyId.isBlank() || !policyId.contains(":")) throw new IllegalStateException("Planet policy ID must be namespaced");
            if(policyVersion < 1) throw new IllegalStateException("Planet policy version must be positive");
            if(compatibilityId == null || compatibilityId.isBlank()) throw new IllegalStateException("Planet policy compatibility ID is required");
            if(recommendedActiveActions < 1 || recommendedActiveActions > 32) throw new IllegalStateException("Planet policy action recommendation is out of range");
            if(!oneOf(researchSharing, "shared", "perPlanet", "custom")) throw new IllegalStateException("Invalid planet research policy: " + researchSharing);
            if(!oneOf(resourceOwnership, "perSector", "sharedPool", "custom")) throw new IllegalStateException("Invalid planet resource policy: " + resourceOwnership);
            if(!oneOf(failureRule, "sectorOnly", "missionAttempt", "campaign", "custom")) throw new IllegalStateException("Invalid planet failure policy: " + failureRule);
            if(!oneOf(invasionRule, "onlineProtected", "continuous", "disabled", "custom")) throw new IllegalStateException("Invalid planet invasion policy: " + invasionRule);
        }
    }

    public static class BackupState{
        public String backupId = "";
        public String displayName = "";
        public String reason = "";
        public String relativePath = "";
        public String sha256 = "";
        public long createdAt;
        public long campaignRevision;
        public long sizeBytes;
        public boolean complete;

        public void validate(){
            if(backupId == null || backupId.isBlank()) throw new IllegalStateException("Backup ID is required");
            if(relativePath == null || relativePath.isBlank() || relativePath.startsWith("/") || relativePath.contains("..")) throw new IllegalStateException("Unsafe backup path");
            if(createdAt <= 0 || campaignRevision < 0 || sizeBytes < 0) throw new IllegalStateException("Invalid backup metadata");
            if(complete && (sha256 == null || sha256.length() != 64)) throw new IllegalStateException("Complete backup has no SHA-256 hash");
        }
    }

    public static class MemberState{
        public String memberId = "";
        public String displayName = "";
        public String lastActionId = "";
        public long lastSeenAt;
        public long lastConnectedAt;
        public long lastDisconnectedAt;
        public ObjectSet<String> tutorialSteps = new ObjectSet<>();
    }

    public static class ActionState{
        public String actionId = UUID.randomUUID().toString();
        public String planetName = "";
        public String sectorName = "";
        public String missionId = "";
        public String attemptId = "";
        public ActionStatus status = ActionStatus.preparing;
        public ActionKind kind = ActionKind.expedition;
        public String objectiveSummary = "";
        public ObjectMap<String, Integer> resourceNeeds = new ObjectMap<>();
        public boolean spectatorsAllowed = true;
        public String hostId = "";
        public long hostGeneration;
        /** Monotonic identity of the concrete runtime instance for this logical Action. Incremented on every resume/restart. */
        public long runtimeIncarnation = 1L;
        public long leaseExpiresAt;
        public String bindAddress = "127.0.0.1";
        public int port;
        public String joinSecretHash = "";
        public String saveRelativePath = "";
        /** Globally-qualified origin sector used for a new expedition. Empty only for a free first landing or resumed save. */
        public String launchOriginSector = "";
        /** Exact launch schematic serialized with Schematics.writeBase64. */
        public String launchLoadout = "";
        /** Extra core inventory carried into the destination, excluding schematic construction requirements. */
        public ObjectMap<String, Integer> launchResources = new ObjectMap<>();
        /** Total resources deducted from the origin for this launch, including schematic requirements. */
        public ObjectMap<String, Integer> launchCosts = new ObjectMap<>();
        /** True once the launch debit has been committed to the authoritative campaign state. */
        public boolean launchCommitted;
        public long actionTick;
        public long createdAt = Time.millis();
        public long startedAt;
        public long updatedAt = createdAt;
        public ObjectSet<String> participants = new ObjectSet<>();
        /** Connected members who joined this action in read-only spectator mode. */
        public ObjectSet<String> spectators = new ObjectSet<>();
        public SectorSummary summary = new SectorSummary();
        public String failureReason = "";
        public int connectedPlayers;
        public int connectedSpectators;
        public String lastSaveHash = "";

        public void validate(){
            if(actionId == null || actionId.isBlank()) throw new IllegalStateException("actionId is required");
            if(sectorName == null || sectorName.isBlank()) throw new IllegalStateException("sectorName is required");
            if(port < 0 || port > 65535) throw new IllegalStateException("Invalid action port: " + port);
            if(status.isLive() && hostGeneration <= 0) throw new IllegalStateException("Live action has no host generation: " + actionId);
            if(runtimeIncarnation <= 0L) throw new IllegalStateException("Action runtime incarnation must be positive: " + actionId);
            if(connectedPlayers < 0 || connectedSpectators < 0) throw new IllegalStateException("Negative action presence count: " + actionId);
            for(String spectator : spectators) if(!participants.contains(spectator)) throw new IllegalStateException("Action spectator is not a participant: " + actionId + " / " + spectator);
            if(status.isLive()){
                if(connectedSpectators != spectators.size) throw new IllegalStateException("Action spectator count does not match live presence: " + actionId);
                if(connectedPlayers != Math.max(0, participants.size - spectators.size)) throw new IllegalStateException("Action player count does not match live presence: " + actionId);
            }else if(status == ActionStatus.suspended || status == ActionStatus.failed || status == ActionStatus.completed || status == ActionStatus.incompatible){
                if(connectedPlayers != 0 || connectedSpectators != 0 || !spectators.isEmpty()) throw new IllegalStateException("Inactive action retains live presence: " + actionId);
            }
            if(launchCommitted && launchOriginSector.isBlank() && !launchCosts.isEmpty()) throw new IllegalStateException("Launch costs have no origin sector: " + actionId);
            for(ObjectMap.Entry<String, Integer> entry : launchResources){
                if(entry.key == null || entry.key.isBlank() || entry.value == null || entry.value < 0) throw new IllegalStateException("Invalid launch resource for " + actionId);
            }
            if(kind == null) throw new IllegalStateException("Action kind is required");
            for(ObjectMap.Entry<String, Integer> entry : resourceNeeds){
                if(entry.key == null || entry.key.isBlank() || entry.value == null || entry.value < 0) throw new IllegalStateException("Invalid action resource requirement for " + actionId);
            }
            for(ObjectMap.Entry<String, Integer> entry : launchCosts){
                if(entry.key == null || entry.key.isBlank() || entry.value == null || entry.value <= 0) throw new IllegalStateException("Invalid launch cost for " + actionId);
            }
        }
    }

    public enum ActionKind{expedition, defense, baseBuilding, resourceExpansion, storyMission, modMission}

    public enum ActionStatus{
        preparing, starting, running, suspending, suspended, failed, completed, incompatible;

        public boolean isLive(){
            return this == starting || this == running || this == suspending;
        }
    }

    public static class SectorState{
        public String sectorName = "";
        public String planetName = "";
        /** Globally-qualified vanilla launch-pad destination. Empty when no destination is configured. */
        public String destinationSector = "";
        public boolean legacyLaunchPads;
        public boolean hasBase;
        public boolean captured;
        public boolean attacked;
        public boolean hasSpawns;
        public boolean discovered;
        public boolean expeditionAvailable;
        public boolean waitingSettlement;
        public String logisticsWarning = "";
        /** Shared-authoritative presentation metadata. These fields must never be read from the local profile by Shared UI. */
        public String displayName = "";
        public String icon = "";
        /** Stable ContentType:name identity for a content-backed custom sector icon. */
        public String contentIcon = "";
        /** Stable ContentType:name identities of resources discovered in this sector. */
        public ObjectSet<String> resources = new ObjectSet<>();
        public boolean hasEnemyBase;
        /** Strategic threat sampled by the authoritative runtime/importer. */
        public float threat;
        public float minutesCaptured;
        public long lastSavedAt;
        public long lastOpenedAt;
        public long lastSummaryTick;
        public String saveRelativePath = "";
        public SectorSummary summary = new SectorSummary();
        public ObjectMap<String, Integer> items = new ObjectMap<>();
        public ObjectMap<String, Float> productionPerSecond = new ObjectMap<>();
        public ObjectMap<String, Float> exportPerSecond = new ObjectMap<>();
        public ObjectMap<String, Float> importPerSecond = new ObjectMap<>();
        /** Raw items imported during the previous completed vanilla campaign turn. */
        public ObjectMap<String, Integer> lastImportedItems = new ObjectMap<>();
    }

    /** Conservative summary used while a sector is not running a real action process. */
    public static class SectorSummary{
        public int wave = 1;
        public int winWave = -1;
        public boolean waves;
        public boolean attackMode;
        public int coreCount;
        public int unitCount;
        public int enemyCount;
        public int storageCapacity;
        /** Best core block available in this sector; used to validate launch schematic size without opening the world save. */
        public String coreType = "";
        public float powerProduced;
        public float powerConsumed;
        public float defenseScore;
        public long capturedAtTick;
        public long sampledAtTick;
        public String phase = "";
        public String planetName = "";
        public String destinationSector = "";
        public boolean legacyLaunchPads;
        public boolean attacked;
        public boolean hasSpawns;
        /** Shared-authoritative presentation metadata mirrored from the Action world. */
        public String displayName = "";
        public String icon = "";
        public String contentIcon = "";
        public ObjectSet<String> resources = new ObjectSet<>();
        public boolean hasEnemyBase;
        public float threat;
        public float minutesCaptured;
        public ObjectMap<String, Integer> items = new ObjectMap<>();
        public ObjectMap<String, Float> productionPerSecond = new ObjectMap<>();
        public ObjectMap<String, Float> exportPerSecond = new ObjectMap<>();
        public ObjectMap<String, Float> importPerSecond = new ObjectMap<>();

        /**
         * Returns the small strategic view that may be duplicated in Action/Sector UI state and sent to campaign
         * clients. Large persisted world payloads belong to the storage layer, not this strategic summary.
         */
        public SectorSummary strategicCopy(){
            SectorSummary copy = new SectorSummary();
            copy.wave = wave; copy.winWave = winWave; copy.waves = waves; copy.attackMode = attackMode;
            copy.coreCount = coreCount; copy.unitCount = unitCount; copy.enemyCount = enemyCount; copy.storageCapacity = storageCapacity; copy.coreType = coreType;
            copy.powerProduced = powerProduced; copy.powerConsumed = powerConsumed; copy.defenseScore = defenseScore;
            copy.capturedAtTick = capturedAtTick; copy.sampledAtTick = sampledAtTick; copy.phase = phase;
            copy.planetName = planetName; copy.destinationSector = destinationSector; copy.legacyLaunchPads = legacyLaunchPads;
            copy.attacked = attacked; copy.hasSpawns = hasSpawns;
            copy.displayName = displayName; copy.icon = icon; copy.contentIcon = contentIcon; copy.resources.addAll(resources); copy.hasEnemyBase = hasEnemyBase; copy.threat = threat; copy.minutesCaptured = minutesCaptured;
            copy.items.putAll(items); copy.productionPerSecond.putAll(productionPerSecond); copy.exportPerSecond.putAll(exportPerSecond); copy.importPerSecond.putAll(importPerSecond);
            return copy;
        }
    }

    public static class MissionState{
        public String missionId = "";
        public String planetName = "";
        public String sectorName = "";
        public String definitionVersion = "vanilla-1";
        public String definitionFingerprint = "";
        public String attemptId = "";
        public MissionStatus status = MissionStatus.locked;
        public String phase = "";
        public long actionTick;
        public long eventSequence;
        public ObjectMap<String, ObjectiveState> objectives = new ObjectMap<>();
        public ObjectSet<String> signals = new ObjectSet<>();
        public ObjectSet<String> completedWorldEvents = new ObjectSet<>();
        public Seq<MissionEvent> eventJournal = new Seq<>();
    }

    public enum MissionStatus{locked, available, preparing, running, paused, failed, completed, incompatible}

    public static class ObjectiveState{
        public String objectiveId = "";
        public ObjectiveStatus status = ObjectiveStatus.locked;
        public long progress;
        public long requiredProgress;
        public long activatedAtTick = -1;
        public long completedAtTick = -1;
        public long completionEventSequence;
    }

    public enum ObjectiveStatus{locked, active, completed, cancelled}

    public static class MissionEvent{
        public long sequence;
        public String eventId = "";
        public String type = "";
        public long actionTick;
        public String payload = "";
    }

    public static class ResearchState{
        public String contentName = "";
        public ObjectMap<String, Integer> required = new ObjectMap<>();
        public ObjectMap<String, Integer> contributed = new ObjectMap<>();
        public long startedAtRevision = -1;
        public long completedAtRevision = -1;

        public int remaining(String itemName){
            return Math.max(0, required.get(itemName, 0) - contributed.get(itemName, 0));
        }

        public boolean complete(){
            for(ObjectMap.Entry<String, Integer> entry : required){
                if(contributed.get(entry.key, 0) < entry.value) return false;
            }
            return true;
        }

        public void validate(){
            if(contentName == null || contentName.isBlank()) throw new IllegalStateException("Research content name is required");
            for(ObjectMap.Entry<String, Integer> entry : required){
                int contributedAmount = contributed.get(entry.key, 0);
                if(entry.value < 0 || contributedAmount < 0 || contributedAmount > entry.value){
                    throw new IllegalStateException("Invalid research progress for " + contentName + "/" + entry.key);
                }
            }
        }
    }


    public static class ResearchTransaction{
        public String transactionId = "";
        public String actorId = "";
        public String contentName = "";
        public ResearchTransactionStatus status = ResearchTransactionStatus.preparing;
        public long createdAt;
        public long updatedAt;
        public ObjectMap<String, ResearchDebit> debits = new ObjectMap<>();
        public ObjectSet<String> preparedActions = new ObjectSet<>();
        public String failureReason = "";

        public void validate(){
            if(transactionId == null || transactionId.isBlank()) throw new IllegalStateException("Research transaction ID is required");
            if(contentName == null || contentName.isBlank()) throw new IllegalStateException("Research transaction content is required");
            if(createdAt <= 0 || updatedAt <= 0) throw new IllegalStateException("Research transaction timestamps are required");
            for(ObjectMap.Entry<String, ResearchDebit> entry : debits){
                if(entry.key == null || !entry.key.equals(entry.value.sectorName)) throw new IllegalStateException("Invalid research debit key");
                entry.value.validate();
            }
        }
    }

    public enum ResearchTransactionStatus{preparing, committed, aborted}

    public static class ResearchDebit{
        public String sectorName = "";
        /** Empty for coordinator-owned suspended sectors; otherwise the live action that must prepare the debit. */
        public String actionId = "";
        public ObjectMap<String, Integer> items = new ObjectMap<>();

        public void validate(){
            if(sectorName == null || sectorName.isBlank()) throw new IllegalStateException("Research debit sector is required");
            for(ObjectMap.Entry<String, Integer> entry : items){
                if(entry.key == null || entry.key.isBlank() || entry.value == null || entry.value <= 0) throw new IllegalStateException("Invalid research debit item");
            }
        }
    }


    public static class LaunchTransaction{
        public String transactionId = "";
        public String actionId = "";
        public String actorId = "";
        public String originSector = "";
        /** Empty when the source is coordinator-owned/suspended; otherwise the action that reserved the debit. */
        public String sourceActionId = "";
        public ObjectMap<String, Integer> costs = new ObjectMap<>();
        public LaunchTransactionStatus status = LaunchTransactionStatus.preparing;
        public long createdAt;
        public long updatedAt;
        public String failureReason = "";

        public void validate(){
            if(transactionId == null || transactionId.isBlank()) throw new IllegalStateException("Launch transaction ID is required");
            if(actionId == null || actionId.isBlank()) throw new IllegalStateException("Launch transaction action is required");
            if(originSector == null) throw new IllegalStateException("Launch origin may not be null");
            if(createdAt <= 0 || updatedAt <= 0) throw new IllegalStateException("Launch transaction timestamps are required");
            for(ObjectMap.Entry<String, Integer> entry : costs){
                if(entry.key == null || entry.key.isBlank() || entry.value == null || entry.value <= 0) throw new IllegalStateException("Invalid launch debit item");
            }
        }
    }

    public enum LaunchTransactionStatus{preparing, prepared, committed, aborted}


    public static class TransportTransaction{
        public String transactionId = "";
        public String orderId = "";
        public String actionId = "";
        public String itemName = "";
        public int requested;
        public int applied;
        public TransportTransactionStatus status = TransportTransactionStatus.preparing;
        public long createdAt;
        public long updatedAt;
        public String failureReason = "";

        public void validate(){
            if(transactionId == null || transactionId.isBlank()) throw new IllegalStateException("Transport transaction ID is required");
            if(orderId == null || orderId.isBlank() || actionId == null || actionId.isBlank()) throw new IllegalStateException("Transport transaction routing is required");
            if(itemName == null || itemName.isBlank() || requested <= 0 || applied < 0 || applied > requested) throw new IllegalStateException("Invalid transport transaction amount");
            if(createdAt <= 0 || updatedAt <= 0) throw new IllegalStateException("Transport transaction timestamps are required");
        }
    }

    public enum TransportTransactionStatus{preparing, committed, acknowledged, aborted}

    public static class TransportOrder{
        public String orderId = UUID.randomUUID().toString();
        public String sourceSector = "";
        public String destinationSector = "";
        public String itemName = "";
        public int amount;
        /** Amount already removed from the source and committed to transit. */
        public int loaded;
        public int delivered;
        public long createdAt;
        public long etaCampaignTick;
        public TransportStatus status = TransportStatus.queued;

        public void validate(){
            if(orderId == null || orderId.isBlank()) throw new IllegalStateException("orderId required");
            if(sourceSector.equals(destinationSector)) throw new IllegalStateException("Transport source and destination must differ");
            if(amount < 0 || loaded < 0 || loaded > amount || delivered < 0 || delivered > loaded) throw new IllegalStateException("Invalid transport amount");
        }
    }

    public enum TransportStatus{queued, inTransit, delivered, cancelled}

    public static class CampaignEvent{
        public long sequence;
        public long timestamp;
        public String type = "";
        public String subjectId = "";
        public String payload = "";
    }
}
