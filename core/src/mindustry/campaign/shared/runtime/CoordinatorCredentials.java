package mindustry.campaign.shared.runtime;

import arc.files.*;
import java.nio.charset.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Durable coordinator, invitation, action-control and player-admission secrets kept outside campaign snapshots. */
public class CoordinatorCredentials{
    private final Fi directory;
    private String inviteSecret;

    public CoordinatorCredentials(Fi directory){
        this.directory = directory;
        directory.mkdirs();
        inviteSecret = loadOrCreate(directory.child("invite.secret"));
    }

    public synchronized byte[] inviteKey(){ return ControlProtocol.deriveKey(inviteSecret); }
    public synchronized String inviteCode(){ return inviteSecret; }

    /** Rotates the campaign invitation secret. Previously issued invite codes immediately stop authenticating. */
    public synchronized String rotateInviteSecret(){
        inviteSecret = randomSecret();
        replaceSecret(directory.child("invite.secret"), inviteSecret);
        return inviteSecret;
    }

    /** Returns the durable member credential for an existing identity, creating it only for trusted local authority code. */
    public synchronized MemberCredential ensureMemberCredential(String memberId){
        String clean = requireMemberId(memberId);
        return new MemberCredential(clean, loadOrCreate(memberFile(clean)));
    }

    /** Issues a new server-owned member identity. The invite code never determines or authenticates this identity. */
    public synchronized MemberCredential issueMemberCredential(){
        String memberId;
        do{ memberId = "member-" + UUID.randomUUID(); }while(memberFile(memberId).exists());
        return new MemberCredential(memberId, loadOrCreate(memberFile(memberId)));
    }

    /** Resolves only already-issued per-member credentials; unknown claimed identities never fall back to the invite key. */
    public synchronized byte[] memberKey(String memberId){
        if(memberId == null || memberId.isBlank()) return null;
        Fi file = memberFile(memberId);
        return file.exists() ? ControlProtocol.deriveKey(readSecret(file)) : null;
    }

    /** Permanently revokes one member control credential. Durable membership must be removed in the same operation. */
    public synchronized void revokeMemberCredential(String memberId){
        if(memberId == null || memberId.isBlank()) return;
        memberFile(memberId).delete();
    }

    /** Rotates one existing member credential and returns the replacement secret to trusted authority code. */
    public synchronized MemberCredential rotateMemberCredential(String memberId){
        String clean = requireMemberId(memberId);
        Fi file = memberFile(clean);
        if(!file.exists()) throw new IllegalArgumentException("Unknown member credential: " + clean);
        String secret = randomSecret();
        replaceSecret(file, secret);
        return new MemberCredential(clean, secret);
    }

    public String createActionControlSecret(String actionId){ return loadOrCreate(actionFile(actionId, "control")); }
    public String createActionJoinSecret(String actionId){ return loadOrCreate(actionFile(actionId, "join")); }

    /** Issues a short-lived legacy token bound to one action, one Mindustry UUID and one admission role. */
    public String issueActionJoinToken(String actionId, String networkUuid, long validForMillis){
        return issueActionJoinToken(actionId, networkUuid, false, validForMillis);
    }

    public String issueActionJoinToken(String actionId, String networkUuid, boolean spectator, long validForMillis){
        if(actionId == null || actionId.isBlank()) throw new IllegalArgumentException("Action ID is required");
        if(networkUuid == null || networkUuid.isBlank()) throw new IllegalArgumentException("Mindustry network UUID is required");
        long issuedAt = System.currentTimeMillis();
        long expiresAt = Math.addExact(issuedAt, Math.max(10_000L, Math.min(validForMillis, 10L * 60L * 1000L)));
        String nonce = randomSecret().substring(0, 22);
        String payload = "2\n" + actionId + "\n" + networkUuid + "\n" + issuedAt + "\n" + expiresAt + "\n" + nonce + "\n" + (spectator ? "spectator" : "player");
        byte[] encoded = payload.getBytes(StandardCharsets.UTF_8);
        byte[] signature = hmac(ControlProtocol.deriveKey(createActionJoinSecret(actionId)), encoded);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /**
     * Issues the current action-admission token. In addition to the Mindustry wire UUID, it cryptographically binds
     * the connection to the authenticated Shared Campaign member identity that requested admission. Action runtimes
     * must use this member identity in authoritative heartbeats instead of treating the unrelated network UUID as a
     * second campaign member.
     */
    public String issueActionJoinToken(String actionId, String networkUuid, String memberId, boolean spectator, long validForMillis){
        if(actionId == null || actionId.isBlank()) throw new IllegalArgumentException("Action ID is required");
        if(networkUuid == null || networkUuid.isBlank()) throw new IllegalArgumentException("Mindustry network UUID is required");
        String cleanMemberId = requireMemberId(memberId);
        long issuedAt = System.currentTimeMillis();
        long expiresAt = Math.addExact(issuedAt, Math.max(10_000L, Math.min(validForMillis, 10L * 60L * 1000L)));
        String nonce = randomSecret().substring(0, 22);
        String payload = "3\n" + actionId + "\n" + networkUuid + "\n" + cleanMemberId + "\n" + issuedAt + "\n" + expiresAt + "\n" + nonce + "\n" + (spectator ? "spectator" : "player");
        byte[] encoded = payload.getBytes(StandardCharsets.UTF_8);
        byte[] signature = hmac(ControlProtocol.deriveKey(createActionJoinSecret(actionId)), encoded);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /**
     * Non-secret diagnostics for a rejected action token. This intentionally exposes only boolean comparisons and
     * timestamps; callers must never log the token, UUID or secret itself. The verifier remains authoritative.
     */
    public static JoinTokenDiagnostics diagnoseActionJoinToken(String actionSecret, String token, String expectedActionId, String expectedNetworkUuid, long now){
        try{
            if(actionSecret == null || actionSecret.isBlank() || token == null) return JoinTokenDiagnostics.invalid(now);
            int separator = token.indexOf('.');
            if(separator <= 0 || separator != token.lastIndexOf('.')) return JoinTokenDiagnostics.invalid(now);
            byte[] payload = Base64.getUrlDecoder().decode(token.substring(0, separator));
            byte[] supplied = Base64.getUrlDecoder().decode(token.substring(separator + 1));
            byte[] expected = hmac(ControlProtocol.deriveKey(actionSecret), payload);
            boolean signatureValid = MessageDigest.isEqual(supplied, expected);
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
            boolean version1 = fields.length == 6 && fields[0].equals("1");
            boolean version2 = fields.length == 7 && fields[0].equals("2");
            boolean version3 = fields.length == 8 && fields[0].equals("3");
            if(!version1 && !version2 && !version3) return new JoinTokenDiagnostics(false, signatureValid, false, false, false, 0L, 0L, now, fields.length == 0 ? "" : fields[0]);
            int issuedIndex = version3 ? 4 : 3, expiresIndex = version3 ? 5 : 4;
            long issuedAt = Long.parseLong(fields[issuedIndex]), expiresAt = Long.parseLong(fields[expiresIndex]);
            boolean timeValid = issuedAt <= now + 30_000L && expiresAt >= now && expiresAt - issuedAt <= 10L * 60L * 1000L;
            boolean actionMatches = constantTime(fields[1], expectedActionId);
            boolean uuidMatches = constantTime(fields[2], expectedNetworkUuid);
            return new JoinTokenDiagnostics(true, signatureValid, actionMatches, uuidMatches, timeValid, issuedAt, expiresAt, now, fields[0]);
        }catch(RuntimeException ignored){
            return JoinTokenDiagnostics.invalid(now);
        }
    }

    /**
     * Coordinator-side broker check performed before opening an internal Action socket. This validates the token
     * signature, action binding and lifetime, but intentionally defers network-UUID matching and nonce consumption
     * to the Action world, which remains the final admission authority.
     */
    public static JoinGrant verifyActionJoinTokenForRouting(String actionSecret, String token, String expectedActionId, long now){
        try{
            if(actionSecret == null || actionSecret.isBlank() || token == null) return null;
            int separator = token.indexOf('.');
            if(separator <= 0 || separator != token.lastIndexOf('.')) return null;
            byte[] payload = Base64.getUrlDecoder().decode(token.substring(0, separator));
            byte[] supplied = Base64.getUrlDecoder().decode(token.substring(separator + 1));
            byte[] expected = hmac(ControlProtocol.deriveKey(actionSecret), payload);
            if(!MessageDigest.isEqual(supplied, expected)) return null;
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
            boolean version1 = fields.length == 6 && fields[0].equals("1");
            boolean version2 = fields.length == 7 && fields[0].equals("2");
            boolean version3 = fields.length == 8 && fields[0].equals("3");
            if(!version1 && !version2 && !version3) return null;
            int issuedIndex = version3 ? 4 : 3, expiresIndex = version3 ? 5 : 4;
            long issuedAt = Long.parseLong(fields[issuedIndex]), expiresAt = Long.parseLong(fields[expiresIndex]);
            if(issuedAt > now + 30_000L || expiresAt < now || expiresAt - issuedAt > 10L * 60L * 1000L) return null;
            if(!constantTime(fields[1], expectedActionId)) return null;
            String memberId = version3 ? fields[3] : "";
            if(version3 && memberId.isBlank()) return null;
            int roleIndex = version3 ? 7 : 6;
            boolean spectator = (version2 || version3) && fields[roleIndex].equals("spectator");
            if((version2 || version3) && !spectator && !fields[roleIndex].equals("player")) return null;
            int nonceIndex = version3 ? 6 : 5;
            return new JoinGrant(fields[1], fields[2], memberId, issuedAt, expiresAt, fields[nonceIndex], spectator);
        }catch(RuntimeException ignored){
            return null;
        }
    }

    /** Verifies a token without consulting campaign state; the action secret fences it to one action process. */
    public static JoinGrant verifyActionJoinToken(String actionSecret, String token, String expectedActionId, String expectedNetworkUuid, long now){
        try{
            if(actionSecret == null || actionSecret.isBlank() || token == null) return null;
            int separator = token.indexOf('.');
            if(separator <= 0 || separator != token.lastIndexOf('.')) return null;
            byte[] payload = Base64.getUrlDecoder().decode(token.substring(0, separator));
            byte[] supplied = Base64.getUrlDecoder().decode(token.substring(separator + 1));
            byte[] expected = hmac(ControlProtocol.deriveKey(actionSecret), payload);
            if(!MessageDigest.isEqual(supplied, expected)) return null;
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
            boolean version1 = fields.length == 6 && fields[0].equals("1");
            boolean version2 = fields.length == 7 && fields[0].equals("2");
            boolean version3 = fields.length == 8 && fields[0].equals("3");
            if(!version1 && !version2 && !version3) return null;
            int issuedIndex = version3 ? 4 : 3, expiresIndex = version3 ? 5 : 4;
            long issuedAt = Long.parseLong(fields[issuedIndex]), expiresAt = Long.parseLong(fields[expiresIndex]);
            if(issuedAt > now + 30_000L || expiresAt < now || expiresAt - issuedAt > 10L * 60L * 1000L) return null;
            if(!constantTime(fields[1], expectedActionId) || !constantTime(fields[2], expectedNetworkUuid)) return null;
            String memberId = version3 ? fields[3] : "";
            if(version3 && memberId.isBlank()) return null;
            int roleIndex = version3 ? 7 : 6;
            boolean spectator = (version2 || version3) && fields[roleIndex].equals("spectator");
            if((version2 || version3) && !spectator && !fields[roleIndex].equals("player")) return null;
            int nonceIndex = version3 ? 6 : 5;
            return new JoinGrant(fields[1], fields[2], memberId, issuedAt, expiresAt, fields[nonceIndex], spectator);
        }catch(RuntimeException ignored){
            return null;
        }
    }

    public byte[] actionKey(String actionId){
        Fi file = actionFile(actionId, "control");
        return file.exists() ? ControlProtocol.deriveKey(readSecret(file)) : null;
    }

    public void deleteActionSecrets(String actionId){
        actionFile(actionId, "control").delete();
        actionFile(actionId, "join").delete();
    }

    private Fi memberFile(String memberId){
        Fi file = directory.child("members").child(hexSha256(requireMemberId(memberId)) + ".secret");
        file.parent().mkdirs();
        return file;
    }

    private Fi actionFile(String actionId, String purpose){
        Fi file = directory.child("actions").child(actionId + "." + purpose + ".secret");
        file.parent().mkdirs();
        return file;
    }

    private static String requireMemberId(String memberId){
        if(memberId == null || memberId.isBlank()) throw new IllegalArgumentException("Member ID is required");
        return memberId.trim();
    }

    private static String hexSha256(String value){
        try{
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for(byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        }catch(NoSuchAlgorithmException e){ throw new AssertionError(e); }
    }

    private static String loadOrCreate(Fi file){
        if(file.exists()) return readSecret(file);
        String value = randomSecret();
        replaceSecret(file, value);
        return value;
    }

    private static String readSecret(Fi file){
        String value = file.readString(StandardCharsets.UTF_8.name()).trim();
        validateSecret(value, file.path());
        securePermissions(file.file().toPath());
        return value;
    }

    private static void validateSecret(String value, String label){
        try{
            byte[] decoded = Base64.getUrlDecoder().decode(value == null ? "" : value);
            if(decoded.length != 32) throw new IllegalStateException("Credential secret must contain exactly 32 bytes of entropy: " + label);
        }catch(IllegalArgumentException error){
            throw new IllegalStateException("Credential secret is not valid base64url: " + label, error);
        }
    }

    private static void replaceSecret(Fi file, String value){
        validateSecret(value, file.path());
        file.parent().mkdirs();
        Fi temp = file.sibling(file.name() + ".tmp-" + UUID.randomUUID());
        Path tempPath = temp.file().toPath(), targetPath = file.file().toPath();
        try{
            // Files.writeString(Path, CharSequence, ...) is Java 11+ and is missing on some Android runtimes
            // (NoSuchMethodError on device); Files.write(Path, byte[], ...) is Java 7 and universally present.
            Files.write(tempPath, value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            securePermissions(tempPath);
            try(FileChannel channel = FileChannel.open(tempPath, StandardOpenOption.WRITE)){ channel.force(true); }
            try{
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }catch(AtomicMoveNotSupportedException ignored){
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            securePermissions(targetPath);
            forceDirectory(targetPath.getParent());
        }catch(java.io.IOException error){
            try{ Files.deleteIfExists(tempPath); }catch(java.io.IOException ignored){}
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static void securePermissions(Path path){
        // On Android, Files.getFileStore throws SecurityException from the unix provider, and the app-private
        // data directory already enforces OS-level sandboxing, so the POSIX chmod is redundant there.
        // Desktop keeps the fail-closed behavior.
        if(arc.util.OS.isAndroid) return;
        try{
            if(Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)){
                Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        }catch(java.io.IOException error){
            throw new java.io.UncheckedIOException("Could not restrict credential permissions for " + path, error);
        }catch(UnsupportedOperationException ignored){}
    }

    private static void forceDirectory(Path directory){
        if(directory == null) return;
        try(FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)){ channel.force(true); }
        catch(java.io.IOException | UnsupportedOperationException ignored){ /* Best effort on filesystems that reject directory fsync. */ }
    }

    private static byte[] hmac(byte[] key, byte[] payload){
        try{
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        }catch(GeneralSecurityException e){
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static boolean constantTime(String left, String right){
        if(left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomSecret(){
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record MemberCredential(String memberId, String secret){}
    public record JoinGrant(String actionId, String networkUuid, String memberId, long issuedAt, long expiresAt, String nonce, boolean spectator){}
    public record JoinTokenDiagnostics(boolean wellFormed, boolean signatureValid, boolean actionMatches, boolean networkUuidMatches, boolean timeValid, long issuedAt, long expiresAt, long now, String version){
        private static JoinTokenDiagnostics invalid(long now){
            return new JoinTokenDiagnostics(false, false, false, false, false, 0L, 0L, now, "");
        }
    }
}
