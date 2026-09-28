package mindustry.net;

import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.*;
import mindustry.core.*;
import mindustry.io.*;

import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Class for storing all packets. */
public class Packets{

    public enum KickReason{
        kick, clientOutdated, serverOutdated, banned, gameover(true), recentKick,
        nameInUse, idInUse, nameEmpty, customClient, serverClose, vote, typeMismatch,
        whitelist, playerLimit, serverRestarting;

        public static final KickReason[] all = values();

        public final boolean quiet;

        KickReason(){
            this(false);
        }

        KickReason(boolean quiet){
            this.quiet = quiet;
        }

        @Override
        public String toString(){
            return Core.bundle.get("server.kicked." + name());
        }

        public String extraText(){
            return Core.bundle.getOrNull("server.kicked." + name() + ".text");
        }
    }

    public enum AdminAction{
        kick, ban, trace, wave, switchTeam;

        public static final AdminAction[] all = values();
    }

    /** Generic client connection event. */
    public static class Connect extends Packet{
        public String addressTCP;

        @Override
        public int getPriority(){
            return priorityHigh;
        }
    }

    /** Generic client disconnection event. */
    public static class Disconnect extends Packet{
        public String reason;

        @Override
        public int getPriority(){
            return priorityHigh;
        }
    }

    public static class WorldStream extends Streamable{

    }

    public static class TextureStream extends Streamable{

    }

    public static class AssetRequirementStream extends Streamable{

    }

    public static class AssetStream extends Streamable{

        @Override
        public boolean incremental(){
            return true;
        }
    }

    /** Marks the beginning of a stream. */
    public static class StreamBegin extends Packet{
        private static final AtomicInteger lastid = new AtomicInteger();

        public int id = lastid.getAndIncrement();
        public int total;
        public byte type;

        //only used when handling on the client (not sent)
        public @Nullable transient InputStream incrementalStream;

        @Override
        public boolean allow(boolean server){
            return !server;
        }

        @Override
        public void write(Writes buffer){
            buffer.i(id);
            buffer.i(total);
            buffer.b(type);
        }

        @Override
        public void read(Reads buffer){
            id = buffer.i();
            total = buffer.i();
            type = buffer.b();
        }
    }

    public static class StreamChunk extends Packet{
        public int id;
        public byte[] data;

        @Override
        public boolean allow(boolean server){
            return !server;
        }

        @Override
        public void write(Writes buffer){
            buffer.i(id);
            buffer.s((short)data.length);
            buffer.b(data);
        }

        @Override
        public void read(Reads buffer){
            id = buffer.i();
            data = buffer.b(buffer.s());
        }
    }

    public static class ConnectPacket extends Packet{
        public int version;
        public String versionType;
        public Seq<String> mods;
        public String name, locale, uuid, usid;
        public boolean mobile;
        public int color;

        /**
         * Converts the persistent 8-byte client UUID seed into the 16-byte identity observed by the server after
         * ConnectPacket deserialization (seed + CRC field encoded as Base64).
         */
        public static String serverUuid(String platformUuid){
            if(platformUuid == null || platformUuid.isBlank()) return null;
            try{
                byte[] seed = Base64Coder.decode(platformUuid);
                if(seed.length != 8) return null;
                CRC32 crc = new CRC32();
                crc.update(seed, 0, seed.length);
                ByteBuffer wire = ByteBuffer.allocate(16);
                wire.put(seed).putLong(crc.getValue());
                return new String(Base64Coder.encode(wire.array()));
            }catch(RuntimeException ignored){
                return null;
            }
        }

        /**
         * Recovers and validates the persistent 8-byte client UUID seed from the 16-byte server-observed identity.
         * Returns null when the wire identity is malformed or its CRC field does not match.
         */
        public static String platformUuid(String serverUuid){
            if(serverUuid == null || serverUuid.isBlank()) return null;
            try{
                byte[] wire = Base64Coder.decode(serverUuid);
                if(wire.length != 16) return null;
                byte[] seed = new byte[8];
                System.arraycopy(wire, 0, seed, 0, seed.length);
                long supplied = ByteBuffer.wrap(wire, 8, Long.BYTES).getLong();
                CRC32 crc = new CRC32();
                crc.update(seed, 0, seed.length);
                if(supplied != crc.getValue()) return null;
                return new String(Base64Coder.encode(seed));
            }catch(RuntimeException ignored){
                return null;
            }
        }

        @Override
        public void write(Writes buffer){
            // A compatibility session may advertise an exact legacy build for this one connection.
            // The field is never written back to Version and therefore cannot leak into later sessions.
            buffer.i(version > 0 ? version : Version.build);
            TypeIO.writeString(buffer, versionType);
            TypeIO.writeString(buffer, name);
            TypeIO.writeString(buffer, locale);
            TypeIO.writeString(buffer, usid);

            byte[] b = Base64Coder.decode(uuid);
            buffer.b(b);
            CRC32 crc = new CRC32();
            crc.update(Base64Coder.decode(uuid), 0, b.length);
            buffer.l(crc.getValue());

            buffer.b(mobile ? (byte)1 : 0);
            buffer.i(color);
            buffer.b((byte)mods.size);
            for(int i = 0; i < mods.size; i++){
                TypeIO.writeString(buffer, mods.get(i));
            }
        }

        @Override
        public void read(Reads buffer){
            version = buffer.i();
            versionType = TypeIO.readString(buffer);
            name = TypeIO.readString(buffer);
            locale = TypeIO.readString(buffer);
            usid = TypeIO.readString(buffer);
            byte[] idbytes =  buffer.b(16);
            uuid = new String(Base64Coder.encode(idbytes));
            mobile = buffer.b() == 1;
            color = buffer.i();
            int totalMods = buffer.b();
            mods = new Seq<>(totalMods);
            for(int i = 0; i < totalMods; i++){
                mods.add(TypeIO.readString(buffer));
            }
        }

        @Override
        public int getPriority(){
            return priorityHigh;
        }
    }
}
