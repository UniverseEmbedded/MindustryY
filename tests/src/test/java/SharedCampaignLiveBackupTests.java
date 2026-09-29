import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** A published restore point must checkpoint live Action worlds instead of archiving a stale autosave. */
public class SharedCampaignLiveBackupTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void restorePointForcesRunningActionSaveBeforeArchive() throws Exception{
        Path root = Files.createTempDirectory("shared-live-backup-");
        Path campaign = root.resolve("campaign");
        Path restored = root.resolve("restored");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1);
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory)){
            service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Live backup";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.persistenceProfile = PersistenceProfile.lowFrequencyWal;
            service.createLocal(new Fi(campaign.toString()), options, "127.0.0.1", 0, 0);

            SharedCampaignClient owner = service.controlClient();
            RuntimePayloads.StartResult started = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertTrue(started.error() == null || started.error().isBlank(), started.error());
            awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));

            Path save = campaign.resolve("actions").resolve(started.actionId()).resolve("config/saves/action.msav");
            Files.deleteIfExists(save);
            assertFalse(Files.exists(save), "test precondition: remove any earlier autosave");

            BackupState backup = service.createRestorePoint("owner", "live", "test-live-world", 2);
            assertTrue(Files.isRegularFile(save) && Files.size(save) > 0L,
                "creating a restore point must force the running Action to save first");
            String liveHash = SharedFileDigests.sha256(new Fi(save.toFile()));
            ActionState action = owner.snapshot().actions.get(started.actionId());
            assertNotNull(action);
            assertEquals(liveHash, action.lastSaveHash, "authority must commit the exact forced-save hash before backup capture");

            Path archive = campaign.resolve(backup.relativePath);
            assertTrue(Files.isRegularFile(archive));
            SharedCampaignBackupService.restoreArchive(new Fi(archive.toFile()), new Fi(restored.toFile()));
            Path restoredSave = restored.resolve("actions").resolve(started.actionId()).resolve("config/saves/action.msav");
            assertTrue(Files.isRegularFile(restoredSave), "restore archive must contain the newly checkpointed Action world");
            assertEquals(liveHash, SharedFileDigests.sha256(new Fi(restoredSave.toFile())));
        }finally{
            deleteTree(root);
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void restoringLiveBackupColdStartsActionsAsSuspended() throws Exception{
        Path root = Files.createTempDirectory("shared-live-backup-reopen-");
        Path campaign = root.resolve("campaign");
        Path restored = root.resolve("restored");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1);
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory)){
            service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Live backup reopen";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            service.createLocal(new Fi(campaign.toString()), options, "127.0.0.1", 0, 0);
            SharedCampaignClient owner = service.controlClient();
            RuntimePayloads.StartResult started = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertTrue(started.error() == null || started.error().isBlank(), started.error());
            awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));
            BackupState backup = service.createRestorePoint("owner", "live-reopen", "test-live-reopen", 2);
            Path archive = campaign.resolve(backup.relativePath);
            service.closeCurrent();

            SharedCampaignCreationOptions restore = new SharedCampaignCreationOptions();
            restore.origin = CampaignOrigin.backupRestore;
            restore.source = new Fi(archive.toString());
            SharedCampaignState reopened = service.createLocal(new Fi(restored.toString()), restore, "127.0.0.1", 0, 0);
            ActionState action = reopened.actions.get(started.actionId());
            assertNotNull(action);
            assertEquals(ActionStatus.suspended, action.status,
                "a backup cannot carry a live process; restored live Actions must cold-start from their forced save");
            assertEquals(0, reopened.runningActions(), "restoring a backup must not advertise phantom live Actions");
            assertTrue(new Fi(restored.resolve(action.saveRelativePath).toString()).exists(), "cold restore must retain the Action save");
        }finally{
            deleteTree(root);
        }
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) fail("Action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(40L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("Action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
        return last;
    }

    private static void deleteTree(Path root) throws Exception{
        if(root == null || !Files.exists(root)) return;
        try(var stream = Files.walk(root)){
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try{ Files.deleteIfExists(path); }catch(Exception ignored){} });
        }
    }
}
