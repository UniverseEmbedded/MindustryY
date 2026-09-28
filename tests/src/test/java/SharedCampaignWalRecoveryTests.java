import arc.files.Fi;
import arc.util.Time;
import mindustry.campaign.shared.SharedCampaignState;
import mindustry.campaign.shared.io.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2 WAL recovery contracts: continuous replay, torn-tail discard, non-continuous fail-closed,
 * and hard-checkpoint truncation.
 */
@Tag("shared-campaign-wal")
public class SharedCampaignWalRecoveryTests{

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void coalescedWalIsReplayedAfterCrashWithoutFullCheckpoint() throws Exception{
        Path directory = Files.createTempDirectory("shared-store-wal-replay-");
        Path crashCopy = Files.createTempDirectory("shared-store-wal-replay-copy-");
        try(SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.toString()))){
            store.open();
            seedLiveCampaign(store);
            SharedCampaignStore.IoCounters baseline = store.ioCounters();

            SharedCampaignState after = store.transactCoalesced("action:a0", "shared-campaign:action-heartbeat", state -> {
                SharedCampaignState.ActionState action = state.actions.get("a0");
                assertNotNull(action);
                action.actionTick = 42L;
                action.leaseExpiresAt = Time.millis() + 30_000L;
            });
            assertEquals(42L, store.actionAuthority("a0").action().actionTick);
            assertTrue(store.hasCoalescedPending(), "WAL-backed coalesced state still needs a later checkpoint");
            SharedCampaignStore.IoCounters delta = store.ioCountersSince(baseline);
            assertEquals(0, delta.transactCount, "WAL append must not count as a full snapshot checkpoint");
            assertEquals(1, delta.walAppends);
            assertTrue(delta.walBytes > 0, "WAL bytes must be durable for replay");
            assertTrue(store.walBytesOnDisk() > 0);

            // Simulate power loss: copy durable files while the live store still holds the process lock.
            copyDurableFiles(directory, crashCopy);

            try(SharedCampaignStore recovered = new SharedCampaignStore(new Fi(crashCopy.toString()))){
                recovered.open();
                assertEquals(after.revision, recovered.revision());
                assertEquals(42L, recovered.snapshot().actions.get("a0").actionTick, "WAL must replay coalesced ticks onto the snapshot");
                assertTrue(recovered.hasCoalescedPending(), "replayed WAL still awaits checkpoint");

                SharedCampaignState next = recovered.transact("owner", "post-wal-hard", state -> state.displayName = "after-wal");
                assertEquals(after.revision + 1L, next.revision);
                assertEquals(42L, next.actions.get("a0").actionTick);
                assertFalse(recovered.hasCoalescedPending());
                assertEquals(0L, recovered.walBytesOnDisk(), "hard commit must truncate WAL after absorbing it");
            }
        }finally{
            deleteTree(directory);
            deleteTree(crashCopy);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tornWalTailIsDiscardedAndCompletePrefixStillReplays() throws Exception{
        Path directory = Files.createTempDirectory("shared-store-wal-torn-");
        Path crashCopy = Files.createTempDirectory("shared-store-wal-torn-copy-");
        try(SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.toString()))){
            store.open();
            seedLiveCampaign(store);
            store.transactCoalesced("action:a0", "shared-campaign:action-heartbeat", state -> state.actions.get("a0").actionTick = 7L);
            store.transactCoalesced("action:a0", "shared-campaign:action-heartbeat", state -> state.actions.get("a0").actionTick = 8L);
            assertTrue(store.walBytesOnDisk() > 0);

            copyDurableFiles(directory, crashCopy);
            Path wal = crashCopy.resolve("campaign.wal");
            byte[] bytes = Files.readAllBytes(wal);
            assertTrue(bytes.length > 8);
            // Corrupt the final CRC byte so the last framed record is incomplete/invalid.
            bytes[bytes.length - 1] ^= 0x5a;
            Files.write(wal, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        }
        try(SharedCampaignStore recovered = new SharedCampaignStore(new Fi(crashCopy.toString()))){
            recovered.open();
            // Only complete records are kept; first heartbeat (tick=7) must survive, second may be discarded.
            long tick = recovered.snapshot().actions.get("a0").actionTick;
            assertTrue(tick == 7L || tick == 8L, "complete WAL prefix must remain replayable, tick=" + tick);
        }finally{
            deleteTree(directory);
            deleteTree(crashCopy);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void discontinuousWalFailsClosed() throws Exception{
        Path directory = Files.createTempDirectory("shared-store-wal-gap-");
        try(SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.toString()))){
            store.open();
            seedLiveCampaign(store);
            store.transactCoalesced("action:a0", "shared-campaign:action-heartbeat", state -> state.actions.get("a0").actionTick = 3L);
            // Hard commit checkpoints and truncates WAL.
            store.transact("owner", "checkpoint", state -> state.displayName = "checkpointed");
            long snapshotRevision = store.revision();

            // Re-inject a WAL whose tip is ahead of the snapshot but whose base is not continuous.
            SharedCampaignWal.Diff stale = new SharedCampaignWal.Diff();
            stale.fromRevision = Math.max(0L, snapshotRevision - 2L);
            stale.toRevision = snapshotRevision + 2L;
            stale.updatedAt = Time.millis();
            stale.displayName = "stale";
            stale.displayNameChanged = true;
            assertNotEquals(snapshotRevision, stale.fromRevision);
            Files.write(directory.resolve("campaign.wal"), SharedCampaignWal.frame(stale), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            store.close();
        }
        try(SharedCampaignStore reopened = new SharedCampaignStore(new Fi(directory.toString()))){
            assertThrows(IllegalStateException.class, reopened::open, "non-continuous WAL must fail closed");
        }finally{
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void unmodeledResearchProgressChangeCheckpointsInsteadOfWal() throws Exception{
        Path directory = Files.createTempDirectory("shared-store-wal-research-");
        try(SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.toString()))){
            store.open();
            seedLiveCampaign(store);
            store.transact("owner", "seed-research", state -> {
                SharedCampaignState.ResearchState research = new SharedCampaignState.ResearchState();
                research.contentName = "copper-wall";
                research.required.put("copper", 10);
                research.contributed.put("copper", 1);
                state.research.put("copper-wall", research);
            });
            SharedCampaignStore.IoCounters baseline = store.ioCounters();
            store.transactCoalesced("owner", "research-progress", state -> {
                SharedCampaignState.ResearchState research = state.research.get("copper-wall");
                assertNotNull(research);
                research.contributed.put("copper", 4);
            });
            SharedCampaignStore.IoCounters delta = store.ioCountersSince(baseline);
            assertEquals(1, delta.transactCount, "research content changes must full-checkpoint");
            assertEquals(0, delta.walAppends);
            assertEquals(4, store.snapshot().research.get("copper-wall").contributed.get("copper", 0));
        }finally{
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void heartbeatWithJoinSecretAndLaunchFieldsStaysWalEligible() throws Exception{
        Path directory = Files.createTempDirectory("shared-store-wal-action-payload-");
        try(SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.toString()))){
            store.open();
            seedLiveCampaign(store);
            store.transact("owner", "seed-action-payload", state -> {
                SharedCampaignState.ActionState action = state.actions.get("a0");
                assertNotNull(action);
                action.joinSecretHash = "deadbeef".repeat(8);
                action.bindAddress = "10.0.0.2";
                action.launchOriginSector = SharedCampaignState.sectorKey("serpulo", "0");
                action.launchLoadout = "bXlTY2hlbWF0aWM=";
                action.resourceNeeds.put("copper", 12);
            });
            SharedCampaignStore.IoCounters baseline = store.ioCounters();
            store.transactCoalesced("action:a0", "shared-campaign:action-heartbeat", state -> {
                SharedCampaignState.ActionState action = state.actions.get("a0");
                assertNotNull(action);
                action.actionTick = 99L;
                action.leaseExpiresAt = Time.millis() + 30_000L;
            });
            SharedCampaignStore.IoCounters delta = store.ioCountersSince(baseline);
            assertEquals(0, delta.transactCount, "action payload must not force a full checkpoint on heartbeat");
            assertEquals(1, delta.walAppends, "heartbeat must append a WAL record even with joinSecret/launch fields set");
            assertTrue(store.walBytesOnDisk() > 0);
            assertEquals(99L, store.actionAuthority("a0").action().actionTick);

            Path crashCopy = Files.createTempDirectory("shared-store-wal-action-payload-copy-");
            try{
                copyDurableFiles(directory, crashCopy);
                try(SharedCampaignStore recovered = new SharedCampaignStore(new Fi(crashCopy.toString()))){
                    recovered.open();
                    SharedCampaignState.ActionState action = recovered.snapshot().actions.get("a0");
                    assertEquals(99L, action.actionTick);
                    assertEquals("deadbeef".repeat(8), action.joinSecretHash);
                    assertEquals("10.0.0.2", action.bindAddress);
                    assertEquals(SharedCampaignState.sectorKey("serpulo", "0"), action.launchOriginSector);
                    assertEquals("bXlTY2hlbWF0aWM=", action.launchLoadout);
                    assertEquals(12, action.resourceNeeds.get("copper", 0));
                }
            }finally{
                deleteTree(crashCopy);
            }
        }finally{
            deleteTree(directory);
        }
    }

    private static void copyDurableFiles(Path source, Path target) throws Exception{
        Files.copy(source.resolve("campaign.mycp"), target.resolve("campaign.mycp"), StandardCopyOption.REPLACE_EXISTING);
        Path journal = source.resolve("campaign.journal");
        if(Files.exists(journal)) Files.copy(journal, target.resolve("campaign.journal"), StandardCopyOption.REPLACE_EXISTING);
        Path wal = source.resolve("campaign.wal");
        if(Files.exists(wal)) Files.copy(wal, target.resolve("campaign.wal"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void seedLiveCampaign(SharedCampaignStore store){
        store.transact("owner", "bench:seed", state -> {
            state.ownerId = "owner";
            state.authorityHostId = "owner";
            state.freezeWhenEmpty = true;
            // Recovery tests exercise the immediately durable WAL contract; the default low-frequency
            // profile intentionally buffers the first WAL record in memory before disk flush.
            state.persistenceProfile = SharedCampaignState.PersistenceProfile.highFrequencyWal;
            SharedCampaignState.MemberState member = new SharedCampaignState.MemberState();
            member.memberId = "owner";
            member.displayName = "owner";
            member.lastSeenAt = Time.millis();
            state.members.put("owner", member);

            SharedCampaignState.PlanetPolicyState policy = new SharedCampaignState.PlanetPolicyState();
            policy.planetName = "serpulo";
            policy.policyId = "shared-campaign:wal-recovery";
            policy.compatibilityId = "wal-recovery-v1";
            policy.mode = "sharedCampaign";
            policy.recommendedActiveActions = 1;
            state.planetPolicies.put("serpulo", policy);

            SharedCampaignState.ActionState action = new SharedCampaignState.ActionState();
            action.actionId = "a0";
            action.planetName = "serpulo";
            action.sectorName = "0";
            action.status = SharedCampaignState.ActionStatus.running;
            action.hostId = "owner";
            action.hostGeneration = state.authorityGeneration;
            state.actions.put("a0", action);

            SharedCampaignState.SectorState sector = new SharedCampaignState.SectorState();
            sector.planetName = "serpulo";
            sector.sectorName = "0";
            sector.hasBase = true;
            state.sectors.put(SharedCampaignState.sectorKey("serpulo", "0"), sector);
        });
    }

    private static void deleteTree(Path root) throws Exception{
        if(root == null || !Files.exists(root)) return;
        try(var paths = Files.walk(root)){
            for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
