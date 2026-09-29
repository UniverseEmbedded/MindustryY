import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.game.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Crash-recovery coverage for durable Action capture/final outcome markers. */
public class SharedCampaignDurableOutcomeRecoveryTests{
    @TempDir Path temp;

    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void crashedActionRecoversDurableFinalOutcomeBeforeGenericAutosaveFallback() throws Exception{
        Path directory = temp.resolve("final-outcome");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                service.createLocal(new Fi(directory.toString()), options("Final outcome recovery"), "127.0.0.1", 0, 0);
                owner = service.controlClient();
                RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
                assertBlank(started.error());
                SharedCampaignState running = awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                service.authority().coordinator().actionCommands().forceSaveRunningActions("test");
                ActionState action = service.authority().state().actions.get(started.actionId());
                assertNotNull(action);
                assertFalse(action.lastSaveHash == null || action.lastSaveHash.isBlank());

                String marker = "durable-final-outcome-marker";
                RuntimePayloads.ActionStopped outcome = new RuntimePayloads.ActionStopped(
                    action.actionId, action.hostGeneration, action.runtimeIncarnation,
                    true, false, action.lastSaveHash, marker, null, null, new ObjectSet<>());
                Fi outcomeFile = outcomeFile(service, action.actionId, SharedActionAgent.finalOutcomeRelativePath);
                outcomeFile.parent().mkdirs();
                outcomeFile.writeBytes(RuntimePayloads.encode(outcome), false);
                assertTrue(outcomeFile.exists());

                SectorRuntime runtime = service.authority().coordinator().actionRuntimes().runtime(action.actionId);
                assertNotNull(runtime);
                runtime.crashForTesting();
                awaitDead(runtime, Duration.ofSeconds(10));
                service.authority().coordinator().actionRuntimes().monitorOnce();

                SharedCampaignState recovered = awaitStatus(owner, action.actionId, ActionStatus.suspended, Duration.ofSeconds(10));
                ActionState recoveredAction = recovered.actions.get(action.actionId);
                assertEquals(marker, recoveredAction.failureReason,
                    "durable final outcome must win over generic crash/autosave recovery");
                assertFalse(outcomeFile.exists(), "acknowledged durable final outcome must be deleted after recovery");
            }finally{
                if(owner != null) try{ owner.close(); }catch(Exception ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size());
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void crashedActionRecoversDurableCaptureMilestoneBeforeSuspendingAutosave() throws Exception{
        Path directory = temp.resolve("capture-outcome");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                service.createLocal(new Fi(directory.toString()), options("Capture outcome recovery"), "127.0.0.1", 0, 0);
                owner = service.controlClient();
                RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
                assertBlank(started.error());
                awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                service.authority().coordinator().actionCommands().forceSaveRunningActions("test");
                ActionState action = service.authority().state().actions.get(started.actionId());
                assertNotNull(action);
                String sectorKey = SharedCampaignState.sectorKey(action.planetName, action.sectorName);
                SectorState beforeSector = service.authority().state().sectors.get(sectorKey);
                assertTrue(beforeSector == null || !beforeSector.captured,
                    "fixture must begin before the capture milestone is committed");

                RuntimePayloads.ActionStopped capture = new RuntimePayloads.ActionStopped(
                    action.actionId, action.hostGeneration, action.runtimeIncarnation,
                    true, true, action.lastSaveHash, "", null, null, new ObjectSet<>());
                Fi outcomeFile = outcomeFile(service, action.actionId, SharedActionAgent.captureOutcomeRelativePath);
                outcomeFile.parent().mkdirs();
                outcomeFile.writeBytes(RuntimePayloads.encode(capture), false);
                assertTrue(outcomeFile.exists());

                SectorRuntime runtime = service.authority().coordinator().actionRuntimes().runtime(action.actionId);
                assertNotNull(runtime);
                runtime.crashForTesting();
                awaitDead(runtime, Duration.ofSeconds(10));
                service.authority().coordinator().actionRuntimes().monitorOnce();

                SharedCampaignState recovered = awaitStatus(owner, action.actionId, ActionStatus.suspended, Duration.ofSeconds(10));
                SectorState sector = recovered.sectors.get(sectorKey);
                assertNotNull(sector);
                assertTrue(sector.captured, "durable capture marker must be applied before crash autosave recovery");
                assertTrue(sector.hasBase);
                assertFalse(outcomeFile.exists(), "recovered capture marker must be deleted after durable commit");
            }finally{
                if(owner != null) try{ owner.close(); }catch(Exception ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size());
        }
    }

    private static Fi outcomeFile(SharedCampaignService service, String actionId, String relative){
        return service.authority().coordinator().store().root().child("actions").child(actionId).child(relative);
    }

    private static SharedCampaignCreationOptions options(String name){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.ownerId = "owner";
        options.ownerDisplayName = "Owner";
        options.displayName = name;
        options.primaryPlanetName = "serpulo";
        return options;
    }

    private static void awaitDead(SectorRuntime runtime, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(!runtime.isAlive()) return;
            Thread.sleep(20L);
        }
        fail("runtime did not die");
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == expected) return last;
            Thread.sleep(40L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
        return last;
    }

    private static void assertBlank(String value){
        assertTrue(value == null || value.isBlank(), value);
    }
}
