import arc.files.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.io.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

public class SharedCampaignPersistenceProfileTests{
    private Path temp;

    @BeforeEach
    void setup() throws Exception{
        temp = Files.createTempDirectory("shared-persistence-profile-");
    }

    @AfterEach
    void cleanup() throws Exception{
        if(temp != null) try(var walk = Files.walk(temp)){
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try{ Files.deleteIfExists(path); }catch(Exception ignored){} });
        }
    }

    @Test
    void highFrequencyWalFsyncsCoalescedMutationImmediately(){
        Fi root = new Fi(temp.resolve("high").toString());
        try(SharedCampaignStore store = initialized(root, PersistenceProfile.highFrequencyWal)){
            SharedCampaignStore.IoCounters before = store.ioCounters();
            store.transactCoalesced("test", "heartbeat", state -> state.campaignTick++);
            SharedCampaignStore.IoCounters delta = store.ioCounters().delta(before);
            assertTrue(store.walBytesOnDisk() > 0L, "high-frequency mode must make WAL durable immediately");
            assertTrue(delta.walAppends >= 1L);
            assertTrue(delta.forceCount >= 1L, "high-frequency mode must fsync the WAL append");
        }
    }

    @Test
    void lowFrequencyWalBatchesBeforeDiskWrite(){
        Fi root = new Fi(temp.resolve("low").toString());
        try(SharedCampaignStore store = initialized(root, PersistenceProfile.lowFrequencyWal)){
            SharedCampaignStore.IoCounters before = store.ioCounters();
            store.transactCoalesced("test", "heartbeat", state -> state.campaignTick++);
            SharedCampaignStore.IoCounters delta = store.ioCounters().delta(before);
            assertEquals(0L, store.walBytesOnDisk(), "low-frequency mode should batch the first WAL record in memory");
            assertEquals(0L, delta.forceCount, "low-frequency mode must not fsync every coalesced mutation");
            assertTrue(store.hasCoalescedPending());
        }
    }

    @Test
    void traditionalModeUsesFullCheckpointWithoutWal(){
        Fi root = new Fi(temp.resolve("traditional").toString());
        long revision;
        try(SharedCampaignStore store = initialized(root, PersistenceProfile.traditional)){
            store.transactCoalesced("test", "heartbeat", state -> state.campaignTick += 9L);
            assertEquals(0L, store.walBytesOnDisk());
            assertFalse(root.child("campaign.wal").exists());
            revision = store.snapshot().revision;
            store.flushCoalescedIfNeeded();
            assertFalse(root.child("campaign.wal").exists());
        }
        try(SharedCampaignStore reopened = new SharedCampaignStore(root)){
            reopened.open();
            assertEquals(revision, reopened.snapshot().revision);
            assertEquals(9L, reopened.snapshot().campaignTick);
            assertEquals(PersistenceProfile.traditional, reopened.snapshot().persistenceProfile);
        }
    }

    @Test
    void switchingProfilesAbsorbsPendingWalWithoutLosingProgress(){
        Fi root = new Fi(temp.resolve("switching").toString());
        long finalRevision;
        try(SharedCampaignStore store = initialized(root, PersistenceProfile.lowFrequencyWal)){
            store.transactCoalesced("test", "heartbeat-low", state -> state.campaignTick += 2L);
            assertEquals(0L, store.walBytesOnDisk(), "low-frequency WAL should still be buffered before the switch");

            SharedCampaignState traditional = store.transact("owner", "switch-traditional",
                state -> state.persistenceProfile = PersistenceProfile.traditional);
            assertEquals(PersistenceProfile.traditional, traditional.persistenceProfile);
            assertEquals(2L, traditional.campaignTick, "hard profile switch must absorb the pending low-frequency mutation");
            assertFalse(root.child("campaign.wal").exists(), "switching to traditional must leave no stale WAL segment");

            store.transactCoalesced("test", "heartbeat-traditional", state -> state.campaignTick += 3L);
            assertFalse(root.child("campaign.wal").exists());

            SharedCampaignState high = store.transact("owner", "switch-high",
                state -> state.persistenceProfile = PersistenceProfile.highFrequencyWal);
            assertEquals(5L, high.campaignTick, "traditional in-memory progress must be checkpointed by the hard switch");
            assertEquals(PersistenceProfile.highFrequencyWal, high.persistenceProfile);

            store.transactCoalesced("test", "heartbeat-high", state -> state.campaignTick += 5L);
            assertTrue(store.walBytesOnDisk() > 0L, "high-frequency mode must immediately persist the next WAL record");
            finalRevision = store.snapshot().revision;
        }
        try(SharedCampaignStore reopened = new SharedCampaignStore(root)){
            reopened.open();
            assertEquals(finalRevision, reopened.snapshot().revision);
            assertEquals(10L, reopened.snapshot().campaignTick);
            assertEquals(PersistenceProfile.highFrequencyWal, reopened.snapshot().persistenceProfile);
        }
    }

    @Test
    void schema26MigratesWithoutWeakeningExistingDurability(){
        SharedCampaignState old = new SharedCampaignState();
        old.schema = 26;
        old.persistenceProfile = null;
        SharedCampaignState migrated = SharedCampaignMigrations.migrate(old);
        assertEquals(SharedCampaignState.currentSchema, migrated.schema);
        assertEquals(PersistenceProfile.highFrequencyWal, migrated.persistenceProfile);
    }

    @Test
    void schema25MigratesToHighFrequencyWal(){
        // Real schema-25 states decode with the field default (lowFrequencyWal), never null: the legacy
        // migration validates before rewriting the profile, so keep the default and assert the upgrade.
        SharedCampaignState old = new SharedCampaignState();
        old.schema = 25;
        SharedCampaignState migrated = SharedCampaignMigrations.migrate(old);
        assertEquals(SharedCampaignState.currentSchema, migrated.schema);
        assertEquals(PersistenceProfile.highFrequencyWal, migrated.persistenceProfile);
    }

    @Test
    void presetsMatchProductIntent(){
        assertTrue(SharedCampaignPersistence.policy(PersistenceProfile.highFrequencyWal).walImmediateSync());
        assertTrue(SharedCampaignPersistence.policy(PersistenceProfile.lowFrequencyWal).walEnabled());
        assertFalse(SharedCampaignPersistence.policy(PersistenceProfile.lowFrequencyWal).walImmediateSync());
        assertFalse(SharedCampaignPersistence.policy(PersistenceProfile.traditional).walEnabled());
        assertTrue(SharedCampaignPersistence.policy(PersistenceProfile.highFrequencyWal).actionAutosaveSeconds()
            < SharedCampaignPersistence.policy(PersistenceProfile.lowFrequencyWal).actionAutosaveSeconds());
    }

    private static SharedCampaignStore initialized(Fi root, PersistenceProfile profile){
        SharedCampaignStore store = new SharedCampaignStore(root);
        store.open();
        store.transact("owner", "init", state -> {
            state.ownerId = "owner";
            state.authorityHostId = "owner";
            state.freezeWhenEmpty = false;
            state.persistenceProfile = profile;
            SharedCampaignState.MemberState owner = new SharedCampaignState.MemberState();
            owner.memberId = "owner";
            owner.displayName = "Owner";
            state.members.put(owner.memberId, owner);
            SharedCampaignState.PlanetPolicyState policy = new SharedCampaignState.PlanetPolicyState();
            policy.planetName = "serpulo";
            policy.policyId = "shared-campaign:persistence-profile";
            policy.compatibilityId = "shared-campaign:persistence-profile-v1";
            policy.mode = "parallel-actions";
            policy.recommendedActiveActions = 2;
            state.planetPolicies.put("serpulo", policy);
        });
        return store;
    }
}
