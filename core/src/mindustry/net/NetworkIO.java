package mindustry.net;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.runtime.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.maps.Map;
import mindustry.mod.*;
import mindustry.mod.data.*;
import mindustry.net.Administration.*;
import mindustry.type.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

import static mindustry.Vars.*;

public class NetworkIO{

    public static void writeWorld(Player player, OutputStream os){

        try(DataOutputStream stream = new DataOutputStream(os)){
            Rules networkRules = rulesForWorldWrite();

            var writer = SaveIO.getSaveWriter();

            //data patches must be first, as rules can involve patched content
            writer.writeDataPatches(stream, false);

            stream.writeUTF(JsonIO.write(networkRules));
            stream.writeUTF(JsonIO.write(game().state.mapLocales));
            writer.writeStringMap(stream, game().state.map.tags);

            stream.writeInt(game().state.wave);
            stream.writeFloat(game().state.wavetime);
            stream.writeDouble(game().state.tick);
            stream.writeLong(mindustry.Vars.game().logicVars.rand.seed0);
            stream.writeLong(mindustry.Vars.game().logicVars.rand.seed1);

            stream.writeInt(player.id);
            player.write(new Writes(stream));

            writer.writeContentHeader(stream);
            writer.writeMap(stream);
            //these three calls mimic what writeEntities has, except with a custom filter, which is a bit fragile
            writer.writeEntityMapping(stream);
            writer.writeTeamBlocks(stream);
            writer.writeWorldEntities(stream, game().state.rules.fog ? u -> !u.inFogTo(player.team()) : null);

            writer.writeMarkers(stream);
            writer.writeCustomChunks(stream, true);
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }


    /** Builds the Rules sent to a joining client without mutating the live authoritative world. */
    static Rules rulesForWorldWrite(){
        Rules live = game().state.rules;
        Rules outbound = live.copy();
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.find(game());
        boolean actionRuntime = shared != null && shared.actionEnabled();
        if(game().state.isCampaign() && RuntimeContexts.isPrimary() && !actionRuntime){
            outbound.researched.clear();
            for(ContentType type : ContentType.all){
                for(Content c : content.getBy(type)){
                    if(c instanceof UnlockableContent u && u.unlocked() && u.techNode != null) outbound.researched.add(u);
                }
            }
        }
        return outbound;
    }

    public static void loadWorld(InputStream is){

        try(DataInputStream stream = new DataInputStream(is)){
            var writer = SaveIO.getSaveWriter();
            Time.clear();
            writer.readDataPatches(stream, new SaveReadState(game().world.context));

            game().state.rules = JsonIO.read(Rules.class, stream.readUTF());
            game().state.mapLocales = JsonIO.read(MapLocales.class, stream.readUTF());
            game().state.map = new Map(writer.readStringMap(stream));

            game().state.wave = stream.readInt();
            game().state.wavetime = stream.readFloat();
            game().state.tick = stream.readDouble();
            mindustry.Vars.game().logicVars.rand.seed0 = stream.readLong();
            mindustry.Vars.game().logicVars.rand.seed1 = stream.readLong();

            Reads read = new Reads(stream);

            Groups.clear();
            int id = stream.readInt();
            player.reset();
            player.read(read);
            player.id = id;
            player.add();

            var state = new SaveReadState(game().world.context);

            writer.readContentHeader(stream);
            writer.readMap(stream, state);
            writer.readEntities(stream, state);
            writer.readMarkers(stream);
            writer.readCustomChunks(stream);

            Groups.current().all.each(e -> netClient.addRemovedEntity(e.id()));
            Groups.current().unit.each(e -> netClient.addRemovedEntity(e.id()));
        }catch(IOException e){
            throw new RuntimeException(e);
        }finally{
            content.setTemporaryMapper(null);
        }
    }

    public static void writeRequiredAssets(OutputStream os, Seq<DataAsset> assets){

        try(DataOutputStream stream = new DataOutputStream(os)){
            stream.writeInt(assets.size);
            //can't use iterator as this seq might be accessed by multiple threads
            for(int i = 0; i < assets.size; i ++){
                var asset = assets.get(i);
                if(asset.byteHash == null) throw new RuntimeException("Invalid asset (missing hash): " + asset.path);
                stream.write(asset.byteHash);
            }
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static Seq<String> readRequiredAssets(InputStream is){
        Seq<String> result = new Seq<>();
        byte[] bytes = new byte[32];
        try(DataInputStream stream = new DataInputStream(is)){
            int amount = stream.readInt();
            for(int i = 0; i < amount; i++){
                stream.readFully(bytes);
                result.add(DataAssetCache.encodeHash(bytes));
            }
        }catch(IOException e){
            throw new RuntimeException(e);
        }
        return result;
    }

    public static void writeAssets(OutputStream os, Seq<DataAsset> assets){
        try(DataOutputStream stream = new DataOutputStream(os)){
            stream.writeInt(assets.size);
            for(var asset : assets){
                Fi file = asset.getCacheFileNoNull();
                byte[] bytes = file.readBytes();
                stream.writeInt(bytes.length);
                stream.write(bytes);
            }
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static void loadAssets(InputStream is) throws IOException{
        try(DataInputStream stream = new DataInputStream(is)){
            int amount = stream.readInt();
            for(int i = 0; i < amount; i++){
                int len = stream.readInt();
                byte[] bytes = new byte[len];
                stream.readFully(bytes);
                assetCache.add(bytes);
            }
        }catch(ClosedChannelException ignored){
            //happens when the input stream is closed externally
        }
    }

    public static void packTexture(OutputStream os, String name, byte[] pngData){
        try(DataOutputStream stream = new DataOutputStream(os)){
            stream.writeUTF(name);
            stream.writeInt(pngData.length);
            stream.write(pngData);
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static ByteBuffer writeServerData(){
        String name = (headless ? Config.serverName.string() : player.name);
        String description = headless && !Config.desc.string().equals("off") ? Config.desc.string() : "";
        String map = game().state.map.name();

        ByteBuffer buffer = ByteBuffer.allocate(500);

        writeString(buffer, name, 100);
        writeString(buffer, map, 64);

        buffer.putInt(Core.settings.getInt("totalPlayers", Groups.current().player.size()));
        buffer.putInt(game().state.wave);
        buffer.putInt(Version.build);
        writeString(buffer, Version.type);

        buffer.put((byte)game().state.rules.mode().ordinal());
        buffer.putInt(netServer.admins.getPlayerLimit());

        writeString(buffer, description, 100);
        writeString(buffer, game().state.rules.modeName == null ? "" : game().state.rules.modeName, 50);
        buffer.putShort((short)Core.settings.getInt("port", port));
        return buffer;
    }

    public static Host readServerData(int ping, String hostAddress, ByteBuffer buffer){
        String host = readString(buffer);
        String map = readString(buffer);

        int players = buffer.getInt();
        int wave = buffer.getInt();
        int version = buffer.getInt();
        String vertype = readString(buffer);

        byte mode = buffer.get();
        Gamemode gamemode = Gamemode.all[mode < Gamemode.all.length ? mode : 0];
        int limit = buffer.getInt();

        String description = readString(buffer);
        String modeName = readString(buffer);
        short port = buffer.getShort();
        int hostPort = port != 0 ? port : Vars.port;

        return new Host(ping, host, hostAddress, hostPort, map, wave, players, version, vertype, gamemode, limit, description, modeName.isEmpty() ? null : modeName);
    }

    private static void writeString(ByteBuffer buffer, String string, int maxlen){
        byte[] bytes = string.getBytes(charset);
        //todo truncating this way may lead to wierd encoding errors at the ends of strings...
        if(bytes.length > maxlen){
            bytes = Arrays.copyOfRange(bytes, 0, maxlen);
        }

        buffer.put((byte)bytes.length);
        buffer.put(bytes);
    }

    private static void writeString(ByteBuffer buffer, String string){
        writeString(buffer, string, 32);
    }

    private static String readString(ByteBuffer buffer){
        short length = (short)(buffer.get() & 0xff);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, charset);
    }
}
