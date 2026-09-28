package mindustry.campaign.shared.runtime;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Authenticated, length-prefixed control protocol shared by coordinator, action hosts and lobby clients. */
public final class ControlProtocol{
    public static final int magic = 0x4d594354; // MYCT
    public static final int version = 26;
    /** Shared Action entry capability advertised during MYCT handshake; 0 = shared Action entry unavailable. */
    public static final int noEntryPort = 0;
    /** Absolute hard ceiling kept for framing/proxy compatibility; normal frames use the stricter per-type limits below. */
    public static final int maxPayload = 64 * 1024 * 1024;
    private static final int tinyPayload = 64 * 1024;
    private static final int requestPayload = 1024 * 1024;
    private static final int bulkPayload = 4 * 1024 * 1024;
    private static final int statePayload = 16 * 1024 * 1024;

    private ControlProtocol(){}

    private static int handshakeTimeoutMillis(){
        return Math.max(1_000, Integer.getInteger("mindustry.sharedCampaign.control.handshakeTimeoutMillis", 10_000));
    }

    /**
     * Enforces one absolute deadline across an entire control handshake. Socket SO_TIMEOUT alone is insufficient:
     * a slow peer can otherwise send one byte before each per-read timeout and retain a scarce reader indefinitely.
     * The wrapper is disarmed after authentication so the same buffered stream can safely carry normal frames.
     */
    private static final class HandshakeInputStream extends FilterInputStream{
        private final Socket socket;
        private final long deadlineNanos;
        private volatile boolean armed = true;

        HandshakeInputStream(Socket socket, InputStream input, int timeoutMillis) throws IOException{
            super(input);
            this.socket = socket;
            this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMillis));
            applyDeadline();
        }

        void disarm(int normalTimeoutMillis) throws SocketException{
            armed = false;
            socket.setSoTimeout(Math.max(0, normalTimeoutMillis));
        }

        private void applyDeadline() throws SocketException, SocketTimeoutException{
            if(!armed) return;
            long remaining = deadlineNanos - System.nanoTime();
            if(remaining <= 0L) throw new SocketTimeoutException("Shared Campaign control handshake deadline exceeded");
            long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
            socket.setSoTimeout((int)Math.min(Integer.MAX_VALUE, millis));
        }

        @Override public int read() throws IOException{
            applyDeadline();
            return super.read();
        }

        @Override public int read(byte[] b, int off, int len) throws IOException{
            applyDeadline();
            return super.read(b, off, len);
        }
    }

    public enum Role{actionHost, campaignClient, campaignEnroll}
    public enum Type{
        snapshotRequest, snapshotResponse,
        startActionRequest, startActionResponse,
        suspendActionRequest, suspendActionResponse,
        joinActionRequest, joinActionResponse,
        researchRequest, researchResponse,
        campaignSettingsRequest, campaignSettingsResponse,
        inviteCodeRequest, inviteCodeResponse,
        sectorLogisticsRequest, sectorLogisticsResponse,
        actionLogisticsRequest, actionLogisticsResponse,
        actionHello, actionHeartbeat,
        // v16 semantic change: actionSaveCommitted is the durable Sector-capture milestone; capture no longer terminates the Action.
        actionSaveCommitted, actionStopped,
        researchPrepareRequest, researchPrepareResponse,
        researchDecisionRequest, researchDecisionResponse,
        transportPrepareRequest, transportPrepareResponse,
        transportDecisionRequest, transportDecisionResponse,
        migrationLeaseRequest, migrationLeaseResponse,
        error, ping, pong,
        // Legacy donor v24 occupied these six ordinals with a content-specific relay protocol.
        // Keep the slots reserved so every later wire ordinal remains stable, but clean Shared Campaign never emits them.
        reservedLegacyRelayDispatchRequest, reservedLegacyRelayDispatchResponse,
        reservedLegacyRelayPrepareRequest, reservedLegacyRelayPrepareResponse,
        reservedLegacyRelayDecisionRequest, reservedLegacyRelayDecisionResponse,
        // v10: invite-authenticated bootstrap only; normal campaignClient identities use per-member credentials.
        // v11: joinActionRequest explicitly carries the Mindustry network UUID used by the initial ConnectPacket admission token.
        memberEnrollRequest, memberEnrollResponse,
        // v13: heavyweight Sector/Mission/tutorial state is decoupled from the 2-second liveness heartbeat.
        // v17 donor payloads could include product-specific model bodies; clean v25 keeps only the shared state-update slot.
        actionStateUpdate,
        // v14: a correlated authoritative snapshot apply barrier lets the coordinator know a live Action has
        // actually installed a strategic revision on its owning game thread before a critical mutation returns.
        snapshotApplyRequest, snapshotApplyResponse,
        // v15: non-legacy LaunchPad launches become durable idempotent Shared Campaign transport orders.
        transportDispatchRequest, transportDispatchResponse,
        // v18: explicit membership lifecycle. Invitation visibility and invitation validity are separate concepts;
        // owners can rotate the invitation secret, and members/owners can durably remove membership plus credential.
        inviteRotateRequest, inviteRotateResponse,
        memberRemoveRequest, memberRemoveResponse,
        // v23: same-TCP Action hot-switch. Coordinator pauses the entry relay, the client detaches Arc state, and the
        // same client→entry TCP is resumed onto the destination Action before Arc re-registers on that channel.
        hotSwitchRequest, hotSwitchResponse,
        hotSwitchResumeRequest, hotSwitchResumeResponse,
        // v24: fail-safe cancellation when Arc detach/rebind cannot complete after the broker paused a relay.
        hotSwitchAbortRequest, hotSwitchAbortResponse,
        // v26: pure-vanilla compatibility lane. An authenticated source Action asks the coordinator to stage a
        // one-shot direct admission on a destination Action, then vanilla Call.connect performs the actual reconnect.
        vanillaTransferRequest, vanillaTransferResponse,
        vanillaAdmissionPrepareRequest, vanillaAdmissionPrepareResponse,
        vanillaAdmissionRevokeRequest, vanillaAdmissionRevokeResponse,
        // v27: explicit maintenance checkpoint. The authority can ask every running Action to durably save its
        // current world before an operator backup/shutdown, independent of the normal autosave cadence.
        actionSaveNowRequest, actionSaveNowResponse
    }

    public static class Connection implements Closeable{
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final byte[] key;
        /** Shared public Action entry port advertised by the coordinator (0 = no joinable shared entry). */
        public final int entryPort;
        private final AtomicLong requestIds = new AtomicLong(ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE / 2L));
        private final AtomicLong sendSequence = new AtomicLong(1L);
        private long receiveSequence = 1L;

        private Connection(Socket socket, byte[] key) throws IOException{
            this(socket, key, noEntryPort,
                new DataInputStream(new BufferedInputStream(socket.getInputStream())),
                new DataOutputStream(new BufferedOutputStream(socket.getOutputStream())));
        }

        private Connection(Socket socket, byte[] key, DataInputStream in, DataOutputStream out) throws IOException{
            this(socket, key, noEntryPort, in, out);
        }

        private Connection(Socket socket, byte[] key, int entryPort, DataInputStream in, DataOutputStream out) throws IOException{
            this.socket = socket;
            this.key = key.clone();
            this.entryPort = entryPort;
            this.in = in;
            this.out = out;
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(45_000);
        }

        /** Shared public Action entry port advertised by the coordinator during the handshake (0 = no joinable shared entry). */
        public int entryPort(){ return entryPort; }

        public static Connection connect(String host, int port, Role role, String identity, byte[] key) throws IOException{
            Socket socket = new Socket();
            try{
                int handshakeTimeout = handshakeTimeoutMillis();
                socket.connect(new InetSocketAddress(host, port), handshakeTimeout);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                HandshakeInputStream handshakeIn = new HandshakeInputStream(socket, socket.getInputStream(), handshakeTimeout);
                DataInputStream rawIn = new DataInputStream(new BufferedInputStream(handshakeIn));
                DataOutputStream rawOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                byte[] clientNonce = randomNonce();
                rawOut.writeInt(magic); rawOut.writeInt(version); writeEnum(rawOut, role); writeString(rawOut, identity); writeBytes(rawOut, clientNonce); rawOut.flush();

                int challengeMagic = rawIn.readInt();
                int remoteVersion = rawIn.readInt();
                if(challengeMagic != magic) throw new IOException("Coordinator rejected control handshake");
                if(remoteVersion != version) throw new IOException("Unsupported coordinator protocol " + remoteVersion);
                byte[] serverNonce = readBytes(rawIn, 4096);
                byte[] transcript = handshakeData(role, identity, clientNonce, serverNonce);
                writeBytes(rawOut, hmac(key, labeled("client-proof", transcript))); rawOut.flush();

                int accepted = rawIn.readInt();
                int acceptedVersion = rawIn.readInt();
                byte[] serverProof = readBytes(rawIn, 128);
                int entryPort = rawIn.readInt();
                if(entryPort < 0 || entryPort > 65535) throw new IOException("Coordinator advertised invalid shared entry port: " + entryPort);
                if(accepted != magic || acceptedVersion != version ||
                    !MessageDigest.isEqual(serverProof, hmac(key, labeled("server-proof", transcript)))){
                    throw new SecurityException("Coordinator server authentication failed");
                }
                handshakeIn.disarm(45_000);
                return new Connection(socket, deriveSessionKey(key, transcript), entryPort, rawIn, rawOut);
            }catch(IOException | RuntimeException failure){
                try{ socket.close(); }catch(IOException ignored){}
                throw failure;
            }
        }

        public static Accepted accept(Socket socket, SecretResolver resolver) throws IOException{
            return accept(socket, null, resolver, noEntryPort);
        }

        /** Entry-port-aware accept: a demultiplexing entry point forwards already-read bytes via {@code preRead}. */
        public static Accepted accept(Socket socket, byte[] preRead, SecretResolver resolver, int entryPort) throws IOException{
            int handshakeTimeout = handshakeTimeoutMillis();
            InputStream source = socket.getInputStream();
            if(preRead != null && preRead.length > 0){
                source = new SequenceInputStream(new ByteArrayInputStream(preRead), source);
            }
            HandshakeInputStream handshakeIn = new HandshakeInputStream(socket, source, handshakeTimeout);
            DataInputStream rawIn = new DataInputStream(new BufferedInputStream(handshakeIn));
            try{
                int receivedMagic = rawIn.readInt();
                int receivedVersion = rawIn.readInt();
                if(receivedMagic != magic || receivedVersion != version) throw new IOException("Unsupported control protocol");
                Role role = readEnum(rawIn, Role.class);
                String identity = readString(rawIn, 4096);
                byte[] clientNonce = readBytes(rawIn, 4096);
                byte[] key = resolver.resolve(role, identity);
                if(key == null) throw new SecurityException("Invalid shared campaign control credentials for " + identity + " (no control secret registered for this identity)");
                DataOutputStream rawOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                byte[] serverNonce = randomNonce();
                rawOut.writeInt(magic); rawOut.writeInt(version); writeBytes(rawOut, serverNonce); rawOut.flush();
                byte[] transcript = handshakeData(role, identity, clientNonce, serverNonce);
                byte[] proof = readBytes(rawIn, 128);
                if(!MessageDigest.isEqual(proof, hmac(key, labeled("client-proof", transcript)))){
                    throw new SecurityException("Invalid shared campaign control credentials for " + identity + " (control secret mismatch)");
                }
                rawOut.writeInt(magic); rawOut.writeInt(version);
                writeBytes(rawOut, hmac(key, labeled("server-proof", transcript)));
                rawOut.writeInt(entryPort); rawOut.flush();
                // Reuse the exact buffered stream that consumed the handshake; it may already hold bytes from the first frame.
                handshakeIn.disarm(45_000);
                return new Accepted(new Connection(socket, deriveSessionKey(key, transcript), entryPort, rawIn, rawOut), role, identity);
            }catch(IOException | RuntimeException failure){
                try{ socket.close(); }catch(IOException ignored){}
                throw failure;
            }
        }

        public long nextRequestId(){ return requestIds.getAndIncrement(); }

        public synchronized void send(Type type, long requestId, byte[] payload) throws IOException{
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(payload, "payload");
            int limit = maxPayload(type);
            if(payload.length > limit) throw new IOException("Control payload too large for " + type + ": " + payload.length + " > " + limit);
            long sequence = sendSequence.getAndIncrement();
            if(sequence <= 0L) throw new IOException("Control frame sequence exhausted");
            byte[] header = frameHeader(type.ordinal(), requestId, sequence, payload.length);
            out.write(header);
            out.write(payload);
            writeBytes(out, hmac(key, header, payload));
            out.flush();
        }

        public Frame receive() throws IOException{
            int typeOrdinal;
            try{ typeOrdinal = in.readInt(); }catch(SocketTimeoutException e){ throw e; }
            Type[] types = Type.values();
            if(typeOrdinal < 0 || typeOrdinal >= types.length) throw new IOException("Invalid control frame type: " + typeOrdinal);
            Type type = types[typeOrdinal];
            long requestId = in.readLong();
            long sequence = in.readLong();
            int length = in.readInt();
            int limit = maxPayload(type);
            if(length < 0 || length > limit) throw new IOException("Invalid control payload length for " + type + ": " + length + " > " + limit);
            byte[] payload = new byte[length];
            in.readFully(payload);
            byte[] receivedMac = readBytes(in, 128);
            byte[] header = frameHeader(typeOrdinal, requestId, sequence, length);
            if(!MessageDigest.isEqual(receivedMac, hmac(key, header, payload))) throw new SecurityException("Control frame authentication failed");
            if(sequence != receiveSequence) throw new SecurityException("Control frame replay/out-of-order sequence: expected " + receiveSequence + ", got " + sequence);
            receiveSequence = Math.addExact(receiveSequence, 1L);
            return new Frame(type, requestId, payload);
        }

        public SocketAddress remoteAddress(){ return socket.getRemoteSocketAddress(); }
        public void setReadTimeout(int millis) throws SocketException{ socket.setSoTimeout(millis); }
        @Override public void close() throws IOException{ socket.close(); }
    }


    /** Returns the largest authenticated payload accepted for this specific control message type. */
    public static int maxPayload(Type type){
        return switch(type){
            // Full campaign-state snapshots/responses are the only control messages expected to grow with campaign history.
            case snapshotResponse, snapshotApplyRequest, researchResponse, campaignSettingsResponse, sectorLogisticsResponse, actionSaveCommitted, actionStopped, actionStateUpdate -> statePayload;

            // Batched logistics/relay transactions can contain many item entries but should never approach snapshot scale.
            case reservedLegacyRelayDispatchRequest, reservedLegacyRelayDispatchResponse, reservedLegacyRelayPrepareRequest, reservedLegacyRelayPrepareResponse,
                 reservedLegacyRelayDecisionRequest, reservedLegacyRelayDecisionResponse, transportPrepareRequest, transportPrepareResponse,
                 transportDecisionRequest, transportDecisionResponse, transportDispatchRequest, transportDispatchResponse -> bulkPayload;

            // High-frequency control/handshake-like messages stay deliberately small to bound per-connection memory pressure.
            case ping, pong, actionHello, actionHeartbeat, snapshotApplyResponse, memberEnrollRequest, memberEnrollResponse,
                 inviteCodeRequest, inviteCodeResponse, inviteRotateRequest, inviteRotateResponse,
                 memberRemoveRequest, memberRemoveResponse, suspendActionRequest, suspendActionResponse, error,
                 hotSwitchRequest, hotSwitchResponse, hotSwitchResumeRequest, hotSwitchResumeResponse,
                 hotSwitchAbortRequest, hotSwitchAbortResponse,
                 vanillaTransferRequest, vanillaTransferResponse,
                 vanillaAdmissionPrepareRequest, vanillaAdmissionPrepareResponse,
                 vanillaAdmissionRevokeRequest, vanillaAdmissionRevokeResponse,
                 actionSaveNowRequest, actionSaveNowResponse -> tinyPayload;

            // Remaining request/response payloads carry bounded strings/settings/logistics metadata.
            default -> requestPayload;
        };
    }

    private static byte[] frameHeader(int typeOrdinal, long requestId, long sequence, int payloadLength){
        return ByteBuffer.allocate(24).putInt(typeOrdinal).putLong(requestId).putLong(sequence).putInt(payloadLength).array();
    }
    public record Accepted(Connection connection, Role role, String identity){}
    public record Frame(Type type, long requestId, byte[] payload){}
    @FunctionalInterface public interface SecretResolver{ byte[] resolve(Role role, String identity); }

    public static byte[] deriveKey(String secret){
        try{ return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException e){ throw new AssertionError(e); }
    }

    public static byte[] utf8(String value){ return value.getBytes(StandardCharsets.UTF_8); }
    public static String utf8(byte[] value){ return new String(value, StandardCharsets.UTF_8); }

    private static byte[] handshakeData(Role role, String identity, byte[] clientNonce, byte[] serverNonce){
        byte[] id = identity.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(12 + id.length + clientNonce.length + serverNonce.length);
        buffer.putInt(role.ordinal()).putInt(id.length).put(id).putInt(clientNonce.length).put(clientNonce).put(serverNonce); return buffer.array();
    }

    private static byte[] randomNonce(){ byte[] nonce = new byte[32]; new SecureRandom().nextBytes(nonce); return nonce; }

    private static byte[] labeled(String label, byte[] transcript){
        byte[] prefix = label.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(4 + prefix.length + transcript.length).putInt(prefix.length).put(prefix).put(transcript).array();
    }

    private static byte[] deriveSessionKey(byte[] longTermKey, byte[] transcript){
        return hmac(longTermKey, labeled("session-key", transcript));
    }

    private static byte[] hmac(byte[] key, byte[] data){
        try{
            Mac mac = newMac(key);
            return mac.doFinal(data);
        }catch(GeneralSecurityException e){ throw new IllegalStateException(e); }
    }

    /** Authenticates header and payload incrementally so a large payload is not copied into a second contiguous buffer. */
    private static byte[] hmac(byte[] key, byte[] header, byte[] payload){
        try{
            Mac mac = newMac(key);
            mac.update(header);
            return mac.doFinal(payload);
        }catch(GeneralSecurityException e){ throw new IllegalStateException(e); }
    }

    private static Mac newMac(byte[] key) throws GeneralSecurityException{
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac;
    }

    public static byte[] strings(String... values){
        try{
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try(DataOutputStream out = new DataOutputStream(bytes)){ out.writeInt(values.length); for(String value : values) writeString(out, value); }
            return bytes.toByteArray();
        }catch(IOException e){ throw new UncheckedIOException(e); }
    }

    public static String[] readStrings(byte[] payload){
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))){
            int length = in.readInt(); if(length < 0 || length > 1024) throw new IOException("Invalid string vector");
            String[] values = new String[length]; for(int i = 0; i < length; i++) values[i] = readString(in, 1_048_576); return values;
        }catch(IOException e){ throw new UncheckedIOException(e); }
    }

    private static void writeEnum(DataOutput out, Enum<?> value) throws IOException{ out.writeInt(value.ordinal()); }
    private static <E extends Enum<E>> E readEnum(DataInput in, Class<E> type) throws IOException{
        int value = in.readInt(); E[] values = type.getEnumConstants(); if(value < 0 || value >= values.length) throw new IOException("Invalid enum value"); return values[value];
    }
    private static void writeString(DataOutput out, String value) throws IOException{ writeBytes(out, value.getBytes(StandardCharsets.UTF_8)); }
    private static String readString(DataInput in, int max) throws IOException{ return new String(readBytes(in, max), StandardCharsets.UTF_8); }
    private static void writeBytes(DataOutput out, byte[] value) throws IOException{ out.writeInt(value.length); out.write(value); }
    private static byte[] readBytes(DataInput in, int max) throws IOException{
        int length = in.readInt(); if(length < 0 || length > max) throw new IOException("Invalid byte vector length: " + length); byte[] value = new byte[length]; in.readFully(value); return value;
    }
}
