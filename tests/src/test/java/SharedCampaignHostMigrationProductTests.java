import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end cold-host migration gate including a real authoritative Action save and post-import resume. */
@Tag("shared-campaign-host-migration")
public class SharedCampaignHostMigrationProductTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void fencedSourceMigratesSuspendedActionAndTargetContinuesAuthority() throws Exception{
        Path root = Files.createTempDirectory("shared-campaign-host-migration-product-");
        Path sourceDir = root.resolve("source-campaign");
        Path targetDir = root.resolve("target-campaign");
        Fi bundleFile = new Fi(root.resolve("transfer.mycm").toString());
        SharedCampaignService source = new SharedCampaignService(Vars.game(), new Fi(root.resolve("source-mods").toString()));
        SharedCampaignClient sourceOwner = null;
        String campaignId;
        String actionId;
        long sourceGeneration;
        long sourceRevision;
        String sourceSaveHash;
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            source.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Migration product gate";
            options.ownerId = "source-host";
            options.ownerDisplayName = "Source Owner";
            options.primaryPlanetName = "serpulo";
            source.createLocal(new Fi(sourceDir.toString()), options, "127.0.0.1", 0, 0);
            sourceOwner = source.controlClient();

            RuntimePayloads.StartResult started = sourceOwner.startAction("serpulo", "groundZero", "");
            assertBlank(started.error(), "source Action start failed");
            awaitStatus(sourceOwner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));
            sourceOwner.suspendAction(started.actionId());
            SharedCampaignState suspended = awaitStatus(sourceOwner, started.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
            awaitSchedulerSize(scheduler, 0, Duration.ofSeconds(10));

            campaignId = suspended.campaignId;
            actionId = started.actionId();
            sourceGeneration = suspended.authorityGeneration;
            sourceRevision = suspended.revision;
            sourceSaveHash = suspended.actions.get(actionId).lastSaveHash;
            assertFalse(sourceSaveHash.isBlank());
            assertTrue(Files.isRegularFile(sourceDir.resolve("actions").resolve(actionId).resolve("config/saves/action.msav")));

            HostMigrationService.MigrationBundle bundle = source.exportMigration(bundleFile, "target-host", 120_000L);
            assertTrue(bundle.file().exists() && bundle.file().length() > 0L);
            assertFalse(bundle.transferCode().isBlank());
            assertEquals(sourceGeneration + 1L, bundle.authorityGeneration());
            assertFalse(source.localAuthorityOpen(), "source authority must close after durable migration fence");
            assertEquals(0, scheduler.size(), "source runtime leaked across migration fence");

            // The original directory is durably fenced to target-host and may not be reclaimed by the old authority.
            SharedCampaignService staleSource = new SharedCampaignService(Vars.game(), new Fi(root.resolve("stale-mods").toString()));
            try{
                assertThrows(SecurityException.class,
                    () -> staleSource.openLocal(new Fi(sourceDir.toString()), "source-host", "127.0.0.1", 0, 0));
            }finally{
                staleSource.close();
            }

            SharedCampaignService target = new SharedCampaignService(Vars.game(), new Fi(root.resolve("target-mods").toString()));
            SharedCampaignClient targetOwner = null;
            try(InProcessSectorScheduler targetScheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
                target.runtimeFactory(SectorRuntimeFactory.inProcess(targetScheduler));
                SharedCampaignState imported = target.importMigration(bundle.file(), new Fi(targetDir.toString()), "target-host",
                    bundle.transferCode(), "127.0.0.1", 0, 0);
                assertEquals(campaignId, imported.campaignId);
                assertEquals("target-host", imported.authorityHostId);
                assertEquals(sourceGeneration + 1L, imported.authorityGeneration);
                assertFalse(imported.migrationPending);
                assertTrue(imported.revision > sourceRevision, "migration acceptance must advance the durable revision");
                ActionState importedAction = imported.actions.get(actionId);
                assertNotNull(importedAction);
                assertEquals(ActionStatus.suspended, importedAction.status);
                assertEquals(sourceSaveHash, importedAction.lastSaveHash);
                assertTrue(Files.isRegularFile(targetDir.resolve("actions").resolve(actionId).resolve("config/saves/action.msav")),
                    "migration bundle did not preserve the authoritative Action save");

                targetOwner = target.controlClient();
                RuntimePayloads.StartResult resumed = targetOwner.startAction("serpulo", "groundZero", "");
                assertBlank(resumed.error(), "target failed to resume migrated Action");
                assertEquals(actionId, resumed.actionId(), "migration resume created a replacement logical Action");
                SharedCampaignState running = awaitStatus(targetOwner, actionId, ActionStatus.running, Duration.ofSeconds(30));
                assertEquals(2L, running.actions.get(actionId).runtimeIncarnation);
                assertEquals(sourceGeneration + 1L, running.actions.get(actionId).hostGeneration);

                targetOwner.suspendAction(actionId);
                SharedCampaignState finalState = awaitStatus(targetOwner, actionId, ActionStatus.suspended, Duration.ofSeconds(30));
                awaitSchedulerSize(targetScheduler, 0, Duration.ofSeconds(10));
                assertEquals("target-host", finalState.authorityHostId);
                assertEquals(sourceGeneration + 1L, finalState.authorityGeneration);
                assertFalse(finalState.actions.get(actionId).lastSaveHash.isBlank());
            }finally{
                if(targetOwner != null) try{ targetOwner.close(); }catch(IOException ignored){}
                target.close();
            }
        }finally{
            if(sourceOwner != null) try{ sourceOwner.close(); }catch(IOException ignored){}
            source.close();
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
            Thread.sleep(50L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("Action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
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
