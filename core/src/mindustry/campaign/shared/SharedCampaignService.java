package mindustry.campaign.shared;

import arc.files.*;
import arc.struct.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.mission.*;
import mindustry.campaign.shared.net.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.runtime.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Clean product-level Shared Campaign lifecycle for one Mindustry {@link GameContext}. */
public final class SharedCampaignService implements Closeable, SharedCampaignApi{
    private final GameContext owner;
    private final Fi modsSource;
    private final SharedCampaignMissionRegistry missions = new SharedCampaignMissionRegistry();
    private final SharedCampaignUiRegistry uiExtensions = new SharedCampaignUiRegistry();
    private final SharedCampaignPlanetRegistry planetPolicies = new SharedCampaignPlanetRegistry();
    private final ObjectMap<String, SharedCampaignExtension> registeredExtensions = new ObjectMap<>();
    private boolean registriesSealed;
    private volatile SectorRuntimeFactory runtimeFactory = SectorRuntimeFactory.jvmProcess();
    private volatile ActionControlPlane.LossPolicy lossPolicy = action -> false;
    private SharedCampaignAuthority authority;
    private SharedCampaignClient client;
    private SharedCampaignState remoteSnapshot;
    /** Cached effective unlock names from the last accepted authoritative snapshot. */
    private ObjectSet<String> sharedUnlockCache = new ObjectSet<>();
    /** Explicit research must remain distinct from discovered/produced unlocks for ResearchObjective semantics. */
    private ObjectSet<String> sharedResearchedCache = new ObjectSet<>();
    private ObjectSet<String> sharedDiscoveredCache = new ObjectSet<>();
    /** Fences callbacks from a client that was replaced while its receiver thread was still winding down. */
    private long clientSessionGeneration;

    public SharedCampaignService(GameContext owner, Fi modsSource){
        this.owner = Objects.requireNonNull(owner, "owner");
        this.modsSource = modsSource;
        VanillaPlanetPolicies.register(planetPolicies);
    }

    public static SharedCampaignService install(GameContext owner, Fi modsSource){
        SharedCampaignRuntimeState runtime = SharedCampaignRuntimeState.install(owner);
        SharedCampaignService existing = runtime.component(SharedCampaignService.class);
        if(existing != null) return existing;
        return runtime.attach(SharedCampaignService.class, new SharedCampaignService(owner, modsSource));
    }

    /** Returns the Shared Campaign service already attached to this context without creating product state. */
    public static SharedCampaignService find(GameContext owner){
        SharedCampaignRuntimeState runtime = SharedCampaignRuntimeState.find(owner);
        return runtime == null ? null : runtime.component(SharedCampaignService.class);
    }

    public synchronized void runtimeFactory(SectorRuntimeFactory factory){ requireInactive(); runtimeFactory = Objects.requireNonNull(factory, "runtime factory"); }
    public synchronized void lossPolicy(ActionControlPlane.LossPolicy policy){ requireInactive(); lossPolicy = policy == null ? action -> false : policy; }
    public SharedCampaignMissionRegistry missions(){ ensureVanillaMissions(); return missions; }
    @Override public SharedCampaignMissionRegistry missionDefinitions(){ return missions(); }
    @Override public SharedCampaignUiRegistry uiExtensions(){ return uiExtensions; }
    @Override public SharedCampaignPlanetRegistry planetPolicies(){ return planetPolicies; }
    public GameContext owner(){ return owner; }

    @Override public synchronized void register(SharedCampaignExtension extension){
        requireInactive();
        if(registriesSealed) throw new IllegalStateException("Shared Campaign extension registry is sealed");
        Objects.requireNonNull(extension, "extension");
        String id = extension.id() == null ? "" : extension.id().trim();
        if(id.isBlank() || !id.contains(":")) throw new IllegalArgumentException("Shared Campaign extension ID must be namespaced");
        if(extension.schemaVersion() < 1) throw new IllegalArgumentException("Shared Campaign extension schema must be positive");
        if(extension.minimumApiVersion() > SharedCampaignApi.apiVersion || extension.maximumApiVersion() < SharedCampaignApi.apiVersion)
            throw new IllegalArgumentException("Shared Campaign extension " + id + " is incompatible with API " + SharedCampaignApi.apiVersion);
        if(extension.compatibilityId() == null || extension.compatibilityId().isBlank()) throw new IllegalArgumentException("Shared Campaign extension compatibility identity is required");
        if(registeredExtensions.containsKey(id)) throw new IllegalArgumentException("Duplicate Shared Campaign extension " + id);
        registeredExtensions.put(id, extension);
        extension.registerMissionDefinitions((missionId, version, definition) -> missions.register(id, missionId, version, new SharedCampaignMissionRegistry.MissionSpec(){
            @Override public String compatibilityId(){ return definition.compatibilityId(); }
            @Override public String planetName(){ return definition.planetName(); }
            @Override public String sectorName(){ return definition.sectorName(); }
            @Override public String displayName(){ return definition.displayName(); }
            @Override public boolean freezeWhenEmpty(){ return definition.freezeWhenEmpty(); }
        }));
    }

    @Override public synchronized Seq<SharedCampaignExtension> extensions(){
        Seq<SharedCampaignExtension> result = registeredExtensions.values().toSeq();
        result.sort(Comparator.comparing(SharedCampaignExtension::id));
        return result;
    }

    @Override public synchronized byte[] extensionState(String extensionId){
        byte[] data = state().extensionData.get(extensionId);
        return data == null ? new byte[0] : Arrays.copyOf(data, data.length);
    }

    @Override public synchronized SharedCampaignState transactExtension(String actorId, String extensionId, String mutationType, java.util.function.UnaryOperator<byte[]> mutation){
        if(!registeredExtensions.containsKey(extensionId)) throw new IllegalArgumentException("Shared Campaign extension is not registered: " + extensionId);
        if(mutationType == null || mutationType.isBlank() || !mutationType.contains(":") || mutationType.startsWith("mindustry-y:") || mutationType.startsWith("shared-campaign:"))
            throw new IllegalArgumentException("Extension mutation type must use an extension-owned namespace");
        return authority().transactExtension(actorId, extensionId, mutationType, mutation);
    }

    public synchronized SharedCampaignState createLocal(Fi directory, SharedCampaignCreationOptions options,
                                                        String advertisedHost, int actionControlPort, int publicEntryPort){
        closeCurrent();
        SharedCampaignCreationOptions creation = validateCreation(options);
        if(creation.origin == SharedCampaignState.CampaignOrigin.backupRestore){
            SharedCampaignBackupService.restoreArchive(creation.source, directory);
            SharedCampaignState restored = readRestoredSnapshot(directory);
            if(restored.authorityHostId == null || restored.authorityHostId.isBlank())
                throw new SecurityException("Restored Shared Campaign has no durable authority host identity");
            SharedCampaignAuthority opened = newAuthority(directory, restored.authorityHostId, advertisedHost, actionControlPort, publicEntryPort);
            try{
                SharedCampaignState state = opened.open();
                authority = opened;
                opened.activateExtensionLifecycle();
                markLastOpened(directory);
                return SharedCampaignStateCopy.copy(state);
            }catch(Throwable failure){ opened.close(); throw failure; }
        }
        SharedCampaignAuthority opened = newAuthority(directory, creation.ownerId, advertisedHost, actionControlPort, publicEntryPort);
        try{
            SharedCampaignState state = opened.createNew(creation);
            authority = opened;
            opened.activateExtensionLifecycle();
            markLastOpened(directory);
            return SharedCampaignStateCopy.copy(state);
        }catch(Throwable failure){ opened.close(); throw failure; }
    }

    public synchronized SharedCampaignState openLocal(Fi directory, String localHostId, String advertisedHost,
                                                      int actionControlPort, int publicEntryPort){
        closeCurrent();
        SharedCampaignAuthority opened = newAuthority(directory, localHostId, advertisedHost, actionControlPort, publicEntryPort);
        try{
            SharedCampaignState state = opened.open(); authority = opened; opened.activateExtensionLifecycle();
            markLastOpened(directory);
            return SharedCampaignStateCopy.copy(state);
        }catch(Throwable failure){ opened.close(); throw failure; }
    }

    public synchronized SharedCampaignState importMigration(Fi bundle, Fi destination, String targetHostId,
                                                            String transferCode, String advertisedHost,
                                                            int actionControlPort, int publicEntryPort){
        closeCurrent();
        SharedCampaignAuthority imported = newAuthority(destination, targetHostId, advertisedHost, actionControlPort, publicEntryPort);
        try{
            SharedCampaignState state = imported.importMigration(bundle, transferCode); authority = imported; imported.activateExtensionLifecycle();
            markLastOpened(destination);
            return SharedCampaignStateCopy.copy(state);
        }catch(Throwable failure){ imported.close(); throw failure; }
    }

    /** Sidecar marker for the local campaign list; never part of the durable snapshot schema. */
    private static void markLastOpened(Fi directory){
        try{
            directory.child("last-opened").writeString(Long.toString(System.currentTimeMillis()));
        }catch(Throwable ignored){
        }
    }

    public SharedCampaignState connect(String host, int port, String inviteCode, String memberHint) throws IOException{
        // Enrollment, transport setup and the first snapshot can all block. Prove the replacement connection before
        // touching the currently open Campaign, and never hold the service monitor across those network waits.
        return installRemoteClient(new SharedCampaignClient(host, port, inviteCode, memberHint));
    }

    public SharedCampaignState connect(String host, int port, SharedCampaignClient.Credential credential) throws IOException{
        return installRemoteClient(new SharedCampaignClient(host, port, credential));
    }

    private SharedCampaignState installRemoteClient(SharedCampaignClient connected) throws IOException{
        boolean installed = false;
        try{
            SharedCampaignState snapshot = connected.snapshot();
            synchronized(this){
                // A failed candidate handshake leaves the current Campaign intact. Only a proven client replaces it.
                closeCurrent();
                client = connected;
                long generation = ++clientSessionGeneration;
                installRemoteSnapshotListenerLocked(connected, generation, snapshot.campaignId);
                installed = true;
            }
            seedRemoteSnapshot(snapshot);
            return SharedCampaignStateCopy.copy(snapshot);
        }catch(Throwable failure){
            if(!installed) connected.close();
            if(failure instanceof IOException io) throw io;
            if(failure instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Failed to connect Shared Campaign client", failure);
        }
    }

    /** Initial successful handshakes must make state() usable before connect()/controlClient() returns. */
    private void seedRemoteSnapshot(SharedCampaignState snapshot){
        if(snapshot == null) return;
        SharedCampaignState owned = SharedCampaignStateCopy.copy(snapshot);
        boolean changed, unlocksChanged;
        synchronized(this){
            unlocksChanged = unlockSetChangedLocked(owned);
            changed = acceptRemoteSnapshotOwnedLocked(owned);
        }
        if(changed) fireSnapshotUpdatedOnOwner(owned.campaignId, owned.revision, unlocksChanged);
    }

    private void fireSnapshotUpdatedOnOwner(String campaignId, long revision, boolean unlocksChanged){
        Runnable fire = () -> {
            projectAuthoritativeUnlocksIntoLiveAction();
            if(unlocksChanged) arc.Events.fire(new SharedCampaignEvents.CampaignUnlocksChanged(campaignId, revision));
            arc.Events.fire(new SharedCampaignEvents.CampaignSnapshotUpdated(campaignId, revision));
        };
        boolean ownerLane = RuntimeContexts.bound() == owner &&
            (owner != RuntimeContexts.primary() || arc.Core.app == null || arc.Core.app.isOnMainThread());
        if(ownerLane) fire.run();
        else{
            try{ RuntimeContexts.post(owner, fire); }
            catch(java.util.concurrent.RejectedExecutionException ignored){}
        }
    }

    /** Accepts only monotonic authoritative revisions; worker/transport threads hand state application to the owner lane. */
    private void acceptRemoteSnapshot(SharedCampaignState snapshot){
        if(snapshot == null) return;
        // Copy before crossing lanes; the transport/caller retains ownership of its decoded/result object.
        SharedCampaignState owned = SharedCampaignStateCopy.copy(snapshot);
        Runnable apply = () -> applyRemoteSnapshotOwned(owned);
        boolean ownerLane = RuntimeContexts.bound() == owner &&
            (owner != RuntimeContexts.primary() || arc.Core.app == null || arc.Core.app.isOnMainThread());
        if(ownerLane) apply.run();
        else{
            try{ RuntimeContexts.post(owner, apply); }
            catch(java.util.concurrent.RejectedExecutionException ignored){}
        }
    }

    private void applyRemoteSnapshotOwned(SharedCampaignState owned){
        boolean changed, unlocksChanged;
        synchronized(this){
            unlocksChanged = unlockSetChangedLocked(owned);
            changed = acceptRemoteSnapshotOwnedLocked(owned);
        }
        if(changed) fireSnapshotUpdatedOnOwner(owned.campaignId, owned.revision, unlocksChanged);
    }

    private void applyRemoteSnapshotOwned(SharedCampaignClient source, long generation, String campaignId, SharedCampaignState owned){
        boolean changed, unlocksChanged;
        synchronized(this){
            if(client != source || clientSessionGeneration != generation) return;
            if(owned == null || !Objects.equals(campaignId, owned.campaignId)) return;
            unlocksChanged = unlockSetChangedLocked(owned);
            changed = acceptRemoteSnapshotOwnedLocked(owned);
        }
        // The remote cache is ordinary synchronized product state and must advance as soon as the authenticated
        // reader receives a newer revision. Only Arc Events are GameContext-affine. Posting the cache mutation itself
        // to the owner lane can leave headless/server clients permanently stale when no UI loop is pumping that queue.
        if(changed) fireSnapshotUpdatedOnOwner(owned.campaignId, owned.revision, unlocksChanged);
    }

    /** Caller holds this service monitor and owns the supplied deep copy. */
    private boolean acceptRemoteSnapshotOwnedLocked(SharedCampaignState owned){
        if(owned == null) return false;
        long previousRevision = remoteSnapshot == null || !Objects.equals(remoteSnapshot.campaignId, owned.campaignId) ? -1L : remoteSnapshot.revision;
        if(previousRevision >= 0L && owned.revision < previousRevision) return false;
        remoteSnapshot = owned;
        ObjectSet<String> researched = new ObjectSet<>();
        researched.addAll(owned.researched);
        sharedResearchedCache = researched;
        ObjectSet<String> discovered = new ObjectSet<>();
        discovered.addAll(owned.discovered);
        sharedDiscoveredCache = discovered;
        ObjectSet<String> unlocks = new ObjectSet<>();
        unlocks.addAll(researched);
        unlocks.addAll(discovered);
        sharedUnlockCache = unlocks;
        return owned.revision != previousRevision;
    }

    /** Caller holds this service monitor. */
    private boolean unlockSetChangedLocked(SharedCampaignState owned){
        if(owned == null) return false;
        ObjectSet<String> unlocks = new ObjectSet<>();
        unlocks.addAll(owned.researched);
        unlocks.addAll(owned.discovered);
        return !sameStrings(sharedUnlockCache, unlocks);
    }

    private static boolean sameStrings(ObjectSet<String> left, ObjectSet<String> right){
        if(left == right) return true;
        if(left == null || right == null || left.size != right.size) return false;
        for(String value : left) if(!right.contains(value)) return false;
        return true;
    }

    /** Installs a callback fenced to the exact logical client generation that registered it. */
    private void installRemoteSnapshotListenerLocked(SharedCampaignClient source, long generation, String campaignId){
        source.onSnapshot(snapshot -> {
            if(snapshot == null || !Objects.equals(campaignId, snapshot.campaignId)) return;
            // Take ownership on the authenticated reader thread and advance the synchronized cache immediately.
            // applyRemoteSnapshotOwned fences replaced client generations; event delivery itself is marshalled back
            // to the owning GameContext by fireSnapshotUpdatedOnOwner().
            applyRemoteSnapshotOwned(source, generation, campaignId, SharedCampaignStateCopy.copy(snapshot));
        });
    }

    public synchronized boolean localAuthorityOpen(){ return authority != null && authority.isOpen(); }
    public synchronized boolean remoteConnected(){ return client != null; }
    public synchronized SharedCampaignAuthority authority(){ if(authority == null || !authority.isOpen()) throw new IllegalStateException("No local Shared Campaign authority is open"); return authority; }
    public synchronized SharedCampaignClient client(){ if(client == null) throw new IllegalStateException("No remote Shared Campaign client is connected"); return client; }
    public SharedCampaignClient controlClient(){
        SharedCampaignAuthority expectedAuthority;
        SharedCampaignClient.Credential credential;
        int publicEntryPort;
        synchronized(this){
            if(client != null) return client;
            if(authority == null || !authority.isOpen()) throw new IllegalStateException("No Shared Campaign is active");
            expectedAuthority = authority;
            SharedCampaignCoordinator coordinator = expectedAuthority.coordinator();
            publicEntryPort = coordinator.publicEntryPort();
            CoordinatorCredentials.MemberCredential member = coordinator.credentials().ensureMemberCredential(expectedAuthority.state().ownerId);
            credential = new SharedCampaignClient.Credential(member.memberId(), member.secret());
        }

        SharedCampaignClient opened = null;
        try{
            // Local control handshakes are still real TCP I/O; do not pin state()/UI callers behind the monitor.
            opened = new SharedCampaignClient("127.0.0.1", publicEntryPort, credential);
            SharedCampaignState snapshot = opened.snapshot();
            SharedCampaignClient installed;
            synchronized(this){
                if(client != null){
                    SharedCampaignClient winner = client;
                    opened.close();
                    return winner;
                }
                if(authority != expectedAuthority || !expectedAuthority.isOpen())
                    throw new IllegalStateException("Shared Campaign changed while the local control client was connecting");
                client = opened;
                long generation = ++clientSessionGeneration;
                installRemoteSnapshotListenerLocked(opened, generation, snapshot.campaignId);
                installed = opened;
                opened = null;
            }
            seedRemoteSnapshot(snapshot);
            return installed;
        }catch(IOException failure){
            throw new UncheckedIOException(failure);
        }finally{
            if(opened != null){
                try{ opened.close(); }catch(IOException ignored){}
            }
        }
    }

    public synchronized SharedCampaignState state(){
        if(authority != null && authority.isOpen()) return SharedCampaignStateCopy.copy(authority.state());
        if(remoteSnapshot != null) return SharedCampaignStateCopy.copy(remoteSnapshot);
        throw new IllegalStateException("No Shared Campaign is active");
    }

    public synchronized String localInviteCode(){ return authority().coordinator().credentials().inviteCode(); }
    public synchronized SharedCampaignClient.Credential localMemberCredential(String memberId){
        CoordinatorCredentials.MemberCredential credential = authority().coordinator().credentials().ensureMemberCredential(memberId);
        return new SharedCampaignClient.Credential(credential.memberId(), credential.secret());
    }
    public synchronized SharedCampaignState.BackupState createRestorePoint(String actorId, String name, String reason, int keep){ return authority().createRestorePoint(actorId, name, reason, keep); }
    public synchronized HostMigrationService.MigrationBundle exportMigration(Fi target, String targetHostId, long validForMillis){
        SharedCampaignAuthority current = authority(); HostMigrationService.MigrationBundle result = current.exportMigration(target, targetHostId, validForMillis); authority = null; return result;
    }
    public synchronized HostMigrationService.MigrationBundle recoverPendingMigration(Fi campaignDirectory){ requireInactive(); return HostMigrationService.recoverPendingBundle(campaignDirectory); }
    public synchronized HostMigrationService.MigrationBundle reissuePendingMigration(Fi campaignDirectory, Fi target, long validForMillis){ requireInactive(); return HostMigrationService.reissuePendingBundle(campaignDirectory, target, validForMillis); }
    public synchronized void restoreBackup(Fi archive, Fi destination){ requireInactive(); SharedCampaignBackupService.restoreArchive(archive, destination); }

    /** Permanently deletes one inactive campaign under the first-party Shared Campaign storage root. */
    public synchronized void deleteLocalCampaign(Fi directory){
        requireInactive();
        if(directory == null) throw new IllegalArgumentException("Campaign directory is required");
        try{
            java.io.File root = mindustry.Vars.dataDirectory.child("shared-campaigns").file().getCanonicalFile();
            java.io.File target = directory.file().getCanonicalFile();
            String prefix = root.getPath() + java.io.File.separator;
            if(target.equals(root) || !target.getPath().startsWith(prefix)) throw new SecurityException("Refusing to delete outside Shared Campaign storage");
        }catch(IOException failure){
            throw new UncheckedIOException("Failed to validate Shared Campaign directory", failure);
        }
        if(!directory.exists()) return;
        if(!directory.isDirectory()) throw new IllegalArgumentException("Shared Campaign path is not a directory");
        directory.deleteDirectory();
        if(directory.exists()) throw new IllegalStateException("Failed to delete Shared Campaign directory: " + directory);
    }

    private static SharedCampaignState readRestoredSnapshot(Fi directory){
        Fi snapshot = directory.child("campaign.mycp");
        if(!snapshot.exists()) throw new IllegalStateException("Restored Shared Campaign has no campaign.mycp snapshot");
        try(InputStream input = new BufferedInputStream(snapshot.read())){
            return SharedCampaignCodec.decode(input);
        }catch(IOException error){
            throw new UncheckedIOException("Failed to read restored Shared Campaign snapshot", error);
        }
    }

    public synchronized void closeCurrent(){
        SharedCampaignClient closingClient = client; SharedCampaignAuthority closingAuthority = authority;
        client = null; remoteSnapshot = null; sharedUnlockCache = new ObjectSet<>(); sharedResearchedCache = new ObjectSet<>(); sharedDiscoveredCache = new ObjectSet<>(); clientSessionGeneration++;
        if(closingClient != null){ try{ closingClient.close(); }catch(IOException ignored){} }
        try{ if(closingAuthority != null) closingAuthority.close(); }finally{ authority = null; }
    }
    @Override public void close(){ closeCurrent(); }



    /**
     * Attempts a same-TCP switch from the currently displayed Shared Action to another live Action.
     * Falls back cleanly when the current connection is not broker-backed or the stream barrier cannot complete.
     */
    public RuntimePayloads.HotSwitchResult hotSwitchRemoteAction(String fromActionId, String toActionId, boolean spectator) throws IOException{
        if(fromActionId == null || fromActionId.isBlank()) throw new IllegalArgumentException("fromActionId is required");
        if(toActionId == null || toActionId.isBlank()) throw new IllegalArgumentException("toActionId is required");

        SharedCampaignClient control = controlClient();
        String networkUuid = mindustry.Vars.platform == null ? "" : mindustry.Vars.platform.getUUID();
        if(networkUuid == null || networkUuid.isBlank()) throw new IOException("Mindustry network UUID is unavailable for action admission");

        SharedCampaignNet sharedNet = SharedCampaignNet.install(owner);
        String activeActionId = sharedNet.activeClientActionId();
        String brokerSessionId = sharedNet.activeBrokerSessionId();
        if(activeActionId == null || activeActionId.isBlank() || !activeActionId.equals(fromActionId)){
            return hotSwitchFallback(brokerSessionId, toActionId, "hot-switch source no longer matches the active Action");
        }
        if(brokerSessionId == null || brokerSessionId.isBlank()){
            return hotSwitchFallback("", toActionId, "hot-switch unavailable outside the shared entry");
        }
        if(owner.net == null) return hotSwitchFallback(brokerSessionId, toActionId, "hot-switch network transport is unavailable");

        SocketChannel detached;
        try{
            detached = owner.net.detachClientChannel();
        }catch(Throwable detachFailure){
            disconnectGameTransport();
            return hotSwitchFallback(brokerSessionId, toActionId, "hot-switch detach failed");
        }
        if(detached == null){
            disconnectGameTransport();
            return hotSwitchFallback(brokerSessionId, toActionId, "hot-switch transport cannot be detached");
        }

        byte[] barrier = ActionSessionHandshake.hotSwitchBarrier(brokerSessionId, fromActionId, toActionId);
        CompletableFuture<Boolean> barrierSync = new CompletableFuture<>();
        Thread barrierDrain = new Thread(() -> {
            try{ barrierSync.complete(synchronizeHotSwitchStream(detached, barrier, 15_000L, ActionSessionBroker.hotSwitchBarrierMaxDrainBytes)); }
            catch(Throwable failure){ barrierSync.completeExceptionally(failure); }
        }, "shared-campaign-hot-switch-drain");
        barrierDrain.setDaemon(true);
        barrierDrain.start();

        RuntimePayloads.HotSwitchResult result;
        try{
            result = control.hotSwitchAction(brokerSessionId, fromActionId, toActionId, spectator, networkUuid);
        }catch(IOException failure){
            closeQuietly(detached);
            disconnectGameTransport();
            throw failure;
        }
        if(!result.sameTcp()){
            closeQuietly(detached);
            disconnectGameTransport();
            return result;
        }

        try{
            if(!barrierSync.get(8L, TimeUnit.SECONDS)){
                try{ control.abortHotSwitch(result.sessionId()); }catch(Throwable ignored){}
                closeQuietly(detached);
                disconnectGameTransport();
                return hotSwitchFallback(result, "hot-switch stream synchronization failed");
            }
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            try{ control.abortHotSwitch(result.sessionId()); }catch(Throwable ignored){}
            closeQuietly(detached);
            disconnectGameTransport();
            return hotSwitchFallback(result, "hot-switch stream synchronization interrupted");
        }catch(ExecutionException | TimeoutException failure){
            try{ control.abortHotSwitch(result.sessionId()); }catch(Throwable ignored){}
            closeQuietly(detached);
            disconnectGameTransport();
            return hotSwitchFallback(result, "hot-switch stream synchronization failed");
        }

        boolean resumed;
        try{ resumed = control.resumeHotSwitch(result.sessionId()); }
        catch(IOException failure){
            closeQuietly(detached);
            disconnectGameTransport();
            throw failure;
        }
        if(!resumed){
            closeQuietly(detached);
            disconnectGameTransport();
            return hotSwitchFallback(result, "hot-switch resume failed");
        }

        String resetFailure = resetWorldOnOwnerLane();
        if(!resetFailure.isEmpty()){
            closeQuietly(detached);
            disconnectGameTransport();
            return hotSwitchFallback(result, resetFailure);
        }

        // Do not let a wedged client admission callback pin the strategic planet UI behind ui.loadfrag forever.
        // A timed prepare can fail closed and drop back to the ordinary reconnect lane.
        boolean prepared;
        try{
            prepared = sharedNet.tryPrepareRebindJoin(toActionId, result.joinToken(), spectator, false, control.memberId(), result.sessionId(), 3_000L);
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            closeQuietly(detached);
            disconnectGameTransport();
            return hotSwitchFallback(result, "hot-switch prepare-rebind interrupted");
        }
        if(!prepared){
            closeQuietly(detached);
            disconnectGameTransport();
            return hotSwitchFallback(result, "hot-switch prepare-rebind timed out");
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
        try{
            owner.net.rebindClientChannel(detached, finished::countDown, failure -> { rebindFailure.set(failure); finished.countDown(); });
        }catch(Throwable failure){
            closeQuietly(detached);
            disconnectGameTransport();
            return hotSwitchFallback(result, "hot-switch rebind failed");
        }
        try{
            if(!finished.await(8L, TimeUnit.SECONDS)){
                closeQuietly(detached);
                disconnectGameTransport();
                return hotSwitchFallback(result, "hot-switch rebind timed out");
            }
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            closeQuietly(detached);
            disconnectGameTransport();
            return hotSwitchFallback(result, "hot-switch rebind interrupted");
        }
        if(rebindFailure.get() != null){
            closeQuietly(detached);
            disconnectGameTransport();
            return hotSwitchFallback(result, "hot-switch rebind failed");
        }
        return result;
    }

    /** Drains the detached game socket through the deterministic broker marker, then echoes exactly that marker. */
    static boolean synchronizeHotSwitchStream(SocketChannel channel, byte[] marker, long timeoutMillis, int maxBytes) throws IOException{
        if(channel == null || marker == null || marker.length == 0 || maxBytes < marker.length) return false;
        channel.configureBlocking(false);
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
        int[] fallback = new int[marker.length];
        for(int i = 1, prefix = 0; i < marker.length; i++){
            while(prefix > 0 && marker[i] != marker[prefix]) prefix = fallback[prefix - 1];
            if(marker[i] == marker[prefix]) prefix++;
            fallback[i] = prefix;
        }
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        int matched = 0, consumed = 0;
        boolean found = false;
        while(!found && consumed < maxBytes && System.currentTimeMillis() < deadline){
            buffer.clear();
            buffer.limit(Math.min(buffer.capacity(), maxBytes - consumed));
            int read = channel.read(buffer);
            if(read < 0) return false;
            if(read == 0){
                try{ Thread.sleep(1L); }
                catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); throw new InterruptedIOException("Interrupted during hot-switch stream barrier"); }
                continue;
            }
            consumed += read;
            buffer.flip();
            while(buffer.hasRemaining()){
                byte value = buffer.get();
                while(matched > 0 && value != marker[matched]) matched = fallback[matched - 1];
                if(value == marker[matched]) matched++;
                if(matched == marker.length){ found = true; break; }
            }
        }
        if(!found) return false;
        ByteBuffer echo = ByteBuffer.wrap(marker);
        while(echo.hasRemaining() && System.currentTimeMillis() < deadline){
            int wrote = channel.write(echo);
            if(wrote < 0) return false;
            if(wrote == 0){
                try{ Thread.sleep(1L); }
                catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); throw new InterruptedIOException("Interrupted while echoing hot-switch stream barrier"); }
            }
        }
        return !echo.hasRemaining();
    }

    private boolean ownerLaneBound(){
        return RuntimeContexts.bound() == owner && (owner != RuntimeContexts.primary() || arc.Core.app == null || arc.Core.app.isOnMainThread());
    }

    private String resetWorldOnOwnerLane(){
        Runnable markConnecting = () -> { if(owner.netClient != null) owner.netClient.beginConnecting(); };
        if(ownerLaneBound()){
            try{
                if(owner.logic == null) return "hot-switch world reset is unavailable; use ordinary join";
                owner.logic.reset();
                markConnecting.run();
                return "";
            }catch(Throwable failure){
                arc.util.Log.err("Shared Campaign hot-switch world reset failed", failure);
                return "hot-switch world reset failed; use ordinary join";
            }
        }
        FutureTask<Object> reset = new FutureTask<>(() -> {
            if(owner.logic == null) throw new IllegalStateException("Mindustry logic is unavailable");
            owner.logic.reset();
            markConnecting.run();
        }, null);
        try{ RuntimeContexts.post(owner, reset); }
        catch(Throwable failure){
            arc.util.Log.err("Shared Campaign hot-switch world reset could not be scheduled", failure);
            return "hot-switch world reset could not be scheduled; use ordinary join";
        }
        try{ reset.get(8L, TimeUnit.SECONDS); }
        catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); return "hot-switch world reset interrupted; use ordinary join"; }
        catch(ExecutionException failure){ arc.util.Log.err("Shared Campaign hot-switch world reset failed", failure.getCause()); return "hot-switch world reset failed; use ordinary join"; }
        catch(TimeoutException failure){ return "hot-switch world reset timed out; use ordinary join"; }
        return "";
    }

    private void disconnectGameTransport(){
        try{ if(owner.net != null) owner.net.disconnect(); }catch(Throwable ignored){}
    }

    private static void closeQuietly(SocketChannel channel){
        if(channel == null) return;
        try{ channel.close(); }catch(IOException ignored){}
    }

    private static RuntimePayloads.HotSwitchResult hotSwitchFallback(String sessionId, String actionId, String error){
        return new RuntimePayloads.HotSwitchResult(sessionId == null ? "" : sessionId, actionId == null ? "" : actionId, "", 0, "", false,
            error + "; use ordinary join");
    }

    private static RuntimePayloads.HotSwitchResult hotSwitchFallback(RuntimePayloads.HotSwitchResult result, String error){
        return new RuntimePayloads.HotSwitchResult(result.sessionId(), result.actionId(), result.host(), result.port(), result.joinToken(), false,
            error + "; use ordinary join");
    }

    /**
     * Applies Shared Campaign research against the exact planet/tree selected by the UI. When this process owns the
     * authority, use the local command path instead of performing an avoidable loopback control-plane round trip.
     */
    public SharedCampaignState requestResearch(String contentName) throws IOException{
        return requestResearch(contentName, "");
    }

    public SharedCampaignState requestResearch(String contentName, String planetName) throws IOException{
        SharedCampaignAuthority localAuthority;
        synchronized(this){ localAuthority = authority != null && authority.isOpen() ? authority : null; }
        if(localAuthority != null){
            String actor = localAuthority.state().ownerId;
            SharedCampaignState result = localAuthority.coordinator().actionCommands().research(actor, contentName, planetName);
            // The local authority publishes the commit to connected clients as well, but accept it immediately so a
            // host-side ResearchDialog never waits on its own loopback client before showing the authoritative state.
            acceptRemoteSnapshot(result);
            return SharedCampaignStateCopy.copy(result);
        }
        SharedCampaignState result = controlClient().research(contentName, planetName);
        acceptRemoteSnapshot(result);
        return SharedCampaignStateCopy.copy(result);
    }

    /** True only while this GameContext is actually running/displaying a Shared Action. A lobby/control-plane connection is not gameplay. */
    public boolean sharedModeActive(){
        SharedActionAgent agent = SharedActionBootstrap.findAgent(owner);
        if(agent != null && agent.enabled()) return true;
        SharedCampaignNet network = SharedCampaignNet.find(owner);
        return network != null && network.activeClientAction();
    }

    /** Projects the authoritative Shared Campaign unlock set into vanilla content/UI queries without copying full state. */
    public synchronized boolean sharedUnlocked(String contentName){
        if(contentName == null) return false;
        if(authority != null && authority.isOpen()) return authority.effectiveUnlocked(contentName);
        return sharedUnlockCache.contains(contentName);
    }

    /** True only for explicit Shared Campaign research; discovered/produced content does not satisfy ResearchObjective. */
    public synchronized boolean sharedResearched(String contentName){
        if(contentName == null) return false;
        if(authority != null && authority.isOpen()){
            SharedCampaignState state = authority.state();
            return state != null && state.researched.contains(contentName);
        }
        return sharedResearchedCache.contains(contentName);
    }

    /** Applies authoritative researched + discovered content only to a live Shared Action runtime. */
    private void projectAuthoritativeUnlocksIntoLiveAction(){
        if(!sharedModeActive() || owner.state == null || owner.state.rules == null) return;
        SharedCampaignState snapshot = strategicState();
        if(snapshot == null) return;
        owner.state.rules.researched.clear();
        mindustry.Vars.content.each(value -> {
            if(value instanceof mindustry.ctype.UnlockableContent unlock &&
                (unlock.alwaysUnlocked || SharedCampaignState.effectiveUnlocked(snapshot.researched, snapshot.discovered, unlock.name))){
                owner.state.rules.researched.add(unlock);
            }
        });
    }

    public synchronized SharedCampaignState strategicState(){
        if(authority != null && authority.isOpen()) return SharedCampaignStateCopy.copy(authority.state());
        return remoteSnapshot == null ? null : SharedCampaignStateCopy.copy(remoteSnapshot);
    }
    public synchronized String activeMemberId(){
        if(client != null) return client.memberId();
        if(authority != null && authority.isOpen()) return authority.state().ownerId;
        return "";
    }

    private SharedCampaignCreationOptions validateCreation(SharedCampaignCreationOptions options){
        sealRegistries();
        return (options == null ? new SharedCampaignCreationOptions() : options).validate(planetPolicies);
    }
    private SharedCampaignAuthority newAuthority(Fi directory, String localHostId, String advertisedHost, int actionControlPort, int publicEntryPort){
        ensureVanillaMissions();
        sealRegistries();
        return new SharedCampaignAuthority(owner, directory, localHostId, advertisedHost, actionControlPort, publicEntryPort, modsSource, missions, planetPolicies, extensions(), this, lossPolicy, runtimeFactory);
    }
    private synchronized void sealRegistries(){
        if(registriesSealed) return;
        planetPolicies.seal();
        registriesSealed = true;
    }
    private synchronized void ensureVanillaMissions(){
        if(missions.get("vanilla-erekir:onset") != null) return;
        for(var sector : Planets.erekir.sectors){
            if(sector.preset == null) continue;
            String id = "vanilla-erekir:" + sector.preset.name;
            missions.register("mindustry:core", id, 1, new ErekirMissionCatalog.Definition(sector.preset.name, sector.id));
        }
    }
    private void requireInactive(){ if((authority != null && authority.isOpen()) || client != null) throw new IllegalStateException("Close the active Shared Campaign first"); }
}
