package mindustry.campaign.shared.io;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * Transactional restore-point manager. Archives contain a per-entry SHA-256 manifest and are verified before they are
 * published. Restoration extracts into a sibling staging directory and replaces the destination only after validating
 * the campaign snapshot, manifest, path bounds and archive expansion limits.
 */
public class SharedCampaignBackupService{
    private static final int format = 1;
    private static final int maxEntries = 100_000;
    private static final long maxEntryBytes = 512L * 1024L * 1024L;
    private static final long maxArchiveExpandedBytes = 8L * 1024L * 1024L * 1024L;
    private static final String manifestName = "MINDUSTRYY-BACKUP.MANIFEST";
    // Live coordinators commit member heartbeats continuously; two attempts with a 2-10ms nap could never
    // outwait a multi-second whole-tree stream, so transient entry churn (world saves, disappearing
    // sidecars) gets a short exponential window before the fail-fast error surfaces.
    private static final int captureAttempts = 5;
    private static final String[] snapshotFileNames = {"campaign.mycp", "campaign.mycp.bak", "campaign.journal", "campaign.wal"};
    private final SharedCampaignStore store;
    private final Fi root;

    public SharedCampaignBackupService(SharedCampaignStore store){
        this.store = Objects.requireNonNull(store);
        this.root = store.root();
    }

    private SharedCampaignBackupService(){
        this.store = null;
        this.root = null;
    }

    /** Verifies and transactionally restores an archive without opening the restored campaign. */
    public static void restoreArchive(Fi archive, Fi destination){
        new SharedCampaignBackupService().restoreTo(archive, destination);
    }

    public BackupState create(String actorId, String displayName, String reason, int keep){
        Fi directory = root.child("backups"); directory.mkdirs();
        CapturedBackup captured = null;
        Throwable lastChange = null;

        for(int attempt = 0; attempt < captureAttempts && captured == null; attempt++){
            Fi temp = directory.child(".capture-" + UUID.randomUUID() + ".tmp");
            try{
                CapturedBackup attemptCapture = writeArchive(temp);
                verifyArchive(temp);
                captured = attemptCapture;
            }catch(SnapshotChangedException changed){
                lastChange = changed;
                temp.delete();
                backoffCaptureAttempt(attempt);
            }catch(IOException error){
                if(isMissingFile(error)){
                    lastChange = error;
                    temp.delete();
                    backoffCaptureAttempt(attempt);
                }else{
                    temp.delete();
                    throw new UncheckedIOException(error);
                }
            }catch(UncheckedIOException wrapped){
                Throwable cause = wrapped.getCause();
                if(cause instanceof FileNotFoundException || cause instanceof NoSuchFileException){
                    lastChange = cause;
                    temp.delete();
                    backoffCaptureAttempt(attempt);
                }else{
                    temp.delete();
                    throw wrapped;
                }
            }catch(Throwable error){
                temp.delete();
                if(isMissingFile(error)){
                    lastChange = error;
                    backoffCaptureAttempt(attempt);
                    continue;
                }
                if(error instanceof RuntimeException runtime) throw runtime;
                throw new UncheckedIOException(error instanceof IOException io ? io : new IOException(error));
            }
        }

        if(captured == null){
            throw new IllegalStateException("Campaign files kept changing while creating a restore point; retry shortly", lastChange);
        }

        SharedCampaignState snapshot = captured.snapshot;
        String id = Time.millis() + "-r" + snapshot.revision + "-" + UUID.randomUUID().toString().substring(0, 8);
        String safeName = sanitize(displayName == null || displayName.isBlank() ? reason : displayName);
        Fi archive = directory.child(id + "-" + safeName + ".mycb");
        atomicMove(captured.temp, archive);
        BackupState metadata = new BackupState();
        metadata.backupId = id;
        metadata.displayName = displayName == null || displayName.isBlank() ? safeName : displayName.trim();
        metadata.reason = reason == null ? "manual" : reason;
        metadata.relativePath = relative(archive);
        metadata.sha256 = SharedFileDigests.sha256(archive);
        metadata.createdAt = Time.millis();
        metadata.campaignRevision = snapshot.revision;
        metadata.sizeBytes = archive.length();
        metadata.complete = true;
        store.transact(actorId, "mindustry-y:create-restore-point", state -> {
            state.backups.add(metadata);
            state.backups.sort(Comparator.comparingLong(value -> value.createdAt));
            while(state.backups.size > Math.max(1, Math.min(128, keep))) state.backups.remove(0);
        });
        pruneOrphanArchives(store.snapshot());
        return metadata;
    }

    /**
     * Two-phase capture. Phase 1 holds the exclusive store lock only long enough to snapshot state and read the
     * store-authoritative files (mycp/.bak/journal/WAL) as bytes, so concurrent commits can never desynchronize
     * them — the previous design re-verified them against disk after the stream and failed whenever a heartbeat
     * committed in between. Phase 2 streams everything else (Action/sector worlds, immutable model sidecars)
     * outside the lock so heartbeats/startAction are not stalled for a best-effort backup; churn there still
     * costs a per-entry retry.
     */
    private CapturedBackup writeArchive(Fi output) throws IOException{
        AuthoritativeCapture auth = store.withStableCampaignView(() -> {
            SharedCampaignState state = store.snapshot();
            if(state == null) throw new SnapshotChangedException("Store has no campaign snapshot to capture");
            Seq<CapturedFile> captured = new Seq<>();
            try{
                for(String name : snapshotFileNames){
                    Fi file = root.child(name);
                    if(!file.exists()){
                        if("campaign.mycp".equals(name)) throw new SnapshotChangedException("Store snapshot file disappeared during capture");
                        continue;
                    }
                    captured.add(new CapturedFile(name, Files.readAllBytes(file.file().toPath()), file.lastModified()));
                }
            }catch(IOException error){
                throw new UncheckedIOException(error);
            }
            return new AuthoritativeCapture(state, captured);
        });

        ObjectMap<String, ManifestEntry> manifest = new ObjectMap<>();
        try(ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output.write(false)))){
            Seq<Fi> files = new Seq<>();
            collect(root, root, files);
            files.sort(Comparator.comparing(this::relative));
            for(Fi file : files){
                String path = relative(file);
                if(isSnapshotFile(path)) continue;
                streamFile(zip, manifest, path, file);
            }
            for(CapturedFile file : auth.files()){
                ZipEntry zipEntry = new ZipEntry(file.path()); zipEntry.setTime(file.modifiedAt());
                zip.putNextEntry(zipEntry); zip.write(file.data()); zip.closeEntry();
                manifest.put(file.path(), new ManifestEntry(file.path(), file.data().length, file.modifiedAt(), sha256(file.data())));
            }
            long total = 0L;
            for(ManifestEntry entry : manifest.values()) total = Math.addExact(total, entry.size);
            if(manifest.size > maxEntries) throw new IOException("Backup contains too many files");
            if(total > maxArchiveExpandedBytes) throw new IOException("Backup exceeds expanded-size limit");
            byte[] manifestBytes = encodeManifest(auth.state(), manifest);
            ZipEntry manifestEntry = new ZipEntry(manifestName); manifestEntry.setTime(Time.millis());
            zip.putNextEntry(manifestEntry); zip.write(manifestBytes); zip.closeEntry();
        }
        return new CapturedBackup(auth.state(), output);
    }

    private void streamFile(ZipOutputStream zip, ObjectMap<String, ManifestEntry> manifest, String path, Fi file) throws IOException{
        if(manifest.size >= maxEntries) throw new IOException("Backup contains too many files");
        long expectedSize = file.length();
        if(expectedSize < 0 || expectedSize > maxEntryBytes) throw new IOException("Backup entry is too large: " + path);
        MessageDigest digest = digest(); long written = 0L;
        ZipEntry zipEntry = new ZipEntry(path); zipEntry.setTime(file.lastModified());
        zip.putNextEntry(zipEntry);
        try(InputStream input = new BufferedInputStream(file.read())){
            byte[] buffer = new byte[64 * 1024];
            for(int read; (read = input.read(buffer)) >= 0; ){
                if(read == 0) continue;
                written += read;
                if(written > maxEntryBytes) throw new SnapshotChangedException("Backup entry grew while reading: " + path);
                digest.update(buffer, 0, read);
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
        if(written != expectedSize) throw new SnapshotChangedException("Backup entry changed while reading: " + path);
        manifest.put(path, new ManifestEntry(path, written, file.lastModified(), hex(digest.digest())));
    }

    private static boolean isSnapshotFile(String path){
        for(String name : snapshotFileNames) if(name.equals(path)) return true;
        return false;
    }

    private static void backoffCaptureAttempt(int attempt){
        try{
            Thread.sleep(Math.min(250L, 10L << attempt));
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Restore point capture interrupted", interrupted));
        }
    }

    private static ObjectMap<String, ManifestEntry> scanManifest(Fi root) throws IOException{
        Seq<Fi> files = new Seq<>();
        collect(root, root, files);
        files.sort(Comparator.comparing(file -> relative(root, file)));
        ObjectMap<String, ManifestEntry> out = new ObjectMap<>(files.size);
        long total = 0L;
        for(Fi file : files){
            String path = relative(root, file);
            long expected = file.length();
            if(expected < 0 || expected > maxEntryBytes) throw new IOException("Backup entry is too large: " + path);
            total = Math.addExact(total, expected);
            if(total > maxArchiveExpandedBytes) throw new IOException("Backup exceeds expanded-size limit");
            MessageDigest digest = digest(); long readTotal = 0L;
            try(InputStream input = new BufferedInputStream(file.read())){
                byte[] buffer = new byte[64 * 1024];
                for(int read; (read = input.read(buffer)) >= 0; ){
                    if(read == 0) continue;
                    readTotal += read;
                    if(readTotal > maxEntryBytes) throw new SnapshotChangedException("Backup entry grew while verifying: " + path);
                    digest.update(buffer, 0, read);
                }
            }
            if(readTotal != expected) throw new SnapshotChangedException("Backup entry changed while verifying: " + path);
            out.put(path, new ManifestEntry(path, readTotal, file.lastModified(), hex(digest.digest())));
        }
        return out;
    }

    private static boolean isMissingFile(Throwable error){
        for(Throwable current = error; current != null; current = current.getCause()){
            if(current instanceof FileNotFoundException || current instanceof NoSuchFileException) return true;
        }
        return false;
    }

    private static boolean sameManifest(ObjectMap<String, ManifestEntry> left, ObjectMap<String, ManifestEntry> right){
        if(left.size != right.size) return false;
        for(ObjectMap.Entry<String, ManifestEntry> entry : left){
            ManifestEntry other = right.get(entry.key);
            if(other == null || other.size != entry.value.size || !Objects.equals(other.sha256, entry.value.sha256)) return false;
        }
        return true;
    }

    private static final class SnapshotChangedException extends RuntimeException{
        SnapshotChangedException(String message){ super(message); }
    }

    private static final class CapturedBackup{
        final SharedCampaignState snapshot;
        final Fi temp;
        CapturedBackup(SharedCampaignState snapshot, Fi temp){ this.snapshot = snapshot; this.temp = temp; }
    }

    /** Store-authoritative file bytes read under the exclusive store lock. */
    private record CapturedFile(String path, byte[] data, long modifiedAt){}

    /** In-memory snapshot plus the authoritative file set captured atomically with it. */
    private record AuthoritativeCapture(SharedCampaignState state, Seq<CapturedFile> files){}

    public void restoreTo(Fi archive, Fi destination){
        if(archive == null || !archive.exists()) throw new IllegalArgumentException("Backup archive does not exist");
        if(destination == null) throw new IllegalArgumentException("Restore destination is required");
        Manifest manifest = verifyArchive(archive);
        Fi parent = destination.parent(); parent.mkdirs();
        try{ SharedDirectoryInstall.recoverParent(parent.file().toPath()); }
        catch(IOException error){ throw new UncheckedIOException("Failed to recover an interrupted Shared Campaign restore", error); }
        Fi staging = parent.child("." + destination.name() + ".restore-" + UUID.randomUUID());
        staging.mkdirs();
        try(ZipInputStream zip = new ZipInputStream(new BufferedInputStream(archive.read()))){
            int count = 0; long total = 0L;
            for(ZipEntry entry; (entry = zip.getNextEntry()) != null; ){
                if(entry.isDirectory() || manifestName.equals(entry.getName())) continue;
                if(count++ >= maxEntries) throw new IOException("Backup contains too many entries");
                String path = safePath(entry.getName());
                ManifestEntry expected = manifest.entries.get(path);
                if(expected == null) throw new IOException("Backup entry is absent from manifest: " + path);
                Fi output = staging.child(path);
                ensureInside(staging, output); output.parent().mkdirs();
                MessageDigest digest = digest(); long written = 0L;
                try(OutputStream out = new BufferedOutputStream(output.write(false))){
                    byte[] buffer = new byte[64 * 1024];
                    for(int read; (read = zip.read(buffer)) >= 0; ){
                        if(read == 0) continue;
                        written += read; total += read;
                        if(written > maxEntryBytes || total > maxArchiveExpandedBytes) throw new IOException("Backup expansion limit exceeded");
                        digest.update(buffer, 0, read); out.write(buffer, 0, read);
                    }
                }
                if(written != expected.size || !hex(digest.digest()).equals(expected.sha256)) throw new IOException("Backup entry verification failed: " + path);
                if(expected.modifiedAt > 0) output.file().setLastModified(expected.modifiedAt);
            }
            Fi stateFile = staging.child("campaign.mycp");
            if(!stateFile.exists()) throw new IOException("Backup has no campaign snapshot");
            SharedCampaignState decoded;
            try(InputStream input = new BufferedInputStream(stateFile.read())){ decoded = SharedCampaignCodec.decode(input); }
            if(decoded.revision != manifest.campaignRevision) throw new IOException("Backup revision does not match manifest");
            SharedDirectoryInstall.install(staging.file().toPath(), destination.file().toPath());
        }catch(Throwable error){
            if(staging.exists()) staging.deleteDirectory();
            if(error instanceof RuntimeException runtime) throw runtime;
            throw new UncheckedIOException(error instanceof IOException io ? io : new IOException(error));
        }
    }

    public Manifest verifyArchive(Fi archive){
        ObjectMap<String, ManifestEntry> actual = new ObjectMap<>();
        Manifest manifest = null;
        int count = 0; long total = 0L;
        try(ZipInputStream zip = new ZipInputStream(new BufferedInputStream(archive.read()))){
            for(ZipEntry entry; (entry = zip.getNextEntry()) != null; ){
                if(entry.isDirectory()) continue;
                if(count++ >= maxEntries) throw new IOException("Backup contains too many entries");
                String path = safePath(entry.getName());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); MessageDigest digest = digest(); long length = 0L;
                byte[] buffer = new byte[64 * 1024];
                for(int read; (read = zip.read(buffer)) >= 0; ){
                    if(read == 0) continue;
                    length += read; total += read;
                    if(length > maxEntryBytes || total > maxArchiveExpandedBytes) throw new IOException("Backup expansion limit exceeded");
                    digest.update(buffer, 0, read);
                    if(manifestName.equals(path)) bytes.write(buffer, 0, read);
                }
                if(manifestName.equals(path)) manifest = decodeManifest(bytes.toByteArray());
                else actual.put(path, new ManifestEntry(path, length, entry.getTime(), hex(digest.digest())));
            }
        }catch(IOException error){ throw new UncheckedIOException(error); }
        if(manifest == null) throw new IllegalStateException("Backup manifest is missing");
        if(manifest.format != format) throw new IllegalStateException("Unsupported backup format " + manifest.format);
        if(actual.size != manifest.entries.size) throw new IllegalStateException("Backup manifest entry count mismatch");
        for(ObjectMap.Entry<String, ManifestEntry> entry : manifest.entries){
            ManifestEntry observed = actual.get(entry.key);
            if(observed == null || observed.size != entry.value.size || !observed.sha256.equals(entry.value.sha256)) throw new IllegalStateException("Backup entry verification failed: " + entry.key);
        }
        return manifest;
    }

    private void pruneOrphanArchives(SharedCampaignState state){
        ObjectSet<String> retained = new ObjectSet<>();
        for(BackupState backup : state.backups) retained.add(backup.relativePath);
        Fi directory = root.child("backups");
        if(!directory.exists()) return;
        for(Fi file : directory.list("mycb")) if(!retained.contains(relative(file))) file.delete();
    }

    private static void collect(Fi root, Fi current, Seq<Fi> output) throws IOException{
        Path rootPath = root.file().toPath().toAbsolutePath().normalize();
        Path currentPath = current.file().toPath().toAbsolutePath().normalize();
        if(Files.isSymbolicLink(currentPath)) throw new IOException("Shared Campaign backups do not permit symbolic links: " + currentPath);
        if(!currentPath.startsWith(rootPath)) throw new IOException("Backup traversal escaped campaign root: " + currentPath);
        for(Fi child : current.list()){
            Path childPath = child.file().toPath().toAbsolutePath().normalize();
            String relative = relative(root, child);
            if(relative.equals("campaign.lock") || relative.startsWith("backups/") || relative.startsWith(".import-staging-") || relative.contains("/.restore-") || relative.endsWith(".tmp")) continue;
            if(Files.isSymbolicLink(childPath)) throw new IOException("Shared Campaign backups do not permit symbolic links: " + relative);
            if(!childPath.startsWith(rootPath)) throw new IOException("Backup traversal escaped campaign root: " + relative);
            if(Files.isDirectory(childPath, LinkOption.NOFOLLOW_LINKS)) collect(root, child, output);
            else if(Files.isRegularFile(childPath, LinkOption.NOFOLLOW_LINKS)) output.add(child);
            else if(!Files.exists(childPath, LinkOption.NOFOLLOW_LINKS)) continue; // raced with atomic temp delete
            else throw new IOException("Unsupported Shared Campaign backup entry type: " + relative);
        }
    }

    private String relative(Fi file){ return relative(root, file); }
    private static String relative(Fi root, Fi file){
        String base = root.file().toPath().toAbsolutePath().normalize().toString();
        String value = file.file().toPath().toAbsolutePath().normalize().toString();
        if(!value.startsWith(base)) throw new IllegalArgumentException("File is outside campaign root");
        String result = value.substring(base.length()).replace('\\', '/');
        return result.startsWith("/") ? result.substring(1) : result;
    }

    private static String safePath(String path) throws IOException{
        if(path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains("\\") || path.contains("\0")) throw new IOException("Unsafe backup path");
        Path normalized = Paths.get(path).normalize();
        if(normalized.isAbsolute() || normalized.startsWith("..")) throw new IOException("Unsafe backup path: " + path);
        return normalized.toString().replace('\\', '/');
    }

    private static void ensureInside(Fi root, Fi output) throws IOException{
        Path base = root.file().toPath().toAbsolutePath().normalize();
        Path target = output.file().toPath().toAbsolutePath().normalize();
        if(!target.startsWith(base)) throw new IOException("Backup path escapes destination");
    }

    private static byte[] encodeManifest(SharedCampaignState state, ObjectMap<String, ManifestEntry> entries) throws IOException{
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(bytes)){
            out.writeInt(0x4d59424b); out.writeInt(format); out.writeUTF(state.campaignId); out.writeLong(state.revision); out.writeLong(Time.millis());
            Seq<String> keys = entries.keys().toSeq().sort(); out.writeInt(keys.size);
            for(String key : keys){ ManifestEntry value = entries.get(key); out.writeUTF(value.path); out.writeLong(value.size); out.writeLong(value.modifiedAt); out.writeUTF(value.sha256); }
        }
        return bytes.toByteArray();
    }

    private static Manifest decodeManifest(byte[] data) throws IOException{
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            if(in.readInt() != 0x4d59424b) throw new IOException("Invalid backup manifest");
            int version = in.readInt(); String campaignId = in.readUTF(); long revision = in.readLong(); long createdAt = in.readLong();
            int count = in.readInt(); if(count < 0 || count > maxEntries) throw new IOException("Invalid manifest entry count");
            ObjectMap<String, ManifestEntry> entries = new ObjectMap<>(count);
            for(int i = 0; i < count; i++){
                String path = safePath(in.readUTF()); long size = in.readLong(); long modified = in.readLong(); String hash = in.readUTF();
                if(size < 0 || size > maxEntryBytes || hash.length() != 64 || entries.containsKey(path)) throw new IOException("Invalid manifest entry: " + path);
                entries.put(path, new ManifestEntry(path, size, modified, hash));
            }
            if(in.read() != -1) throw new IOException("Trailing backup manifest data");
            return new Manifest(version, campaignId, revision, createdAt, entries);
        }
    }

    private static String sanitize(String value){
        String result = value == null ? "backup" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        if(result.isBlank()) result = "backup";
        return result.length() > 64 ? result.substring(0, 64) : result;
    }

    private static void atomicMove(Fi source, Fi target){
        try{
            target.parent().mkdirs();
            try{ Files.move(source.file().toPath(), target.file().toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ignored){ Files.move(source.file().toPath(), target.file().toPath(), StandardCopyOption.REPLACE_EXISTING); }
        }catch(IOException error){ throw new UncheckedIOException(error); }
    }

    private static MessageDigest digest(){ try{ return MessageDigest.getInstance("SHA-256"); }catch(NoSuchAlgorithmException error){ throw new AssertionError(error); } }
    private static String sha256(byte[] data){ return hex(digest().digest(data)); }
    private static String hex(byte[] data){ StringBuilder out = new StringBuilder(data.length * 2); for(byte value : data) out.append(String.format(Locale.ROOT, "%02x", value & 0xff)); return out.toString(); }

    private record ManifestEntry(String path, long size, long modifiedAt, String sha256){}
    public record Manifest(int format, String campaignId, long campaignRevision, long createdAt, ObjectMap<String, ManifestEntry> entries){}
}
