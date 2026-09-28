import arc.files.*;
import arc.net.*;
import arc.struct.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.net.ArcNetProvider.*;
import mindustry.net.Packets.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Product-network gate proving that two JVM_PROCESS Actions can host real admitted ArcNet players concurrently.
 *
 * <p>The clients in this test use the same ArcNet serializer and ConnectPacket/ConnectConfirm RPCs as a vanilla
 * Mindustry client. They intentionally do not render or install streamed world data; the server side must nevertheless
 * perform the real admission path, create a Player, add it to Groups.player, and thereby release ServerControl's
 * autoPause. The authoritative heartbeat must then report one member in each Action while both world ticks advance.</p>
 */
@Tag("shared-campaign-jvm-multi-action")
public class SharedCampaignJvmConcurrentPlayersTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(true);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
        System.setProperty(PackagedSectorLauncher.developmentFallbackProperty, "true");
        System.setProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty, "256");
    }

    @AfterAll static void resetLauncherProperties(){
        System.clearProperty(PackagedSectorLauncher.developmentFallbackProperty);
        System.clearProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty);
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void twoRealArcNetPlayersUnpauseAndAdvanceIndependentJvmWorlds() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-concurrent-players-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RealArcClient serpuloClient = null, erekirClient = null;
        RuntimePayloads.StartResult serpulo = null, erekir = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM concurrent real players gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            serpulo = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(serpulo.error(), "Serpulo start failed");
            awaitStatus(owner, serpulo.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            erekir = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(erekir.error(), "Erekir start failed");
            awaitStatus(owner, erekir.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential serpuloMember = coordinator.clientControl().enrollTrustedLocal("Serpulo player");
            CoordinatorCredentials.MemberCredential erekirMember = coordinator.clientControl().enrollTrustedLocal("Erekir player");
            String serpuloUuid = platformUuid(91);
            String erekirUuid = platformUuid(92);

            RuntimePayloads.VanillaTransferResult serpuloGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                serpuloMember.memberId(), serpulo.actionId(), serpuloUuid, "127.0.0.1", false);
            RuntimePayloads.VanillaTransferResult erekirGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                erekirMember.memberId(), erekir.actionId(), erekirUuid, "127.0.0.1", false);
            assertBlank(serpuloGrant.error(), "Serpulo vanilla admission failed");
            assertBlank(erekirGrant.error(), "Erekir vanilla admission failed");

            serpuloClient = RealArcClient.connectVanilla("Serpulo player", serpuloUuid, serpuloGrant.host(), serpuloGrant.port());
            erekirClient = RealArcClient.connectVanilla("Erekir player", erekirUuid, erekirGrant.host(), erekirGrant.port());

            SharedCampaignState present = awaitPresence(owner, serpulo.actionId(), serpuloMember.memberId(),
                erekir.actionId(), erekirMember.memberId(), Duration.ofSeconds(30));
            long serpuloTick = requireAction(present, serpulo.actionId()).actionTick;
            long erekirTick = requireAction(present, erekir.actionId()).actionTick;

            SharedCampaignState advanced = awaitTicks(owner, serpulo.actionId(), serpuloTick + 30L,
                erekir.actionId(), erekirTick + 30L, Duration.ofSeconds(20));
            ActionState serpuloAdvanced = requireAction(advanced, serpulo.actionId());
            ActionState erekirAdvanced = requireAction(advanced, erekir.actionId());
            assertEquals(1, serpuloAdvanced.connectedPlayers, "Serpulo lost its admitted player while advancing");
            assertEquals(1, erekirAdvanced.connectedPlayers, "Erekir lost its admitted player while advancing");
            assertTrue(serpuloAdvanced.participants.contains(serpuloMember.memberId()));
            assertTrue(erekirAdvanced.participants.contains(erekirMember.memberId()));
            assertTrue(serpuloAdvanced.actionTick > serpuloTick, "Serpulo world stayed auto-paused with a real player online");
            assertTrue(erekirAdvanced.actionTick > erekirTick, "Erekir world stayed auto-paused with a real player online");

            // Disconnect one real client. Its sibling Action must remain live and keep advancing with its own player.
            serpuloClient.close();
            serpuloClient = null;
            SharedCampaignState oneLeft = awaitPlayerCount(owner, serpulo.actionId(), 0, erekir.actionId(), 1, Duration.ofSeconds(30));
            long erekirBeforeSolo = requireAction(oneLeft, erekir.actionId()).actionTick;
            SharedCampaignState soloAdvanced = awaitSingleTick(owner, erekir.actionId(), erekirBeforeSolo + 30L, Duration.ofSeconds(20));
            assertEquals(ActionStatus.running, requireAction(soloAdvanced, serpulo.actionId()).status,
                "disconnecting Serpulo's player stopped the Action instead of merely auto-pausing it");
            assertEquals(1, requireAction(soloAdvanced, erekir.actionId()).connectedPlayers,
                "Erekir player disappeared when the sibling client disconnected");

            erekirClient.close();
            erekirClient = null;
            awaitPlayerCount(owner, serpulo.actionId(), 0, erekir.actionId(), 0, Duration.ofSeconds(30));

            owner.suspendAction(serpulo.actionId());
            awaitStatus(owner, serpulo.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(erekir.actionId());
            awaitStatus(owner, erekir.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(serpuloClient != null) try{ serpuloClient.close(); }catch(Throwable ignored){}
            if(erekirClient != null) try{ erekirClient.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }


    @Test
    @Timeout(value = 360, unit = TimeUnit.SECONDS)
    void threeRealPlayersKeepPeerWorldsAdvancingAcrossOneChildCrash() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-three-real-players-crash-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RealArcClient frozenClient = null, groundClient = null, onsetClient = null;
        RuntimePayloads.StartResult frozen = null, ground = null, onset = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Three real players child-crash gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 3;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            // Frozen Forest requires Ground Zero completion plus junction/router. Seed only the strategic prerequisite;
            // every live world below is still a real JVM_PROCESS child started by the production action path.
            service.authority().coordinator().store().transact("owner", "test:seed-three-player-frozen-progress", state -> {
                SectorState strategic = new SectorState();
                strategic.planetName = Planets.serpulo.name;
                strategic.sectorName = "groundZero";
                strategic.hasBase = true;
                strategic.captured = true;
                strategic.summary = new SectorSummary();
                strategic.summary.planetName = Planets.serpulo.name;
                strategic.summary.coreType = "core-foundation";
                for(var item : Vars.content.items()) strategic.items.put(item.name, 100_000);
                state.sectors.put(SharedCampaignSectors.sectorKey(Planets.serpulo.name, "groundZero"), strategic);
                state.researched.add("junction");
                state.researched.add("router");
            });

            frozen = owner.startAction(Planets.serpulo.name, "frozenForest", "");
            assertBlank(frozen.error(), "Frozen Forest start failed");
            awaitStatus(owner, frozen.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(ground.error(), "Ground Zero start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            // Give the Action a durable recovery point, then bring it back as incarnation 2 before players arrive.
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            RuntimePayloads.StartResult seededGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(seededGround.error(), "Ground Zero seeded resume failed");
            assertEquals(ground.actionId(), seededGround.actionId());
            SharedCampaignState seeded = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(seeded, ground.actionId()).runtimeIncarnation);

            onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential frozenMember = coordinator.clientControl().enrollTrustedLocal("Frozen player");
            CoordinatorCredentials.MemberCredential groundMember = coordinator.clientControl().enrollTrustedLocal("Ground crash player");
            CoordinatorCredentials.MemberCredential onsetMember = coordinator.clientControl().enrollTrustedLocal("Onset player");
            String frozenUuid = platformUuid(141), groundUuid = platformUuid(142), onsetUuid = platformUuid(143);

            RuntimePayloads.VanillaTransferResult frozenGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                frozenMember.memberId(), frozen.actionId(), frozenUuid, "127.0.0.1", false);
            RuntimePayloads.VanillaTransferResult groundGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                groundMember.memberId(), ground.actionId(), groundUuid, "127.0.0.1", false);
            RuntimePayloads.VanillaTransferResult onsetGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                onsetMember.memberId(), onset.actionId(), onsetUuid, "127.0.0.1", false);
            assertBlank(frozenGrant.error(), "Frozen Forest admission failed");
            assertBlank(groundGrant.error(), "Ground Zero admission failed");
            assertBlank(onsetGrant.error(), "Onset admission failed");

            frozenClient = RealArcClient.connectVanilla("Frozen player", frozenUuid, frozenGrant.host(), frozenGrant.port());
            groundClient = RealArcClient.connectVanilla("Ground crash player", groundUuid, groundGrant.host(), groundGrant.port());
            onsetClient = RealArcClient.connectVanilla("Onset player", onsetUuid, onsetGrant.host(), onsetGrant.port());

            SharedCampaignState allPresent = awaitThreePlayerCounts(owner,
                frozen.actionId(), 1, ground.actionId(), 1, onset.actionId(), 1, Duration.ofSeconds(30));
            long frozenTick = requireAction(allPresent, frozen.actionId()).actionTick;
            long groundTick = requireAction(allPresent, ground.actionId()).actionTick;
            long onsetTick = requireAction(allPresent, onset.actionId()).actionTick;
            SharedCampaignState allAdvanced = awaitThreeTicks(owner,
                frozen.actionId(), frozenTick + 30L,
                ground.actionId(), groundTick + 30L,
                onset.actionId(), onsetTick + 30L, Duration.ofSeconds(25));
            assertTrue(requireAction(allAdvanced, frozen.actionId()).participants.contains(frozenMember.memberId()));
            assertTrue(requireAction(allAdvanced, ground.actionId()).participants.contains(groundMember.memberId()));
            assertTrue(requireAction(allAdvanced, onset.actionId()).participants.contains(onsetMember.memberId()));

            SectorRuntime frozenRuntime = coordinator.actionRuntimes().runtime(frozen.actionId());
            SectorRuntime groundRuntime = coordinator.actionRuntimes().runtime(ground.actionId());
            SectorRuntime onsetRuntime = coordinator.actionRuntimes().runtime(onset.actionId());
            assertNotNull(frozenRuntime); assertNotNull(groundRuntime); assertNotNull(onsetRuntime);
            long frozenIncarnation = requireAction(allAdvanced, frozen.actionId()).runtimeIncarnation;
            long onsetIncarnation = requireAction(allAdvanced, onset.actionId()).runtimeIncarnation;

            groundRuntime.crashForTesting();
            SharedCampaignState crashed = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(crashed, ground.actionId()).runtimeIncarnation,
                "crashed Ground Zero unexpectedly restarted before explicit resume");

            // The two peer players remain admitted to the exact same runtime incarnations and both worlds keep ticking.
            SharedCampaignState peersPresent = awaitThreePlayerCounts(owner,
                frozen.actionId(), 1, ground.actionId(), 0, onset.actionId(), 1, Duration.ofSeconds(30));
            assertSame(frozenRuntime, coordinator.actionRuntimes().runtime(frozen.actionId()), "Frozen runtime was replaced by sibling crash");
            assertSame(onsetRuntime, coordinator.actionRuntimes().runtime(onset.actionId()), "Onset runtime was replaced by sibling crash");
            assertEquals(frozenIncarnation, requireAction(peersPresent, frozen.actionId()).runtimeIncarnation);
            assertEquals(onsetIncarnation, requireAction(peersPresent, onset.actionId()).runtimeIncarnation);
            long frozenAfterCrash = requireAction(peersPresent, frozen.actionId()).actionTick;
            long onsetAfterCrash = requireAction(peersPresent, onset.actionId()).actionTick;
            awaitTwoTicks(owner, frozen.actionId(), frozenAfterCrash + 30L, onset.actionId(), onsetAfterCrash + 30L, Duration.ofSeconds(25));

            groundClient.close();
            groundClient = null;
            RuntimePayloads.StartResult recoveredGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(recoveredGround.error(), "Ground Zero recovery resume failed");
            assertEquals(ground.actionId(), recoveredGround.actionId());
            SharedCampaignState recovered = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(3L, requireAction(recovered, ground.actionId()).runtimeIncarnation);

            RuntimePayloads.VanillaTransferResult rejoin = coordinator.actionCommands().prepareVanillaBootstrap(
                groundMember.memberId(), ground.actionId(), groundUuid, "127.0.0.1", false);
            assertBlank(rejoin.error(), "Ground Zero recovery admission failed");
            groundClient = RealArcClient.connectVanilla("Ground crash player", groundUuid, rejoin.host(), rejoin.port());
            SharedCampaignState rejoined = awaitThreePlayerCounts(owner,
                frozen.actionId(), 1, ground.actionId(), 1, onset.actionId(), 1, Duration.ofSeconds(30));
            long recoveredGroundTick = requireAction(rejoined, ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), recoveredGroundTick + 30L, Duration.ofSeconds(20));

            frozenClient.close(); frozenClient = null;
            groundClient.close(); groundClient = null;
            onsetClient.close(); onsetClient = null;
            awaitThreePlayerCounts(owner, frozen.actionId(), 0, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));

            owner.suspendAction(frozen.actionId());
            awaitStatus(owner, frozen.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(frozenClient != null) try{ frozenClient.close(); }catch(Throwable ignored){}
            if(groundClient != null) try{ groundClient.close(); }catch(Throwable ignored){}
            if(onsetClient != null) try{ onsetClient.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }

    /** A real ArcNet transport client that sends the same vanilla join packets but intentionally does not render. */
    static final class RealArcClient implements AutoCloseable{
        private final GameContext context;
        private final Client client;
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Redirect> redirect = new AtomicReference<>();

        private RealArcClient(String name, String platformUuid) throws Exception{
            context = new GameContext("network-player-" + name.replace(' ', '-'));
            RuntimeContexts.run(context, () -> context.net = new Net(null));
            client = new Client(16384, ArcNetProvider.clientReadBufferSize, new PacketSerializer(context));
            client.addListener(new NetListener(){
                @Override public void connected(Connection connection){
                    try{
                        ConnectPacket packet = new ConnectPacket();
                        packet.version = Version.build;
                        packet.versionType = Version.type;
                        packet.mods = new Seq<>();
                        packet.name = name;
                        packet.locale = "en";
                        packet.uuid = platformUuid;
                        packet.usid = "shared-campaign-test-" + name.replace(' ', '-');
                        packet.mobile = false;
                        packet.color = 0xffffffff;
                        connection.sendTCP(packet);
                        // TCP ordering guarantees the server has created con.player from ConnectPacket first.
                        connection.sendTCP(new ConnectConfirmCallPacket());
                    }catch(Throwable error){
                        failure.compareAndSet(null, error);
                    }
                }

                @Override public void disconnected(Connection connection, DcReason reason){
                    // Explicit close during cleanup is expected. Protocol failures are caught by presence/tick assertions.
                }

                @Override public void received(Connection connection, Object object){
                    // The normal client handles this RPC by disconnecting and reconnecting to the supplied endpoint.
                    // Tests capture it so they can reproduce that transport transition without installing a graphical UI.
                    if(object instanceof ConnectCallPacket packet){
                        packet.handled();
                        redirect.compareAndSet(null, new Redirect(packet.ip, packet.port));
                    }
                    // Keep the real ArcNet stream flowing. World chunks/other RPCs are deliberately not installed/rendered;
                    // these gates validate server-side real-player presence and simulation, not graphical world loading.
                }
            });
            thread = new Thread(() -> {
                try{ client.run(); }catch(Throwable error){ if(client.isConnected()) failure.compareAndSet(null, error); }
            }, "real-arc-client-" + name.replace(' ', '-'));
            thread.setDaemon(true);
            thread.start();
        }

        static RealArcClient connect(String name, String platformUuid, String host, int port) throws Exception{
            return connect(name, platformUuid, host, port, false);
        }

        /** Uses the stock ArcNet TCP+UDP handshake performed by an unmodified Mindustry Desktop client. */
        static RealArcClient connectVanilla(String name, String platformUuid, String host, int port) throws Exception{
            return connect(name, platformUuid, host, port, true);
        }

        private static RealArcClient connect(String name, String platformUuid, String host, int port, boolean udp) throws Exception{
            RealArcClient result = new RealArcClient(name, platformUuid);
            try{
                if(udp) result.client.connect(5_000, host, port, port);
                else result.client.connect(5_000, host, port);
                waitUntil(() -> result.client.isConnected() || result.failure.get() != null, Duration.ofSeconds(8));
                if(result.failure.get() != null) throw new AssertionError("ArcNet client failed", result.failure.get());
                assertTrue(result.client.isConnected(), "ArcNet client did not connect to " + host + ":" + port);
                return result;
            }catch(Throwable failure){
                result.close();
                throw failure;
            }
        }

        void sendCommand(String command){
            if(!client.isConnected()) throw new IllegalStateException("ArcNet client is not connected");
            redirect.set(null);
            SendChatMessageCallPacket packet = new SendChatMessageCallPacket();
            packet.message = command;
            client.sendTCP(packet);
        }

        Redirect awaitRedirect(Duration timeout) throws Exception{
            waitUntil(() -> redirect.get() != null || failure.get() != null, timeout);
            if(failure.get() != null) throw new AssertionError("ArcNet client failed while waiting for sector redirect", failure.get());
            Redirect result = redirect.get();
            assertNotNull(result, "server did not send the vanilla connect RPC");
            return result;
        }

        void awaitDisconnected(Duration timeout) throws Exception{
            waitUntil(() -> !client.isConnected() || failure.get() != null, timeout);
            if(failure.get() != null) throw new AssertionError("ArcNet client failed while waiting for disconnect", failure.get());
            assertFalse(client.isConnected(), "ArcNet client remained connected after server-side admission rejection");
        }

        boolean connected(){ return client.isConnected() && failure.get() == null; }

        record Redirect(String host, int port){}

        @Override public void close(){
            try{ client.close(); }catch(Throwable ignored){}
            try{ client.stop(); }catch(Throwable ignored){}
            try{ client.dispose(); }catch(Throwable ignored){}
            try{ thread.join(1_000L); }catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); }
            context.dispose();
        }
    }


    private static SharedCampaignState awaitThreePlayerCounts(SharedCampaignClient client,
                                                               String actionA, int playersA,
                                                               String actionB, int playersB,
                                                               String actionC, int playersC,
                                                               Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB), c = state.actions.get(actionC);
                if(a != null && b != null && c != null
                    && a.connectedPlayers == playersA && b.connectedPlayers == playersB && c.connectedPlayers == playersC){
                    found[0] = state;
                    return true;
                }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitThreeTicks(SharedCampaignClient client,
                                                        String actionA, long tickA,
                                                        String actionB, long tickB,
                                                        String actionC, long tickC,
                                                        Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB), c = state.actions.get(actionC);
                if(a != null && b != null && c != null && a.actionTick >= tickA && b.actionTick >= tickB && c.actionTick >= tickC){
                    found[0] = state;
                    return true;
                }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitTwoTicks(SharedCampaignClient client,
                                                      String actionA, long tickA,
                                                      String actionB, long tickB,
                                                      Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
                if(a != null && b != null && a.actionTick >= tickA && b.actionTick >= tickB){
                    found[0] = state;
                    return true;
                }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitPresence(SharedCampaignClient client, String actionA, String memberA,
                                                       String actionB, String memberB, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
                if(a != null && b != null && a.connectedPlayers == 1 && b.connectedPlayers == 1
                    && a.participants.contains(memberA) && b.participants.contains(memberB)){
                    found[0] = state;
                    return true;
                }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitTicks(SharedCampaignClient client, String actionA, long tickA,
                                                   String actionB, long tickB, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
                if(a != null && b != null && a.actionTick >= tickA && b.actionTick >= tickB){ found[0] = state; return true; }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitSingleTick(SharedCampaignClient client, String actionId, long tick, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState action = state.actions.get(actionId);
                if(action != null && action.actionTick >= tick){ found[0] = state; return true; }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitPlayerCount(SharedCampaignClient client, String actionA, int playersA,
                                                         String actionB, int playersB, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
                if(a != null && b != null && a.connectedPlayers == playersA && b.connectedPlayers == playersB){ found[0] = state; return true; }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) fail("Action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(50L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("Action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
        return last;
    }

    private static ActionState requireAction(SharedCampaignState state, String actionId){
        ActionState action = state.actions.get(actionId);
        assertNotNull(action, "Action missing from authority snapshot: " + actionId);
        return action;
    }

    private static String platformUuid(int marker){
        byte[] seed = new byte[8];
        seed[7] = (byte)marker;
        return new String(Base64Coder.encode(seed));
    }

    private static void assertBlank(String value, String message){
        assertTrue(value == null || value.isBlank(), () -> message + ": " + value);
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(condition.getAsBoolean()) return;
            Thread.sleep(50L);
        }
        fail("condition not met within " + timeout);
    }

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths = Files.walk(root)){
            for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
