import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
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
 * Product backend acceptance: the embedded and child-JVM hosts must obey the same authoritative Action lifecycle.
 * This deliberately exercises the real SharedSectorLauncher process rather than replacing JVM_PROCESS with a fake.
 */
@Tag("shared-campaign-backend-differential")
public class SharedCampaignBackendDifferentialTests{
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
    void inProcessAndJvmProcessMatchSuspendResumeAndCrashRecovery() throws Exception{
        BackendResult embedded;
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            embedded = exerciseBackend("in-process", SectorRuntimeFactory.inProcess(scheduler), SectorRuntime.Backend.inProcess);
            assertEquals(0, scheduler.size(), "embedded runtime leaked after authority shutdown");
        }
        BackendResult child = exerciseBackend("jvm-process", SectorRuntimeFactory.jvmProcess(), SectorRuntime.Backend.jvmProcess);

        assertEquals(embedded.planet, child.planet);
        assertEquals(embedded.sector, child.sector);
        assertEquals(embedded.kind, child.kind);
        assertEquals(embedded.statusAfterCleanSuspend, child.statusAfterCleanSuspend);
        assertEquals(embedded.statusAfterCrashRecovery, child.statusAfterCrashRecovery);
        assertEquals(embedded.runtimeIncarnation, child.runtimeIncarnation);
        assertEquals(embedded.launchCommitted, child.launchCommitted);
        assertEquals(embedded.hasDurableSave, child.hasDurableSave);
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void jvmProcessRepeatedForcedDeathIsRecoveredByScheduledMonitorWithoutOrphans() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-chaos-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        int livePort = -1;
        SectorRuntime lastRuntime = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            service.createLocal(new Fi(directory.toString()), options("jvm-chaos"), "127.0.0.1", 0, 0);
            owner = service.controlClient();

            RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
            assertNoStartError(started, "jvm chaos initial start");
            awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertListening(started.host(), started.port(), Duration.ofSeconds(15));

            // Seed an authenticated durable autosave so every subsequent abrupt death has a recovery point.
            owner.suspendAction(started.actionId());
            awaitStatus(owner, started.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(started.port(), Duration.ofSeconds(15));

            for(int cycle = 1; cycle <= 3; cycle++){
                RuntimePayloads.StartResult resumed = owner.startAction("serpulo", "groundZero", "");
                assertNoStartError(resumed, "jvm chaos resume " + cycle);
                assertEquals(started.actionId(), resumed.actionId(), "jvm chaos changed logical Action identity");
                SharedCampaignState live = awaitStatus(owner, resumed.actionId(), ActionStatus.running, Duration.ofSeconds(45));
                ActionState liveAction = live.actions.get(resumed.actionId());
                assertNotNull(liveAction);
                assertEquals(cycle + 1L, liveAction.runtimeIncarnation, "runtime incarnation did not advance on cycle " + cycle);
                assertListening(resumed.host(), resumed.port(), Duration.ofSeconds(15));

                SectorRuntime runtime = requireRuntime(service, resumed.actionId());
                assertEquals(SectorRuntime.Backend.jvmProcess, runtime.backend());
                runtime.crashForTesting();
                awaitRuntimeDead(runtime, Duration.ofSeconds(15));

                // Do not call monitorOnce(): this is explicitly validating the production scheduled monitor.
                SharedCampaignState recovered = awaitStatus(owner, resumed.actionId(), ActionStatus.suspended, Duration.ofSeconds(15));
                ActionState recoveredAction = recovered.actions.get(resumed.actionId());
                assertNotNull(recoveredAction);
                assertFalse(recoveredAction.lastSaveHash == null || recoveredAction.lastSaveHash.isBlank(),
                    "scheduled recovery lost the authenticated autosave on cycle " + cycle);
                awaitPortReleased(resumed.port(), Duration.ofSeconds(15));
            }

            RuntimePayloads.StartResult finalResume = owner.startAction("serpulo", "groundZero", "");
            assertNoStartError(finalResume, "jvm chaos final live resume");
            awaitStatus(owner, finalResume.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertListening(finalResume.host(), finalResume.port(), Duration.ofSeconds(15));
            livePort = finalResume.port();
            lastRuntime = requireRuntime(service, finalResume.actionId());
            assertTrue(lastRuntime.isAlive(), "final child JVM was not alive before authority shutdown");

            // Product shutdown must terminate a still-live child and release its endpoint, not merely abandon it.
            service.close();
            awaitRuntimeDead(lastRuntime, Duration.ofSeconds(20));
            awaitPortReleased(livePort, Duration.ofSeconds(15));
        }finally{
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            if(lastRuntime != null && lastRuntime.isAlive()) try{ lastRuntime.close(); }catch(Throwable ignored){}
            if(livePort > 0) try{ awaitPortReleased(livePort, Duration.ofSeconds(5)); }catch(Throwable ignored){}
            deleteTree(directory);
        }
    }

    private static BackendResult exerciseBackend(String label, SectorRuntimeFactory factory, SectorRuntime.Backend expectedBackend) throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-backend-" + label + "-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        try{
            service.runtimeFactory(factory);
            service.createLocal(new Fi(directory.toString()), options(label), "127.0.0.1", 0, 0);
            owner = service.controlClient();

            RuntimePayloads.StartResult first = owner.startAction("serpulo", "groundZero", "");
            assertNoStartError(first, label + " initial start");
            SharedCampaignState running = awaitStatus(owner, first.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertListening(first.host(), first.port(), Duration.ofSeconds(15));
            SectorRuntime firstRuntime = requireRuntime(service, first.actionId());
            assertEquals(expectedBackend, firstRuntime.backend());

            owner.suspendAction(first.actionId());
            SharedCampaignState suspended = awaitStatus(owner, first.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(first.port(), Duration.ofSeconds(15));
            Path save = directory.resolve("actions").resolve(first.actionId()).resolve("config/saves/action.msav");
            assertTrue(Files.isRegularFile(save) && Files.size(save) > 0L, label + " clean suspend did not publish a durable save");

            RuntimePayloads.StartResult resumed = owner.startAction("serpulo", "groundZero", "");
            assertNoStartError(resumed, label + " resume");
            assertEquals(first.actionId(), resumed.actionId(), label + " resume changed logical Action identity");
            SharedCampaignState resumedState = awaitStatus(owner, resumed.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertListening(resumed.host(), resumed.port(), Duration.ofSeconds(15));
            ActionState live = resumedState.actions.get(resumed.actionId());
            assertNotNull(live);
            assertEquals(2L, live.runtimeIncarnation, label + " resume did not advance runtime incarnation");

            SectorRuntime runtime = requireRuntime(service, resumed.actionId());
            assertEquals(expectedBackend, runtime.backend());
            runtime.crashForTesting();
            awaitRuntimeDead(runtime, Duration.ofSeconds(15));
            service.authority().coordinator().actionRuntimes().monitorOnce();
            SharedCampaignState recovered = awaitStatus(owner, resumed.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
            ActionState recoveredAction = recovered.actions.get(resumed.actionId());
            assertNotNull(recoveredAction);
            assertFalse(recoveredAction.lastSaveHash == null || recoveredAction.lastSaveHash.isBlank(), label + " crash recovery did not authenticate a save");
            assertTrue(Files.isRegularFile(save) && Files.size(save) > 0L, label + " crash recovery lost its durable save");
            awaitPortReleased(resumed.port(), Duration.ofSeconds(15));

            ActionState cleanSuspended = suspended.actions.get(first.actionId());
            return new BackendResult(recoveredAction.planetName, recoveredAction.sectorName, recoveredAction.kind,
                cleanSuspended.status, recoveredAction.status, recoveredAction.runtimeIncarnation,
                recoveredAction.launchCommitted, Files.isRegularFile(save) && Files.size(save) > 0L);
        }finally{
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }

    private static SectorRuntime requireRuntime(SharedCampaignService service, String actionId){
        SectorRuntime runtime = service.authority().coordinator().actionRuntimes().runtime(actionId);
        assertNotNull(runtime, "runtime missing for " + actionId);
        return runtime;
    }

    private static SharedCampaignCreationOptions options(String label){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = "Backend differential " + label;
        options.ownerId = "owner";
        options.ownerDisplayName = "Owner";
        options.primaryPlanetName = "serpulo";
        return options;
    }

    private static void assertNoStartError(RuntimePayloads.StartResult result, String phase){
        assertTrue(result.error() == null || result.error().isBlank(), () -> phase + " failed: " + result.error());
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient owner, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = owner.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) fail("action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(50L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
        return last;
    }

    private static void awaitRuntimeDead(SectorRuntime runtime, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(!runtime.isAlive()) return;
            Thread.sleep(25L);
        }
        fail("runtime did not terminate after crash injection: " + runtime.backend());
    }

    private static void assertListening(String host, int port, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos(); IOException last = null;
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

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root)) return;
        try(var paths = Files.walk(root)){
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try{ Files.deleteIfExists(path); }catch(IOException error){ throw new UncheckedIOException(error); }
            });
        }catch(UncheckedIOException error){ throw error.getCause(); }
    }

    private record BackendResult(String planet, String sector, ActionKind kind, ActionStatus statusAfterCleanSuspend,
                                 ActionStatus statusAfterCrashRecovery, long runtimeIncarnation, boolean launchCommitted,
                                 boolean hasDurableSave){}
}
