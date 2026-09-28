package mindustry.campaign.shared.io;


import arc.files.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.legacy.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.security.*;
import java.util.Objects;
import java.util.HexFormat;
import java.util.concurrent.locks.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.zip.*;

/**
 * Crash-safe snapshot store with optimistic revisions and an append-only audit journal.
 */
public class SharedCampaignStore implements Closeable{
    private static final int journalMagic = 0x4d59434a; // MYCJ
    private final Fi root, snapshot, backup, journal, lockFile, walFile;
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock(true);
    /** Authority-wide durable mutation admission fence. Host migration seals this store itself, not only one caller. */
    private final Object mutationAdmission = new Object();
    private boolean mutationsSealed;
    private Thread mutationSealOwner;
    private int inFlightMutations;
    private FileChannel lockChannel;
    private FileLock processLock;
    private SharedCampaignState state;
    private long journalRevision = -1L;
    /** True only when open() had to reject the primary snapshot and fall back to campaign.mycp.bak. */
    private boolean recoveredFromBackup;
    /** Hash of the exact container bytes read from disk, retained across codec-version migration. */
    private String openedSnapshotHash = "";
    private volatile Consumer<Commit> commitListener = commit -> {};
    private final IoCounters ioCounters = new IoCounters();
    /** True when at least one coalesced mutation has updated memory since the last durable snapshot write. */
    private boolean coalescedPending;
    /** Wall-clock time of the oldest still-pending coalesced mutation. */
    private long coalescedSinceMillis;
    private Thread coalesceFlusher;
    private volatile boolean coalesceFlusherRunning;
    private final Object coalesceSignal = new Object();
    /** Set after the campaign directory has received its durable directory-entry barrier (P1). */
    private boolean directoryBarrierDone;
    /** Complete WAL records appended since the last full snapshot checkpoint. */
    private int walRecordCount;
    /** Framed WAL bytes currently on disk since the last checkpoint truncate. */
    private long walBytesOnDisk;
    /** Wall-clock of the oldest still-uncheckpointed WAL record / coalesced mutation. */
    private long walFirstAtMillis;
    /** Low-frequency WAL accumulates complete framed records here and fsyncs them as one batch. */
    private final ByteArrayOutputStream pendingWal = new ByteArrayOutputStream();
    private int pendingWalRecords;
    private long pendingWalSinceMillis;

    /** Immutable description of a durable campaign commit. */
    public record Commit(SharedCampaignState state, long fromRevision, long toRevision, String actor, String mutationType){}

    /**
     * Application-level durable I/O counters for telemetry. Counts only bytes this store intentionally wrote (or
     * intended to write for backup materialization) plus explicit force/fsync barriers — not unrelated process I/O.
     */
    public static final class IoCounters{
        public long snapshotBytes;
        public long backupBytes;
        public long journalBytes;
        public long forceCount;
        public long transactCount;
        public long backupHardlinks;
        public long backupCopies;
        /** Logical coalesced mutations admitted into memory (may share one durable snapshot). */
        public long coalescedTransactCount;
        /** Durable snapshot writes that only drained previously pending coalesced mutations. */
        public long coalescedFlushCount;
        /** Framed bytes appended to campaign.wal. */
        public long walBytes;
        /** Complete coalesced WAL records appended. */
        public long walAppends;
        /** Full snapshot checkpoints that truncated campaign.wal. */
        public long walCheckpoints;

        public IoCounters copy(){
            IoCounters out = new IoCounters();
            out.snapshotBytes = snapshotBytes; out.backupBytes = backupBytes; out.journalBytes = journalBytes;
            out.forceCount = forceCount; out.transactCount = transactCount;
            out.backupHardlinks = backupHardlinks; out.backupCopies = backupCopies;
            out.coalescedTransactCount = coalescedTransactCount; out.coalescedFlushCount = coalescedFlushCount;
            out.walBytes = walBytes; out.walAppends = walAppends; out.walCheckpoints = walCheckpoints;
            return out;
        }

        public IoCounters delta(IoCounters baseline){
            IoCounters out = new IoCounters();
            out.snapshotBytes = snapshotBytes - baseline.snapshotBytes;
            out.backupBytes = backupBytes - baseline.backupBytes;
            out.journalBytes = journalBytes - baseline.journalBytes;
                        out.forceCount = forceCount - baseline.forceCount;
            out.transactCount = transactCount - baseline.transactCount;
            out.backupHardlinks = backupHardlinks - baseline.backupHardlinks;
            out.backupCopies = backupCopies - baseline.backupCopies;
            out.coalescedTransactCount = coalescedTransactCount - baseline.coalescedTransactCount;
            out.coalescedFlushCount = coalescedFlushCount - baseline.coalescedFlushCount;
            out.walBytes = walBytes - baseline.walBytes;
            out.walAppends = walAppends - baseline.walAppends;
            out.walCheckpoints = walCheckpoints - baseline.walCheckpoints;
            return out;
        }

        public long totalBytes(){ return snapshotBytes + backupBytes + journalBytes + walBytes; }
    }

    /** Snapshot of durable counters; does not reset. */
    public IoCounters ioCounters(){ return ioCounters.copy(); }

    /** Returns counters accumulated since {@code baseline} without resetting live counters. */
    public IoCounters ioCountersSince(IoCounters baseline){ return ioCounters.delta(baseline); }

    public SharedCampaignStore(Fi root){
        this.root = root;
        snapshot = root.child("campaign.mycp");
        backup = root.child("campaign.mycp.bak");
        journal = root.child("campaign.journal");
        lockFile = root.child("campaign.lock");
        walFile = root.child("campaign.wal");
    }

    public void open(){
        rw.writeLock().lock();
        try{
            root.mkdirs();
            lockChannel = FileChannel.open(lockFile.file().toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            processLock = lockChannel.tryLock();
            if(processLock == null) throw new IllegalStateException("Shared campaign is already open by another process: " + root);
            SharedCampaignState loaded = loadBestSnapshot();
            int loadedSchema = loaded.schema;
            state = loaded;
            // Authenticate the audit chain against the exact on-disk snapshot before schema migration changes bytes.
            verifyJournal();
            state = mindustry.campaign.shared.api.SharedCampaignMigrations.migrate(loaded);
            if(snapshot.exists() && state.schema != loadedSchema){
                long fromRevision = state.revision;
                state.revision = fromRevision + 1L;
                state.updatedAt = Time.millis();
                state.validate();
                // Route legacy migration through the normal durable snapshot path.
                String migratedHash = writeSnapshotAtomically(state);
                try{
                    appendJournal(fromRevision, state.revision, "system", "shared-campaign:legacy-migration", migratedHash, openedSnapshotHash);
                }catch(RuntimeException error){
                    Log.err("Shared campaign schema migration was committed, but its audit record could not be written", error);
                }
                openedSnapshotHash = migratedHash;
                // Schema migration is a hard checkpoint; any prior WAL is obsolete.
                truncateWalFile();
            }
            // P2: authenticate tip, then replay any continuous WAL prefix on top of the snapshot.
            replayWalLocked();
            coalescedPending = walRecordCount > 0;
            coalescedSinceMillis = coalescedPending ? (walFirstAtMillis > 0L ? walFirstAtMillis : Time.millis()) : 0L;
            directoryBarrierDone = snapshot.exists() || backup.exists() || journal.exists();
            startCoalesceFlusher();
        }catch(IOException e){
            cleanupFailedOpen(e);
            throw new UncheckedIOException(e);
        }catch(RuntimeException | Error e){
            cleanupFailedOpen(e);
            throw e;
        }finally{
            rw.writeLock().unlock();
        }
    }

    /** open() owns the process lock as soon as tryLock succeeds; every exceptional exit must release it. */
    private void cleanupFailedOpen(Throwable failure){
        state = null;
        stopCoalesceFlusher();
        try{ if(processLock != null) processLock.release(); }catch(IOException close){ failure.addSuppressed(close); }
        try{ if(lockChannel != null) lockChannel.close(); }catch(IOException close){ failure.addSuppressed(close); }
        processLock = null;
        lockChannel = null;
        journalRevision = -1L;
        recoveredFromBackup = false;
        openedSnapshotHash = "";
        coalescedPending = false;
        coalescedSinceMillis = 0L;
        directoryBarrierDone = false;
        walRecordCount = 0;
        walBytesOnDisk = 0L;
        walFirstAtMillis = 0L;
        synchronized(mutationAdmission){
            mutationsSealed = false;
            mutationSealOwner = null;
            inFlightMutations = 0;
            mutationAdmission.notifyAll();
        }
    }

    public SharedCampaignState snapshot(){
        rw.readLock().lock();
        try{
            ensureOpen();
            // Arc ObjectMap/ObjectSet reuse iterator objects inside each collection and reject concurrent/nested
            // iteration, even when callers are only reading. Multiple read-lock holders therefore cannot deep-copy
            // the same authoritative state concurrently. Serialize the copy while retaining the read lock so the
            // state reference cannot be replaced by a transaction midway through the round trip.
            synchronized(state){
                return SharedCampaignStateCopy.copy(state);
            }
        }finally{
            rw.readLock().unlock();
        }
    }

    /**
     * Allocation-bounded read projection for lobby/research/UI callers. The strategic projection is kept as a separate API so future large runtime payloads do not leak into UI callers.
     */
    public SharedCampaignState strategicSnapshot(){
        rw.readLock().lock();
        try{
            ensureOpen();
            synchronized(state){
                return SharedCampaignStateCopy.strategicCopy(state);
            }
        }finally{
            rw.readLock().unlock();
        }
    }

    /**
     * Liveness probe for callers that may race a concurrent {@link #close()}. Cheap: no snapshot copy is allocated.
     *
     * <p>{@link #close()} takes the write lock, so a caller that reads liveness <em>while holding the read lock</em>
     * (as every read entry point below does) observes either a consistent open store or the closed state, never a
     * half-closed one. Returning "closed" rather than throwing is what lets frame/tick hooks probe without risking a
     * crash when the user closes the campaign from another thread.</p>
     */
    public boolean isOpen(){
        rw.readLock().lock();
        try{ return state != null; }
        finally{ rw.readLock().unlock(); }
    }

    /**
     * Cheap authoritative revision read for hot per-frame callers that only need to detect change. Returns -1 when no
     * campaign is open; revisions are always >= 1 for an open campaign, so -1 is never mistaken for a live revision.
     */
    public long revision(){
        rw.readLock().lock();
        try{ return state == null ? -1L : state.revision; }
        finally{ rw.readLock().unlock(); }
    }

    /** Cheap authoritative unlock projection for hot vanilla UI/content queries; does not allocate a campaign copy. */
    public boolean effectiveUnlocked(String contentName){
        if(contentName == null) return false;
        rw.readLock().lock();
        try{
            return state != null && SharedCampaignState.effectiveUnlocked(state.researched, state.discovered, contentName);
        }finally{
            rw.readLock().unlock();
        }
    }

    /**
     * Opaque change token for the active campaign: {@code campaignId@revision}. Allocates one short String and never a
     * snapshot copy, and is read under the same lock {@link #close()} needs, so a caller that memoizes a projection can
     * detect both a revision change and a campaign identity change in one atomic read. The identity is part of the key
     * because two different campaigns can legitimately sit on the same revision, which must never reuse a cached
     * projection. Empty when no campaign is open.
     */
    public String campaignChangeKey(){
        rw.readLock().lock();
        try{ return state == null ? "" : state.campaignId + "@" + state.revision; }
        finally{ rw.readLock().unlock(); }
    }

    /**
     * Read projection for UI/render/update callers that may race a concurrent {@link #close()}: a campaign that is gone
     * is reported as null - the same "no campaign" signal every {@link #snapshot()} caller already null-checks - instead
     * of throwing "store is not open" out of a frame or tick hook. Writers keep using the fail-closed entry points.
     */
    public SharedCampaignState snapshotIfOpen(){
        rw.readLock().lock();
        try{
            SharedCampaignState current = state;
            if(current == null) return null;
            synchronized(current){
                return SharedCampaignStateCopy.copy(current);
            }
        }finally{
            rw.readLock().unlock();
        }
    }

    /** {@link #strategicSnapshot()} projection that reports a concurrently closed store as null instead of throwing. */
    public SharedCampaignState strategicSnapshotIfOpen(){
        rw.readLock().lock();
        try{
            SharedCampaignState current = state;
            if(current == null) return null;
            synchronized(current){
                return SharedCampaignStateCopy.strategicCopy(current);
            }
        }finally{
            rw.readLock().unlock();
        }
    }

    /** Minimal isolated read used by high-frequency Action heartbeat/lease paths without copying the whole campaign. */
    public ActionAuthorityView actionAuthority(String actionId){
        rw.readLock().lock();
        try{
            ensureOpen();
            synchronized(state){
                SharedCampaignState.ActionState action = state.actions.get(actionId);
                return new ActionAuthorityView(action == null ? null : SharedCampaignStateCopy.copyAction(action), state.authorityGeneration);
            }
        }finally{ rw.readLock().unlock(); }
    }

    /** Scalar authority read; avoids a full state copy for admission/enrollment limits. */
    public int memberCount(){
        rw.readLock().lock();
        try{ ensureOpen(); synchronized(state){ return state.members.size; } }
        finally{ rw.readLock().unlock(); }
    }

    public record ActionAuthorityView(SharedCampaignState.ActionState action, long authorityGeneration){}

    public SharedCampaignState transact(String actor, String type, Consumer<SharedCampaignState> mutation){
        enterMutationAdmission();
        Commit committed;
        SharedCampaignState callerResult;
        try{
            rw.writeLock().lock();
            try{
                ensureOpen();
                // Hard durable commits absorb any pending coalesced mutations into this one snapshot. Intermediate
                // coalesced revisions are then audit-bridged as gap records; only the hard tip keeps its real type.
                SharedCampaignState next = SharedCampaignStateCopy.copy(state);
                long expected = next.revision;
                mutation.accept(next);
                if(next.revision != expected) throw new IllegalStateException("Mutations must not directly change campaign revision");
                next.compactTerminalTransactionHistory();
                next.revision = expected + 1;
                next.updatedAt = Time.millis();
                next.validate();
                String previousHash = openedSnapshotHash.isBlank() ? SharedCampaignCodec.sha256(state) : openedSnapshotHash;
                String snapshotHash = writeSnapshotAtomically(next);
                state = next;
                openedSnapshotHash = snapshotHash;
                coalescedPending = false;
                coalescedSinceMillis = 0L;
                // Hard durable commits are full checkpoints: WAL content is already absorbed into this snapshot.
                truncateWalFile();
                ioCounters.transactCount++;
                try{
                    appendJournal(expected, next.revision, actor, type, snapshotHash, previousHash);
                }catch(RuntimeException error){
                    Log.err("Shared campaign revision " + next.revision + " was committed, but its audit record could not be written", error);
                }
                SharedCampaignState listenerState = SharedCampaignStateCopy.strategicCopy(next);
                SharedCampaignState callerState = SharedCampaignStateCopy.copy(next);
                committed = new Commit(listenerState, expected, next.revision, actor == null ? "" : actor, type == null ? "" : type);
                callerResult = callerState;
            }finally{
                rw.writeLock().unlock();
            }
        }finally{
            exitMutationAdmission();
        }
        try{
            commitListener.accept(committed);
        }catch(Throwable error){
            Log.err("Shared campaign commit observer failed after revision " + committed.toRevision(), error);
        }
        return callerResult;
    }

    /**
     * Memory-authoritative mutation for lease/presence/strategic-summary traffic. Updates the live revision
     * immediately (read paths stay newest). WAL-eligible field patches are appended to {@code campaign.wal};
     * ineligible mutations full-checkpoint a durable snapshot instead. Pending checkpoint work is absorbed by the
     * next hard {@link #transact}, drained by the flusher after {@code coalesceMaxLagMs} / WAL thresholds, forced
     * when the campaign freezes empty, and always drained before seal/close.
     *
     * <p>Crash semantics: WAL-backed coalesced work survives reopen; a torn WAL tail is discarded (fail-closed on
     * discontinuous WAL); losing an acknowledged hard transaction is not allowed. Callers must never route
     * launch / research 2PC / membership key changes / settings / migration / terminal pause-fail through this path.</p>
     */
    public SharedCampaignState transactCoalesced(String actor, String type, Consumer<SharedCampaignState> mutation){
        enterMutationAdmission();
        Commit committed;
        SharedCampaignState callerResult;
        try{
            rw.writeLock().lock();
            try{
                ensureOpen();
                SharedCampaignState next = SharedCampaignStateCopy.copy(state);
                long expected = next.revision;
                mutation.accept(next);
                if(next.revision != expected) throw new IllegalStateException("Mutations must not directly change campaign revision");
                next.compactTerminalTransactionHistory();
                next.revision = expected + 1;
                next.updatedAt = Time.millis();
                next.validate();
                SharedCampaignPersistence.Policy policy = persistencePolicy(next);
                SharedCampaignWal.Diff walDiff = policy.walEnabled() ? SharedCampaignWal.tryDiff(state, next) : null;
                if(policy.walEnabled() && walDiff != null){
                    appendWalRecord(walDiff, policy);
                    state = next;
                    coalescedPending = true;
                    if(walFirstAtMillis == 0L) walFirstAtMillis = Time.millis();
                    if(coalescedSinceMillis == 0L) coalescedSinceMillis = walFirstAtMillis;
                    if(shouldForceCoalescedFlushLocked() || walThresholdExceededLocked()){
                        flushCoalescedLocked();
                    }else{
                        synchronized(coalesceSignal){ coalesceSignal.notifyAll(); }
                    }
                }else if(!policy.walEnabled()){
                    // Traditional mode deliberately keeps ordinary lease/presence/summary churn in memory until the
                    // periodic full checkpoint. Hard transactions still use transact() and remain immediately durable.
                    state = next;
                    coalescedPending = true;
                    if(coalescedSinceMillis == 0L) coalescedSinceMillis = Time.millis();
                    if(walFirstAtMillis == 0L) walFirstAtMillis = coalescedSinceMillis;
                    if(shouldForceCoalescedFlushLocked()) flushCoalescedLocked();
                    else synchronized(coalesceSignal){ coalesceSignal.notifyAll(); }
                }else{
                    // Not WAL-eligible (mission/transaction/membership-key or unmodeled change): checkpoint immediately.
                    String previousHash = openedSnapshotHash.isBlank() ? SharedCampaignCodec.sha256(state) : openedSnapshotHash;
                    String snapshotHash = writeSnapshotAtomically(next);
                    state = next;
                    openedSnapshotHash = snapshotHash;
                    coalescedPending = false;
                    coalescedSinceMillis = 0L;
                    truncateWalFile();
                    ioCounters.transactCount++;
                    ioCounters.coalescedFlushCount++;
                    try{
                        appendJournal(expected, next.revision, actor, type, snapshotHash, previousHash);
                    }catch(RuntimeException error){
                        Log.err("Shared campaign coalesced checkpoint committed revision " + next.revision + ", but its audit record could not be written", error);
                    }
                }
                ioCounters.coalescedTransactCount++;
                SharedCampaignState listenerState = SharedCampaignStateCopy.strategicCopy(next);
                SharedCampaignState callerState = SharedCampaignStateCopy.copy(next);
                committed = new Commit(listenerState, expected, next.revision, actor == null ? "" : actor, type == null ? "" : type);
                callerResult = callerState;
            }finally{
                rw.writeLock().unlock();
            }
        }finally{
            exitMutationAdmission();
        }
        try{
            commitListener.accept(committed);
        }catch(Throwable error){
            Log.err("Shared campaign coalesced commit observer failed after revision " + committed.toRevision(), error);
        }
        return callerResult;
    }

    /** True when coalesced durability may not lag further: freezeWhenEmpty campaigns with no live Action. */
    private boolean shouldForceCoalescedFlushLocked(){
        if(state == null || !state.freezeWhenEmpty || !coalescedPending) return false;
        for(SharedCampaignState.ActionState action : state.actions.values()){
            if(action.status != null && action.status.isLive()) return false;
        }
        return true;
    }

    /** Drains any pending coalesced mutations to a durable snapshot+journal revision. No-op when nothing is pending. */
    public void flushCoalescedIfNeeded(){
        rw.writeLock().lock();
        try{
            if(state != null && coalescedPending) flushCoalescedLocked();
        }finally{
            rw.writeLock().unlock();
        }
    }

    /**
     * Runs {@code body} under the exclusive store write lock after draining pending coalesced work.
     * Capture uses this only for a short authoritative snapshot; the whole-tree zip stays outside the lock
     * so heartbeats/startAction are not stalled for the duration of a best-effort restore point.
     */
    public <T> T withStableCampaignView(java.util.function.Supplier<T> body){
        Objects.requireNonNull(body, "body");
        rw.writeLock().lock();
        try{
            ensureOpen();
            if(state != null && coalescedPending) flushCoalescedLocked();
            return body.get();
        }finally{
            rw.writeLock().unlock();
        }
    }

    /** True while at least one coalesced mutation is memory-visible but not yet checkpointed to mycp. */
    public boolean hasCoalescedPending(){
        rw.readLock().lock();
        try{ return state != null && coalescedPending; }
        finally{ rw.readLock().unlock(); }
    }

    /** Complete campaign.wal bytes currently tracked by this store (0 after checkpoint). */
    public long walBytesOnDisk(){
        rw.readLock().lock();
        try{ return walBytesOnDisk; }
        finally{ rw.readLock().unlock(); }
    }

    private boolean walThresholdExceededLocked(){
        SharedCampaignPersistence.Policy policy = persistencePolicy(state);
        if(!policy.walEnabled()) return false;
        return walRecordCount + pendingWalRecords >= policy.walMaxRecords()
            || walBytesOnDisk + pendingWal.size() >= policy.walMaxBytes();
    }

    private void flushCoalescedLocked(){
        ensureOpen();
        if(!coalescedPending) return;
        SharedCampaignState current = state;
        long fromRevision = current.revision - 1L;
        if(fromRevision < 0L) throw new IllegalStateException("Cannot flush coalesced state before the first revision");
        String previousHash = openedSnapshotHash.isBlank() ? SharedCampaignCodec.sha256(current) : openedSnapshotHash;
        String snapshotHash = writeSnapshotAtomically(current);
        openedSnapshotHash = snapshotHash;
        coalescedPending = false;
        coalescedSinceMillis = 0L;
        walFirstAtMillis = 0L;
        clearPendingWal();
        truncateWalFile();
        ioCounters.transactCount++;
        ioCounters.coalescedFlushCount++;
        ioCounters.walCheckpoints++;
        try{
            appendJournal(fromRevision, current.revision, "system", "shared-campaign:coalesce-flush", snapshotHash, previousHash);
        }catch(RuntimeException error){
            Log.err("Shared campaign coalesced flush committed revision " + current.revision + ", but its audit record could not be written", error);
        }
    }

    private void appendWalRecord(SharedCampaignWal.Diff diff, SharedCampaignPersistence.Policy policy){
        byte[] framed = SharedCampaignWal.frame(diff);
        if(!policy.walImmediateSync()){
            try{ pendingWal.write(framed); }catch(IOException impossible){ throw new AssertionError(impossible); }
            pendingWalRecords++;
            if(pendingWalSinceMillis == 0L) pendingWalSinceMillis = Time.millis();
            return;
        }
        appendWalBytes(framed, 1);
    }

    private void flushPendingWalLocked(){
        if(pendingWalRecords == 0) return;
        byte[] framed = pendingWal.toByteArray();
        int records = pendingWalRecords;
        appendWalBytes(framed, records);
        clearPendingWal();
    }

    private void appendWalBytes(byte[] framed, int records){
        try{
            Files.createDirectories(walFile.file().toPath().getParent());
            try(FileOutputStream out = new FileOutputStream(walFile.file(), true)){
                out.write(framed);
                out.flush();
                out.getFD().sync();
            }
            walRecordCount += records;
            walBytesOnDisk += framed.length;
            ioCounters.walBytes += framed.length;
            ioCounters.walAppends += records;
            ioCounters.forceCount++;
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    private void clearPendingWal(){
        pendingWal.reset();
        pendingWalRecords = 0;
        pendingWalSinceMillis = 0L;
    }

    private static SharedCampaignPersistence.Policy persistencePolicy(SharedCampaignState state){
        return SharedCampaignPersistence.policy(state == null ? SharedCampaignState.PersistenceProfile.lowFrequencyWal : state.persistenceProfile);
    }

    private void truncateWalFile(){
        clearPendingWal();
        try{
            if(walFile.exists()) Files.deleteIfExists(walFile.file().toPath());
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
        walRecordCount = 0;
        walBytesOnDisk = 0L;
        walFirstAtMillis = 0L;
    }

    /**
     * Loads {@code campaign.wal}, discards a torn tail, and replays only a continuous prefix starting at the
     * authenticated snapshot revision. Non-continuous WAL content fails closed.
     */
    private void replayWalLocked(){
        walRecordCount = 0;
        walBytesOnDisk = 0L;
        walFirstAtMillis = 0L;
        if(!walFile.exists()) return;
        byte[] bytes;
        try{
            bytes = Files.readAllBytes(walFile.file().toPath());
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
        if(bytes.length == 0) return;
        SharedCampaignWal.ScanResult scan = SharedCampaignWal.scan(bytes);
        if(scan.truncatedTornTail()){
            Log.warn("Shared campaign WAL had a torn tail; keeping only the complete prefix (@ bytes)", scan.completeBytes());
            try{
                if(scan.completeBytes() <= 0L){
                    Files.deleteIfExists(walFile.file().toPath());
                    return;
                }
                try(FileChannel channel = FileChannel.open(walFile.file().toPath(), StandardOpenOption.WRITE)){
                    channel.truncate(scan.completeBytes());
                    channel.force(true);
                }
            }catch(IOException error){
                throw new UncheckedIOException(error);
            }
        }
        long snapshotRevision = state.revision;
        java.util.List<SharedCampaignWal.Diff> records = scan.records();
        // Drop already-checkpointed prefix; require the remainder to start exactly at the snapshot tip.
        int start = 0;
        while(start < records.size() && records.get(start).toRevision <= snapshotRevision) start++;
        if(start > 0){
            java.util.List<SharedCampaignWal.Diff> remaining = new java.util.ArrayList<>(records.subList(start, records.size()));
            records = remaining;
            // Compact file to the remaining continuous suffix if any; otherwise delete.
            try{
                if(records.isEmpty()){
                    Files.deleteIfExists(walFile.file().toPath());
                }else{
                    ByteArrayOutputStream compact = new ByteArrayOutputStream();
                    for(SharedCampaignWal.Diff diff : records) compact.write(SharedCampaignWal.frame(diff));
                    Files.write(walFile.file().toPath(), compact.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                }
            }catch(IOException error){
                throw new UncheckedIOException(error);
            }
        }
        if(records.isEmpty()) return;
        SharedCampaignWal.Diff first = records.get(0);
        if(first.fromRevision != snapshotRevision){
            throw new IllegalStateException("Shared campaign WAL is not continuous with snapshot revision " + snapshotRevision
                + " (WAL starts at " + first.fromRevision + " -> " + first.toRevision + ")");
        }
        for(SharedCampaignWal.Diff diff : records){
            SharedCampaignWal.apply(state, diff);
        }
        // Recovered WAL content is durable but still requires a future full checkpoint for migration bundles.
        walRecordCount = records.size();
        try{
            walBytesOnDisk = Files.size(walFile.file().toPath());
        }catch(IOException error){
            walBytesOnDisk = walBytesOnDiskFromRecords(records);
        }
        walFirstAtMillis = Time.millis();
        ioCounters.walAppends += records.size();
    }

    private static long walBytesOnDiskFromRecords(java.util.List<SharedCampaignWal.Diff> records){
        long total = 0L;
        for(SharedCampaignWal.Diff diff : records) total += SharedCampaignWal.frame(diff).length;
        return total;
    }

    private void startCoalesceFlusher(){
        if(coalesceFlusher != null) return;
        coalesceFlusherRunning = true;
        Thread thread = new Thread(() -> {
            while(coalesceFlusherRunning){
                try{
                    synchronized(coalesceSignal){ coalesceSignal.wait(1_000L); }
                    if(!coalesceFlusherRunning) break;
                    maybeFlushCoalescedByDeadline();
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    return;
                }catch(Throwable error){
                    Log.err("Shared campaign coalesce flusher failed", error);
                }
            }
        }, "shared-campaign-coalesce-flush");
        thread.setDaemon(true);
        coalesceFlusher = thread;
        thread.start();
    }

    private void stopCoalesceFlusher(){
        coalesceFlusherRunning = false;
        Thread thread = coalesceFlusher;
        coalesceFlusher = null;
        synchronized(coalesceSignal){ coalesceSignal.notifyAll(); }
        if(thread != null && thread != Thread.currentThread()){
            try{ thread.join(1_000L); }catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); }
        }
    }

    private void maybeFlushCoalescedByDeadline(){
        rw.writeLock().lock();
        try{
            if(state == null || !coalescedPending) return;
            long now = Time.millis();
            SharedCampaignPersistence.Policy policy = persistencePolicy(state);
            if(policy.walEnabled() && !policy.walImmediateSync() && pendingWalRecords > 0
                && now - pendingWalSinceMillis >= policy.walFlushIntervalMillis()){
                flushPendingWalLocked();
            }
            boolean intervalExceeded = now - coalescedSinceMillis >= policy.checkpointIntervalMillis();
            if(intervalExceeded || walThresholdExceededLocked() || shouldForceCoalescedFlushLocked()){
                flushCoalescedLocked();
            }
        }finally{
            rw.writeLock().unlock();
        }
    }


    /**
     * Seals every durable write through this store and waits for already-admitted writes to finish. The sealing thread
     * itself remains privileged so HostMigrationService can commit the single migration fence while all other writers
     * are blocked. This is the actual authority-wide fence; callers cannot bypass it by calling store.transact directly.
     */
    public void sealMutationsAndAwaitQuiescence(long timeoutMillis){
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
        synchronized(mutationAdmission){
            if(mutationsSealed && mutationSealOwner != Thread.currentThread()) throw new IllegalStateException("Shared Campaign store mutations are already sealed");
            mutationsSealed = true;
            mutationSealOwner = Thread.currentThread();
            while(inFlightMutations > 0){
                long remaining = deadline - System.nanoTime();
                if(remaining <= 0L){
                    mutationsSealed = false; mutationSealOwner = null; mutationAdmission.notifyAll();
                    throw new IllegalStateException("Timed out waiting for Shared Campaign store mutations to quiesce");
                }
                try{ TimeUnit.NANOSECONDS.timedWait(mutationAdmission, remaining); }
                catch(InterruptedException interrupted){
                    mutationsSealed = false; mutationSealOwner = null; mutationAdmission.notifyAll();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while sealing Shared Campaign store mutations", interrupted);
                }
            }
        }
        // Drain coalesced lease/presence work while new admissions remain sealed, so migration observes a durable image.
        rw.writeLock().lock();
        try{
            if(state != null && coalescedPending) flushCoalescedLocked();
        }finally{
            rw.writeLock().unlock();
        }
    }

    public void reopenMutations(){
        synchronized(mutationAdmission){
            if(mutationsSealed && mutationSealOwner != null && mutationSealOwner != Thread.currentThread()){
                throw new IllegalStateException("Only the thread that sealed Shared Campaign mutations may reopen them");
            }
            mutationsSealed = false; mutationSealOwner = null; mutationAdmission.notifyAll();
        }
    }

    private void enterMutationAdmission(){
        synchronized(mutationAdmission){
            while(mutationsSealed && mutationSealOwner != Thread.currentThread()){
                try{ mutationAdmission.wait(); }
                catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Shared Campaign store mutation admission", interrupted);
                }
            }
            inFlightMutations++;
        }
    }

    private void exitMutationAdmission(){
        synchronized(mutationAdmission){
            if(inFlightMutations <= 0) throw new IllegalStateException("Shared Campaign store mutation gate underflow");
            inFlightMutations--;
            if(inFlightMutations == 0) mutationAdmission.notifyAll();
        }
    }

    /** Installs the process-local observer used by the public Mod lifecycle API. */
    public void onCommit(Consumer<Commit> listener){
        commitListener = listener == null ? commit -> {} : listener;
    }

    public Fi root(){ return root; }

    private SharedCampaignState loadBestSnapshot(){
        recoveredFromBackup = false;
        if(!snapshot.exists() && !backup.exists()){
            SharedCampaignState created = new SharedCampaignState();
            openedSnapshotHash = SharedCampaignCodec.sha256(created);
            return created;
        }
        RuntimeException primary = null;
        if(snapshot.exists()){
            try{
                LoadedSnapshot loaded = decodeAndHash(snapshot);
                openedSnapshotHash = loaded.sha256;
                return loaded.state;
            }catch(RuntimeException e){ primary = e; }
        }
        if(backup.exists()){
            LoadedSnapshot loaded = decodeAndHash(backup);
            SharedCampaignState recovered = loaded.state;
            openedSnapshotHash = loaded.sha256;
            recoveredFromBackup = true;
            Log.warn("Recovered shared campaign from backup snapshot: @", root);
            // Never allow an invalid primary snapshot to overwrite the valid backup on the next atomic commit.
            if(snapshot.exists()) snapshot.delete();
            return recovered;
        }
        throw primary == null ? new IllegalStateException("No valid campaign snapshot") : primary;
    }


    /** Streams snapshot decode and SHA-256 through one pass without allocating a file-sized byte array. */
    private static LoadedSnapshot decodeAndHash(Fi file){
        byte[] bytes = file.readBytes();
        SharedCampaignState decoded;
        try{
            decoded = SharedCampaignCodec.decode(bytes);
        }catch(RuntimeException cleanFailure){
            try{
                decoded = LegacyMdtYSharedCampaignReader.decode(bytes);
            }catch(RuntimeException legacyFailure){
                cleanFailure.addSuppressed(legacyFailure);
                throw cleanFailure;
            }
        }
        return new LoadedSnapshot(decoded, SharedCampaignCodec.sha256(bytes));
    }

    private record LoadedSnapshot(SharedCampaignState state, String sha256){}

    /** Writes one clean schema-26 snapshot atomically while preserving the previous image as a recovery backup. */
    private String writeSnapshotAtomically(SharedCampaignState value){
        byte[] bytes = SharedCampaignCodec.encode(value);
        String hash = SharedCampaignCodec.sha256(bytes);
        Path target = snapshot.file().toPath();
        Path bak = backup.file().toPath();
        try{
            root.mkdirs();
            if(Files.exists(target)) materializeBackup(target, bak);
            writeSnapshotAtomically(bytes);
            return hash;
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    private void materializeBackup(Path target, Path bak) throws IOException{
        long size = Files.size(target);
        try{
            Files.deleteIfExists(bak);
            Files.createLink(bak, target);
            ioCounters.backupHardlinks++;
            // No data rewrite and no extra force: the linked inode was already forced when the primary was written.
        }catch(UnsupportedOperationException | IOException | SecurityException e){
            Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            // The backup is a formal corruption-recovery snapshot, not a best-effort cache. Persist its data
            // before installing the new primary so a power loss cannot leave both the primary and backup without
            // a durable pre-commit image.
            try(FileChannel backupChannel = FileChannel.open(bak, StandardOpenOption.WRITE)){ backupChannel.force(true); }
            ioCounters.backupBytes += size;
            ioCounters.backupCopies++;
            ioCounters.forceCount++;
        }
    }

    /** One durable directory-entry barrier per open store (new campaign root). Regular atomic-rename paths skip this. */
    private void forceDirectoryOnce(Path directory){
        if(directoryBarrierDone) return;
        try(FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)){
            dir.force(true);
            ioCounters.forceCount++;
        }catch(Exception ignored){}
        directoryBarrierDone = true;
    }

    private void writeSnapshotAtomically(byte[] bytes){
        Path target = snapshot.file().toPath();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Path bak = backup.file().toPath();
        try{
            boolean createdDirectories = !Files.exists(target.getParent());
            Files.createDirectories(target.getParent());
            if(createdDirectories) forceDirectoryOnce(target.getParent());
            try(FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)){
                out.write(java.nio.ByteBuffer.wrap(bytes));
                out.force(true);
                ioCounters.snapshotBytes += bytes.length;
                ioCounters.forceCount++;
            }
            if(Files.exists(target)){
                materializeBackup(target, bak);
            }
            boolean atomicMoved = true;
            try{
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }catch(AtomicMoveNotSupportedException ignored){
                atomicMoved = false;
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if(!atomicMoved) forceDirectoryOnce(target.getParent());
        }catch(IOException e){
            try{ Files.deleteIfExists(temp); }catch(IOException ignored){}
            throw new UncheckedIOException(e);
        }
    }

    private void appendJournal(long fromRevision, long toRevision, String actor, String type, String snapshotHash, String previousSnapshotHash){
        if(journalRevision > fromRevision) throw new IllegalStateException("Audit journal is newer than the mutation base: " + journalRevision + " > " + fromRevision);
        //If a previous append failed after its authoritative snapshot committed, explicitly bridge the missing audit
        //revision before writing the next record. The bridge hash authenticates the current pre-mutation snapshot.
        long priorJournalRevision = journalRevision;
        java.util.List<JournalRecord> records = new java.util.ArrayList<>();
        long bridgeFrom = journalRevision;
        while(bridgeFrom >= 0L && bridgeFrom < fromRevision){
            records.add(new JournalRecord(bridgeFrom, bridgeFrom + 1L, "system", "mindustry-y:audit-gap-recovery", previousSnapshotHash));
            bridgeFrom++;
        }
        records.add(new JournalRecord(fromRevision, toRevision, actor, type, snapshotHash));
        try{
            writeJournalRecords(records);
        }catch(IOException e){
            //Restore the pre-batch journalRevision so a later append can bridge the same gap again.
            journalRevision = priorJournalRevision;
            throw new UncheckedIOException(e);
        }
    }

    private record JournalRecord(long fromRevision, long toRevision, String actor, String type, String snapshotHash){}

    /** Writes a gap-bridge + tip as one journal append with a single durability barrier. */
    private void writeJournalRecords(java.util.List<JournalRecord> records) throws IOException{
        if(records.isEmpty()) return;
        long originalLength = journal.exists() ? journal.length() : 0L;
        long highestTo = records.get(records.size() - 1).toRevision();
        try{
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try(FileOutputStream fos = new FileOutputStream(journal.file(), true); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))){
                for(JournalRecord record : records){
                    payloadBytes.reset();
                    try(DataOutputStream payload = new DataOutputStream(payloadBytes)){
                        payload.writeLong(record.fromRevision()); payload.writeLong(record.toRevision()); payload.writeLong(Time.millis());
                        payload.writeUTF(record.actor() == null ? "" : record.actor());
                        payload.writeUTF(record.type() == null ? "" : record.type());
                        payload.writeUTF(record.snapshotHash());
                    }
                    byte[] data = payloadBytes.toByteArray();
                    CRC32 crc = new CRC32(); crc.update(data);
                    out.writeInt(journalMagic); out.writeInt(data.length); out.write(data); out.writeLong(crc.getValue());
                    ioCounters.journalBytes += 4 + 4 + data.length + 8;
                }
                out.flush(); fos.getFD().sync();
                ioCounters.forceCount++;
            }
            journalRevision = highestTo;
        }catch(IOException e){
            //Never leave a torn tail that would make an otherwise valid authoritative snapshot impossible to reopen.
            try(RandomAccessFile file = new RandomAccessFile(journal.file(), "rw")){ file.setLength(originalLength); file.getFD().sync(); }
            catch(IOException rollback){ e.addSuppressed(rollback); }
            throw e;
        }
    }

    private void verifyJournal(){
        journalRevision = -1L;
        if(!journal.exists()) return;
        try(RandomAccessFile file = new RandomAccessFile(journal.file(), "rw")){
            long lastRevision = -1L;
            String lastHash = "";
            long recoveredSnapshotJournalEnd = -1L;
            boolean authorityFenceAfterRecoveredSnapshot = false;
            while(file.getFilePointer() < file.length()){
                long recordStart = file.getFilePointer();
                int magic;
                try{ magic = file.readInt(); }catch(EOFException done){ break; }
                if(magic != journalMagic) throw new IOException("Invalid shared campaign journal header");
                int length = file.readInt();
                if(length < 0 || length > 1_048_576) throw new IOException("Invalid journal record length: " + length);
                byte[] data = new byte[length]; file.readFully(data); long expectedCrc = file.readLong();
                CRC32 crc = new CRC32(); crc.update(data); if(crc.getValue() != expectedCrc) throw new IOException("Journal checksum mismatch");
                long from, to; String hash, mutationType;
                try(DataInputStream record = new DataInputStream(new ByteArrayInputStream(data))){
                    from = record.readLong();
                    to = record.readLong();
                    record.readLong();
                    record.readUTF();
                    mutationType = record.readUTF();
                    hash = record.readUTF();
                    if(to != from + 1L) throw new IOException("Journal revision does not advance by one: " + from + " -> " + to);
                    if(lastRevision >= 0L && from != lastRevision) throw new IOException("Journal revision chain is discontinuous at " + from + ", expected " + lastRevision);
                    if(record.read() != -1) throw new IOException("Trailing bytes in journal record");
                }
                lastRevision = to;
                lastHash = hash;

                // A backup snapshot is exactly the previous authoritative snapshot preserved by atomic commit.
                // If the primary snapshot is corrupt, newer journal entries describe the discarded primary and
                // cannot be replayed (the audit journal intentionally stores hashes, not mutation payloads).
                // Recovery is safe only after the complete journal prefix is validated and its record for the
                // backup revision authenticates the exact backup bytes. Then trim only the now-orphaned suffix.
                if(recoveredFromBackup && to == state.revision){
                    if(!MessageDigest.isEqual(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII), openedSnapshotHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))){
                        throw new IOException("Backup snapshot hash does not match audit journal at revision " + state.revision);
                    }
                    recoveredSnapshotJournalEnd = file.getFilePointer();
                }
                if(recoveredFromBackup && to > state.revision){
                    if(recoveredSnapshotJournalEnd < 0L){
                        throw new IOException("Audit journal skipped recovered backup revision " + state.revision + " at record offset " + recordStart);
                    }
                    // Ordinary strategic commits after the preserved backup can be discarded because the audit
                    // journal stores hashes, not replayable mutation payloads. Authority transfer is different: once
                    // a migration fence has committed, recovering a pre-fence backup would resurrect the old writable
                    // host. Authority generations are monotonic, so fail closed instead of trimming that evidence.
                    if("shared-campaign:prepare-host-migration".equals(mutationType)) authorityFenceAfterRecoveredSnapshot = true;
                }
            }

            if(recoveredFromBackup){
                if(recoveredSnapshotJournalEnd < 0L){
                    throw new IOException("Audit journal does not authenticate recovered backup revision " + state.revision);
                }
                if(authorityFenceAfterRecoveredSnapshot){
                    throw new IOException("Refusing to recover a pre-migration backup after an authority fence was durably committed");
                }
                long originalLength = file.length();
                if(recoveredSnapshotJournalEnd < originalLength){
                    file.setLength(recoveredSnapshotJournalEnd);
                    file.getFD().sync();
                    Log.warn("Trimmed shared campaign audit journal to recovered backup revision @ (discarded @ bytes)", state.revision, originalLength - recoveredSnapshotJournalEnd);
                }
                journalRevision = state.revision;
                return;
            }
            journalRevision = lastRevision;
            if(lastRevision > state.revision) throw new IOException("Journal revision is newer than snapshot: " + lastRevision + " > " + state.revision);
            if(lastRevision == state.revision && !lastHash.isEmpty()){
                String snapshotHash = openedSnapshotHash.isBlank() ? SharedCampaignCodec.sha256(SharedCampaignCodec.encode(state)) : openedSnapshotHash;
                if(!MessageDigest.isEqual(lastHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII), snapshotHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))){
                    throw new IOException("Journal tip hash does not match authoritative snapshot");
                }
            }
        }catch(IOException e){
            throw new UncheckedIOException("Shared campaign journal is corrupt", e);
        }
    }

    private void ensureOpen(){ if(state == null) throw new IllegalStateException("Shared campaign store is not open"); }

    @Override
    public void close(){
        stopCoalesceFlusher();
        rw.writeLock().lock();
        try{
            // Graceful close drains coalesced work so a reopen never depends on crash-loss of unflushed leases.
            if(state != null && coalescedPending){
                try{
                    flushCoalescedLocked();
                }catch(Throwable error){
                    Log.err("Shared campaign coalesced flush failed during close", error);
                }
            }
            state = null;
            if(processLock != null) processLock.release();
            if(lockChannel != null) lockChannel.close();
            processLock = null; lockChannel = null; journalRevision = -1L; recoveredFromBackup = false; openedSnapshotHash = "";
            coalescedPending = false;
            coalescedSinceMillis = 0L;
            directoryBarrierDone = false;
            walRecordCount = 0;
            walBytesOnDisk = 0L;
            walFirstAtMillis = 0L;
            synchronized(mutationAdmission){
                mutationsSealed = false;
                mutationSealOwner = null;
                inFlightMutations = 0;
                mutationAdmission.notifyAll();
            }
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }finally{
            rw.writeLock().unlock();
        }
    }
}
