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

/**
 * Product-network fault gate for the pure-vanilla /sector handoff window.
 *
 * <p>The destination crashes after it has installed a one-shot admission and after the source has received the
 * vanilla connect RPC, but before the client follows that redirect. Recovery must not resurrect that in-memory grant,
 * create a duplicate player, or strand the durable member: the stale redirect is rejected and a fresh /sector command
 * succeeds after the destination resumes.</p>
 */
@Tag("shared-campaign-jvm-multi-action")
public class SharedCampaignJvmVanillaTransferCrashTests{
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
    void targetCrashInvalidatesPreparedVanillaGrantAndFreshTransferStillWorks() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-vanilla-transfer-crash-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        SharedCampaignJvmConcurrentPlayersTests.RealArcClient sourcePlayer = null, staleAttempt = null, transferredPlayer = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM vanilla transfer crash gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(ground.error(), "Ground Zero start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            RuntimePayloads.StartResult onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            // Give the destination a durable save so the production monitor has a legitimate post-crash recovery path.
            owner.suspendAction(onset.actionId());
            SharedCampaignState seeded = awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            assertFalse(requireAction(seeded, onset.actionId()).lastSaveHash.isBlank(), "Onset did not produce a recovery save");
            RuntimePayloads.StartResult resumedOnset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(resumedOnset.error(), "Onset seeded resume failed");
            SharedCampaignState resumed = awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            ActionState resumedOnsetState = requireAction(resumed, onset.actionId());
            assertEquals(2L, resumedOnsetState.runtimeIncarnation);

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential member = coordinator.clientControl().enrollTrustedLocal("Crash traveler");
            String networkUuid = platformUuid(111);
            RuntimePayloads.VanillaTransferResult bootstrap = coordinator.actionCommands().prepareVanillaBootstrap(
                member.memberId(), ground.actionId(), networkUuid, "127.0.0.1", false);
            assertBlank(bootstrap.error(), "Ground bootstrap failed");
            sourcePlayer = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Crash traveler", networkUuid, bootstrap.host(), bootstrap.port());
            awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));

            // The redirect proves that authority completed destination snapshot synchronization and admission prepare.
            sourcePlayer.sendCommand("/sector onset");
            var staleRedirect = sourcePlayer.awaitRedirect(Duration.ofSeconds(30));
            assertEquals(resumedOnsetState.port, staleRedirect.port(), "initial /sector redirected to the wrong live Action port");

            SectorRuntime doomed = requireJvmRuntime(service, onset.actionId());
            doomed.crashForTesting();
            awaitRuntimeDead(doomed, Duration.ofSeconds(15));
            SharedCampaignState recovered = awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
            assertEquals(1, requireAction(recovered, ground.actionId()).connectedPlayers,
                "source player disappeared before following the failed redirect");
            assertEquals(0, requireAction(recovered, onset.actionId()).connectedPlayers,
                "destination crash created a ghost player");

            RuntimePayloads.StartResult restartedOnset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(restartedOnset.error(), "Onset post-crash resume failed");
            SharedCampaignState restarted = awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            ActionState restartedOnsetState = requireAction(restarted, onset.actionId());
            assertEquals(3L, restartedOnsetState.runtimeIncarnation,
                "Onset did not advance incarnation after the crash recovery");

            // A one-shot grant lives only in the crashed child. Reusing its old endpoint/UUID against the new
            // incarnation must be rejected before Player creation, even though the endpoint itself is live again.
            if(staleRedirect.port() != restartedOnsetState.port){
                // Current production recovery allocates a fresh live port. In that case the old redirect must simply
                // be dead; it must never alias another Action or the recovered incarnation.
                assertThrows(IOException.class, () -> SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                    "Stale crash traveler", networkUuid, staleRedirect.host(), staleRedirect.port()),
                    "stale redirect unexpectedly connected after target recovery moved to a new port");
            }else{
                // Keep this branch so the gate remains valid if port reuse is introduced later. The restarted child
                // must not inherit the crashed process' in-memory one-shot grant.
                staleAttempt = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                    "Stale crash traveler", networkUuid, staleRedirect.host(), staleRedirect.port());
                staleAttempt.awaitDisconnected(Duration.ofSeconds(10));
                staleAttempt.close();
                staleAttempt = null;
            }
            assertCountsRemain(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(2));

            // The durable member is not stranded. Ask the still-authenticated source Action for a new one-shot grant,
            // then perform the same vanilla reconnect and ensure presence moves exactly once.
            sourcePlayer.sendCommand("/sector onset");
            var freshRedirect = sourcePlayer.awaitRedirect(Duration.ofSeconds(30));
            assertEquals(restartedOnsetState.port, freshRedirect.port(), "fresh /sector redirected to the wrong recovered Action");
            sourcePlayer.close();
            sourcePlayer = null;
            transferredPlayer = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Crash traveler recovered", networkUuid, freshRedirect.host(), freshRedirect.port());
            SharedCampaignState moved = awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 1, Duration.ofSeconds(30));
            assertEquals(1, totalPlayers(moved, ground.actionId(), onset.actionId()), "post-crash transfer duplicated player presence");
            assertTrue(requireAction(moved, onset.actionId()).participants.contains(member.memberId()),
                "recovered destination did not retain the durable transferred member");
            long onsetTick = requireAction(moved, onset.actionId()).actionTick;
            awaitSingleTick(owner, onset.actionId(), onsetTick + 30L, Duration.ofSeconds(20));

            transferredPlayer.close();
            transferredPlayer = null;
            awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(sourcePlayer != null) try{ sourcePlayer.close(); }catch(Throwable ignored){}
            if(staleAttempt != null) try{ staleAttempt.close(); }catch(Throwable ignored){}
            if(transferredPlayer != null) try{ transferredPlayer.close(); }catch(Throwable ignored){}
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

    private static void assertCountsRemain(SharedCampaignClient client, String actionA, int playersA,
                                            String actionB, int playersB, Duration duration) throws Exception{
        long deadline = System.nanoTime() + duration.toNanos();
        while(System.nanoTime() < deadline){
            SharedCampaignState state = client.snapshot();
            assertEquals(playersA, requireAction(state, actionA).connectedPlayers, "source presence changed during stale-grant rejection");
            assertEquals(playersB, requireAction(state, actionB).connectedPlayers, "stale grant created destination presence");
            Thread.sleep(50L);
        }
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
