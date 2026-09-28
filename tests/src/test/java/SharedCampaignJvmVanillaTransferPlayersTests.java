import arc.files.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Product-network gate for the pure-vanilla /sector journey between two already-live JVM_PROCESS Actions.
 *
 * <p>The player first enters Ground Zero through the real one-shot vanilla admission path, sends the actual
 * {@code /sector onset} chat command to the child NetServer, receives the generated {@code connect(ip, port)} RPC,
 * and follows that redirect with the same durable member/network UUID. The same journey is then repeated back to
 * Ground Zero. No coordinator endpoint is handed directly to the client by the test after bootstrap.</p>
 */
@Tag("shared-campaign-jvm-multi-action")
public class SharedCampaignJvmVanillaTransferPlayersTests{
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
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void vanillaPlayerMovesGroundZeroToOnsetAndBackThroughRealSectorCommand() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-vanilla-transfer-player-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        SharedCampaignJvmConcurrentPlayersTests.RealArcClient player = null;
        RuntimePayloads.StartResult ground = null, onset = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM vanilla real-player transfer gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(ground.error(), "Ground Zero start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential member = coordinator.clientControl().enrollTrustedLocal("Traveler");
            String networkUuid = platformUuid(101);
            RuntimePayloads.VanillaTransferResult bootstrap = coordinator.actionCommands().prepareVanillaBootstrap(
                member.memberId(), ground.actionId(), networkUuid, "127.0.0.1", false);
            assertBlank(bootstrap.error(), "initial Ground Zero admission failed");

            player = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Traveler", networkUuid, bootstrap.host(), bootstrap.port());
            SharedCampaignState onGround = awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));
            assertEquals(ground.port(), requireAction(onGround, ground.actionId()).port);
            long groundBefore = requireAction(onGround, ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), groundBefore + 30L, Duration.ofSeconds(20));

            // Exercise the real child-side /sector command. The only target endpoint the test accepts is the RPC sent
            // back through the player's existing game connection, exactly as an unmodified vanilla client would see it.
            player.sendCommand("/sector onset");
            var toOnset = player.awaitRedirect(Duration.ofSeconds(30));
            assertEquals(onset.port(), toOnset.port(), "/sector onset redirected to the wrong Action port");
            player.close();
            player = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Traveler", networkUuid, toOnset.host(), toOnset.port());

            SharedCampaignState onOnset = awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 1, Duration.ofSeconds(30));
            ActionState onsetLive = requireAction(onOnset, onset.actionId());
            assertTrue(onsetLive.participants.contains(member.memberId()), "destination Action did not record the transferred member");
            assertEquals(1, totalPlayers(onOnset, ground.actionId(), onset.actionId()), "vanilla transfer duplicated player presence");
            long onsetBefore = onsetLive.actionTick;
            awaitSingleTick(owner, onset.actionId(), onsetBefore + 30L, Duration.ofSeconds(20));

            player.sendCommand("/sector groundZero");
            var toGround = player.awaitRedirect(Duration.ofSeconds(30));
            assertEquals(ground.port(), toGround.port(), "/sector groundZero redirected to the wrong Action port");
            player.close();
            player = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Traveler", networkUuid, toGround.host(), toGround.port());

            SharedCampaignState returned = awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));
            assertEquals(1, totalPlayers(returned, ground.actionId(), onset.actionId()), "return transfer duplicated player presence");
            long returnedTick = requireAction(returned, ground.actionId()).actionTick;
            SharedCampaignState advanced = awaitSingleTick(owner, ground.actionId(), returnedTick + 30L, Duration.ofSeconds(20));
            assertEquals(ActionStatus.running, requireAction(advanced, onset.actionId()).status,
                "moving the player away stopped the target Action instead of allowing it to auto-pause");

            player.close();
            player = null;
            awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));

            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(player != null) try{ player.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }

    private static int totalPlayers(SharedCampaignState state, String a, String b){
        return requireAction(state, a).connectedPlayers + requireAction(state, b).connectedPlayers;
    }

    private static SharedCampaignState awaitCounts(SharedCampaignClient client, String actionA, int playersA,
                                                     String actionB, int playersB, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
                if(a != null && b != null && a.connectedPlayers == playersA && b.connectedPlayers == playersB){
                    found[0] = state;
                    return true;
                }
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
