import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
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
 * Product-level multi-world acceptance: two real campaign sectors remain live at the same time under the
 * production in-process parallel scheduler, both advance authoritative ticks, and both tear down cleanly.
 *
 * <p>This closes the gap between low-level worker/scheduler tests and the single-Action lifecycle soak: the lanes
 * here are real {@code InProcessSectorRuntime}s with real Mindustry Worlds, Net servers and Action control planes.</p>
 */
@Tag("shared-campaign-parallel-soak")
public class SharedCampaignParallelSectorSoakTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(true);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void twoRealSectorsTickConcurrentlyAndTearDownWithoutLeaks() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-parallel-sector-soak-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.parallel, 2)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Parallel real-sector soak";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = Planets.serpulo.name;
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult serpulo = owner.startAction(Planets.serpulo.name, "groundZero", "");
                assertBlank(serpulo.error(), "Serpulo start failed");
                RuntimePayloads.StartResult erekir = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(erekir.error(), "Erekir start failed");
                String serpuloActionId = serpulo.actionId(), erekirActionId = erekir.actionId();
                assertNotEquals(serpuloActionId, erekirActionId);

                long previousParallelBatches = 0L;
                for(int cycle = 1; cycle <= 3; cycle++){
                    SharedCampaignState bothRunning = awaitStatus(owner, serpuloActionId, ActionStatus.running, Duration.ofSeconds(30));
                    bothRunning = awaitStatus(owner, erekirActionId, ActionStatus.running, Duration.ofSeconds(30));
                    ActionState serpuloState = bothRunning.actions.get(serpuloActionId);
                    ActionState erekirState = bothRunning.actions.get(erekirActionId);
                    assertEquals(cycle, serpuloState.runtimeIncarnation, "Serpulo runtime incarnation mismatch at cycle " + cycle);
                    assertEquals(cycle, erekirState.runtimeIncarnation, "Erekir runtime incarnation mismatch at cycle " + cycle);
                    assertListening(serpulo.host(), serpulo.port(), Duration.ofSeconds(10));
                    assertListening(erekir.host(), erekir.port(), Duration.ofSeconds(10));
                    assertNotEquals(serpulo.port(), erekir.port());
                    awaitSchedulerSize(scheduler, 2, Duration.ofSeconds(10));

                    long serpuloTick = serpuloState.actionTick;
                    long erekirTick = erekirState.actionTick;
                    InProcessSectorScheduler.Metrics metrics = awaitParallelBatchAfter(scheduler, previousParallelBatches, Duration.ofSeconds(20));
                    previousParallelBatches = metrics.parallelBatches();
                    assertTrue(metrics.peakConcurrentTicks() >= 2,
                        () -> "parallel scheduler never overlapped two real sector ticks: " + metrics);
                    assertEquals(2, metrics.registeredRuntimes());
                    assertTrue(metrics.scheduledRuntimeTicks() >= cycle * 4L, "too few real-world ticks were dispatched");

                    SharedCampaignState advanced = awaitTicksAdvance(owner, serpuloActionId, serpuloTick, erekirActionId, erekirTick, Duration.ofSeconds(15));
                    assertEquals(ActionStatus.running, advanced.actions.get(serpuloActionId).status);
                    assertEquals(ActionStatus.running, advanced.actions.get(erekirActionId).status);

                    owner.suspendAction(serpuloActionId);
                    awaitStatus(owner, serpuloActionId, ActionStatus.suspended, Duration.ofSeconds(30));
                    owner.suspendAction(erekirActionId);
                    SharedCampaignState stopped = awaitStatus(owner, erekirActionId, ActionStatus.suspended, Duration.ofSeconds(30));
                    awaitSchedulerSize(scheduler, 0, Duration.ofSeconds(10));
                    awaitPortReleased(serpulo.port(), Duration.ofSeconds(10));
                    awaitPortReleased(erekir.port(), Duration.ofSeconds(10));

                    for(String actionId : new String[]{serpuloActionId, erekirActionId}){
                        ActionState action = stopped.actions.get(actionId);
                        assertEquals(ActionStatus.suspended, action.status);
                        assertFalse(action.lastSaveHash == null || action.lastSaveHash.isBlank(), "suspended Action lacks authoritative save hash: " + actionId);
                        Path save = directory.resolve("actions").resolve(actionId).resolve("config/saves/action.msav");
                        assertTrue(Files.isRegularFile(save) && Files.size(save) > 0L, "authoritative Action save missing: " + actionId);
                    }

                    if(cycle < 3){
                        RuntimePayloads.StartResult resumedSerpulo = owner.startAction(Planets.serpulo.name, "groundZero", "");
                        RuntimePayloads.StartResult resumedErekir = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                        assertBlank(resumedSerpulo.error(), "Serpulo resume failed at cycle " + cycle);
                        assertBlank(resumedErekir.error(), "Erekir resume failed at cycle " + cycle);
                        assertEquals(serpuloActionId, resumedSerpulo.actionId(), "Serpulo resume created a replacement Action");
                        assertEquals(erekirActionId, resumedErekir.actionId(), "Erekir resume created a replacement Action");
                        serpulo = resumedSerpulo;
                        erekir = resumedErekir;
                    }
                }
            }finally{
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "parallel soak leaked a registered embedded runtime");
        }finally{
            deleteTree(directory);
        }
    }

    private static SharedCampaignState awaitTicksAdvance(SharedCampaignClient client, String first, long firstTick,
                                                          String second, long secondTick, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState a = last.actions.get(first), b = last.actions.get(second);
            if(a != null && a.status == ActionStatus.failed) fail("first Action failed: " + a.failureReason);
            if(b != null && b.status == ActionStatus.failed) fail("second Action failed: " + b.failureReason);
            if(a != null && b != null && a.actionTick > firstTick && b.actionTick > secondTick) return last;
            Thread.sleep(50L);
        }
        fail("both live Actions did not advance authoritative ticks; first=" + firstTick + " second=" + secondTick);
        return last;
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

    private static InProcessSectorScheduler.Metrics awaitParallelBatchAfter(InProcessSectorScheduler scheduler, long previousBatches, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        InProcessSectorScheduler.Metrics last = scheduler.metrics();
        while(System.nanoTime() < deadline){
            last = scheduler.metrics();
            if(last.parallelBatches() > previousBatches && last.peakConcurrentTicks() >= 2) return last;
            Thread.sleep(25L);
        }
        fail("parallel scheduler never overlapped real Action runtimes after batch " + previousBatches + "; metrics=" + last);
        return last;
    }

    private static void awaitSchedulerSize(InProcessSectorScheduler scheduler, int expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(scheduler.size() == expected) return;
            Thread.sleep(25L);
        }
        fail("scheduler size did not become " + expected + "; current=" + scheduler.size());
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
        long deadline = System.nanoTime() + timeout.toNanos(); IOException last = null;
        while(System.nanoTime() < deadline){
            try(ServerSocket tcp = new ServerSocket(); DatagramSocket udp = new DatagramSocket(null)){
                tcp.setReuseAddress(true); udp.setReuseAddress(true);
                tcp.bind(new InetSocketAddress("127.0.0.1", port));
                udp.bind(new InetSocketAddress("127.0.0.1", port));
                return;
            }catch(IOException error){ last = error; }
            Thread.sleep(25L);
        }
        fail("game port was not released after suspend: " + port + ", last=" + last);
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
