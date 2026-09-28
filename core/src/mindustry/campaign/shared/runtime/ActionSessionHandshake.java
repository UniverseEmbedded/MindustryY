package mindustry.campaign.shared.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Objects;

/**
 * Structured single-entry session handshake (MYCS).
 *
 * Routes Action clients before ArcNet registration. The shared entry accepts MYCT control or this MYCS preface;
 * raw ArcNet cannot be sniffed because ArcNet registration is server-first.
 *
 * Wire (big-endian):
 * magic(4) | version(1) | kind(1) | flags(2) |
 * sessionIdLen(2) sessionId | actionIdLen(2) actionId |
 * memberIdLen(2) memberId | admissionLen(2) admission | capsLen(2) caps
 *
 * Admission is verified cryptographically by the broker before an internal world socket is opened. The Action world
 * independently verifies the same signed grant plus network UUID and one-use nonce as the final fail-closed layer.
 */
public final class ActionSessionHandshake{
    public static final int magic = 0x4d594353; // MYCS
    public static final int version = 1;
    public static final int maxFieldLength = 4096;
    public static final int maxHandshakeBytes = 16 * 1024;
    /** Action request flag: after this MYCS request the same TCP becomes an ArcNet stream; no MYCS response is sent. */
    public static final int flagInlineArc = 1;
    /** Hot-switch stream barrier magic (MYHB) followed by a SHA-256 session/switch digest. */
    public static final int hotSwitchBarrierMagic = 0x4d594842; // MYHB
    public static final int hotSwitchBarrierBytes = 4 + 32;

    /**
     * Deterministic high-entropy marker used to prove both directions of a preserved game TCP are byte-clean before
     * attaching a destination Arc server. It contains no admission secret and is bound to this exact session/switch.
     */
    public static byte[] hotSwitchBarrier(String sessionId, String fromActionId, String toActionId){
        if(sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if(fromActionId == null || fromActionId.isBlank()) throw new IllegalArgumentException("fromActionId is required");
        if(toActionId == null || toActionId.isBlank()) throw new IllegalArgumentException("toActionId is required");
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("mindustry-shared-campaign-hot-switch-v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(sessionId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            digest.update(fromActionId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            digest.update(toActionId.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            byte[] barrier = new byte[hotSwitchBarrierBytes];
            barrier[0] = (byte)(hotSwitchBarrierMagic >>> 24);
            barrier[1] = (byte)(hotSwitchBarrierMagic >>> 16);
            barrier[2] = (byte)(hotSwitchBarrierMagic >>> 8);
            barrier[3] = (byte)hotSwitchBarrierMagic;
            System.arraycopy(hash, 0, barrier, 4, hash.length);
            return barrier;
        }catch(NoSuchAlgorithmException impossible){
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public enum Kind{
        /** Route this same TCP to an Action game world before ArcNet registration. */
        action;

        public static Kind of(int value){
            for(Kind kind : values()) if(kind.ordinal() == value) return kind;
            return null;
        }
    }

    public static final class Request{
        public final int version;
        public final Kind kind;
        public final int flags;
        /** Client-generated opaque ID for this exact game TCP; required for authenticated same-TCP hot-switch. */
        public final String sessionId;
        public final String actionId;
        public final String memberId;
        /** Signed Action admission grant; broker verifies routing identity and the world performs final admission. */
        public final String admission;
        /** Bitmask of client capabilities; reserved for negotiation. */
        public final int capabilities;

        public Request(int version, Kind kind, int flags, String sessionId, String actionId, String memberId, String admission, int capabilities){
            this.version = version;
            this.kind = Objects.requireNonNull(kind, "kind");
            this.flags = flags;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.actionId = actionId == null ? "" : actionId;
            this.memberId = memberId == null ? "" : memberId;
            this.admission = admission == null ? "" : admission;
            this.capabilities = capabilities;
        }

        public boolean inlineArc(){
            return (flags & flagInlineArc) != 0;
        }

        public Request(Kind kind, String sessionId, String actionId, String memberId, String admission){
            this(ActionSessionHandshake.version, kind, ActionSessionHandshake.flagInlineArc, sessionId, actionId, memberId, admission, 0);
        }
    }

    private ActionSessionHandshake(){}

    public static boolean looksLikeHandshake(int firstInt){
        return firstInt == magic;
    }

    public static byte[] encode(Request request){
        Objects.requireNonNull(request, "request");
        if(request.kind == null) throw new IllegalArgumentException("kind is required");
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeInt(out, magic);
        out.write(request.version & 0xff);
        out.write(request.kind.ordinal() & 0xff);
        writeShort(out, request.flags);
        writeField(out, request.sessionId);
        writeField(out, request.actionId);
        writeField(out, request.memberId);
        writeField(out, request.admission);
        writeShort(out, request.capabilities);
        byte[] bytes = out.toByteArray();
        if(bytes.length > maxHandshakeBytes) throw new IllegalArgumentException("MYCS handshake exceeds " + maxHandshakeBytes + " bytes");
        return bytes;
    }

    /**
     * Decodes a request from a complete handshake buffer. {@code length} is the number of valid bytes in
     * {@code data}. Throws {@link IOException} on any framing error (fail-closed).
     */
    public static Request decodeRequest(byte[] data, int length) throws IOException{
        Cursor cursor = new Cursor(data, length);
        requireMagic(cursor);
        int handshakeVersion = cursor.u8();
        if(handshakeVersion != version) throw new IOException("Unsupported MYCS handshake version: " + handshakeVersion);
        int kindValue = cursor.u8();
        Kind kind = Kind.of(kindValue);
        if(kind == null) throw new IOException("Unknown MYCS session kind: " + kindValue);
        int flags = cursor.u16();
        String sessionId = cursor.field("sessionId");
        String actionId = cursor.field("actionId");
        String memberId = cursor.field("memberId");
        String admission = cursor.field("admission");
        int capabilities = cursor.u16();
        if(cursor.remaining() != 0) throw new IOException("Trailing bytes after MYCS request: " + cursor.remaining());
        return new Request(version, kind, flags, sessionId, actionId, memberId, admission, capabilities);
    }

    /** Reads one complete request from the stream after the caller has already observed the magic int. */
    public static Request readRequest(InputStream in, byte[] magicBytes) throws IOException{
        if(magicBytes == null || magicBytes.length != 4 || readInt(magicBytes, 0) != magic) throw new IOException("MYCS magic prefix required");
        // The broker has already consumed the four-byte MYCS magic before calling this method. Do not prepend it
        // again: doing so would make 'M' (0x4d) become the version byte. Count the consumed prefix toward the
        // aggregate frame bound while the inline fields are read.
        CountingInputStream counted = new CountingInputStream(in);
        DataInputStream data = new DataInputStream(counted);
        int handshakeVersion = data.readUnsignedByte();
        if(handshakeVersion != version) throw new IOException("Unsupported MYCS handshake version: " + handshakeVersion);
        int kindValue = data.readUnsignedByte();
        Kind kind = Kind.of(kindValue);
        if(kind == null) throw new IOException("Unknown MYCS session kind: " + kindValue);
        int flags = data.readUnsignedShort();
        String sessionId = readField(data, "sessionId");
        String actionId = readField(data, "actionId");
        String memberId = readField(data, "memberId");
        String admission = readField(data, "admission");
        int capabilities = data.readUnsignedShort();
        if(4L + counted.count > maxHandshakeBytes) throw new IOException("MYCS handshake exceeds " + maxHandshakeBytes + " bytes");
        return new Request(version, kind, flags, sessionId, actionId, memberId, admission, capabilities);
    }

    private static void requireMagic(Cursor cursor) throws IOException{
        int value = cursor.i32();
        if(value != magic) throw new IOException("Not a MYCS handshake");
    }

    private static void writeInt(ByteArrayOutputStream out, int value){
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream out, int value){
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeField(ByteArrayOutputStream out, String value){
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length > maxFieldLength) throw new IllegalArgumentException("MYCS field exceeds " + maxFieldLength + " bytes");
        writeShort(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static String readField(DataInputStream in, String name) throws IOException{
        int length = in.readUnsignedShort();
        if(length > maxFieldLength) throw new IOException("MYCS " + name + " exceeds " + maxFieldLength + " bytes");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readInt(byte[] data, int offset){
        return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16) | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }

    private static final class CountingInputStream extends FilterInputStream{
        long count;

        CountingInputStream(InputStream in){
            super(in);
        }

        @Override public int read() throws IOException{
            int value = super.read();
            if(value >= 0) count++;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException{
            int read = super.read(bytes, offset, length);
            if(read > 0) count += read;
            return read;
        }
    }

    private static final class Cursor{
        private final byte[] data;
        private final int length;
        private int index;

        Cursor(byte[] data, int length){
            if(data == null) throw new IllegalArgumentException("null handshake buffer");
            if(length < 0 || length > data.length) throw new IllegalArgumentException("invalid handshake length");
            this.data = data;
            this.length = length;
        }

        int remaining(){ return length - index; }

        int i32() throws IOException{
            require(4);
            int value = ((data[index] & 0xff) << 24) | ((data[index + 1] & 0xff) << 16) | ((data[index + 2] & 0xff) << 8) | (data[index + 3] & 0xff);
            index += 4;
            return value;
        }

        int u8() throws IOException{
            require(1);
            return data[index++] & 0xff;
        }

        int u16() throws IOException{
            require(2);
            int value = ((data[index] & 0xff) << 8) | (data[index + 1] & 0xff);
            index += 2;
            return value;
        }

        String field(String name) throws IOException{
            int fieldLength = u16();
            if(fieldLength > maxFieldLength) throw new IOException("MYCS " + name + " exceeds " + maxFieldLength + " bytes");
            require(fieldLength);
            String value = new String(data, index, fieldLength, StandardCharsets.UTF_8);
            index += fieldLength;
            return value;
        }

        private void require(int bytes) throws IOException{
            if(remaining() < bytes) throw new IOException("Truncated MYCS handshake");
        }
    }
}
