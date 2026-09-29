import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.game.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.Comparator;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Product gate: persistence-profile changes must reach already-running Action worlds. */
public class SharedCampaignLivePersistenceProfileTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void runningActionAppliesCommittedAutosavePolicyBeforeSettingsReturn() throws Exception{
        Path directory = Files.createTempDirectory("shared-live-persistence-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Live persistence";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = "serpulo";
                options.persistenceProfile = PersistenceProfile.lowFrequencyWal;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
                assertTrue(started.error() == null || started.error().isBlank(), started.error());
                awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                SharedActionAgent agent = agent(service, started.actionId());
                assertEquals(expectedMillis(PersistenceProfile.lowFrequencyWal), autosaveMillis(agent));

                SharedCampaignState high = owner.updateSettings(1, false, false, InvitePolicy.members, PersistenceProfile.highFrequencyWal);
                assertEquals(PersistenceProfile.highFrequencyWal, high.persistenceProfile);
                assertEquals(expectedMillis(PersistenceProfile.highFrequencyWal), autosaveMillis(agent),
                    "settings response must not return before the live Action applies the committed high-frequency policy");

                SharedCampaignState traditional = owner.updateSettings(1, false, false, InvitePolicy.members, PersistenceProfile.traditional);
                assertEquals(PersistenceProfile.traditional, traditional.persistenceProfile);
                assertEquals(expectedMillis(PersistenceProfile.traditional), autosaveMillis(agent),
                    "running Action must apply traditional autosave cadence through the snapshot barrier");
            }finally{
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "live persistence test leaked an embedded Action runtime");
        }finally{
            try(var walk = Files.walk(directory)){
                walk.sorted(Comparator.reverseOrder()).forEach(path -> { try{ Files.deleteIfExists(path); }catch(IOException ignored){} });
            }
        }
    }

    private static SharedActionAgent agent(SharedCampaignService service, String actionId){
        SectorRuntime runtime = service.authority().coordinator().actionRuntimes().runtime(actionId);
        assertTrue(runtime instanceof InProcessSectorRuntime, "expected embedded runtime");
        GameContext context = ((InProcessSectorRuntime)runtime).context();
        SharedActionAgent agent = SharedActionBootstrap.findAgent(context);
        assertNotNull(agent);
        return agent;
    }

    private static long autosaveMillis(SharedActionAgent agent) throws Exception{
        Field field = SharedActionAgent.class.getDeclaredField("actionAutosaveIntervalMillis");
        field.setAccessible(true);
        return field.getLong(agent);
    }

    private static long expectedMillis(PersistenceProfile profile){
        return TimeUnit.SECONDS.toMillis(SharedCampaignPersistence.policy(profile).actionAutosaveSeconds());
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) fail("action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(50L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
        return last;
    }
}
