import arc.files.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real-player fault gate: killing one occupied Action must not interrupt a player in a sibling JVM world. */
@Tag("shared-campaign-jvm-multi-action")
public class SharedCampaignJvmPlayerCrashIsolationTests{
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
    void occupiedChildCrashLeavesPeerPlayerOnlineAndTicking() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-player-crash-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        SharedCampaignJvmConcurrentPlayersTests.RealArcClient groundClient = null, onsetClient = null, recoveredGroundClient = null;
        RuntimePayloads.StartResult ground = null, onset = null, seededGround = null, recoveredGround = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM real-player crash isolation";
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

            // Seed a real recovery save before players join, because an occupied Action correctly rejects suspend.
            owner.suspendAction(ground.actionId());
            SharedCampaignState seeded = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            assertFalse(requireAction(seeded, ground.actionId()).lastSaveHash.isBlank());
            seededGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(seededGround.error(), "Ground Zero seeded resume failed");
            SharedCampaignState bothLive = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(bothLive, ground.actionId()).runtimeIncarnation);
            assertEquals(1L, requireAction(bothLive, onset.actionId()).runtimeIncarnation);

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential groundMember = coordinator.clientControl().enrollTrustedLocal("Ground player");
            CoordinatorCredentials.MemberCredential onsetMember = coordinator.clientControl().enrollTrustedLocal("Onset player");
            String groundUuid = platformUuid(101), onsetUuid = platformUuid(102);
            RuntimePayloads.VanillaTransferResult groundGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                groundMember.memberId(), ground.actionId(), groundUuid, "127.0.0.1", false);
            RuntimePayloads.VanillaTransferResult onsetGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                onsetMember.memberId(), onset.actionId(), onsetUuid, "127.0.0.1", false);
            assertBlank(groundGrant.error(), "Ground admission failed");
            assertBlank(onsetGrant.error(), "Onset admission failed");

            groundClient = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla("Ground player", groundUuid, groundGrant.host(), groundGrant.port());
            onsetClient = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla("Onset player", onsetUuid, onsetGrant.host(), onsetGrant.port());
            SharedCampaignState occupied = awaitPlayerCount(owner, ground.actionId(), 1, onset.actionId(), 1, Duration.ofSeconds(30));
            long onsetTick = requireAction(occupied, onset.actionId()).actionTick;
            SectorRuntime doomed = requireJvmRuntime(service, ground.actionId());
            SectorRuntime peer = requireJvmRuntime(service, onset.actionId());

            doomed.crashForTesting();
            awaitRuntimeDead(doomed, Duration.ofSeconds(15));
            SharedCampaignState recovered = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(25));
            ActionState peerAfterCrash = requireAction(recovered, onset.actionId());
            assertEquals(ActionStatus.running, peerAfterCrash.status, "peer Action stopped after occupied sibling crashed");
            assertEquals(1, peerAfterCrash.connectedPlayers, "peer player disappeared after sibling child crash");
            assertEquals(1L, peerAfterCrash.runtimeIncarnation, "peer Action restarted after sibling child crash");
            assertSame(peer, requireJvmRuntime(service, onset.actionId()), "peer runtime object was replaced");

            SharedCampaignState peerAdvanced = awaitSingleTick(owner, onset.actionId(), onsetTick + 30L, Duration.ofSeconds(20));
            assertEquals(1, requireAction(peerAdvanced, onset.actionId()).connectedPlayers,
                "peer player did not stay online while its world continued ticking");

            // The crashed client's transport should be dead. Recreate the failed child and re-admit the same durable member.
            groundClient.close();
            groundClient = null;
            recoveredGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(recoveredGround.error(), "Ground post-crash resume failed");
            SharedCampaignState resumed = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(3L, requireAction(resumed, ground.actionId()).runtimeIncarnation);
            assertEquals(1L, requireAction(resumed, onset.actionId()).runtimeIncarnation);

            RuntimePayloads.VanillaTransferResult recoveryGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                groundMember.memberId(), ground.actionId(), groundUuid, "127.0.0.1", false);
            assertBlank(recoveryGrant.error(), "Ground recovery admission failed");
            recoveredGroundClient = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Ground player recovered", groundUuid, recoveryGrant.host(), recoveryGrant.port());
            SharedCampaignState bothRejoined = awaitPlayerCount(owner, ground.actionId(), 1, onset.actionId(), 1, Duration.ofSeconds(30));
            long groundTick = requireAction(bothRejoined, ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), groundTick + 30L, Duration.ofSeconds(20));

            recoveredGroundClient.close(); recoveredGroundClient = null;
            onsetClient.close(); onsetClient = null;
            awaitPlayerCount(owner, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(groundClient != null) try{ groundClient.close(); }catch(Throwable ignored){}
            if(recoveredGroundClient != null) try{ recoveredGroundClient.close(); }catch(Throwable ignored){}
            if(onsetClient != null) try{ onsetClient.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }

    private static SectorRuntime requireJvmRuntime(SharedCampaignService service, String actionId){
        SectorRuntime runtime = service.authority().coordinator().actionRuntimes().runtime(actionId);
        assertNotNull(runtime, "runtime missing for " + actionId);
        assertEquals(SectorRuntime.Backend.jvmProcess, runtime.backend());
        assertTrue(runtime.isAlive(), "child JVM is not alive for " + actionId);
        return runtime;
    }

    private static void awaitRuntimeDead(SectorRuntime runtime, Duration timeout) throws Exception{
        waitUntil(() -> !runtime.isAlive(), timeout);
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
        byte[] seed = new byte[8]; seed[7] = (byte)marker;
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
