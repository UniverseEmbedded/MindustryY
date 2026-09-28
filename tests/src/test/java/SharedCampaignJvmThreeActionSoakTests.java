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

/** Short production-backend soak with three real child JVM Actions and full authority-shutdown reclamation. */
@Tag("shared-campaign-jvm-multi-action")
public class SharedCampaignJvmThreeActionSoakTests{
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
    void threeJvmActionsRunAndTickTogetherThenAuthorityShutdownReclaimsEverything() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-three-action-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RuntimePayloads.StartResult frozen = null, ground = null, onset = null;
        SectorRuntime frozenRuntime = null, groundRuntime = null, onsetRuntime = null;
        boolean closed = false;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Three JVM Action soak";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 3;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            // Frozen Forest's vanilla objectives are Ground Zero complete + junction/router researched. Seed a durable,
            // resource-rich completed Ground Zero so the normal production startAction() path performs a legitimate
            // offline-origin launch. The soak deliberately does not assert incidental launch-cost/origin metadata.
            service.authority().coordinator().store().transact("owner", "test:seed-frozen-forest-progress", state -> {
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

            onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(onset.error(), "Onset start failed");
            SharedCampaignState allRunning = awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            String[] ids = {frozen.actionId(), ground.actionId(), onset.actionId()};
            int[] ports = {frozen.port(), ground.port(), onset.port()};
            assertEquals(3, Arrays.stream(ports).distinct().count(), "three live child JVMs must use distinct game ports");
            for(int i = 0; i < ids.length; i++){
                ActionState action = requireAction(allRunning, ids[i]);
                assertEquals(ActionStatus.running, action.status, "Action was not simultaneously RUNNING: " + ids[i]);
                assertEquals(1L, action.runtimeIncarnation, "fresh Action started with unexpected incarnation: " + ids[i]);
                assertListening("127.0.0.1", ports[i], Duration.ofSeconds(15));
            }

            frozenRuntime = requireJvmRuntime(service, frozen.actionId());
            groundRuntime = requireJvmRuntime(service, ground.actionId());
            onsetRuntime = requireJvmRuntime(service, onset.actionId());

            // Production child servers intentionally use `config autoPause true`; with no players online, world tick
            // progress is not a valid liveness assertion. Instead require a *new* authenticated control-plane heartbeat
            // from every child while all three remain RUNNING. Real world-tick progress belongs in the later multi-client E2E.
            ActionControlPlane control = service.authority().coordinator().actionRuntimes().controlPlane();
            long frozenHeartbeat = control.lastHeartbeatSeen(frozen.actionId(), 1L);
            long groundHeartbeat = control.lastHeartbeatSeen(ground.actionId(), 1L);
            long onsetHeartbeat = control.lastHeartbeatSeen(onset.actionId(), 1L);
            awaitThreeHeartbeatsAdvance(control, frozen.actionId(), frozenHeartbeat, ground.actionId(), groundHeartbeat,
                onset.actionId(), onsetHeartbeat, Duration.ofSeconds(10));
            SharedCampaignState stillRunning = owner.snapshot();
            assertEquals(ActionStatus.running, requireAction(stillRunning, frozen.actionId()).status);
            assertEquals(ActionStatus.running, requireAction(stillRunning, ground.actionId()).status);
            assertEquals(ActionStatus.running, requireAction(stillRunning, onset.actionId()).status);

            // Shutdown from the authority side while all three children are live; product teardown owns their lifetime.
            service.close();
            closed = true;
            awaitRuntimeDead(frozenRuntime, Duration.ofSeconds(20));
            awaitRuntimeDead(groundRuntime, Duration.ofSeconds(20));
            awaitRuntimeDead(onsetRuntime, Duration.ofSeconds(20));
            for(int port : ports) awaitPortReleased(port, Duration.ofSeconds(15));
        }finally{
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            if(!closed) service.close();
            for(SectorRuntime runtime : new SectorRuntime[]{frozenRuntime, groundRuntime, onsetRuntime}){
                if(runtime != null && runtime.isAlive()) try{ runtime.close(); }catch(Throwable ignored){}
            }
            for(RuntimePayloads.StartResult result : new RuntimePayloads.StartResult[]{frozen, ground, onset}){
                if(result != null && result.port() > 0) try{ awaitPortReleased(result.port(), Duration.ofSeconds(5)); }catch(Throwable ignored){}
            }
            deleteTree(directory);
        }
    }

    private static void awaitThreeHeartbeatsAdvance(ActionControlPlane control,
                                                     String first, long firstSeen,
                                                     String second, long secondSeen,
                                                     String third, long thirdSeen,
                                                     Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            long a = control.lastHeartbeatSeen(first, 1L);
            long b = control.lastHeartbeatSeen(second, 1L);
            long c = control.lastHeartbeatSeen(third, 1L);
            if(a > firstSeen && b > secondSeen && c > thirdSeen) return;
            Thread.sleep(50L);
        }
        fail("three live Actions did not all deliver a subsequent control-plane heartbeat");
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

    private static SectorRuntime requireJvmRuntime(SharedCampaignService service, String actionId){
        SectorRuntime runtime = service.authority().coordinator().actionRuntimes().runtime(actionId);
        assertNotNull(runtime, "runtime missing for " + actionId);
        assertEquals(SectorRuntime.Backend.jvmProcess, runtime.backend());
        assertTrue(runtime.isAlive(), "child JVM is not alive for " + actionId);
        return runtime;
    }

    private static void awaitRuntimeDead(SectorRuntime runtime, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(!runtime.isAlive()) return;
            Thread.sleep(25L);
        }
        fail("child JVM survived authority shutdown");
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
