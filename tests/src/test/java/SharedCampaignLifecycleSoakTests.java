import arc.files.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Repeated real embedded lifecycle gate: start/resume -> running/listening -> suspend -> teardown/port release. */
@Tag("shared-campaign-lifecycle")
public class SharedCampaignLifecycleSoakTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void repeatedInProcessStartSuspendResumeDoesNotLeakRuntimeOrPort() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-lifecycle-soak-");
        SharedCampaignService authority = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            authority.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
            authority.createLocal(new Fi(directory.toString()), options(), "127.0.0.1", 0, 0);
            owner = authority.controlClient();

            String logicalAction = "";
            for(int cycle = 0; cycle < 12; cycle++){
                RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
                assertTrue(started.error() == null || started.error().isBlank(), () -> "cycle start failed: " + started.error());
                if(cycle == 0) logicalAction = started.actionId();
                assertEquals(logicalAction, started.actionId(), "resume must preserve logical action identity at cycle " + cycle);
                awaitStatus(owner, logicalAction, ActionStatus.running, Duration.ofSeconds(30));
                assertListening(started.host(), started.port(), Duration.ofSeconds(10));

                owner.suspendAction(logicalAction);
                awaitStatus(owner, logicalAction, ActionStatus.suspended, Duration.ofSeconds(30));
                awaitSchedulerEmpty(scheduler, Duration.ofSeconds(10));
                awaitPortReleased(started.port(), Duration.ofSeconds(10));

                Path save = directory.resolve("actions").resolve(logicalAction).resolve("config/saves/action.msav");
                assertTrue(Files.isRegularFile(save) && Files.size(save) > 0L, "authoritative save missing at cycle " + cycle);
            }
            assertEquals(0, scheduler.size(), "all embedded runtimes must unregister after soak");
        }finally{
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            authority.close();
            deleteTree(directory);
        }
    }

    private static SharedCampaignCreationOptions options(){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = "Lifecycle soak";
        options.ownerId = "owner";
        options.ownerDisplayName = "Owner";
        options.primaryPlanetName = "serpulo";
        return options;
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

    private static void awaitSchedulerEmpty(InProcessSectorScheduler scheduler, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(scheduler.size() == 0) return;
            Thread.sleep(25L);
        }
        fail("embedded runtime remained registered after suspend; scheduler size=" + scheduler.size());
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

    private static int freePort() throws IOException{
        try(ServerSocket socket = new ServerSocket(0)){ socket.setReuseAddress(true); return socket.getLocalPort(); }
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
