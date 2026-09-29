package mindustry.campaign.shared.runtime;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.io.*;
import mindustry.runtime.*;

import java.io.*;
import java.util.*;

/**
 * Owns one authoritative Shared Campaign lifetime: durable store, coordinator, restore points and cold host migration.
 * This class is deliberately UI-independent and contains no MindustryY product services.
 */
public final class SharedCampaignAuthority implements Closeable{
    private final GameContext owner;
    private final Fi campaignDirectory;
    private final String localHostId;
    private final String advertisedHost;
    private final int actionControlPort;
    private final int publicEntryPort;
    private final Fi modsSource;
    private final SharedCampaignMissionRegistry missions;
    private final SharedCampaignPlanetRegistry policies;
    private final Seq<SharedCampaignExtension> extensions;
    private final SharedCampaignApi api;
    private final ActionControlPlane.LossPolicy lossPolicy;
    private final SectorRuntimeFactory runtimeFactory;
    private SharedCampaignStore store;
    private SharedCampaignCoordinator coordinator;
    private SharedCampaignBackupService backups;
    private final Object extensionLifecycleLock = new Object();
    private final ObjectMap<String, SharedCampaignState.ActionStatus> extensionActionStatuses = new ObjectMap<>();
    private volatile boolean extensionLifecycleActive;
    private volatile String extensionCampaignId = "";

    public SharedCampaignAuthority(GameContext owner, Fi campaignDirectory, String localHostId, String advertisedHost,
                                   int actionControlPort, int publicEntryPort, Fi modsSource,
                                   SharedCampaignMissionRegistry missions, SharedCampaignPlanetRegistry policies,
                                   Seq<SharedCampaignExtension> extensions, SharedCampaignApi api, ActionControlPlane.LossPolicy lossPolicy,
                                   SectorRuntimeFactory runtimeFactory){
        this.owner = Objects.requireNonNull(owner, "owner");
        this.campaignDirectory = Objects.requireNonNull(campaignDirectory, "campaignDirectory");
        this.localHostId = requireIdentity(localHostId, "local host identity");
        this.advertisedHost = advertisedHost == null || advertisedHost.isBlank() ? "127.0.0.1" : advertisedHost.trim();
        this.actionControlPort = actionControlPort;
        this.publicEntryPort = publicEntryPort;
        this.modsSource = modsSource;
        this.missions = Objects.requireNonNull(missions, "missions");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.extensions = extensions == null ? new Seq<>() : extensions.copy();
        this.api = Objects.requireNonNull(api, "api");
        this.lossPolicy = lossPolicy == null ? action -> false : lossPolicy;
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
    }

    /**
     * Creates a brand-new clean Shared Campaign in this authority directory, then opens it. The durable campaign
     * identity is committed before any coordinator sockets are started, so a later startup failure leaves a valid
     * reopenable campaign rather than a half-created in-memory authority.
     */
    public synchronized SharedCampaignState createNew(SharedCampaignCreationOptions options){
        if(store != null) throw new IllegalStateException("Shared Campaign authority is already open");
        SharedCampaignCreationOptions creation = (options == null ? new SharedCampaignCreationOptions() : options).validate(policies);
        if(creation.origin == SharedCampaignState.CampaignOrigin.backupRestore){
            throw new IllegalArgumentException("Backup restoration must install the durable campaign before opening authority");
        }
        if(!creation.ownerId.equals(localHostId)){
            throw new IllegalArgumentException("Initial campaign authority host must match the owner identity; migrate authority after creation if needed");
        }
        if(campaignDirectory.exists() && campaignDirectory.list().length > 0){
            throw new IllegalStateException("New Shared Campaign directory must be empty: " + campaignDirectory);
        }

        SharedContentFingerprint.Fingerprints fingerprints = SharedContentFingerprint.calculateBoth();

        boolean initialized = false;
        try(SharedCampaignStore initial = new SharedCampaignStore(campaignDirectory)){
            initial.open();
            initial.transact(creation.ownerId, "shared-campaign:create", campaign -> {
                campaign.displayName = creation.displayName;
                campaign.ownerId = creation.ownerId;
                campaign.authorityHostId = localHostId;
                campaign.authorityGeneration = 1L;
                campaign.primaryPlanetName = creation.primaryPlanetName;
                campaign.multiFrontEnabled = creation.multiFrontEnabled;
                campaign.maxActiveActions = creation.maxActiveActions;
                campaign.freezeWhenEmpty = creation.freezeWhenEmpty;
                campaign.persistenceProfile = creation.persistenceProfile;
                campaign.invitePolicy = creation.invitePolicy;
                campaign.origin = creation.origin;
                campaign.originDescription = "";
                campaign.contentFingerprint = fingerprints.runtime();
                campaign.contentCompatibilityFingerprint = fingerprints.compatibility();

                SharedCampaignState.MemberState ownerMember = new SharedCampaignState.MemberState();
                ownerMember.memberId = creation.ownerId;
                ownerMember.displayName = creation.ownerDisplayName;
                ownerMember.lastSeenAt = arc.util.Time.millis();
                ownerMember.lastConnectedAt = ownerMember.lastSeenAt;
                campaign.members.put(ownerMember.memberId, ownerMember);

                policies.installInto(campaign, creation.planetPolicyIds);
                installNewExtensions(campaign);
                policies.validateCampaign(campaign);
                validateExtensions(campaign);
                if(creation.origin == SharedCampaignState.CampaignOrigin.singlePlayerImport){
                    if(creation.source != null){
                        SharedCampaignImporter.importDataExport(campaignDirectory, campaign, creation.source,
                            creation.primaryPlanetName, creation.importAllPlanets);
                    }else if(creation.importAllPlanets){
                        SharedCampaignImporter.importAllSupportedPlanets(campaignDirectory, campaign, creation.progressReporter);
                    }else{
                        SharedCampaignImporter.importCurrentProfile(campaignDirectory, campaign, creation.primaryPlanetName, creation.progressReporter);
                    }
                }
                SharedCampaignProgress.applyAutomaticUnlocks(campaign);
            });
            new CoordinatorCredentials(campaignDirectory.child("credentials")).ensureMemberCredential(creation.ownerId);
            initialized = true;
        }catch(Throwable failure){
            if(!initialized && campaignDirectory.exists()) campaignDirectory.deleteDirectory();
            throw failure;
        }
        return open();
    }

    /** Opens an ordinary authority lifetime. Pending migrations are rejected. */
    public synchronized SharedCampaignState open(){ return open(false); }

    /** Opens an imported migration only after validating the durable target-host fence and expiry. */
    public synchronized SharedCampaignState openImportedMigration(){ return open(true); }

    private SharedCampaignState open(boolean acceptMigration){
        if(store != null) throw new IllegalStateException("Shared Campaign authority is already open");
        SharedCampaignStore opened = new SharedCampaignStore(campaignDirectory);
        SharedCampaignCoordinator assembled = null;
        try{
            opened.open();
            SharedCampaignState state = opened.snapshot();
            SharedContentFingerprint.Fingerprints fingerprints = SharedContentFingerprint.calculateBoth();
            if(!contentCompatible(state, fingerprints)){
                throw new SecurityException("Shared Campaign content/mod compatibility fingerprint does not match this installation");
            }
            boolean refreshFingerprint = !Objects.equals(state.contentFingerprint, fingerprints.runtime())
                || !Objects.equals(state.contentCompatibilityFingerprint, fingerprints.compatibility());
            if(state.authorityHostId == null || state.authorityHostId.isBlank()){
                throw new SecurityException("Shared Campaign has no durable authority host identity");
            }
            if(!Objects.equals(localHostId, state.authorityHostId)){
                throw new SecurityException("Campaign authority is fenced to host '" + state.authorityHostId + "', not '" + localHostId + "'");
            }
            if(state.migrationPending){
                if(!acceptMigration) throw new IllegalStateException("Campaign is sealed in a pending host migration");
                if(!Objects.equals(localHostId, state.migrationTargetHostId) || state.migrationExpiresAt < arc.util.Time.millis()){
                    throw new SecurityException("Host migration is not valid for this host or has expired");
                }
                state = opened.transact(localHostId, "shared-campaign:accept-host-migration", campaign -> {
                    campaign.migrationPending = false;
                    campaign.migrationTargetHostId = "";
                    campaign.migrationNonce = "";
                    campaign.migrationExpiresAt = 0L;
                    if(refreshFingerprint){
                        campaign.contentFingerprint = fingerprints.runtime();
                        campaign.contentCompatibilityFingerprint = fingerprints.compatibility();
                    }
                });
            }else if(acceptMigration){
                throw new IllegalStateException("Imported campaign is not pending host migration acceptance");
            }else if(refreshFingerprint){
                state = opened.transact(localHostId, "shared-campaign:refresh-runtime-fingerprint", campaign -> {
                    campaign.contentFingerprint = fingerprints.runtime();
                    campaign.contentCompatibilityFingerprint = fingerprints.compatibility();
                });
            }

            state = reconcileExtensions(opened, state);

            Fi credentials = campaignDirectory.child("credentials");
            CoordinatorCredentials credentialStore = new CoordinatorCredentials(credentials);
            credentialStore.ensureMemberCredential(state.ownerId);
            assembled = new SharedCampaignCoordinator(owner, opened, credentials, campaignDirectory.child("actions"),
                advertisedHost, actionControlPort, publicEntryPort, modsSource, missions, policies, lossPolicy, runtimeFactory);
            assembled.start();
            store = opened;
            coordinator = assembled;
            backups = new SharedCampaignBackupService(opened);
            return opened.snapshot();
        }catch(Throwable failure){
            if(assembled != null){ try{ assembled.close(); }catch(Throwable suppressed){ failure.addSuppressed(suppressed); } }
            try{ opened.close(); }catch(Throwable suppressed){ failure.addSuppressed(suppressed); }
            throw failure;
        }
    }

    /** Activates post-open extension callbacks only after the product service has published this authority. */
    public void activateExtensionLifecycle(){
        SharedCampaignStore current;
        SharedCampaignState snapshot;
        synchronized(this){
            current = requireStore();
            if(extensionLifecycleActive) return;
            snapshot = current.snapshot();
            extensionCampaignId = snapshot.campaignId;
            synchronized(extensionLifecycleLock){
                extensionActionStatuses.clear();
                for(SharedCampaignState.ActionState action : snapshot.actions.values()) extensionActionStatuses.put(action.actionId, action.status);
            }
            extensionLifecycleActive = true;
            current.onCommit(this::notifyExtensionCommit);
        }
        SharedCampaignExtension.SharedCampaignContext context = extensionContext(snapshot.campaignId);
        for(SharedCampaignExtension extension : extensions) safeExtensionCallback(extension, "open", () -> extension.onCampaignOpened(context));
        fireExtensionEvent(new SharedCampaignEvents.CampaignOpened(snapshot.campaignId, true));
    }

    private void notifyExtensionCommit(SharedCampaignStore.Commit commit){
        SharedCampaignCoordinator current;
        synchronized(this){ current = coordinator; }
        if(current != null && current.isRunning()) current.clientControl().publishSnapshot(commit.state());
        if(!extensionLifecycleActive) return;
        String campaignId = commit.state().campaignId;
        SharedCampaignExtension.CampaignCommitEvent event = new SharedCampaignExtension.CampaignCommitEvent(
            campaignId, commit.fromRevision(), commit.toRevision(), commit.mutationType(), commit.actor());
        for(SharedCampaignExtension extension : extensions) safeExtensionCallback(extension, "commit", () -> extension.onCampaignCommitted(event));
        fireExtensionEvent(new SharedCampaignEvents.CampaignCommitted(campaignId, commit.toRevision(), commit.mutationType(), commit.actor()));

        Seq<SharedCampaignExtension.ActionLifecycleEvent> changed = new Seq<>();
        synchronized(extensionLifecycleLock){
            for(SharedCampaignState.ActionState action : commit.state().actions.values()){
                SharedCampaignState.ActionStatus previous = extensionActionStatuses.put(action.actionId, action.status);
                if(previous == action.status) continue;
                changed.add(new SharedCampaignExtension.ActionLifecycleEvent(campaignId, action.actionId, action.planetName, action.sectorName,
                    SharedCampaignExtension.ActionLifecycleEvent.Phase.valueOf(action.status.name()), commit.state().authorityGeneration));
            }
        }
        for(SharedCampaignExtension.ActionLifecycleEvent actionEvent : changed){
            for(SharedCampaignExtension extension : extensions) safeExtensionCallback(extension, "action lifecycle", () -> extension.onActionLifecycle(actionEvent));
            fireExtensionEvent(new SharedCampaignEvents.ActionChanged(campaignId, actionEvent.actionId(), SharedCampaignState.ActionStatus.valueOf(actionEvent.phase().name())));
        }
    }

    private SharedCampaignExtension.SharedCampaignContext extensionContext(String campaignId){
        return new SharedCampaignExtension.SharedCampaignContext(){
            @Override public SharedCampaignApi api(){ return api; }
            @Override public String campaignId(){ return campaignId; }
            @Override public boolean authoritative(){ return true; }
        };
    }

    private void safeExtensionCallback(SharedCampaignExtension extension, String phase, Runnable callback){
        try{ RuntimeContexts.run(owner, callback); }
        catch(Throwable failure){ Log.err("Shared Campaign extension '" + extension.id() + "' " + phase + " callback failed", failure); }
    }

    /** Extension-facing Arc events always belong to this authority's GameContext, even when a durable commit originates
     * from broker/control I/O threads that intentionally have no ambient runtime binding. */
    private void fireExtensionEvent(Object event){
        RuntimeContexts.run(owner, () -> Events.fire(event));
    }

    public synchronized boolean isOpen(){ return store != null; }
    public synchronized SharedCampaignState state(){ return requireStore().snapshot(); }
    public synchronized boolean effectiveUnlocked(String contentName){ return store != null && store.effectiveUnlocked(contentName); }
    public synchronized SharedCampaignCoordinator coordinator(){ if(coordinator == null) throw new IllegalStateException("No authoritative Shared Campaign is open"); return coordinator; }
    public synchronized SharedCampaignBackupService backups(){ if(backups == null) throw new IllegalStateException("No authoritative Shared Campaign is open"); return backups; }

    public synchronized SharedCampaignState.BackupState createRestorePoint(String actorId, String name, String reason, int keep){
        // A restore point must describe the worlds that are live at the moment the operator requested it, not whatever
        // autosave happened to be on disk several minutes earlier. Force every connected RUNNING Action through its
        // durable save barrier first, then drain any coalesced strategic WAL before streaming the archive. If any live
        // Action cannot checkpoint, fail closed instead of publishing a deceptively complete restore point.
        coordinator().actionCommands().forceSaveRunningActions(actorId);
        requireStore().flushCoalescedIfNeeded();
        return backups().create(actorId, name, reason, keep);
    }

    /** Restores only while this authority object is closed; restoration never auto-opens the result. */
    public synchronized void restoreBackup(Fi archive){
        if(store != null) throw new IllegalStateException("Close the Shared Campaign before restoring a backup");
        SharedCampaignBackupService.restoreArchive(archive, campaignDirectory);
    }

    /**
     * Seals every durable writer, prepares and commits the cold-migration authority fence, then closes the old host.
     * If preparation fails before the fence is committed, normal writes are reopened on the same authority lifetime.
     */
    public synchronized HostMigrationService.MigrationBundle exportMigration(Fi target, String targetHostId, long validForMillis){
        SharedCampaignStore currentStore = requireStore();
        SharedCampaignCoordinator currentCoordinator = coordinator();
        currentStore.sealMutationsAndAwaitQuiescence(Math.max(5_000L,
            Long.getLong("mindustry.sharedCampaign.migrationQuiesceTimeoutMillis", 30_000L)));
        HostMigrationService.MigrationBundle bundle;
        try{
            bundle = new HostMigrationService(currentStore).exportBundle(target, localHostId, targetHostId, validForMillis);
        }catch(Throwable failure){
            // exportBundle removes its recovery artifact if it failed before the authority fence commit. Only this
            // pre-fence failure path may resume admission. Once exportBundle returns, old-host authority is durably gone.
            currentStore.reopenMutations();
            if(currentCoordinator != coordinator) throw new IllegalStateException("Shared Campaign authority changed while exporting migration", failure);
            throw failure;
        }
        close();
        return bundle;
    }

    public synchronized HostMigrationService.MigrationBundle recoverPendingMigration(){
        if(store != null) throw new IllegalStateException("Close the Shared Campaign before recovering a migration transfer");
        return HostMigrationService.recoverPendingBundle(campaignDirectory);
    }

    public synchronized HostMigrationService.MigrationBundle reissuePendingMigration(Fi target, long validForMillis){
        if(store != null) throw new IllegalStateException("Close the Shared Campaign before reissuing a migration transfer");
        return HostMigrationService.reissuePendingBundle(campaignDirectory, target, validForMillis);
    }

    /** Imports into this authority's directory, then accepts the target-host fence and starts the coordinator. */
    public synchronized SharedCampaignState importMigration(Fi bundle, String transferCode){
        if(store != null) throw new IllegalStateException("Close the Shared Campaign before importing a migration");
        HostMigrationService.importBundle(bundle, campaignDirectory, localHostId, transferCode);
        return openImportedMigration();
    }

    @Override public synchronized void close(){
        SharedCampaignCoordinator closingCoordinator = coordinator;
        SharedCampaignStore closingStore = store;
        if(closingStore != null && extensionLifecycleActive){
            extensionLifecycleActive = false;
            closingStore.onCommit(null);
            String campaignId = extensionCampaignId;
            SharedCampaignExtension.SharedCampaignContext context = extensionContext(campaignId);
            for(SharedCampaignExtension extension : extensions) safeExtensionCallback(extension, "close", () -> extension.onCampaignClosed(context));
            fireExtensionEvent(new SharedCampaignEvents.CampaignClosed(campaignId));
            synchronized(extensionLifecycleLock){ extensionActionStatuses.clear(); }
            extensionCampaignId = "";
        }
        coordinator = null;
        store = null;
        backups = null;
        if(closingCoordinator != null){ try{ closingCoordinator.close(); }catch(Throwable ignored){} }
        if(closingStore != null) closingStore.close();
    }

    public synchronized SharedCampaignState transactExtension(String actorId, String extensionId, String mutationType, java.util.function.UnaryOperator<byte[]> mutation){
        SharedCampaignExtension extension = extension(extensionId);
        Objects.requireNonNull(mutation, "mutation");
        return requireStore().transact(actorId, mutationType, state -> {
            byte[] current = state.extensionData.get(extensionId);
            byte[] input = current == null ? new byte[0] : Arrays.copyOf(current, current.length);
            byte[] output = mutation.apply(input);
            state.extensionData.put(extensionId, output == null ? new byte[0] : Arrays.copyOf(output, output.length));
            extension.validateCampaign(state);
        });
    }

    private SharedCampaignExtension extension(String id){
        for(SharedCampaignExtension extension : extensions) if(Objects.equals(extension.id(), id)) return extension;
        throw new IllegalArgumentException("Shared Campaign extension is not registered: " + id);
    }

    private void installNewExtensions(SharedCampaignState state){
        for(SharedCampaignExtension extension : extensions){
            state.extensionSchemas.put(extension.id(), extension.schemaVersion());
            state.extensionRequired.put(extension.id(), extension.requiredForLoad());
            state.extensionCompatibility.put(extension.id(), extension.compatibilityId());
            state.extensionData.put(extension.id(), new byte[0]);
        }
    }

    private SharedCampaignState reconcileExtensions(SharedCampaignStore opened, SharedCampaignState state){
        ObjectMap<String, SharedCampaignExtension> installed = new ObjectMap<>();
        for(SharedCampaignExtension extension : extensions) installed.put(extension.id(), extension);
        boolean changed = false;
        for(ObjectMap.Entry<String, Integer> entry : state.extensionSchemas){
            SharedCampaignExtension extension = installed.get(entry.key);
            if(extension == null){
                if(state.extensionRequired.get(entry.key, true)) throw new IllegalStateException("Required Shared Campaign extension is missing: " + entry.key);
                continue;
            }
            if(entry.value > extension.schemaVersion()) throw new IllegalStateException("Shared Campaign extension " + entry.key + " requires newer schema " + entry.value);
            String compatibility = state.extensionCompatibility.get(entry.key, "");
            if(!compatibility.isBlank() && !Objects.equals(compatibility, extension.compatibilityId()))
                throw new IllegalStateException("Shared Campaign extension compatibility mismatch: " + entry.key);
            changed |= entry.value < extension.schemaVersion() || compatibility.isBlank()
                || state.extensionRequired.get(entry.key, true) != extension.requiredForLoad();
        }
        for(SharedCampaignExtension extension : extensions) if(!state.extensionSchemas.containsKey(extension.id())) changed = true;
        if(!changed){ validateExtensions(state); return state; }
        return opened.transact(localHostId, "shared-campaign:reconcile-extensions", campaign -> {
            for(SharedCampaignExtension extension : extensions){
                int from = campaign.extensionSchemas.get(extension.id(), 0);
                byte[] data = campaign.extensionData.get(extension.id());
                if(from > 0 && from < extension.schemaVersion()) data = migrate(extension, from, extension.schemaVersion(), data);
                campaign.extensionSchemas.put(extension.id(), extension.schemaVersion());
                campaign.extensionRequired.put(extension.id(), extension.requiredForLoad());
                campaign.extensionCompatibility.put(extension.id(), extension.compatibilityId());
                campaign.extensionData.put(extension.id(), data == null ? new byte[0] : data);
            }
            validateExtensions(campaign);
        });
    }

    private byte[] migrate(SharedCampaignExtension extension, int from, int to, byte[] input){
        final byte[][] output = {input == null ? new byte[0] : Arrays.copyOf(input, input.length)};
        extension.migrate(new SharedCampaignExtension.ExtensionMigration(){
            @Override public String extensionId(){ return extension.id(); }
            @Override public int fromVersion(){ return from; }
            @Override public int toVersion(){ return to; }
            @Override public byte[] input(){ return Arrays.copyOf(output[0], output[0].length); }
            @Override public void output(byte[] data){ output[0] = data == null ? new byte[0] : Arrays.copyOf(data, data.length); }
        });
        return output[0];
    }

    private void validateExtensions(SharedCampaignState state){
        for(SharedCampaignExtension extension : extensions) extension.validateCampaign(state);
    }

    private static boolean contentCompatible(SharedCampaignState state, SharedContentFingerprint.Fingerprints current){
        String durable = state.contentCompatibilityFingerprint == null ? "" : state.contentCompatibilityFingerprint;
        if(Objects.equals(durable, current.compatibility())) return true;
        if(durable.startsWith("compat-v1:") && Objects.equals(durable, SharedContentFingerprint.calculateLegacyMdtYCompatibilityV1())) return true;
        if(!durable.isBlank()) return false;

        String strict = state.contentFingerprint == null ? "" : state.contentFingerprint;
        if(Objects.equals(strict, current.runtime())) return true;
        if(strict.startsWith("runtime-v3:") && Objects.equals(strict, SharedContentFingerprint.calculateLegacyMdtYRuntimeV3())) return true;
        if(strict.startsWith("v2:") && Objects.equals(strict, SharedContentFingerprint.calculateLegacyV2())) return true;
        return SharedContentFingerprint.isLegacyV1(strict) && Objects.equals(strict, SharedContentFingerprint.calculateLegacyV1());
    }

    private SharedCampaignStore requireStore(){ if(store == null) throw new IllegalStateException("No authoritative Shared Campaign is open"); return store; }
    private static String requireIdentity(String value, String label){
        if(value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }
}
