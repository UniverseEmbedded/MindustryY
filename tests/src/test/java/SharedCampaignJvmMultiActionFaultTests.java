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

/** JVM_PROCESS fault-isolation gate: one dead child must not perturb an unrelated live Action. */
@Tag("shared-campaign-jvm-multi-action")
public class SharedCampaignJvmMultiActionFaultTests{
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
    void forcedDeathOfOneJvmActionRecoversWithoutRestartingItsPeer() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-multi-fault-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RuntimePayloads.StartResult ground = null, onset = null, groundSeededResume = null, groundRecoveredResume = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM multi-Action fault isolation";
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
            assertListening(onset.host(), onset.port(), Duration.ofSeconds(15));

            // Give the crash target a durable recovery point while deliberately leaving the peer live.
            owner.suspendAction(ground.actionId());
            SharedCampaignState seeded = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            assertDurableSave(directory, requireAction(seeded, ground.actionId()), ground.actionId());
            assertEquals(ActionStatus.running, requireAction(seeded, onset.actionId()).status);
            awaitPortReleased(ground.port(), Duration.ofSeconds(15));

            groundSeededResume = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(groundSeededResume.error(), "Ground Zero seeded resume failed");
            assertEquals(ground.actionId(), groundSeededResume.actionId());
            SharedCampaignState bothLive = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(bothLive, ground.actionId()).runtimeIncarnation);
            assertEquals(1L, requireAction(bothLive, onset.actionId()).runtimeIncarnation);

            SectorRuntime doomed = requireJvmRuntime(service, ground.actionId());
            SectorRuntime peer = requireJvmRuntime(service, onset.actionId());
            ActionControlPlane control = service.authority().coordinator().actionRuntimes().controlPlane();
            long peerHeartbeatBeforeCrash = control.lastHeartbeatSeen(onset.actionId(), 1L);

            doomed.crashForTesting();
            awaitRuntimeDead(doomed, Duration.ofSeconds(15));

            // Production behavior only: do not call monitorOnce(). The scheduled monitor must recover the dead child.
            SharedCampaignState recovered = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(20));
            awaitPortReleased(groundSeededResume.port(), Duration.ofSeconds(15));
            ActionState peerState = requireAction(recovered, onset.actionId());
            assertEquals(ActionStatus.running, peerState.status, "peer Action was stopped by another child crash");
            assertEquals(1L, peerState.runtimeIncarnation, "peer Action was unnecessarily restarted");
            assertSame(peer, requireJvmRuntime(service, onset.actionId()), "peer runtime object was replaced during unrelated recovery");
            assertListening(onset.host(), onset.port(), Duration.ofSeconds(15));
            awaitHeartbeatAfter(control, onset.actionId(), 1L, peerHeartbeatBeforeCrash, Duration.ofSeconds(10));
            assertDurableSave(directory, requireAction(recovered, ground.actionId()), ground.actionId());

            groundRecoveredResume = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(groundRecoveredResume.error(), "Ground Zero post-crash resume failed");
            assertEquals(ground.actionId(), groundRecoveredResume.actionId(), "crash recovery changed logical Action identity");
            SharedCampaignState restored = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(3L, requireAction(restored, ground.actionId()).runtimeIncarnation,
                "recovered Action did not advance only its own runtime incarnation");
            assertEquals(1L, requireAction(restored, onset.actionId()).runtimeIncarnation,
                "peer incarnation changed during failed-Action resume");
            assertSame(peer, requireJvmRuntime(service, onset.actionId()), "peer runtime was replaced during failed-Action resume");

            owner.suspendAction(ground.actionId());
            SharedCampaignState groundStopped = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(groundRecoveredResume.port(), Duration.ofSeconds(15));
            assertEquals(ActionStatus.running, requireAction(groundStopped, onset.actionId()).status);
            owner.suspendAction(onset.actionId());
            SharedCampaignState allStopped = awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(onset.port(), Duration.ofSeconds(15));
            assertDurableSave(directory, requireAction(allStopped, ground.actionId()), ground.actionId());
            assertDurableSave(directory, requireAction(allStopped, onset.actionId()), onset.actionId());
        }finally{
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            for(RuntimePayloads.StartResult result : new RuntimePayloads.StartResult[]{ground, onset, groundSeededResume, groundRecoveredResume}){
                if(result != null && result.port() > 0) try{ awaitPortReleased(result.port(), Duration.ofSeconds(5)); }catch(Throwable ignored){}
            }
            deleteTree(directory);
        }
    }

    private static void awaitHeartbeatAfter(ActionControlPlane control, String actionId, long incarnation, long previous, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(control.lastHeartbeatSeen(actionId, incarnation) > previous) return;
            Thread.sleep(50L);
        }
        fail("peer Action stopped delivering heartbeats during unrelated child recovery");
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
        fail("runtime did not terminate after forced death");
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
