import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Product lifecycle regressions recovered after the CP019 backend checkpoint. */
@Tag("shared-campaign-product-recovery")
public class SharedCampaignProductRecoveryTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void singlePlayerOriginActuallyRunsImporterDuringCreation() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-singleplayer-origin-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            SharedCampaignCreationOptions options = options("Imported profile", "owner-import");
            options.origin = SharedCampaignState.CampaignOrigin.singlePlayerImport;
            options.source = null; // explicitly import the running vanilla profile
            SharedCampaignState state = service.createLocal(new Fi(directory.resolve("campaign").toString()), options,
                "127.0.0.1", 0, freePort());
            assertEquals(SharedCampaignState.CampaignOrigin.singlePlayerImport, state.origin);
            assertTrue(state.originDescription.startsWith("Local campaign profile:"),
                "singlePlayerImport must invoke the importer instead of silently creating a blank campaign");
        }finally{
            service.close();
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void backupRestorePreservesArchivedOwnerAuthorityAndRevision() throws Exception{
        Path root = Files.createTempDirectory("shared-campaign-product-restore-");
        Path original = root.resolve("original");
        Path restored = root.resolve("restored");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            SharedCampaignState created = service.createLocal(new Fi(original.toString()), options("Original", "owner-a"),
                "127.0.0.1", 0, freePort());
            assertEquals("owner-a", created.authorityHostId);
            SharedCampaignState.BackupState backup = service.createRestorePoint("owner-a", "restore", "test", 2);
            Path archive = original.resolve(backup.relativePath);
            assertTrue(Files.isRegularFile(archive));
            service.closeCurrent();

            SharedCampaignCreationOptions restore = options("Ignored replacement metadata", "replacement-owner");
            restore.origin = SharedCampaignState.CampaignOrigin.backupRestore;
            restore.source = new Fi(archive.toString());
            SharedCampaignState reopened = service.createLocal(new Fi(restored.toString()), restore,
                "127.0.0.1", 0, freePort());

            assertEquals("owner-a", reopened.ownerId, "restore must not replace the archived owner identity");
            assertEquals("owner-a", reopened.authorityHostId, "restore must reopen using the archived authority fence");
            assertEquals(backup.campaignRevision, reopened.revision, "restore must reopen the exact archived durable revision");
        }finally{
            service.close();
            deleteTree(root);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void compatibleRuntimeUpgradeRefreshesStrictFingerprintButContentMismatchFailsClosed() throws Exception{
        Path root = Files.createTempDirectory("shared-campaign-content-gate-");
        Path campaign = root.resolve("campaign");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            service.createLocal(new Fi(campaign.toString()), options("Content gate", "owner"), "127.0.0.1", 0, freePort());
            service.closeCurrent();
            SharedContentFingerprint.Fingerprints current = SharedContentFingerprint.calculateBoth();

            long beforeRefresh;
            try(SharedCampaignStore store = new SharedCampaignStore(new Fi(campaign.toString()))){
                store.open();
                SharedCampaignState changed = store.transact("owner", "test:old-runtime", state -> {
                    state.contentFingerprint = "runtime-v4:" + "0".repeat(64);
                    state.contentCompatibilityFingerprint = current.compatibility();
                });
                beforeRefresh = changed.revision;
            }

            SharedCampaignState refreshed = service.openLocal(new Fi(campaign.toString()), "owner", "127.0.0.1", 0, freePort());
            assertEquals(current.runtime(), refreshed.contentFingerprint);
            assertEquals(current.compatibility(), refreshed.contentCompatibilityFingerprint);
            assertEquals(beforeRefresh + 1L, refreshed.revision, "runtime-only updates should durably refresh the strict fingerprint");
            service.closeCurrent();

            try(SharedCampaignStore store = new SharedCampaignStore(new Fi(campaign.toString()))){
                store.open();
                store.transact("owner", "test:content-mismatch", state -> {
                    state.contentFingerprint = current.runtime();
                    state.contentCompatibilityFingerprint = "compat-v2:" + "f".repeat(64);
                });
            }
            assertThrows(SecurityException.class,
                () -> service.openLocal(new Fi(campaign.toString()), "owner", "127.0.0.1", 0, freePort()),
                "content/mod compatibility mismatch must fail before coordinator authority starts");
            assertFalse(service.localAuthorityOpen());
        }finally{
            service.close();
            deleteTree(root);
        }
    }

    private static SharedCampaignCreationOptions options(String name, String owner){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = name;
        options.ownerId = owner;
        options.ownerDisplayName = owner;
        options.primaryPlanetName = "serpulo";
        return options;
    }

    private static int freePort() throws IOException{
        try(ServerSocket socket = new ServerSocket(0)){ socket.setReuseAddress(true); return socket.getLocalPort(); }
    }

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths = Files.walk(root)){
            for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
