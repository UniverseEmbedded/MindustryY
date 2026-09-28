package mindustry.core;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.CommandHandler.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.annotations.Annotations.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.net.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.io.TypeIO.*;
import mindustry.logic.*;
import mindustry.mod.data.*;
import mindustry.net.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import mindustry.runtime.*;
import mindustry.world.*;
import mindustry.world.meta.*;

import java.io.*;
import java.net.*;
import java.nio.*;

import static arc.util.Log.*;
import static mindustry.Vars.*;

public class NetServer implements ApplicationListener{
    /** note that snapshots are compressed, so the max snapshot size here is above the typical UDP safe limit */
    private static final int maxSnapshotSize = 800;
    private final Timekeeper
        blockSyncTime = Timekeeper.ofSeconds(6f),
        healthSyncTime = Timekeeper.ofSeconds(0.5f),
        planPreviewSyncTime = Timekeeper.ofSeconds(0.5f);

    private final FloatBuffer fbuffer = FloatBuffer.allocate(20);
    private final Writes dataWrites = new Writes(null);
    private final IntSeq hiddenIds = new IntSeq();
    private final IntSeq healthSeq = new IntSeq(maxSnapshotSize / 4 + 1);
    private final Vec2 vector = new Vec2();
    private final ClientBuildPlans plansOut = new ClientBuildPlans();
    /** If a player goes away of their server-side coordinates by this distance, they get teleported back. */
    private static final float correctDist = tilesize * 14f;

    public Administration admins = new Administration(RuntimeContexts.isPrimary());
    public CommandHandler clientCommands = new CommandHandler("/");
    public TeamAssigner assigner = (player, players) -> {
        if(mindustry.Vars.game().state.rules.pvp){
            //find team with minimum amount of players and auto-assign player to that.
            TeamData re = mindustry.Vars.game().state.teams.getActive().min(data -> {
                if((mindustry.Vars.game().state.rules.waveTeam == data.team && mindustry.Vars.game().state.rules.waves) || !data.hasCore() || data.team == Team.derelict || !data.team.rules().protectCores) return Integer.MAX_VALUE;

                int count = 0;
                for(Player other : players){
                    if(other.team() == data.team && other != player){
                        count++;
                    }
                }
                return (float)count + Mathf.random(-0.1f, 0.1f); //if several have the same playercount pick random
            });
            return re == null ? null : re.team;
        }

        return mindustry.Vars.game().state.rules.defaultTeam;
    };
    /** Converts a message + NULLABLE player sender into a single string. Override for custom prefixes/suffixes. */
    public ChatFormatter chatFormatter = (player, message) -> player == null ? message : "[coral][[" + player.coloredName() + "[coral]]:[white] " + message;

    /** Handles an incorrect command response. Returns text that will be sent to player. Override for customisation. */
    public InvalidCommandHandler invalidHandler = (player, response) -> {
        if(response.type == ResponseType.manyArguments){
            return "[scarlet]Too many arguments. Usage:[lightgray] " + response.command.text + "[gray] " + response.command.paramText;
        }else if(response.type == ResponseType.fewArguments){
            return "[scarlet]Too few arguments. Usage:[lightgray] " + response.command.text + "[gray] " + response.command.paramText;
        }else{ //unknown command
            int minDst = 0;
            Command closest = null;

            for(Command command : mindustry.Vars.game().netServer.clientCommands.getCommandList()){
                int dst = Strings.levenshtein(command.text, response.runCommand);
                if(dst < 3 && (closest == null || dst < minDst)){
                    minDst = dst;
                    closest = command;
                }
            }

            if(closest != null){
                return "[scarlet]Unknown command. Did you mean \"[lightgray]" + closest.text + "[]\"?";
            }else{
                return "[scarlet]Unknown command. Check [lightgray]/help[scarlet].";
            }
        }
    };

    private boolean closing = false, pvpAutoPaused = true;
    //private Interval timer = new Interval(10);
    private IntSet buildHealthChanged = new IntSet();

    /** Current kick session. */
    public @Nullable VoteSession currentlyKicking = null;
    /** Duration of a kick in seconds. */
    public static int kickDuration = 60 * 60;
    /** Voting round duration in seconds. */
    public static float voteDuration = 0.5f * 60;
    /** Cooldown between votes in seconds. */
    public static int voteCooldown = 60 * 5;

    private ReusableByteOutStream writeBuffer = new ReusableByteOutStream(127);
    private Writes outputBuffer = new Writes(new DataOutputStream(writeBuffer));

    /** Stream for writing player sync data to. */
    private ReusableByteOutStream syncStream = new ReusableByteOutStream();
    /** Data stream for writing player sync data to. */
    private DataOutputStream dataStream = new DataOutputStream(syncStream);
    private Writes dataStreamWrites = new Writes(dataStream);
    /** Packet handlers for custom types of messages. */
    private ObjectMap<String, Seq<Cons2<Player, String>>> customPacketHandlers = new ObjectMap<>();
    /** Packet handlers for custom types of messages - binary version. */
    private ObjectMap<String, Seq<Cons2<Player, byte[]>>> customBinaryPacketHandlers = new ObjectMap<>();
    /** Packet handlers for logic client data */
    private ObjectMap<String, Seq<Cons2<Player, Object>>> logicClientDataHandlers = new ObjectMap<>();
    /** Reused Seq<Player> for writing entity snapshots per team. */
    private Seq<Player> playersToSend = new Seq<>(false);
    private Seq<NetConnection> tempConnections = new Seq<>(false);
    /** Used for entity snapshot timing. */
    public long snapshotSyncTime;

    public NetServer(){
        SharedCampaignNet sharedNetwork = SharedCampaignNet.install(mindustry.Vars.game());

        mindustry.Vars.game().net.handleServer(Connect.class, (con, connect) -> {
            Events.fire(new ConnectionEvent(con));

            if(admins.isIPBanned(connect.addressTCP) || admins.isSubnetBanned(connect.addressTCP)){
                if(Vars.steam && SteamAdmin.isBanned(connect.addressTCP)){
                    con.kick("You have been banned from Steam lobbies for disruptive and shameful behavior.");
                }else{
                    con.kick(KickReason.banned);
                }
            }
        });

        mindustry.Vars.game().net.handleServer(Disconnect.class, (con, packet) -> {
            // The admission grant is the only source of the Shared Campaign identity for this connection;
            // capture it before discardConnectAdmission wipes it, so the disconnect notice can still name
            // the member's sector transition.
            String memberId = resolveMemberId(con);
            sharedNetwork.discardConnectAdmission(con);
            if(con.player != null){
                onDisconnect(con.player, packet.reason, memberId);
            }
        });

        mindustry.Vars.game().net.handleServer(ConnectPacket.class, (con, packet) -> {
            if(con.kicked) return;

            // Capture and strip the reserved Action credential before Steam UUID rewriting and ordinary Mod checks.
            sharedNetwork.captureConnectAdmission(con, packet);

            if(con.address.startsWith("steam:")){
                packet.uuid = con.address.substring("steam:".length());
            }

            Events.fire(new ConnectPacketEvent(con, packet));

            con.connectTime = Time.millis();

            String uuid = packet.uuid;

            if(admins.isIPBanned(con.address) || admins.isSubnetBanned(con.address) || con.kicked || !con.isConnected()) return;

            if(admins.checkUuidChanges(con.address, packet.uuid)){
                Log.info("Banning IP @ due to more than @ ID changes in @ hour(s).", con.address, Config.uuidChangeLimit.num(), Config.uuidChangeTimePeriod.num());
                con.kick(KickReason.banned);
                return;
            }

            if(con.hasBegunConnecting){
                con.kick(KickReason.idInUse);
                return;
            }

            PlayerInfo info = admins.getInfo(uuid);

            con.hasBegunConnecting = true;
            con.mobile = packet.mobile;

            if(packet.uuid == null || packet.usid == null){
                con.kick(KickReason.idInUse);
                return;
            }

            //there's no reason to tell users that their name is inappropriate, as they may try to bypass it
            if(admins.isIDBanned(uuid) || admins.isNameBanned(packet.name)){
                con.kick(KickReason.banned);
                return;
            }

            if(Time.millis() < admins.getKickTime(uuid, con.address)){
                con.kick(KickReason.recentKick);
                return;
            }

            if(admins.getPlayerLimit() > 0 && Groups.current().player.size() >= admins.getPlayerLimit() && !mindustry.Vars.game().netServer.admins.isAdmin(uuid, packet.usid)){
                con.kick(KickReason.playerLimit);
                return;
            }

            Seq<String> extraMods = packet.mods.copy();
            Seq<String> missingMods = mods.getIncompatibility(extraMods);

            if(!extraMods.isEmpty() || !missingMods.isEmpty()){
                //can't easily be localized since kick reasons can't have formatted text with them
                StringBuilder result = new StringBuilder("[accent]Incompatible mods![]\n\n");
                if(!missingMods.isEmpty()){
                    result.append("Missing:[lightgray]\n").append("> ").append(missingMods.toString("\n> "));
                    result.append("[]\n");
                }

                if(!extraMods.isEmpty()){
                    result.append("Unnecessary mods:[lightgray]\n").append("> ").append(extraMods.toString("\n> "));
                }
                con.kick(result.toString(), 0);
                return;
            }

            if(!admins.isWhitelisted(packet.uuid, packet.usid)){
                info.adminUsid = packet.usid;
                info.lastName = packet.name;
                info.id = packet.uuid;
                admins.save();
                Call.infoMessage(con, "You are not whitelisted here.");
                info("&lcDo &lywhitelist add @&lc to whitelist the player &lb'@'", packet.uuid, packet.name);
                con.kick(KickReason.whitelist);
                return;
            }

            if(packet.versionType == null || ((packet.version == -1 || !packet.versionType.equals(Version.type)) && Version.build != -1 && !admins.allowsCustomClients())){
                con.kick(!Version.type.equals(packet.versionType) ? KickReason.typeMismatch : KickReason.customClient);
                return;
            }

            boolean preventDuplicates = mindustry.Vars.runtimeHeadless() && mindustry.Vars.game().netServer.admins.isStrict();

            if(preventDuplicates){
                if(Groups.current().player.contains(p -> Strings.stripColors(p.name).trim().equalsIgnoreCase(Strings.stripColors(packet.name).trim()))){
                    con.kick(KickReason.nameInUse);
                    return;
                }

                if(Groups.current().player.contains(player -> player.uuid().equals(packet.uuid) || player.usid().equals(packet.usid))){
                    con.uuid = packet.uuid;
                    con.kick(KickReason.idInUse);
                    return;
                }

                for(var otherCon : mindustry.Vars.game().net.getConnections()){
                    if(otherCon != con && uuid.equals(otherCon.uuid)){
                        con.uuid = packet.uuid;
                        con.kick(KickReason.idInUse);
                        return;
                    }
                }
            }

            packet.name = fixName(packet.name);

            if(packet.name.trim().length() <= 0){
                con.kick(KickReason.nameEmpty);
                return;
            }

            if(packet.locale == null){
                packet.locale = "en";
            }

            String ip = con.address;

            admins.updatePlayerJoined(uuid, ip, packet.name);

            if(packet.version != Version.build && Version.build != -1 && packet.version != -1){
                con.kick(packet.version > Version.build ? KickReason.serverOutdated : KickReason.clientOutdated);
                return;
            }

            if(packet.version == -1){
                con.modclient = true;
            }

            // Verify signed Action admission after ordinary identity/version/Mod checks but before Player creation/world data.
            if(!sharedNetwork.validateActionAdmission(con, packet)) return;

            Player player = Player.create();
            player.admin = admins.isAdmin(uuid, packet.usid) || (steam && SteamAdmin.isAdmin(con.address));
            player.con = con;
            player.con.usid = packet.usid;
            player.con.uuid = uuid;
            player.con.mobile = packet.mobile;
            player.name = packet.name;
            player.locale = packet.locale;
            player.color.set(packet.color).a(1f);
            player.spectator(sharedNetwork.consumeSpectator(con));

            //save admin ID but don't overwrite it
            if(!player.admin && !info.admin){
                info.adminUsid = packet.usid;
            }

            try{
                writeBuffer.reset();
                player.write(outputBuffer);
            }catch(Throwable t){
                con.kick(KickReason.nameEmpty);
                err(t);
                return;
            }

            con.player = player;

            //playing in pvp mode automatically assigns players to teams
            player.team(assignTeam(player));

            sendWorldAndAssets(player);

            platform.updateRPC();

            Events.fire(new PlayerConnect(player));
        });

        registerCommands();
    }

    public void sendWorldAndAssets(Player player){
        if(mindustry.Vars.game().state.data.hasExternalAssets()){
            player.con.determiningAssets = true;
            player.con.receivingAssets = false;
            player.con.hasConnected = false;
            sendAssetRequirements(player);
        }else{
            sendWorldData(player);
        }
    }

    @Override
    public void init(){
        mods.eachClass(mod -> mod.registerClientCommands(clientCommands));
    }

    private void registerCommands(){
        clientCommands.<Player>register("help", "[page]", "Lists all commands.", (args, player) -> {
            if(args.length > 0 && !Strings.canParseInt(args[0])){
                player.sendMessage("[scarlet]'page' must be a number.");
                return;
            }
            int commandsPerPage = 6;
            int page = args.length > 0 ? Strings.parseInt(args[0]) : 1;
            int pages = Mathf.ceil((float)clientCommands.getCommandList().size / commandsPerPage);

            page--;

            if(page >= pages || page < 0){
                player.sendMessage("[scarlet]'page' must be a number between[orange] 1[] and[orange] " + pages + "[scarlet].");
                return;
            }

            StringBuilder result = new StringBuilder();
            result.append(Strings.format("[orange]-- Commands Page[lightgray] @[gray]/[lightgray]@[orange] --\n\n", (page + 1), pages));

            for(int i = commandsPerPage * page; i < Math.min(commandsPerPage * (page + 1), clientCommands.getCommandList().size); i++){
                Command command = clientCommands.getCommandList().get(i);
                result.append("[orange] /").append(command.text).append("[white] ").append(command.paramText).append("[lightgray] - ").append(command.description).append("\n");
            }
            player.sendMessage(result.toString());
        });

        clientCommands.<Player>register("sector", "<target>", "Switch to another Shared Campaign sector using a vanilla reconnect.", (args, player) -> {
            SharedActionAgent agent = SharedActionBootstrap.findAgent(mindustry.Vars.game());
            if(agent == null || !agent.enabled()){
                player.sendMessage("[scarlet]This server is not a Shared Campaign Action.");
                return;
            }
            mindustry.runtime.GameContext owner = mindustry.runtime.RuntimeContexts.requireCurrent();
            NetConnection sourceConnection = player.con;
            player.sendMessage("[accent]Preparing Shared Campaign sector...");
            agent.requestVanillaTransferAsync(player, args[0]).whenComplete((result, failure) -> {
                try{
                    mindustry.runtime.RuntimeContexts.post(owner, () -> {
                        if(player.con != sourceConnection || sourceConnection == null || !sourceConnection.isConnected()) return;
                        if(failure != null){
                            player.sendMessage("[scarlet]Cannot switch sector:[] " + failure);
                            return;
                        }
                        if(result.error() != null && !result.error().isBlank()){
                            player.sendMessage("[scarlet]Cannot switch sector:[] " + result.error());
                            return;
                        }
                        player.sendMessage("[accent]Switching Shared Campaign sector...");
                        Call.connect(sourceConnection, result.host(), result.port());
                    });
                }catch(java.util.concurrent.RejectedExecutionException ignored){
                    // The source Action shut down while the coordinator was preparing the destination.
                }
            });
        });

        clientCommands.<Player>register("t", "<message...>", "Send a message only to your teammates.", (args, player) -> {
            String message = admins.filterMessage(player, args[0]);
            if(message != null){
                String raw = "[#" + player.team().color.toString() + "]<T> " + chatFormatter.format(player, message);
                Groups.current().player.each(p -> p.team() == player.team(), o -> o.sendMessage(raw, player, message));
            }
        });

        clientCommands.<Player>register("a", "<message...>", "Send a message only to admins.", (args, player) -> {
            if(!player.admin){
                player.sendMessage("[scarlet]You must be an admin to use this command.");
                return;
            }

            String raw = "[#" + Pal.adminChat.toString() + "]<A> " + chatFormatter.format(player, args[0]);
            Groups.current().player.each(Player::admin, a -> a.sendMessage(raw, player, args[0]));
        });

        //cooldowns per player
        ObjectMap<String, Timekeeper> cooldowns = new ObjectMap<>();

        clientCommands.<Player>register("votekick", "[player] [reason...]", "Vote to kick a player with a valid reason.", (args, player) -> {
            if(!Config.enableVotekick.bool()){
                player.sendMessage("[scarlet]Vote-kick is disabled on this server.");
                return;
            }

            if(Groups.current().player.size() < 3){
                player.sendMessage("[scarlet]At least 3 players are needed to start a votekick.");
                return;
            }

            if(player.isLocal()){
                player.sendMessage("[scarlet]Just kick them yourself if you're the host.");
                return;
            }

            if(currentlyKicking != null){
                player.sendMessage("[scarlet]A vote is already in progress.");
                return;
            }

            if(args.length == 0){
                StringBuilder builder = new StringBuilder();
                builder.append("[orange]Players to kick: \n");

                Groups.current().player.each(p -> !p.admin && p.con != null && p != player, p -> {
                    builder.append("[lightgray] ").append(p.name).append("[accent] (#").append(p.id()).append(")\n");
                });
                player.sendMessage(builder.toString());
            }else if(args.length == 1){
                player.sendMessage("[orange]You need a valid reason to kick the player. Add a reason after the player name.");
            }else{
                Player found;
                if(args[0].length() > 1 && args[0].startsWith("#") && Strings.canParseInt(args[0].substring(1))){
                    int id = Strings.parseInt(args[0].substring(1));
                    found = Groups.current().player.find(p -> p.id() == id);
                }else{
                    found = Groups.current().player.find(p -> p.name.equalsIgnoreCase(args[0]));
                }

                if(found != null){
                    if(found == player){
                        player.sendMessage("[scarlet]You can't vote to kick yourself.");
                    }else if(found.admin){
                        player.sendMessage("[scarlet]Did you really expect to be able to kick an admin?");
                    }else if(found.isLocal()){
                        player.sendMessage("[scarlet]Local players cannot be kicked.");
                    }else if(found.team() != player.team()){
                        player.sendMessage("[scarlet]Only players on your team can be kicked.");
                    }else{
                        Timekeeper vtime = cooldowns.get(player.uuid(), () -> Timekeeper.ofSeconds(voteCooldown));

                        if(!vtime.get()){
                            player.sendMessage("[scarlet]You must wait " + voteCooldown/60 + " minutes between votekicks.");
                            return;
                        }

                        VoteSession session = new VoteSession(found);
                        session.vote(player, 1);
                        Call.sendMessage(Strings.format("[lightgray]Reason:[orange] @[lightgray].", args[1]));
                        vtime.reset();
                        currentlyKicking = session;
                    }
                }else{
                    player.sendMessage("[scarlet]No player [orange]'" + args[0] + "'[scarlet] found.");
                }
            }
        });

        clientCommands.<Player>register("vote", "<y/n/c>", "Vote to kick the current player. Admins can cancel the voting with 'c'.", (arg, player) -> {
            if(currentlyKicking == null){
                player.sendMessage("[scarlet]Nobody is being voted on.");
            }else{
                if(player.admin && arg[0].equalsIgnoreCase("c")){
                    Call.sendMessage(Strings.format("[lightgray]Vote canceled by admin[orange] @[lightgray].", player.name));
                    currentlyKicking.task.cancel();
                    currentlyKicking = null;
                    return;
                }

                if(player.isLocal()){
                    player.sendMessage("[scarlet]Local players can't vote. Kick the player yourself instead.");
                    return;
                }

                int sign = switch(arg[0].toLowerCase()){
                    case "y", "yes" -> 1;
                    case "n", "no" -> -1;
                    default -> 0;
                };

                //hosts can vote all they want
                if((currentlyKicking.voted.get(player.uuid(), 2) == sign || currentlyKicking.voted.get(admins.getInfo(player.uuid()).lastIP, 2) == sign)){
                    player.sendMessage(Strings.format("[scarlet]You've already voted @. Sit down.", arg[0].toLowerCase()));
                    return;
                }

                if(currentlyKicking.target == player){
                    player.sendMessage("[scarlet]You can't vote on your own trial.");
                    return;
                }

                if(currentlyKicking.target.team() != player.team()){
                    player.sendMessage("[scarlet]You can't vote for other teams.");
                    return;
                }

                if(sign == 0){
                    player.sendMessage("[scarlet]Vote either 'y' (yes) or 'n' (no).");
                    return;
                }

                currentlyKicking.vote(player, sign);
            }
        });

        clientCommands.<Player>register("sync", "Re-synchronize world state.", (args, player) -> {
            if(player.isLocal()){
                player.sendMessage("[scarlet]Re-synchronizing as the host is pointless.");
            }else{
                if(Time.timeSinceMillis(player.getInfo().lastSyncTime) < 1000 * 5){
                    player.sendMessage("[scarlet]You may only /sync every 5 seconds.");
                    return;
                }

                player.getInfo().lastSyncTime = Time.millis();
                Call.worldDataBegin(player.con);
                mindustry.Vars.game().netServer.sendWorldData(player);
            }
        });
    }

    public int votesRequired(){
        return 2 + (Groups.current().player.size() > 4 ? 1 : 0);
    }

    public Team assignTeam(Player current){
        return assigner.assign(current, Groups.current().player);
    }

    public Team assignTeam(Player current, Iterable<Player> players){
        return assigner.assign(current, players);
    }

    public void sendAssetRequirements(Player player){
        var assets = mindustry.Vars.game().state.data.getAllExternalAssets();
        mainExecutor.submit(() -> {
            var stream = new ByteArrayOutputStream();
            NetworkIO.writeRequiredAssets(new FastDeflaterOutputStream(stream), assets);
            player.con.sendStreamAsync(new AssetRequirementStream(), stream);
        });
    }

    public void sendWorldData(Player player){
        var stream = new ByteArrayOutputStream();
        NetworkIO.writeWorld(player, new FastDeflaterOutputStream(stream));
        player.con.sendStream(new WorldStream(), stream);

        debug("Packed @ of world data to @ (@ / @)", Strings.formatByteCount(stream.size()), player.name, player.con.address, player.uuid());
    }

    /**
     * Streams a texture to a single connected client. This may take some time if the image is large or if the connection is poor.
     * Make sure to call {@link #removeTexture(NetConnection, String)} when the image is no longer needed to prevent resource leaks.
     * Use {@link PixmapIO#writePngBytes} to get Pixmap bytes. Respect {@link mindustry.mod.DataPatcher#maxImageSize}.
     * Should be called on main thread to ensure correct ordering of sends (multiple with same name), and removals (remove called right after adding). */
    public void sendTexture(NetConnection con, String name, byte[] pngData){
        var stream = new ByteArrayOutputStream();
        NetworkIO.packTexture(stream, name, pngData);
        con.sendStreamAsync(new TextureStream(), stream);
    }

    /** Streams a texture to every connected client.
     * See {@link #sendTexture(NetConnection, String, byte[])} for more info. */
    public void sendTexture(String name, byte[] pngData){
        var stream = new ByteArrayOutputStream();
        NetworkIO.packTexture(stream, name, pngData);
        for(NetConnection con : net.getConnections()){
            con.sendStreamAsync(new TextureStream(), stream);
        }
    }

    /** Removes a texture previously sent with {@link #sendTexture(NetConnection, String, byte[])} from a single client.
     * If called while the texture is in use, it will be replaced with a black rectangle. Do not do this.
     * Should be called on main thread for the same reasons as sendTexture. */
    public void removeTexture(NetConnection con, String name){
        sendTexture(con, name, Streams.emptyBytes);
    }

    /** Removes a texture previously sent with {@link #sendTexture(String, byte[])} from every connected client.
     * See {@link #removeTexture(NetConnection, String)} for more info. */
    public void removeTexture(String name){
        sendTexture(name, Streams.emptyBytes);
    }

    public void addPacketHandler(String type, Cons2<Player, String> handler){
        customPacketHandlers.get(type, Seq::new).add(handler);
    }

    public Seq<Cons2<Player, String>> getPacketHandlers(String type){
        return customPacketHandlers.get(type, Seq::new);
    }

    public void addBinaryPacketHandler(String type, Cons2<Player, byte[]> handler){
        customBinaryPacketHandlers.get(type, Seq::new).add(handler);
    }

    public Seq<Cons2<Player, byte[]>> getBinaryPacketHandlers(String type){
        return customBinaryPacketHandlers.get(type, Seq::new);
    }

    public void addLogicDataHandler(String type, Cons2<Player, Object> handler){
        logicClientDataHandlers.get(type, Seq::new).add(handler);
    }

    public static void onDisconnect(Player player, String reason){
        onDisconnect(player, reason, null);
    }

    private static void onDisconnect(Player player, String reason, String memberId){
        //singleplayer multiplayer weirdness
        if(player.con == null){
            player.remove();
            return;
        }

        if(!player.con.hasDisconnected){
            if(player.con.hasConnected){
                Events.fire(new PlayerLeave(player));
                if(Config.showConnectMessages.bool()) Call.sendMessage("[accent]" + player.name + "[accent] has disconnected." + sectorNotice("←", false, memberId == null ? resolveMemberId(player.con) : memberId));
                Call.playerDisconnect(player.id());
            }

            String message = Strings.format("&lb@&fi&lk has disconnected. [&lb@&fi&lk] (@)", player.plainName(), player.uuid(), reason);
            if(Config.showConnectMessages.bool()) info(message);
        }

        //force despawn the player unit upon disconnection in case the game is paused
        Unit u = player.unit();
        if(u != null && u.spawnedByCore && !u.dead){
            Call.unitDespawn(u);
        }

        player.remove();
        player.con.hasDisconnected = true;
    }

    /** Identity bound to a connection's signed action admission grant; empty for vanilla/non-shared connections. */
    private static String resolveMemberId(NetConnection con){
        SharedCampaignNet network = mindustry.Vars.game() == null ? null : SharedCampaignNet.find(mindustry.Vars.game());
        if(network == null) return "";
        return network.authenticatedMemberId(con);
    }

    /**
     * Chat suffix naming the sector this server hosts, so connect/disconnect notices say which sector the
     * player entered or left. Blank outside campaign contexts (descriptor for shared-action processes,
     * {@code rules.sector} for a vanilla hosted campaign game).
     *
     * <p>Shared Actions append one of three white i18n forms after the vanilla notice: {@code from A to B}
     * when the member's recorded join crossed sectors, otherwise {@code enter X} / {@code exit X}. The
     * transition is resolved from the member's latest {@code member-sector-join} campaign event, so both
     * sides of a hot-switch describe the same move; stale/absent events degrade to enter/exit.
     */
    private static String sectorNotice(String direction, boolean connecting, String memberId){
        String planet = null, sector = null;
        SharedCampaignRuntimeState sharedRuntime = mindustry.Vars.game() == null ? null : SharedCampaignRuntimeState.find(mindustry.Vars.game());
        ActionRuntimeConfig runtime = sharedRuntime == null ? null : sharedRuntime.actionRuntime();
        boolean sharedAction = runtime != null && runtime.enabled() && runtime.descriptor() != null;
        if(sharedAction){
            planet = runtime.descriptor().planetName();
            sector = runtime.descriptor().sectorName();
        }else if(mindustry.Vars.game() != null && mindustry.Vars.game().state != null && mindustry.Vars.game().state.hasSector()){
            mindustry.type.Sector current = mindustry.Vars.game().state.getSector();
            if(current != null){
                planet = current.planet == null ? null : current.planet.name;
                sector = current.name();
            }
        }
        if(planet == null || sector == null || planet.isBlank() || sector.isBlank()) return "";

        if(!sharedAction){
            // Vanilla hosted campaign: no member transitions exist; keep the historical gray arrow suffix.
            return " [gray]" + direction + " " + planet + "/" + sector + "[]";
        }

        SharedCampaignService service = SharedCampaignService.find(mindustry.Vars.game());
        SharedCampaignState campaign = service == null ? null : service.strategicState();
        String ownKey = SharedCampaignProgress.sectorKey(planet, sector);
        String ownDisplay = sectorDisplayName(campaign, planet, sector);
        String[] transition = latestSectorJoin(campaign, memberId);
        if(transition != null){
            boolean crossed = connecting
                ? !transition[0].isEmpty() && !transition[0].equals(ownKey) && transition[1].equals(ownKey)
                : transition[0].equals(ownKey) && !transition[1].isEmpty() && !transition[1].equals(ownKey);
            if(crossed){
                return " [white]from " + sectorDisplayName(campaign, transition[0]) + " to " + sectorDisplayName(campaign, transition[1]) + ".[]";
            }
        }
        return connecting ? " [white]enter " + ownDisplay + ".[]" : " [white]exit " + ownDisplay + ".[]";
    }

    /**
     * Latest {@code member-sector-join} payload of {@code memberId} as {@code [fromKey, toKey]} sector keys,
     * or null when no campaign state/event exists (vanilla, lagging snapshot, never joined).
     */
    private static String[] latestSectorJoin(SharedCampaignState campaign, String memberId){
        if(campaign == null || memberId == null || memberId.isBlank()) return null;
        for(int i = campaign.recentEvents.size - 1; i >= 0; i--){
            SharedCampaignState.CampaignEvent event = campaign.recentEvents.get(i);
            if(!"member-sector-join".equals(event.type) || !memberId.equals(event.subjectId)) continue;
            int split = event.payload.indexOf(" -> ");
            if(split < 0) return null;
            return new String[]{event.payload.substring(0, split), event.payload.substring(split + 4)};
        }
        return null;
    }

    /** i18n display name for a {@code planet/sector} key: shared custom name, then bundle-localized preset name. */
    private static String sectorDisplayName(SharedCampaignState campaign, String sectorKey){
        int slash = sectorKey.indexOf('/');
        return slash <= 0 ? sectorKey : sectorDisplayName(campaign, sectorKey.substring(0, slash), sectorKey.substring(slash + 1));
    }

    private static String sectorDisplayName(SharedCampaignState campaign, String planetName, String sectorName){
        mindustry.type.Sector sector = SharedCampaignProgress.findSector(planetName, sectorName);
        if(sector == null) return sectorName;
        if(campaign != null){
            SharedCampaignState.SectorState saved = campaign.sectors.get(SharedCampaignProgress.sectorKey(planetName, sectorName));
            if(saved != null && saved.displayName != null && !saved.displayName.isBlank()) return saved.displayName;
        }
        if(sector.preset != null && (sector.preset.requireUnlock || sector.preset.showHidden)) return sector.preset.localizedName;
        if(sector.planet != null && sector.planet.sectors.size == 1) return sector.planet.localizedName;
        return sector.name();
    }

    //these functions are for debugging only, and will be removed!

    @Remote(targets = Loc.client, variants = Variant.one)
    public static void requestDebugStatus(Player player){
        int flags =
        (player.con.hasDisconnected ? 1 : 0) |
        (player.con.hasConnected ? 2 : 0) |
        (player.isAdded() ? 4 : 0) |
        (player.con.hasBegunConnecting ? 8 : 0);

        Call.debugStatusClient(player.con, flags, player.con.lastReceivedClientSnapshot);
        Call.debugStatusClientUnreliable(player.con, flags, player.con.lastReceivedClientSnapshot);
    }

    @Remote(variants = Variant.both, priority = PacketPriority.high)
    public static void debugStatusClient(int value, int lastClientSnapshot){
        logClientStatus(true, value, lastClientSnapshot);
    }

    @Remote(variants = Variant.both, priority = PacketPriority.high, unreliable = true)
    public static void debugStatusClientUnreliable(int value, int lastClientSnapshot){
        logClientStatus(false, value, lastClientSnapshot);
    }

    static void logClientStatus(boolean reliable, int value, int lastClientSnapshot){
        Log.info("@ Debug status received. disconnected = @, connected = @, added = @, begunConnecting = @ lastClientSnapshot = @",
        reliable ? "[RELIABLE]" : "[UNRELIABLE]",
        (value & 1) != 0, (value & 2) != 0, (value & 4) != 0, (value & 8) != 0,
        lastClientSnapshot
        );
    }

    @Remote(targets = Loc.client)
    public static void serverPacketReliable(Player player, String type, String contents){
        if(player != null && player.spectator()) return;
        if(mindustry.Vars.game().netServer.customPacketHandlers.containsKey(type)){
            for(Cons2<Player, String> c : mindustry.Vars.game().netServer.customPacketHandlers.get(type)){
                c.get(player, contents);
            }
        }
    }

    @Remote(targets = Loc.client, unreliable = true)
    public static void serverPacketUnreliable(Player player, String type, String contents){
        serverPacketReliable(player, type, contents);
    }

    @Remote(targets = Loc.client)
    public static void serverBinaryPacketReliable(Player player, String type, byte[] contents){
        if(player != null && player.spectator()) return;
        if(mindustry.Vars.game().netServer.customBinaryPacketHandlers.containsKey(type)){
            for(var c : mindustry.Vars.game().netServer.customBinaryPacketHandlers.get(type)){
                c.get(player, contents);
            }
        }
    }

    @Remote(targets = Loc.client, unreliable = true)
    public static void serverBinaryPacketUnreliable(Player player, String type, byte[] contents){
        serverBinaryPacketReliable(player, type, contents);
    }

    @Remote(targets = Loc.client)
    public static void clientLogicDataReliable(Player player, String channel, Object value){
        if(player != null && player.spectator()) return;
        Seq<Cons2<Player, Object>> handlers = mindustry.Vars.game().netServer.logicClientDataHandlers.get(channel);
        if(handlers != null){
            for(Cons2<Player, Object> handler : handlers){
                handler.get(player, value);
            }
        }
    }

    @Remote(targets = Loc.client, unreliable = true)
    public static void clientLogicDataUnreliable(Player player, String channel, Object value){
        clientLogicDataReliable(player, channel, value);
    }

    private static boolean invalid(float f){
        return Float.isInfinite(f) || Float.isNaN(f);
    }

    public static void syncBuilding(Building build){
        if(build == null) return;
        mindustry.Vars.game().netServer.syncStream.reset();
        mindustry.Vars.game().netServer.dataStreamWrites.i(build.pos());
        mindustry.Vars.game().netServer.dataStreamWrites.s(build.block.id);
        build.writeSync(mindustry.Vars.game().netServer.dataStreamWrites);

        Call.blockSnapshot((short)1, mindustry.Vars.game().netServer.syncStream.toByteArray());
        mindustry.Vars.game().netServer.syncStream.reset();
    }

    @Remote(targets = Loc.client, priority = PacketPriority.low, unreliable = true)
    public static void requestBlockSnapshot(Player player, int pos){
        Building build = mindustry.Vars.game().world.build(pos);
        if(build != null && build.team == player.team()){
            mindustry.Vars.game().netServer.syncStream.reset();
            mindustry.Vars.game().netServer.dataStreamWrites.i(build.pos());
            mindustry.Vars.game().netServer.dataStreamWrites.s(build.block.id);
            build.writeSync(mindustry.Vars.game().netServer.dataStreamWrites);

            Call.blockSnapshot(player.con, (short)1, mindustry.Vars.game().netServer.syncStream.toByteArray());
            mindustry.Vars.game().netServer.syncStream.reset();
        }
    }

    //sent from the client to the server in batches with the same incrementing groupId
    @Remote(targets = Loc.client, unreliable = true, priority = PacketPriority.low)
    public static void clientPlanSnapshot(Player player, int groupId, @Nullable ClientBuildPlans plans){
        if(player != null && player.spectator()) return;
        if(player == null) return;
        player.handlePreviewPlans(groupId, plans);
    }

    //sent from the server to the client in batches with the same incrementing groupId
    @Remote(targets = Loc.server, unreliable = true, priority = PacketPriority.low, variants = Variant.one)
    public static void clientPlanSnapshotReceived(Player player, int groupId, @Nullable ClientBuildPlans plans){
        clientPlanSnapshot(player, groupId, plans);
    }

    @Remote(targets = Loc.client, unreliable = true, priority = PacketPriority.high)
    public static void clientSnapshot(
        Player player,
        int snapshotID,
        int unitID,
        boolean dead,
        float x, float y,
        float pointerX, float pointerY,
        float rotation, float baseRotation,
        float xVelocity, float yVelocity,
        Tile mining,
        boolean boosting, boolean shooting, boolean chatting, boolean building,
        Block selectedBlock, int selectedRotation, @Nullable Queue<BuildPlan> plans,
        float viewX, float viewY, float viewWidth, float viewHeight
    ){
        NetConnection con = player.con;
        if(con == null || snapshotID < con.lastReceivedClientSnapshot) return;
        NetServer server = mindustry.Vars.game().netServer;

        //validate coordinates just in case
        if(invalid(x)) x = 0f;
        if(invalid(y)) y = 0f;
        if(invalid(xVelocity)) xVelocity = 0f;
        if(invalid(yVelocity)) yVelocity = 0f;
        if(invalid(pointerX)) pointerX = 0f;
        if(invalid(pointerY)) pointerY = 0f;
        if(invalid(rotation)) rotation = 0f;
        if(invalid(baseRotation)) baseRotation = 0f;

        boolean verifyPosition = mindustry.Vars.game().netServer.admins.isStrict() && mindustry.Vars.runtimeHeadless();

        if(con.lastReceivedClientTime == 0) con.lastReceivedClientTime = Time.millis() - 16;

        con.viewX = viewX;
        con.viewY = viewY;
        con.viewWidth = viewWidth;
        con.viewHeight = viewHeight;

        //disable shooting when a mech flies
        if(!player.dead() && player.unit().isFlying() && player.unit() instanceof Mechc){
            shooting = false;
        }

        if(!player.dead() && (player.unit().type.flying || !player.unit().type.canBoost)){
            boosting = false;
        }

        player.mouseX = pointerX;
        player.mouseY = pointerY;
        player.typing = chatting;
        player.shooting = shooting;
        player.boosting = boosting;
        player.selectedBlock = selectedBlock;
        player.selectedRotation = selectedRotation;

        @Nullable var unit = player.unit();

        if(player.isBuilder()){
            unit.clearBuilding();
            unit.updateBuilding(building);

            if(plans != null){
                for(BuildPlan req : plans){
                    if(req == null) continue;
                    Tile tile = mindustry.Vars.game().world.tile(req.x, req.y);
                    if(tile == null || (!req.breaking && req.block == null)) continue;
                    //auto-skip done requests
                    if(req.breaking && tile.block() == Blocks.air){
                        continue;
                    }else if(!req.breaking && tile.block() == req.block && tile.team() != Team.derelict && (!req.block.rotate || (tile.build != null && tile.build.rotation == req.rotation))){
                        continue;
                    }else if(con.rejectedRequests.contains(r -> r.breaking == req.breaking && r.x == req.x && r.y == req.y)){ //check if request was recently rejected, and skip it if so
                        continue;
                    }else if(!mindustry.Vars.game().netServer.admins.allowAction(player, req.breaking ? ActionType.breakBlock : ActionType.placeBlock, tile, action -> { //make sure request is allowed by the server
                        action.block = req.block;
                        action.rotation = req.rotation;
                        action.config = req.config;
                    })){
                        //force the player to remove this request if that's not the case
                        Call.removeQueueBlock(player.con, req.x, req.y, req.breaking);
                        con.rejectedRequests.add(req);
                        continue;
                    }
                    player.unit().plans().addLast(req);
                }
            }
        }

        con.rejectedRequests.clear();

        if(!player.dead()){
            unit.controlWeapons(shooting, shooting);
            unit.aim(pointerX, pointerY, true);
            unit.mineTile = mining;

            long elapsed = Math.min(Time.timeSinceMillis(con.lastReceivedClientTime), 1500);
            float maxSpeed = unit.speed();

            float maxMove = elapsed / 1000f * 60f * maxSpeed * 1.1f;

            //ignore the position if the player thinks they're dead, or the unit is wrong
            boolean ignorePosition = dead || unit.id != unitID;
            float newx = unit.x, newy = unit.y;

            if(!ignorePosition){
                unit.vel.set(xVelocity, yVelocity).limit(maxSpeed);

                server.vector.set(x, y).sub(unit);
                server.vector.limit(maxMove);

                float prevx = unit.x, prevy = unit.y;
                if(!unit.isFlying()){
                    unit.move(server.vector.x, server.vector.y);
                }else{
                    unit.trns(server.vector.x, server.vector.y);
                }

                newx = unit.x;
                newy = unit.y;

                if(!verifyPosition){
                    unit.set(prevx, prevy);
                    newx = x;
                    newy = y;
                }else if(!Mathf.within(x, y, newx, newy, correctDist)){
                    Call.setPosition(player.con, newx, newy); //teleport and correct position when necessary
                }
            }

            //write sync data to the buffer
            server.fbuffer.limit(20);
            server.fbuffer.position(0);

            //now, put the new position, rotation and baserotation into the buffer so it can be read
            //TODO this is terrible
            if(unit instanceof Mechc) server.fbuffer.put(baseRotation); //base rotation is optional
            server.fbuffer.put(rotation); //rotation is always there
            server.fbuffer.put(newx);
            server.fbuffer.put(newy);
            server.fbuffer.flip();

            //read sync data so it can be used for interpolation for the server
            unit.readSyncManual(server.fbuffer);
        }else{
            player.x = x;
            player.y = y;
        }

        con.lastReceivedClientSnapshot = snapshotID;
        con.lastReceivedClientTime = Time.millis();
    }

    @Remote(targets = Loc.client, called = Loc.server)
    public static void adminRequest(Player player, Player other, AdminAction action, Object params){
        if(player != null && player.spectator()) return;
        if(!player.admin && !player.isLocal()){
            warn("ACCESS DENIED: Player @ / @ attempted to perform admin action '@' on '@' without proper security access.",
            player.plainName(), player.con == null ? "null" : player.con.address, action.name(), other == null ? null : other.plainName());
            return;
        }

        if(other == null || ((other.admin && !player.isLocal()) && other != player)){
            warn("@ &fi&lk[&lb@&fi&lk]&fb attempted to perform admin action on nonexistant or admin player.", player.plainName(), player.uuid());
            return;
        }

        Events.fire(new EventType.AdminRequestEvent(player, other, action));

        switch(action){
            case wave -> {
                //no verification is done, so admins can hypothetically spam waves
                //not a real issue, because server owners may want to do just that
                mindustry.Vars.game().logic.skipWave();
                info("&lc@ &fi&lk[&lb@&fi&lk]&fb has skipped the wave.", player.plainName(), player.uuid());
            }
            case ban -> {
                mindustry.Vars.game().netServer.admins.banPlayerID(other.con.uuid);
                mindustry.Vars.game().netServer.admins.banPlayerIP(other.con.address);
                other.kick(KickReason.banned);
                info("&lc@ &fi&lk[&lb@&fi&lk]&fb has banned @ &fi&lk[&lb@&fi&lk]&fb.", player.plainName(), player.uuid(), other.plainName(), other.uuid());
            }
            case kick -> {
                other.kick(KickReason.kick);
                info("&lc@ &fi&lk[&lb@&fi&lk]&fb has kicked @ &fi&lk[&lb@&fi&lk]&fb.", player.plainName(), player.uuid(), other.plainName(), other.uuid());
            }
            case trace -> {
                PlayerInfo stats = mindustry.Vars.game().netServer.admins.getInfo(other.uuid());
                TraceInfo info = new TraceInfo(other.con.address, other.uuid(), other.locale, other.con.modclient, other.con.mobile, stats.timesJoined, stats.timesKicked, stats.ips.toArray(String.class), stats.names.toArray(String.class));
                if(player.con != null){
                    Call.traceInfo(player.con, other, info);
                }else{
                    NetClient.traceInfo(other, info);
                }
            }
            case switchTeam -> {
                if(params instanceof Team team){
                    other.team(team);
                }
            }
        }
    }

    @Remote(targets = Loc.client, priority = PacketPriority.high)
    public static void requestWorld(Player player){
        if(!player.con.hasBegunConnecting || player.con.determiningAssets || !player.con.receivingAssets || player.con.hasConnected) return;

        player.con.receivingAssets = false;
        mindustry.Vars.game().netServer.sendWorldData(player);
    }

    @Remote(targets = Loc.client, priority = PacketPriority.high)
    public static void requestAssets(Player player, short[] ids){
        if(!player.con.hasBegunConnecting || !player.con.determiningAssets || player.con.receivingAssets || player.con.hasConnected) return;

        player.con.determiningAssets = false;
        player.con.receivingAssets = true;

        if(ids.length == 0){  //no assets required, all cached
            player.con.receivingAssets = false;
            mindustry.Vars.game().netServer.sendWorldData(player);
        }else{
            Seq<DataAsset> res = new Seq<>();
            Seq<DataAsset> allAssets = mindustry.Vars.game().state.data.getAllExternalAssets();
            for(short id : ids){
                if(id >= allAssets.size || id < 0) continue;
                res.add(allAssets.get(id));
            }

            //packing the data and reading it from disk can be async; it shouldn't need to block the main thread.
            mainExecutor.submit(() -> {
                try{
                    var stream = new ByteArrayOutputStream();
                    NetworkIO.writeAssets(stream, res);

                    debug("Packed @ of asset data to @ (@ / @)", Strings.formatByteCount(stream.size()), player.name, player.con.address, player.uuid());

                    player.con.sendStreamAsync(new AssetStream(), stream);
                }catch(Exception e){
                    Log.err(e);
                }
            });
        }
    }

    @Remote(targets = Loc.client, priority = PacketPriority.high)
    public static void connectConfirm(Player player){
        if(player.con.kicked) return;

        player.add();

        Events.fire(new PlayerConnectionConfirmed(player));

        if(player.con == null || player.con.hasConnected) return;

        player.con.hasConnected = true;

        if(Config.showConnectMessages.bool()){
            Call.sendMessage("[accent]" + player.name + "[accent] has connected." + sectorNotice("→", true, resolveMemberId(player.con)));
            String message = Strings.format("&lb@&fi&lk has connected. &fi&lk[&lb@&fi&lk]", player.plainName(), player.uuid());
            info(message);
        }

        if(!Config.motd.string().equalsIgnoreCase("off")){
            player.sendMessage(Config.motd.string());
        }

        Events.fire(new PlayerJoin(player));

        //plugins may have kicked the player immediately in PlayerJoinEvent, so don't respawn if that happens
        if(!player.con.kicked){
            //instantly respawn the player upon connection, even if the game is paused
            player.deathTimer = Player.deathDelay;
            player.update();
        }
    }

    public boolean isWaitingForPlayers(){
        if(mindustry.Vars.game().state.is(State.menu)) return false;
        if(mindustry.Vars.game().state.rules.pvp && !mindustry.Vars.game().state.gameOver){
            int used = 0;
            for(TeamData t : mindustry.Vars.game().state.teams.getActive()){
                if(Groups.current().player.count(p -> p.team() == t.team) > 0){
                    used++;
                }
            }
            return used < 2;
        }
        return false;
    }

    @Override
    public void update(){
        if(!mindustry.Vars.runtimeHeadless() && !closing && mindustry.Vars.game().net.server() && mindustry.Vars.game().state.isMenu()){
            closing = true;
            ui.loadfrag.show("@server.closing");
            Time.runTask(5f, () -> {
                mindustry.Vars.game().net.closeServer();
                ui.loadfrag.hide();
                closing = false;
            });
        }

        if(mindustry.Vars.game().state.isGame() && mindustry.Vars.game().net.server()){
            if(mindustry.Vars.game().state.rules.pvp && mindustry.Vars.game().state.rules.pvpAutoPause){
                boolean waiting = isWaitingForPlayers(), paused = mindustry.Vars.game().state.isPaused();
                if(waiting != paused){
                    if(waiting){
                        //is now waiting, enable pausing, flag it correctly
                        pvpAutoPaused = true;
                        mindustry.Vars.game().state.set(State.paused);
                    }else if(pvpAutoPaused){
                        //no longer waiting, stop pausing
                        mindustry.Vars.game().state.set(State.playing);
                        pvpAutoPaused = false;
                    }
                }
            }

            sync();
        }
    }

    //TODO I don't like where this is, move somewhere else?
    /** Queues a building health update. This will be sent in a Call.buildHealthUpdate packet later. */
    public void buildHealthUpdate(Building build){
        buildHealthChanged.add(build.pos());
    }

    /** Should only be used on the headless backend. */
    public void openServer(){
        openServer(Config.port.num());
    }

    /** Opens this runtime's server on an explicit endpoint; embedded Sectors must not share process-global Config.port. */
    public void openServer(int port){
        if(port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid server port: " + port);
        try{
            mindustry.Vars.game().net.host(port);
            // Shared Action: players dial the coordinator entry; this process only listens on the Action game port.
            int entryPort = port;
            SharedCampaignRuntimeState sharedRuntime = SharedCampaignRuntimeState.find(mindustry.Vars.game());
            ActionRuntimeConfig actionRuntime = sharedRuntime == null ? null : sharedRuntime.actionRuntime();
            if(actionRuntime != null && actionRuntime.enabled() && actionRuntime.descriptor() != null){
                entryPort = actionRuntime.descriptor().coordinatorPort();
            }
            // Arc Log treats every '@' in the format string as a placeholder; build the label first.
            info("Opened a server on @.", "entry@actual=" + mindustry.y.util.YPortLog.entryAtActual(entryPort, port));
        }catch(BindException e){
            err("Unable to host on port @; shared entry/actual port is already in use. Make sure no other servers are running on the same port in your network.", port);
            mindustry.Vars.game().state.set(State.menu);
        }catch(IOException e){
            err(e);
            mindustry.Vars.game().state.set(State.menu);
        }
    }

    public void kickAll(KickReason reason){
        for(NetConnection con : mindustry.Vars.game().net.getConnections()){
            con.kick(reason);
        }
    }

    /** Sends a block snapshot to all players. */
    public void writeBlockSnapshots() throws IOException{
        syncStream.reset();

        short sent = 0;
        for(var team : mindustry.Vars.game().state.teams.present){
            for(var build : mindustry.Vars.game().indexer.getFlagged(team.team, BlockFlag.synced)){
                sent++;

                dataStream.writeInt(build.pos());
                dataStream.writeShort(build.block.id);
                build.writeSync(dataStreamWrites);

                if(syncStream.size() > maxSnapshotSize){
                    dataStream.close();
                    Call.blockSnapshot(sent, syncStream.toByteArray());
                    sent = 0;
                    syncStream.reset();
                }
            }
        }

        if(sent > 0){
            dataStream.close();
            Call.blockSnapshot(sent, syncStream.toByteArray());
        }
    }

    public void writeStateSnapshot() throws IOException{
        byte tps = (byte)Math.min(Core.graphics.getFramesPerSecond(), 255);
        syncStream.reset();
        int activeTeams = (byte)mindustry.Vars.game().state.teams.present.count(t -> t.cores.size > 0);

        dataStream.writeByte(activeTeams);
        dataWrites.output = dataStream;

        //block data isn't important, just send the items for each team, they're synced across cores
        for(TeamData data : mindustry.Vars.game().state.teams.present){
            if(data.cores.size > 0){
                dataStream.writeByte(data.team.id);
                data.cores.first().items.write(dataWrites);
            }
        }

        dataStream.close();

        Call.stateSnapshot(mindustry.Vars.game().state.wavetime, mindustry.Vars.game().state.wave, mindustry.Vars.game().state.enemies, mindustry.Vars.game().state.isPaused(), mindustry.Vars.game().state.gameOver,
        mindustry.Vars.game().universe.seconds(), tps, mindustry.Vars.game().logicVars.rand.seed0, mindustry.Vars.game().logicVars.rand.seed1, syncStream.toByteArray());
    }

    /** Does not check isSyncHidden. Call this if no entities are hidden. */
    public void writeEntitySnapshotsAll() throws IOException{
        syncStream.reset();

        int sent = 0;

        for(Syncc entity : Groups.current().sync){
            writeEntity(entity, dataStream);

            sent++;

            if(syncStream.size() > maxSnapshotSize){
                dataStream.close();
                Call.entitySnapshot((short)sent, syncStream.toByteArray());
                sent = 0;
                syncStream.reset();
            }
        }

        if(sent > 0){
            dataStream.close();

            Call.entitySnapshot((short)sent, syncStream.toByteArray());
        }
    }

    /** Checks isSyncHidden for only one player per team. Called if FoW is enabled. */
    public void writeEntitySnapshotsTeam(Team team, Seq<Player> players) throws IOException{
        syncStream.reset();

        hiddenIds.clear();
        int sent = 0;
        tempConnections.clear();

        for(Player player : players){
            //player.con must not be null here (the players seq must ONLY contain non-local connected clients)
            tempConnections.add(player.con);
        }

        for(Syncc entity : Groups.current().sync){
            if(entity.isSyncHidden(team)){
                hiddenIds.add(entity.id());
                continue;
            }

            writeEntity(entity, dataStream);

            sent++;

            if(syncStream.size() > maxSnapshotSize){
                dataStream.close();
                sendEntitySnapshots(tempConnections, (short)sent, syncStream.toByteArray());
                sent = 0;
                syncStream.reset();
            }
        }

        if(sent > 0){
            dataStream.close();
            sendEntitySnapshots(tempConnections, (short)sent, syncStream.toByteArray());
        }

        if(hiddenIds.size > 0){
            var packet = new HiddenSnapshotCallPacket();
            packet.ids = hiddenIds;
            mindustry.Vars.game().net.send(packet, tempConnections, false);
        }
    }

    protected void sendEntitySnapshots(Seq<NetConnection> connections, short amount, byte[] data){
        var packet = new EntitySnapshotCallPacket();
        packet.amount = amount;
        packet.data = data;
        mindustry.Vars.game().net.send(packet, connections, false);
    }

    /** Writes a custom snapshot containing player-local entities; this is for entities other players don't see. */
    public void writeCustomEntitySnapshot(Player player, Iterable<Syncc> entities) throws IOException{
        syncStream.reset();

        int sent = 0;

        for(Syncc entity : entities){
            writeEntity(entity, dataStream);

            sent++;

            if(syncStream.size() > maxSnapshotSize){
                dataStream.close();
                Call.entitySnapshot(player.con, (short)sent, syncStream.toByteArray());
                sent = 0;
                syncStream.reset();
            }
        }

        if(sent > 0){
            dataStream.close();

            Call.entitySnapshot(player.con, (short)sent, syncStream.toByteArray());
        }
    }

    protected void writeEntity(Syncc entity, DataOutputStream dataStream) throws IOException{
        dataStream.writeInt(entity.id());
        dataStream.writeByte(entity.classId() & 0xFF);
        entity.beforeWrite();
        entity.writeSync(dataStreamWrites);
    }

    public String fixName(String name){
        name = name.trim().replace("\n", "").replace("\t", "");
        if(name.equals("[") || name.equals("]")){
            return "";
        }

        for(int i = 0; i < name.length(); i++){
            if(name.charAt(i) == '[' && i != name.length() - 1 && name.charAt(i + 1) != '[' && (i == 0 || name.charAt(i - 1) != '[')){
                String prev = name.substring(0, i);
                String next = name.substring(i);
                String result = checkColor(next);

                name = prev + result;
            }
        }

        StringBuilder result = new StringBuilder();
        int curChar = 0;
        while(curChar < name.length() && result.toString().getBytes(Strings.utf8).length < maxNameLength){
            result.append(name.charAt(curChar++));
        }
        return result.toString();
    }

    public String checkColor(String str){
        for(int i = 1; i < str.length(); i++){
            if(str.charAt(i) == ']'){
                String color = str.substring(1, i);

                if(Colors.get(color.toUpperCase()) != null || Colors.get(color.toLowerCase()) != null){
                    Color result = (Colors.get(color.toLowerCase()) == null ? Colors.get(color.toUpperCase()) : Colors.get(color.toLowerCase()));
                    if(result.a < 1f){
                        return str.substring(i + 1);
                    }
                }else{
                    try{
                        Color result = Color.valueOf(color);
                        if(result.a < 1f){
                            return str.substring(i + 1);
                        }
                    }catch(Exception e){
                        return str;
                    }
                }
            }
        }
        return str;
    }

    void sync(){
        try{
            int interval = Config.snapshotInterval.num();
            Groups.current().player.each(p -> !p.isLocal(), player -> {
                if(player.con == null || !player.con.isConnected()){
                    onDisconnect(player, "disappeared");
                }
            });

            if(Time.timeSinceMillis(snapshotSyncTime) >= interval){
                snapshotSyncTime = Time.millis();

                writeStateSnapshot();

                if(Vars.state.rules.fog){
                    //Serialize by teams
                    for(Team team : Team.all){ //Not Teams.active, because players can be on inactive teams
                        var tdata = team.data();
                        playersToSend.selectFrom(tdata.players, p -> !p.isLocal() && p.con.hasConnected);
                        if(!playersToSend.isEmpty()){
                            writeEntitySnapshotsTeam(team, playersToSend);
                        }
                    }
                }else{
                    //Serialize once for all players
                    writeEntitySnapshotsAll();
                }

                //write custom player-specific entities (usually labels)
                for(Player player : Groups.current().player){
                    if(player.con != null && player.con.hasConnected && player.con.localEntities.size > 0){
                        writeCustomEntitySnapshot(player, player.con.localEntities);
                    }
                }
            }


            if(Groups.current().player.size() > 0 && Core.settings.getBool("blocksync") && blockSyncTime.poll()){
                writeBlockSnapshots();
            }

            if(Groups.current().player.size() > 0 && buildHealthChanged.size > 0 && healthSyncTime.poll()){
                healthSeq.clear();

                var iter = buildHealthChanged.iterator();
                while(iter.hasNext){
                    int next = iter.next();
                    var build = mindustry.Vars.game().world.build(next);

                    //pack pos + health into update list
                    if(build != null){
                        healthSeq.add(next, Float.floatToRawIntBits(build.health));
                    }

                    //if size exceeds snapshot limit, send it out and begin building it up again
                    if(healthSeq.size * 4 >= maxSnapshotSize){
                        Call.buildHealthUpdate(healthSeq);
                        healthSeq.clear();
                    }
                }

                //send any residual health updates
                if(healthSeq.size > 0){
                    Call.buildHealthUpdate(healthSeq);
                }

                buildHealthChanged.clear();
            }

            //TODO: this system is a big bandwidth waster, it would be nicer to have a diff system instead
            if(Groups.current().player.size() > 0 && planPreviewSyncTime.poll()){

                if(mindustry.Vars.runtimeVisualsEnabled()){ //update local player's plans so that clients see it
                    player.previewPlansCurrent.clear();
                    control.input.getSyncedPlans(player.previewPlansCurrent);
                    player.previewPlansCurrent.truncate(maxPlayerPreviewPlans);
                }

                Groups.current().player.each(player -> {
                    int id = ++player.lastPreviewPlanGroupServer;
                    plansOut.clear();

                    var plans = player.getPreviewPlans();

                    if(plans.isEmpty()){
                        clientPlanSnapshotSend(player, id, null);
                    }else{
                        BuildPlan[] items = plans.items;
                        int size = plans.size;
                        //max snapshot size = 800
                        //max reasonable plan size = 12
                        //divide the two to get the size of plan batches
                        final int chunkSize = 900 / 12;

                        if(size < chunkSize){
                            plansOut.set(plans);
                            clientPlanSnapshotSend(player, id, plansOut);
                        }else{
                            for(int i = 0; i < size; i += chunkSize){
                                int len = Math.min(i + chunkSize, size) - i;
                                plansOut.ensureCapacity(len);
                                System.arraycopy(items, i, plansOut.items, 0, len);
                                plansOut.size = len;

                                clientPlanSnapshotSend(player, id, plansOut);
                            }
                        }
                    }
                });
            }
        }catch(IOException e){
            Log.err(e);
        }
    }

    static void clientPlanSnapshotSend(Player player, int groupId, ClientBuildPlans plans){

        //only send to others of the same team
        for(Player other : player.team().data().players){
            if(other != player && !other.isLocal() && other.con != null && other.con.isConnected()){
                Call.clientPlanSnapshotReceived(other.con, player, groupId, plans);
            }
        }
    }

    public class VoteSession{
        Player target;
        ObjectIntMap<String> voted = new ObjectIntMap<>();
        Timer.Task task;
        int votes;

        public VoteSession(Player target){
            this.target = target;
            this.task = Timer.schedule(() -> {
                if(!checkPass()){
                    Call.sendMessage(Strings.format("[lightgray]Vote failed. Not enough votes to kick[orange] @[lightgray].", target.name));
                    currentlyKicking = null;
                    task.cancel();
                }
            }, voteDuration);
        }

        void vote(Player player, int d){
            int lastVote = voted.get(player.uuid(), 0) | voted.get(admins.getInfo(player.uuid()).lastIP, 0);
            votes -= lastVote;

            votes += d;
            voted.put(player.uuid(), d);
            voted.put(admins.getInfo(player.uuid()).lastIP, d);

            Call.sendMessage(Strings.format("[lightgray]@[lightgray] has voted on kicking[orange] @[lightgray].[accent] (@/@)\n[lightgray]Type[orange] /vote <y/n>[] to agree.",
            player.name, target.name, votes, votesRequired()));

            checkPass();
        }

        boolean checkPass(){
            if(votes >= votesRequired()){
                Call.sendMessage(Strings.format("[orange]Vote passed.[scarlet] @[orange] will be banned from the server for @ minutes.", target.name, (kickDuration / 60)));
                Groups.current().player.each(p -> p.uuid().equals(target.uuid()), p -> p.kick(KickReason.vote, kickDuration * 1000));
                currentlyKicking = null;
                task.cancel();
                return true;
            }
            return false;
        }
    }

    public interface TeamAssigner{
        Team assign(Player player, Iterable<Player> players);
    }

    public interface ChatFormatter{
        /** @return text to be placed before player name */
        String format(@Nullable Player player, String message);
    }

    public interface InvalidCommandHandler{
        String handle(Player player, CommandResponse response);
    }
}
