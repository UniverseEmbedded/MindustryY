package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import mindustry.campaign.shared.io.SharedDirectoryInstall;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * Authenticated cold-host migration. The archive never contains coordinator credentials. A separately transferred,
 * one-time code authenticates the manifest, while per-file hashes protect every campaign save and journal file.
 */
public class HostMigrationService{
    private static final String manifestName = "migration.manifest";
    private static final int format = 2;
    private static final long maximumBundleBytes = 8L * 1024L * 1024L * 1024L;
    private static final int recoveryMagic = 0x53434d52; // SCMR
    private static final int legacyRecoveryMagic = 0x4d594d52; // MYMR
    private static final int recoveryVersion = 1;
    private static final String recoveryPath = "credentials/migration-transfer-recovery.bin";
    private final SharedCampaignStore store;

    public HostMigrationService(SharedCampaignStore store){ this.store = store; }

    public record MigrationBundle(Fi file, String transferCode, long expiresAt, long authorityGeneration){}

    /**
     * Creates a transfer bundle after all actions have reached a committed, non-live state. The returned transfer code
     * must be delivered to the target host through a different channel than the archive.
     */
    public MigrationBundle exportBundle(Fi target, String currentHostId, String targetHostId, long validForMillis){
        SharedCampaignState before = store.snapshot();
        for(ActionState action : before.actions.values()){
            if(action.status.isLive() || action.status == ActionStatus.preparing) throw new IllegalStateException("All actions must be suspended before host migration");
        }
        if(before.researchTransactions.values().toSeq().contains(transaction -> transaction.status == ResearchTransactionStatus.preparing)){
            throw new IllegalStateException("A research transaction is still preparing");
        }
        if(before.transportTransactions.values().toSeq().contains(transaction -> transaction.status == TransportTransactionStatus.preparing || transaction.status == TransportTransactionStatus.committed)){
            throw new IllegalStateException("A live-action transport transaction is not fully acknowledged");
        }
        if(targetHostId == null || targetHostId.isBlank()) throw new IllegalArgumentException("Target host identity is required");
        if(currentHostId == null || currentHostId.isBlank() || !currentHostId.equals(before.authorityHostId)) throw new SecurityException("Only the fenced authority host may migrate this campaign");
        if(before.migrationPending) throw new IllegalStateException("A host migration is already pending");

        long expires = Time.millis() + Math.max(60_000L, validForMillis);
        String nonce = UUID.randomUUID().toString();
        String transferCode = randomCode();
        long nextGeneration = Math.addExact(before.authorityGeneration, 1L);

        // Build the exact target-side logical snapshot before fencing the source authority. The preview uses the next
        // durable revision because the final CAS transaction below is the only source-side mutation in this operation.
        SharedCampaignState preview = SharedCampaignStateCopy.copy(before);
        applyMigrationFence(preview, targetHostId, nonce, expires, nextGeneration);
        preview.revision = Math.addExact(before.revision, 1L);
        preview.updatedAt = Time.millis();
        preview.validate();

        try{
            buildBundleFile(store.root(), preview, target, transferCode, targetHostId, nonce, expires);
            // Install the fully-written bundle before changing authority. A crash here leaves the original host fully
            // authoritative; the transfer code is not returned until the fence commit succeeds.
            // Persist the out-of-band transfer secret locally before the authority fence. If the process crashes after
            // the fence commit but before exportBundle() can return to the UI, the source host is no longer allowed to
            // reopen the campaign normally; this receipt is therefore the only safe way to recover the already-built
            // bundle/code pair. credentials/ is excluded from migration archives.
            writeRecoveryReceipt(store.root(), new RecoveryReceipt(target.absolutePath(), transferCode, targetHostId, nonce, expires, nextGeneration));

            SharedCampaignState migrated;
            try{
                migrated = store.transact(currentHostId, "shared-campaign:prepare-host-migration", state -> {
                    if(state.revision != before.revision) throw new IllegalStateException("Campaign changed while the host migration bundle was being prepared");
                    if(!Objects.equals(state.authorityHostId, currentHostId) || state.migrationPending) throw new IllegalStateException("Campaign authority changed while migration was being prepared");
                    applyMigrationFence(state, targetHostId, nonce, expires, nextGeneration);
                });
            }catch(Throwable commitFailure){
                target.delete();
                deleteRecoveryReceipt(store.root());
                throw commitFailure;
            }
            if(migrated.revision != preview.revision || migrated.authorityGeneration != preview.authorityGeneration){
                target.delete();
                deleteRecoveryReceipt(store.root());
                throw new IllegalStateException("Migration fence commit does not match the prepared bundle");
            }
            return new MigrationBundle(target, transferCode, expires, migrated.authorityGeneration);
        }catch(IOException e){
            target.delete();
            deleteRecoveryReceipt(store.root());
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reissues an expired/lost transfer artifact for the exact same already-fenced authority generation. This never
     * reclaims source authority and therefore cannot create a second live coordinator. Only transfer-artifact metadata
     * (expiry/code/archive) is renewed; strategic campaign state and the target host/generation/nonce remain unchanged.
     */
    public static MigrationBundle reissuePendingBundle(Fi campaignDirectory, Fi target, long validForMillis){
        Objects.requireNonNull(campaignDirectory, "campaignDirectory");
        Objects.requireNonNull(target, "target");
        Fi receiptFile = campaignDirectory.child(recoveryPath);
        if(!receiptFile.exists()) throw new IllegalStateException("Pending migration has no local recovery receipt");
        Fi snapshotFile = campaignDirectory.child("campaign.mycp");
        if(!snapshotFile.exists()) throw new IllegalStateException("Pending migration recovery has no campaign snapshot");
        SharedCampaignState state;
        try(InputStream input = new BufferedInputStream(snapshotFile.read())){
            state = SharedCampaignCodec.decode(input);
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
        RecoveryReceipt receipt = readRecoveryReceipt(receiptFile);
        validateRecoveryIdentity(state, receipt);
        long expires = Time.millis() + Math.max(60_000L, validForMillis);
        String transferCode = randomCode();
        SharedCampaignState preview = SharedCampaignStateCopy.copy(state);
        preview.migrationExpiresAt = expires;
        preview.updatedAt = Time.millis();
        preview.validate();
        try{
            buildBundleFile(campaignDirectory, preview, target, transferCode, state.migrationTargetHostId,
                state.migrationNonce, expires);
            RecoveryReceipt renewed = new RecoveryReceipt(target.absolutePath(), transferCode, state.migrationTargetHostId,
                state.migrationNonce, expires, state.authorityGeneration);
            try{
                writeRecoveryReceipt(campaignDirectory, renewed);
            }catch(IOException receiptFailure){
                target.delete();
                throw receiptFailure;
            }
            return new MigrationBundle(target, transferCode, expires, state.authorityGeneration);
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    /**
     * Recovers a migration bundle/code pair after the source process died after fencing itself but before returning the
     * code to the caller. This does not open campaign authority and is therefore valid even when authorityHostId already
     * points at the target host.
     */
    public static MigrationBundle recoverPendingBundle(Fi campaignDirectory){
        Objects.requireNonNull(campaignDirectory, "campaignDirectory");
        Fi receiptFile = campaignDirectory.child(recoveryPath);
        if(!receiptFile.exists()) return null;
        Fi snapshotFile = campaignDirectory.child("campaign.mycp");
        if(!snapshotFile.exists()) throw new IllegalStateException("Pending migration recovery has no campaign snapshot");
        SharedCampaignState state;
        try(InputStream input = new BufferedInputStream(snapshotFile.read())){
            state = SharedCampaignCodec.decode(input);
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
        RecoveryReceipt receipt = readRecoveryReceipt(receiptFile);
        if(!state.migrationPending){
            receiptFile.delete();
            return null;
        }
        validateRecoveryIdentity(state, receipt);
        // Expiry invalidates this artifact, not the already-fenced authority generation. Preserve the receipt so an
        // administrator can safely reissue a new code/bundle for the same target without reclaiming source authority.
        if(receipt.expiresAt() < Time.millis()) return null;
        Fi bundle = new Fi(receipt.bundlePath());
        if(!bundle.exists() || bundle.isDirectory()) throw new IllegalStateException("Pending migration bundle is missing: " + bundle.absolutePath());
        return new MigrationBundle(bundle, receipt.transferCode(), receipt.expiresAt(), receipt.authorityGeneration());
    }

    private static void writeRecoveryReceipt(Fi campaignDirectory, RecoveryReceipt receipt) throws IOException{
        Fi file = campaignDirectory.child(recoveryPath);
        file.parent().mkdirs();
        Path target = file.file().toPath();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try(FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)){
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)));
            out.writeInt(recoveryMagic);
            out.writeInt(recoveryVersion);
            writeBoundedUtf(out, receipt.bundlePath());
            writeBoundedUtf(out, receipt.transferCode());
            writeBoundedUtf(out, receipt.targetHostId());
            writeBoundedUtf(out, receipt.nonce());
            out.writeLong(receipt.expiresAt());
            out.writeLong(receipt.authorityGeneration());
            out.flush();
            channel.force(true);
        }
        moveReplace(temp, target);
        try(FileChannel directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)){ directory.force(true); }catch(Exception ignored){}
    }

    private static RecoveryReceipt readRecoveryReceipt(Fi file){
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(file.read()))){
            int receiptMagic = in.readInt();
            if((receiptMagic != recoveryMagic && receiptMagic != legacyRecoveryMagic) || in.readInt() != recoveryVersion) throw new IOException("Invalid migration recovery receipt");
            RecoveryReceipt receipt = new RecoveryReceipt(readBoundedUtf(in), readBoundedUtf(in), readBoundedUtf(in), readBoundedUtf(in), in.readLong(), in.readLong());
            if(in.read() != -1) throw new IOException("Trailing data in migration recovery receipt");
            return receipt;
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }

    private static void deleteRecoveryReceipt(Fi campaignDirectory){
        try{ campaignDirectory.child(recoveryPath).delete(); }catch(Throwable ignored){}
    }

    private static void writeBoundedUtf(DataOutputStream out, String value) throws IOException{
        byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
        if(bytes.length > 1024 * 1024) throw new IOException("Migration recovery field is too large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readBoundedUtf(DataInputStream in) throws IOException{
        int length = in.readInt();
        if(length < 0 || length > 1024 * 1024) throw new IOException("Invalid migration recovery field length");
        // InputStream.readNBytes(int) is Java 12+ and missing on Android; readFully is Java 1.0 and also
        // fails on a short read, which the length check above expects.
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record RecoveryReceipt(String bundlePath, String transferCode, String targetHostId, String nonce, long expiresAt, long authorityGeneration){}

    private static void validateRecoveryIdentity(SharedCampaignState state, RecoveryReceipt receipt){
        if(!state.migrationPending) throw new IllegalStateException("Campaign is not sealed in a pending host migration");
        if(state.authorityGeneration != receipt.authorityGeneration() ||
            !Objects.equals(state.migrationTargetHostId, receipt.targetHostId()) ||
            !Objects.equals(state.migrationNonce, receipt.nonce())){
            throw new SecurityException("Pending migration recovery receipt does not match the fenced campaign state");
        }
    }

    private static void applyMigrationFence(SharedCampaignState state, String targetHostId, String nonce, long expires, long generation){
        state.authorityGeneration = generation;
        state.authorityHostId = targetHostId;
        state.migrationPending = true;
        state.migrationTargetHostId = targetHostId;
        state.migrationNonce = nonce;
        state.migrationExpiresAt = expires;
        for(ActionState action : state.actions.values()){
            action.hostGeneration = generation;
            action.hostId = targetHostId;
            action.leaseExpiresAt = 0L;
        }
    }

    public static SharedCampaignState importBundle(Fi bundle, Fi destination, String expectedTargetHost, String transferCode){
        if(transferCode == null || transferCode.isBlank()) throw new SecurityException("Migration transfer code is required");
        if(destination.exists() && destination.list().length > 0) throw new IllegalStateException("Migration destination must be empty");

        Fi temporary = destination.sibling(destination.name() + ".import-" + UUID.randomUUID());
        try(ZipFile zip = new ZipFile(bundle.file())){
            ZipEntry manifestEntry = zip.getEntry(manifestName);
            if(manifestEntry == null || manifestEntry.isDirectory()) throw new IOException("Migration manifest missing");
            byte[] manifestData = readLimited(zip.getInputStream(manifestEntry), 4L * 1024L * 1024L);
            ParsedManifest manifest = parseAndAuthenticate(manifestData, expectedTargetHost, transferCode);

            ObjectSet<String> allowed = new ObjectSet<>();
            allowed.add(manifestName);
            for(FileRecord file : manifest.files) allowed.add("campaign/" + file.path);
            Enumeration<? extends ZipEntry> all = zip.entries();
            ObjectSet<String> seenEntries = new ObjectSet<>();
            while(all.hasMoreElements()){
                ZipEntry entry = all.nextElement();
                if(entry.isDirectory()) continue;
                String safe = safeEntry(entry.getName());
                if(!seenEntries.add(safe)) throw new IOException("Duplicate migration entry: " + safe);
                if(!allowed.contains(safe)) throw new IOException("Unexpected migration entry: " + safe);
            }

            temporary.deleteDirectory();
            temporary.mkdirs();
            long total = 0L;
            for(FileRecord file : manifest.files){
                total = Math.addExact(total, file.length);
                if(total > maximumBundleBytes) throw new IOException("Migration bundle exceeds safety limit");
                ZipEntry entry = zip.getEntry("campaign/" + file.path);
                if(entry == null || entry.isDirectory()) throw new IOException("Missing migration file " + file.path);
                Fi output = temporary.child(file.path);
                output.parent().mkdirs();
                MessageDigest digest = sha256Digest();
                long written = 0L;
                try(InputStream in = new BufferedInputStream(zip.getInputStream(entry)); OutputStream out = new BufferedOutputStream(output.write(false))){
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while((read = in.read(buffer)) >= 0){
                        if(read == 0) continue;
                        written += read;
                        if(written > file.length) throw new IOException("Migration file length exceeds manifest: " + file.path);
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                    }
                }
                if(written != file.length || !hex(digest.digest()).equals(file.sha256)) throw new IOException("Migration file checksum mismatch: " + file.path);
            }

            Fi snapshot = temporary.child("campaign.mycp");
            if(!snapshot.exists()) throw new IOException("Migration snapshot missing");
            SharedCampaignState state;
            try(InputStream input = new BufferedInputStream(snapshot.read())){
                state = SharedCampaignCodec.decode(input);
            }
            if(!state.campaignId.equals(manifest.campaignId) || state.authorityGeneration != manifest.generation) throw new IOException("Migration snapshot does not match manifest");
            if(!state.migrationPending || !manifest.targetHost.equals(state.authorityHostId) || !manifest.targetHost.equals(state.migrationTargetHostId)) throw new SecurityException("Migration snapshot is not fenced for this target host");
            if(!Objects.equals(state.migrationNonce, manifest.nonce)) throw new SecurityException("Migration snapshot nonce does not match the signed manifest");
            if(state.migrationExpiresAt != manifest.expires || state.migrationExpiresAt < Time.millis()) throw new SecurityException("Migration snapshot expiry does not match the signed manifest");

            claimImport(manifest, destination.file().toPath());
            // Once this durable claim exists, any install failure is deliberately fail-closed. Automatically deleting
            // the claim after a partially failed directory swap would re-open the exact double-authority replay window
            // this fence exists to prevent. Recovery tooling may inspect the receipt and decide whether to clear it.
            SharedDirectoryInstall.install(temporary.file().toPath(), destination.file().toPath());
            return state;
        }catch(IOException e){
            temporary.deleteDirectory();
            throw new UncheckedIOException(e);
        }catch(RuntimeException e){
            temporary.deleteDirectory();
            throw e;
        }
    }

    private static ParsedManifest parseAndAuthenticate(byte[] data, String expectedTargetHost, String transferCode) throws IOException{
        String text = new String(data, StandardCharsets.UTF_8);
        int signatureAt = text.lastIndexOf("signature=");
        if(signatureAt < 0) throw new IOException("Migration signature missing");
        String canonical = text.substring(0, signatureAt);
        String signatureLine = text.substring(signatureAt).trim();
        String supplied = signatureLine.substring("signature=".length());
        String expected = hmacHex(transferCode, canonical.getBytes(StandardCharsets.UTF_8));
        if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) throw new SecurityException("Migration signature is invalid");

        ObjectMap<String, String> header = new ObjectMap<>();
        Seq<FileRecord> files = new Seq<>();
        ObjectSet<String> paths = new ObjectSet<>();
        for(String line : canonical.split("\n")){
            if(line.isEmpty()) continue;
            if(line.startsWith("file=")){
                String[] values = line.substring(5).split("\\|", -1);
                if(values.length != 3) throw new IOException("Malformed migration file record");
                String path = safeEntry(values[0]);
                if(!paths.add(path)) throw new IOException("Duplicate migration path: " + path);
                long length = Long.parseLong(values[2]);
                if(length < 0) throw new IOException("Negative migration file length");
                files.add(new FileRecord(path, values[1], length));
            }else{
                int at = line.indexOf('=');
                if(at <= 0) throw new IOException("Malformed migration header");
                String key = line.substring(0, at);
                if(header.containsKey(key)) throw new IOException("Duplicate migration header: " + key);
                header.put(key, line.substring(at + 1));
            }
        }
        if(!Integer.toString(format).equals(header.get("format"))) throw new IOException("Unsupported migration format");
        String targetHost = decodeValue(required(header, "targetHost"));
        if(!Objects.equals(expectedTargetHost, targetHost)) throw new SecurityException("Migration bundle is for a different host");
        long expires = Long.parseLong(required(header, "expires"));
        if(expires < Time.millis()) throw new SecurityException("Migration bundle expired");
        String nonce = required(header, "nonce");
        if(nonce.length() > 256) throw new IOException("Migration nonce is too long");
        return new ParsedManifest(required(header, "campaignId"), Long.parseLong(required(header, "generation"), 10), targetHost, expires, nonce, files);
    }

    private static String required(ObjectMap<String, String> values, String key) throws IOException{
        String value = values.get(key);
        if(value == null || value.isBlank()) throw new IOException("Missing migration header: " + key);
        return value;
    }

    /**
     * Machine-local anti-replay fence for cold migration archives. The archive itself is clonable, so a ZIP-internal
     * "consumed" bit cannot provide one-time semantics. This durable host registry prevents the same signed
     * (campaign, authority generation, nonce, target host) tuple from being imported twice by one installation.
     */
    private static Path claimImport(ParsedManifest manifest, Path destination) throws IOException{
        String configured = System.getProperty("mindustry.sharedCampaign.migrationAcceptanceRoot", "").trim();
        if(configured.isEmpty()) configured = System.getProperty("mindustryY.sharedCampaign.migrationAcceptanceRoot", "").trim();
        Path root = configured.isEmpty()
            ? Paths.get(System.getProperty("user.home", "."), ".mindustry", "shared-campaign-migration-acceptance")
            : Paths.get(configured);
        Files.createDirectories(root);
        forceDirectory(root);
        String identity = manifest.campaignId + "\n" + manifest.generation + "\n" + manifest.targetHost + "\n" + manifest.nonce;
        String key = hex(sha256Digest().digest(identity.getBytes(StandardCharsets.UTF_8)));
        Path claim = root.resolve(key + ".accepted");
        String receipt = "campaignId=" + encodeValue(manifest.campaignId) + "\n"
            + "generation=" + manifest.generation + "\n"
            + "targetHost=" + encodeValue(manifest.targetHost) + "\n"
            + "nonce=" + encodeValue(manifest.nonce) + "\n"
            + "destination=" + encodeValue(destination.toAbsolutePath().normalize().toString()) + "\n"
            + "acceptedAt=" + Time.millis() + "\n";
        try(FileChannel channel = FileChannel.open(claim, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)){
            byte[] bytes = receipt.getBytes(StandardCharsets.UTF_8);
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true);
        }catch(FileAlreadyExistsException replay){
            throw new SecurityException("Migration bundle has already been consumed on this host");
        }
        forceDirectory(root);
        return claim;
    }

    private static void forceDirectory(Path directory){
        try(FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)){ channel.force(true); }catch(Exception ignored){}
    }

    private static void buildBundleFile(Fi campaignRoot, SharedCampaignState preview, Fi target, String transferCode,
                                        String targetHostId, String nonce, long expires) throws IOException{
        Fi temporary = target.sibling(target.name() + ".tmp-" + UUID.randomUUID());
        Fi previewFile = target.sibling(target.name() + ".snapshot-" + UUID.randomUUID());
        try{
            target.parent().mkdirs();
            try(OutputStream output = new BufferedOutputStream(previewFile.write(false))){
                SharedCampaignCodec.write(preview, output);
            }
            Seq<MigrationSource> files = collectMigrationSources(campaignRoot, previewFile);
            StringBuilder canonical = new StringBuilder();
            canonical.append("format=").append(format).append('\n');
            canonical.append("campaignId=").append(preview.campaignId).append('\n');
            canonical.append("generation=").append(preview.authorityGeneration).append('\n');
            canonical.append("targetHost=").append(encodeValue(targetHostId)).append('\n');
            canonical.append("expires=").append(expires).append('\n');
            canonical.append("nonce=").append(nonce).append('\n');
            long total = 0L;
            for(MigrationSource source : files){
                long length = source.file.length();
                total = Math.addExact(total, length);
                if(total > maximumBundleBytes) throw new IOException("Migration bundle exceeds safety limit");
                canonical.append("file=").append(source.path).append('|').append(sha256(source.file)).append('|').append(length).append('\n');
            }
            String signature = hmacHex(transferCode, canonical.toString().getBytes(StandardCharsets.UTF_8));
            byte[] manifestBytes = (canonical + "signature=" + signature + "\n").getBytes(StandardCharsets.UTF_8);
            try(ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(temporary.write(false)))){
                put(zip, manifestName, manifestBytes);
                for(MigrationSource source : files) put(zip, "campaign/" + source.path, source.file);
            }
            moveReplace(temporary.file().toPath(), target.file().toPath());
        }finally{
            previewFile.delete();
            temporary.delete();
        }
    }

    private static Seq<MigrationSource> collectMigrationSources(Fi root, Fi previewSnapshot){
        Seq<MigrationSource> result = new Seq<>();
        boolean snapshotAdded = false;
        for(Fi file : root.findAll(f -> !f.isDirectory())){
            String name = relative(root, file);
            if(name.equals("campaign.lock") || name.endsWith(".tmp") || name.startsWith("credentials/")) continue;
            // Preview mycp is the complete fenced authority image. A source-side campaign.wal is either already
            // reflected in that preview or no longer continuous with it; shipping it would only risk a fail-closed
            // open on the target host.
            if(name.equals("campaign.wal")) continue;
            if(name.equals("campaign.mycp")){
                result.add(new MigrationSource(name, previewSnapshot));
                snapshotAdded = true;
            }else{
                result.add(new MigrationSource(name, file));
            }
        }
        if(!snapshotAdded) result.add(new MigrationSource("campaign.mycp", previewSnapshot));
        result.sort(Comparator.comparing(MigrationSource::path));
        return result;
    }

    private static String relative(Fi root, Fi file){ return root.file().toPath().relativize(file.file().toPath()).toString().replace('\\', '/'); }

    private static String safeEntry(String name) throws IOException{
        Path path = Path.of(name).normalize();
        if(path.isAbsolute() || path.startsWith("..") || path.toString().isBlank()) throw new IOException("Unsafe migration path: " + name);
        return path.toString().replace('\\', '/');
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException{
        ZipEntry entry = new ZipEntry(name); entry.setTime(0L); zip.putNextEntry(entry); zip.write(data); zip.closeEntry();
    }

    private static void put(ZipOutputStream zip, String name, Fi source) throws IOException{
        ZipEntry entry = new ZipEntry(name); entry.setTime(0L); zip.putNextEntry(entry);
        // InputStream.transferTo is Java 9+ and missing on some Android runtimes; manual copy is equivalent.
        try(InputStream in = new BufferedInputStream(source.read())){
            byte[] buffer = new byte[8192];
            int read;
            while((read = in.read(buffer)) >= 0) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }

    private static byte[] readLimited(InputStream input, long limit) throws IOException{
        try(InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()){
            byte[] buffer = new byte[8192]; long total = 0L; int read;
            while((read = in.read(buffer)) >= 0){
                if(read == 0) continue;
                total += read; if(total > limit) throw new IOException("Migration manifest exceeds safety limit");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String randomCode(){
        byte[] data = new byte[32]; new SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String hmacHex(String code, byte[] data){
        try{
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data));
        }catch(GeneralSecurityException e){ throw new AssertionError(e); }
    }


    private static String sha256(Fi file) throws IOException{
        MessageDigest digest = sha256Digest();
        try(InputStream in = new BufferedInputStream(file.read())){
            byte[] buffer = new byte[64 * 1024];
            int read;
            while((read = in.read(buffer)) >= 0){
                if(read > 0) digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest(){
        try{ return MessageDigest.getInstance("SHA-256"); }catch(NoSuchAlgorithmException e){ throw new AssertionError(e); }
    }
    private static String hex(byte[] data){ StringBuilder out = new StringBuilder(); for(byte b : data) out.append(String.format(Locale.ROOT, "%02x", b & 0xff)); return out.toString(); }
    private static String encodeValue(String value){ return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decodeValue(String value){ return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }

    private static void deleteTree(Path root) throws IOException{
        if(!Files.exists(root)) return;
        try(var paths = Files.walk(root)){
            for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException{
        Files.createDirectories(target.toAbsolutePath().getParent());
        try{ Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e){ Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private record MigrationSource(String path, Fi file){}
    private record FileRecord(String path, String sha256, long length){}
    private record ParsedManifest(String campaignId, long generation, String targetHost, long expires, String nonce, Seq<FileRecord> files){}
}
