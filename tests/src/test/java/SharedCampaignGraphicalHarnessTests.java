import arc.files.Fi;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.game.Schematics;
import mindustry.content.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Temporary external-GUI harness; not intended for commit. */
public class SharedCampaignGraphicalHarnessTests{
    @Test
    @Timeout(value = 900, unit = TimeUnit.SECONDS)
    void holdCampaignForExternalGraphicalCrashRecovery() throws Exception{
        String dirProp = System.getenv("SC_GUI_HARNESS_DIR");
        assertNotNull(dirProp, "SC_GUI_HARNESS_DIR required");
        Path ctl = Path.of(dirProp);
        Files.createDirectories(ctl);
        Path campaign = ctl.resolve("campaign");
        Path ready = ctl.resolve("ready.properties");
        Path crash = ctl.resolve("crash-ground");
        Path recovered = ctl.resolve("ground-recovered.properties");
        Path stop = ctl.resolve("stop");
        Files.deleteIfExists(ready); Files.deleteIfExists(crash); Files.deleteIfExists(recovered); Files.deleteIfExists(stop);

        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
        System.setProperty(PackagedSectorLauncher.developmentFallbackProperty, "true");
        System.setProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty, "256");

        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Graphical crash recovery gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(campaign.toString()), options, "127.0.0.1", 57770, 57771);
            SharedCampaignClient owner = service.controlClient();
            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertTrue(ground.error() == null || ground.error().isBlank(), ground.error());
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(60));
            // Seed a recovery save, then resume so the occupied child can be killed and recovered deterministically.
            owner.suspendAction(ground.actionId());
            SharedCampaignState seeded = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(60));
            assertFalse(requireAction(seeded, ground.actionId()).lastSaveHash.isBlank());
            RuntimePayloads.StartResult resumed = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertTrue(resumed.error() == null || resumed.error().isBlank(), resumed.error());
            SharedCampaignState running = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(60));

            RuntimePayloads.StartResult onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertTrue(onset.error() == null || onset.error().isBlank(), onset.error());
            running = awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(60));

            Properties p = new Properties();
            p.setProperty("invite", service.localInviteCode());
            p.setProperty("sharedPort", "57771");
            p.setProperty("ground.actionId", ground.actionId());
            p.setProperty("ground.port", Integer.toString(requireAction(running, ground.actionId()).port));
            p.setProperty("ground.incarnation", Long.toString(requireAction(running, ground.actionId()).runtimeIncarnation));
            p.setProperty("onset.actionId", onset.actionId());
            p.setProperty("onset.port", Integer.toString(requireAction(running, onset.actionId()).port));
            try(OutputStream out = Files.newOutputStream(ready)){ p.store(out, "graphical harness ready"); }

            boolean didCrash = false;
            while(!Files.exists(stop)){
                if(!didCrash && Files.exists(crash)){
                    SectorRuntime runtime = service.authority().coordinator().actionRuntimes().runtime(ground.actionId());
                    assertNotNull(runtime);
                    runtime.crashForTesting();
                    SharedCampaignState suspended = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(60));
                    assertFalse(requireAction(suspended, ground.actionId()).lastSaveHash.isBlank());
                    RuntimePayloads.StartResult rr = owner.startAction(Planets.serpulo.name, "groundZero", "");
                    assertTrue(rr.error() == null || rr.error().isBlank(), rr.error());
                    SharedCampaignState recoveredState = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(60));
                    Properties rp = new Properties();
                    rp.setProperty("incarnation", Long.toString(requireAction(recoveredState, ground.actionId()).runtimeIncarnation));
                    rp.setProperty("port", Integer.toString(requireAction(recoveredState, ground.actionId()).port));
                    try(OutputStream out = Files.newOutputStream(recovered)){ rp.store(out, "ground recovered"); }
                    didCrash = true;
                }
                Thread.sleep(200L);
            }
        }finally{
            service.close();
        }
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus status, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == status) return last;
            Thread.sleep(100L);
        }
        fail("Timed out waiting for " + actionId + " -> " + status + "; last=" + (last == null ? "null" : last.actions.get(actionId)));
        return last;
    }

    private static ActionState requireAction(SharedCampaignState state, String actionId){
        ActionState action = state.actions.get(actionId);
        assertNotNull(action, "missing action " + actionId);
        return action;
    }
}
