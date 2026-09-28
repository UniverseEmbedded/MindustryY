import arc.files.Fi;
import arc.util.Time;
import mindustry.campaign.shared.SharedCampaignState;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.runtime.HostMigrationService;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Destructive persistence/backup/migration recovery gates. These tests intentionally damage durable files or
 * interrupt directory-install state and verify that authority either recovers deterministically or fails closed.
 */
@Tag("shared-campaign-persistence-fault")
public class SharedCampaignPersistenceFaultTests{

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void corruptPrimaryFallsBackToLastAuthenticatedBackupAndCanCommitAgain() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-primary-recovery-");
        try{
            long backupRevision;
            try(SharedCampaignStore store = openSeeded(directory)){
                backupRevision = store.revision();
                store.transact("owner", "test:newer-primary", state -> state.displayName = "newer-primary");
                assertEquals(backupRevision + 1L, store.revision());
            }

            Files.write(directory.resolve("campaign.mycp"), new byte[]{1, 2, 3, 4, 5}, StandardOpenOption.TRUNCATE_EXISTING);
            try(SharedCampaignStore recovered = new SharedCampaignStore(new Fi(directory.toString()))){
                recovered.open();
                assertEquals(backupRevision, recovered.revision(), "recovery must use the last complete backup snapshot");
                SharedCampaignState committed = recovered.transact("owner", "test:after-recovery", state -> state.displayName = "after-recovery");
                assertEquals(backupRevision + 1L, committed.revision);
                assertEquals("after-recovery", committed.displayName);
            }
        }finally{
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void corruptAuditJournalCrcFailsClosed() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-journal-crc-");
        try{
            try(SharedCampaignStore store = openSeeded(directory)){
                store.transact("owner", "test:journal-tip", state -> state.displayName = "journal-tip");
            }
            Path journal = directory.resolve("campaign.journal");
            byte[] bytes = Files.readAllBytes(journal);
            assertTrue(bytes.length > 8);
            bytes[bytes.length - 1] ^= 0x5a;
            Files.write(journal, bytes, StandardOpenOption.TRUNCATE_EXISTING);

            try(SharedCampaignStore reopened = new SharedCampaignStore(new Fi(directory.toString()))){
                assertThrows(RuntimeException.class, reopened::open, "an unauthenticated audit chain must never be accepted");
            }
        }finally{
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void commitObserverFailureCannotTurnDurableSuccessIntoRetryableFailure() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-observer-fault-");
        try{
            long committedRevision;
            try(SharedCampaignStore store = openSeeded(directory)){
                store.onCommit(commit -> { throw new IllegalStateException("synthetic observer crash"); });
                SharedCampaignState committed = assertDoesNotThrow(() -> store.transact("owner", "test:observer-fault",
                    state -> state.displayName = "observer-committed"));
                committedRevision = committed.revision;
                assertEquals("observer-committed", committed.displayName);
            }
            try(SharedCampaignStore reopened = new SharedCampaignStore(new Fi(directory.toString()))){
                reopened.open();
                assertEquals(committedRevision, reopened.revision());
                assertEquals("observer-committed", reopened.snapshot().displayName);
            }
        }finally{
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void migrationBundleIoFailureBeforeFenceLeavesSourceAuthorityWritable() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-migration-prefence-");
        Path blocker = Files.createTempFile("shared-campaign-migration-parent-blocker-", ".tmp");
        try(SharedCampaignStore store = openSeeded(directory)){
            long beforeRevision = store.revision();
            String beforeHost = store.snapshot().authorityHostId;
            Fi impossibleTarget = new Fi(blocker.resolve("migration.mycm").toString());

            assertThrows(RuntimeException.class,
                () -> new HostMigrationService(store).exportBundle(impossibleTarget, "owner", "target-host", 60_000L));

            SharedCampaignState afterFailure = store.snapshot();
            assertEquals(beforeRevision, afterFailure.revision, "pre-fence I/O failure must not mutate the durable authority state");
            assertEquals(beforeHost, afterFailure.authorityHostId);
            assertFalse(afterFailure.migrationPending);

            SharedCampaignState next = store.transact("owner", "test:still-authoritative", state -> state.displayName = "still-authoritative");
            assertEquals(beforeRevision + 1L, next.revision, "source must remain writable after a pre-fence failure");
        }finally{
            deleteTree(directory);
            Files.deleteIfExists(blocker);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void durableMigrationFenceCannotBeUndoneByRecoveringPreFenceBackup() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-migration-fence-");
        Path transfer = Files.createTempFile("shared-campaign-migration-transfer-", ".mycm");
        Files.deleteIfExists(transfer);
        try{
            try(SharedCampaignStore store = openSeeded(directory)){
                // Ensure campaign.mycp.bak is a valid pre-fence snapshot.
                store.transact("owner", "test:pre-fence-checkpoint", state -> state.displayName = "pre-fence");
                assertTrue(directory.resolve("campaign.mycp.bak").toFile().isFile());
                HostMigrationService.MigrationBundle bundle = new HostMigrationService(store).exportBundle(
                    new Fi(transfer.toString()), "owner", "target-host", 60_000L);
                assertTrue(bundle.file().exists());
                assertTrue(store.snapshot().migrationPending);
                assertEquals("target-host", store.snapshot().authorityHostId);
            }

            Files.write(directory.resolve("campaign.mycp"), new byte[]{9, 8, 7, 6}, StandardOpenOption.TRUNCATE_EXISTING);
            try(SharedCampaignStore reopened = new SharedCampaignStore(new Fi(directory.toString()))){
                assertThrows(RuntimeException.class, reopened::open,
                    "a pre-fence backup must not resurrect source authority after the migration fence committed");
            }
        }finally{
            deleteTree(directory);
            Files.deleteIfExists(transfer);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void backupCaptureRejectsSymbolicLinkTraversal() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-backup-symlink-");
        Path outside = Files.createTempFile("shared-campaign-backup-outside-", ".txt");
        try(SharedCampaignStore store = openSeeded(directory)){
            Files.writeString(outside, "outside");
            Path link = directory.resolve("unsafe-link");
            try{
                Files.createSymbolicLink(link, outside);
            }catch(UnsupportedOperationException | FileSystemException unsupported){
                Assumptions.assumeTrue(false, "symbolic links are not available in this test environment: " + unsupported);
            }
            SharedCampaignBackupService backups = new SharedCampaignBackupService(store);
            assertThrows(RuntimeException.class, () -> backups.create("owner", "symlink", "test", 2),
                "backup traversal must fail closed instead of following symlinks");
            assertFalse(directory.resolve("backups").resolve("unsafe-link").toFile().exists());
        }finally{
            deleteTree(directory);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void interruptedDirectoryInstallWithCurrentMydiMarkerCompletesDeterministically() throws Exception{
        Path parent = Files.createTempDirectory("shared-campaign-directory-install-");
        try{
            String sourceName = ".campaign.restore-source";
            String targetName = "campaign";
            String backupName = ".campaign.previous-test";
            Path source = parent.resolve(sourceName);
            Path target = parent.resolve(targetName);
            Path backup = parent.resolve(backupName);
            Path marker = parent.resolve(".mindustry-y-install-test.txn");
            Files.createDirectories(source);
            Files.writeString(source.resolve("new.txt"), "new-authority");
            Files.createDirectories(backup);
            Files.writeString(backup.resolve("old.txt"), "old-authority");
            writeInstallMarker(marker, sourceName, targetName, backupName);

            SharedDirectoryInstall.recoverParent(parent);

            assertTrue(Files.isDirectory(target));
            assertEquals("new-authority", Files.readString(target.resolve("new.txt")));
            assertFalse(Files.exists(source));
            assertFalse(Files.exists(backup), "successful completion must remove stale previous authority data");
            assertFalse(Files.exists(marker), "durable marker must be cleared after recovery");
        }finally{
            deleteTree(parent);
        }
    }

    private static SharedCampaignStore openSeeded(Path directory){
        SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.toString()));
        store.open();
        store.transact("owner", "test:seed", state -> {
            state.displayName = "seed";
            state.ownerId = "owner";
            state.authorityHostId = "owner";
            state.authorityGeneration = Math.max(1L, state.authorityGeneration);
            state.freezeWhenEmpty = true;

            SharedCampaignState.MemberState member = new SharedCampaignState.MemberState();
            member.memberId = "owner";
            member.displayName = "Owner";
            member.lastSeenAt = Time.millis();
            member.lastConnectedAt = member.lastSeenAt;
            state.members.put(member.memberId, member);

            SharedCampaignState.PlanetPolicyState policy = new SharedCampaignState.PlanetPolicyState();
            policy.planetName = "serpulo";
            policy.policyId = "shared-campaign:persistence-fault";
            policy.compatibilityId = "persistence-fault-v1";
            policy.mode = "sharedCampaign";
            policy.recommendedActiveActions = 1;
            state.planetPolicies.put("serpulo", policy);
        });
        return store;
    }

    private static void writeInstallMarker(Path marker, String source, String target, String backup) throws IOException{
        try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(marker,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))){
            out.writeInt(0x4d594449); // MYDI, current SharedDirectoryInstall marker magic
            out.writeInt(1);
            out.writeUTF(source);
            out.writeUTF(target);
            out.writeUTF(backup);
        }
    }

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if(Files.isSymbolicLink(root)){
            Files.deleteIfExists(root);
            return;
        }
        try(var paths = Files.walk(root)){
            for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
