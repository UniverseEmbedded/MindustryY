import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.campaign.shared.io.*;
import mindustry.content.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Product-level JVM_PROCESS gate for more than one simultaneously live Action.
 *
 * <p>This turns the CP041 Desktop two-live-Action journey into a repeatable build gate: two real child JVMs are
 * kept alive together, one Action is independently suspended/resumed, and both children must publish authoritative
 * saves and release their game ports without disturbing the other Action's runtime incarnation.</p>
 */
@Tag("shared-campaign-jvm-multi-action")
public class SharedCampaignJvmMultiActionProductTests{
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
    void twoJvmActionsRemainIndependentAcrossSingleActionSuspendResume() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-multi-action-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RuntimePayloads.StartResult serpulo = null, erekir = null, resumedSerpulo = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM multi-Action product gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            serpulo = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(serpulo.error(), "Serpulo start failed");
            awaitStatus(owner, serpulo.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertListening(serpulo.host(), serpulo.port(), Duration.ofSeconds(15));

            // Match the proven CP041 product journey: once the first real child is established, add a second live Action.
            // The gate is about simultaneous steady-state children and independent lifecycle, not racing two launch handshakes.
            erekir = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(erekir.error(), "Erekir start failed");
            assertNotEquals(serpulo.actionId(), erekir.actionId(), "two sectors must have distinct logical Actions");
            assertNotEquals(serpulo.port(), erekir.port(), "simultaneous child JVMs must not share a game port");

            SharedCampaignState bothRunning = awaitStatus(owner, erekir.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            ActionState serpuloBeforeSuspend = requireAction(bothRunning, serpulo.actionId());
            ActionState erekirBeforeSuspend = requireAction(bothRunning, erekir.actionId());
            long serpuloIncarnation = serpuloBeforeSuspend.runtimeIncarnation;
            long erekirIncarnation = erekirBeforeSuspend.runtimeIncarnation;
            assertEquals(1L, serpuloIncarnation);
            assertEquals(1L, erekirIncarnation);
            assertJvmRuntimeAlive(service, serpulo.actionId());
            assertJvmRuntimeAlive(service, erekir.actionId());
            assertListening(serpulo.host(), serpulo.port(), Duration.ofSeconds(15));
            assertListening(erekir.host(), erekir.port(), Duration.ofSeconds(15));

            owner.suspendAction(serpulo.actionId());
            SharedCampaignState serpuloSuspended = awaitStatus(owner, serpulo.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(serpulo.port(), Duration.ofSeconds(15));
            ActionState erekirStillRunning = requireAction(serpuloSuspended, erekir.actionId());
            assertEquals(ActionStatus.running, erekirStillRunning.status, "suspending Serpulo stopped the independent Erekir Action");
            assertEquals(erekirIncarnation, erekirStillRunning.runtimeIncarnation,
                "suspending another Action changed Erekir runtime incarnation");
            assertJvmRuntimeAlive(service, erekir.actionId());
            assertListening(erekir.host(), erekir.port(), Duration.ofSeconds(15));
            assertDurableSave(directory, requireAction(serpuloSuspended, serpulo.actionId()), serpulo.actionId());

            resumedSerpulo = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(resumedSerpulo.error(), "Serpulo resume failed");
            assertEquals(serpulo.actionId(), resumedSerpulo.actionId(), "resume replaced the logical Serpulo Action");
            SharedCampaignState resumed = awaitStatus(owner, serpulo.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            resumed = awaitStatus(owner, erekir.actionId(), ActionStatus.running, Duration.ofSeconds(15));
            ActionState serpuloAfterResume = requireAction(resumed, serpulo.actionId());
            ActionState erekirAfterResume = requireAction(resumed, erekir.actionId());
            assertEquals(serpuloIncarnation + 1L, serpuloAfterResume.runtimeIncarnation,
                "Serpulo runtime incarnation did not advance on resume");
            assertEquals(erekirIncarnation, erekirAfterResume.runtimeIncarnation,
                "Serpulo resume restarted the independent Erekir child");
            assertJvmRuntimeAlive(service, serpulo.actionId());
            assertJvmRuntimeAlive(service, erekir.actionId());
            assertListening(resumedSerpulo.host(), resumedSerpulo.port(), Duration.ofSeconds(15));
            assertListening(erekir.host(), erekir.port(), Duration.ofSeconds(15));

            owner.suspendAction(serpulo.actionId());
            SharedCampaignState finalSerpulo = awaitStatus(owner, serpulo.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(resumedSerpulo.port(), Duration.ofSeconds(15));
            assertEquals(ActionStatus.running, requireAction(finalSerpulo, erekir.actionId()).status,
                "final Serpulo suspend stopped Erekir");

            owner.suspendAction(erekir.actionId());
            SharedCampaignState allSuspended = awaitStatus(owner, erekir.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(erekir.port(), Duration.ofSeconds(15));
            assertDurableSave(directory, requireAction(allSuspended, serpulo.actionId()), serpulo.actionId());
            assertDurableSave(directory, requireAction(allSuspended, erekir.actionId()), erekir.actionId());
            assertNull(service.authority().coordinator().actionRuntimes().runtime(serpulo.actionId()),
                "Serpulo runtime remained registered after suspend");
            assertNull(service.authority().coordinator().actionRuntimes().runtime(erekir.actionId()),
                "Erekir runtime remained registered after suspend");
        }finally{
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            if(resumedSerpulo != null && resumedSerpulo.port() > 0) try{ awaitPortReleased(resumedSerpulo.port(), Duration.ofSeconds(5)); }catch(Throwable ignored){}
            if(serpulo != null && serpulo.port() > 0) try{ awaitPortReleased(serpulo.port(), Duration.ofSeconds(5)); }catch(Throwable ignored){}
            if(erekir != null && erekir.port() > 0) try{ awaitPortReleased(erekir.port(), Duration.ofSeconds(5)); }catch(Throwable ignored){}
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void maintenanceCheckpointSavesRunningJvmActionAndRecordsHash() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-save-now-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RuntimePayloads.StartResult ground = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM maintenance save gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.persistenceProfile = PersistenceProfile.lowFrequencyWal;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(ground.error(), "Ground Zero start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            int saved = service.authority().coordinator().actionCommands().forceSaveRunningActions("owner");
            assertEquals(1, saved);
            SharedCampaignState checkpointed = owner.snapshot();
            ActionState action = requireAction(checkpointed, ground.actionId());
            assertFalse(action.lastSaveHash.isBlank(), "maintenance checkpoint did not publish a durable world hash");
            Fi save = new Fi(directory.resolve("actions").resolve(ground.actionId()).resolve("config/saves/action.msav").toString());
            assertTrue(save.exists() && save.length() > 0L, "maintenance checkpoint did not write the Action world");
            assertEquals(action.lastSaveHash, SharedFileDigests.sha256(save), "authority save hash does not match Action .msav");

            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            if(ground != null && ground.port() > 0) try{ awaitPortReleased(ground.port(), Duration.ofSeconds(5)); }catch(Throwable ignored){}
            deleteTree(directory);
        }
    }

    private static ActionState requireAction(SharedCampaignState state, String actionId){
        ActionState action = state.actions.get(actionId);
        assertNotNull(action, "Action missing from authority snapshot: " + actionId);
        return action;
    }

    private static void assertJvmRuntimeAlive(SharedCampaignService service, String actionId){
        SectorRuntime runtime = service.authority().coordinator().actionRuntimes().runtime(actionId);
        assertNotNull(runtime, "runtime missing for " + actionId);
        assertEquals(SectorRuntime.Backend.jvmProcess, runtime.backend());
        assertTrue(runtime.isAlive(), "child JVM is not alive for " + actionId);
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

    private static void assertDurableSave(Path directory, ActionState action, String actionId) throws IOException{
        assertFalse(action.lastSaveHash == null || action.lastSaveHash.isBlank(), "Action lacks authoritative save hash: " + actionId);
        Path save = directory.resolve("actions").resolve(actionId).resolve("config/saves/action.msav");
        assertTrue(Files.isRegularFile(save) && Files.size(save) > 0L, "authoritative Action save missing: " + actionId);
    }

    private static void assertListening(String host, int port, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while(System.nanoTime() < deadline){
            try(Socket socket = new Socket()){
                socket.connect(new InetSocketAddress(host, port), 500);
                return;
            }catch(IOException error){ last = error; }
            Thread.sleep(25L);
        }
        fail("game port never became reachable: " + host + ":" + port + ", last=" + last);
    }

    private static void awaitPortReleased(int port, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException lastTcp = null, lastUdp = null;
        while(System.nanoTime() < deadline){
            boolean tcpFree = false, udpFree = false;
            try(ServerSocket tcp = new ServerSocket()){
                tcp.setReuseAddress(true);
                tcp.bind(new InetSocketAddress("127.0.0.1", port));
                tcpFree = true;
            }catch(IOException error){ lastTcp = error; }
            try(DatagramSocket udp = new DatagramSocket(null)){
                udp.setReuseAddress(true);
                udp.bind(new InetSocketAddress("127.0.0.1", port));
                udpFree = true;
            }catch(IOException error){ lastUdp = error; }
            if(tcpFree && udpFree) return;
            Thread.sleep(25L);
        }
        fail("game port was not released: " + port + ", tcp=" + lastTcp + ", udp=" + lastUdp);
    }

    private static void assertBlank(String value, String message){
        assertTrue(value == null || value.isBlank(), () -> message + ": " + value);
    }

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root)) return;
        try(var paths = Files.walk(root)){
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try{ Files.deleteIfExists(path); }catch(IOException error){ throw new UncheckedIOException(error); }
            });
        }catch(UncheckedIOException error){ throw error.getCause(); }
    }
}
