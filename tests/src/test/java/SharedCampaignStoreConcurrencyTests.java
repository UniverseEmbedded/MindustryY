import arc.files.Fi;
import mindustry.campaign.shared.SharedCampaignState;
import mindustry.campaign.shared.SharedCampaignState.PlanetPolicyState;
import mindustry.campaign.shared.io.SharedCampaignStore;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression gate for Arc collection iterator reuse inside concurrent authoritative snapshots. */
@Tag("shared-campaign-parallel")
public class SharedCampaignStoreConcurrencyTests{
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentSnapshotsNeverShareArcIterators() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-snapshot-concurrency-");
        try(SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.toString()))){
            store.open();
            store.transact("owner", "test:init", state -> {
                state.ownerId = "owner";
                state.authorityHostId = "owner";
                SharedCampaignState.MemberState owner = new SharedCampaignState.MemberState();
                owner.memberId = "owner";
                owner.displayName = "owner";
                state.members.put("owner", owner);
                PlanetPolicyState policy = new PlanetPolicyState();
                policy.planetName = "serpulo";
                policy.policyId = "mindustry-y:test-serpulo";
                policy.compatibilityId = "test-serpulo-v1";
                policy.mode = "sharedCampaign";
                state.planetPolicies.put("serpulo", policy);
            });

            int workers = 8, iterations = 250;
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            CyclicBarrier start = new CyclicBarrier(workers);
            List<Future<Long>> futures = new ArrayList<>();
            for(int worker = 0; worker < workers; worker++){
                futures.add(pool.submit(() -> {
                    start.await();
                    long revision = -1L;
                    for(int i = 0; i < iterations; i++){
                        SharedCampaignState snapshot = store.snapshot();
                        assertEquals("owner", snapshot.ownerId);
                        assertNotNull(snapshot.planetPolicies.get("serpulo"));
                        assertTrue(snapshot.revision >= revision);
                        revision = snapshot.revision;
                    }
                    return revision;
                }));
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            for(Future<Long> future : futures) assertEquals(1L, future.get());
        }finally{
            deleteTree(directory);
        }
    }


    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void commitObserverAndTransactionCallerNeverShareArcIterators() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-commit-copy-isolation-");
        try(SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.toString()))){
            store.open();
            store.transact("owner", "test:init", state -> {
                state.ownerId = "owner";
                state.authorityHostId = "owner";
                SharedCampaignState.MemberState owner = new SharedCampaignState.MemberState();
                owner.memberId = "owner";
                owner.displayName = "owner";
                state.members.put("owner", owner);
                PlanetPolicyState policy = new PlanetPolicyState();
                policy.planetName = "serpulo";
                policy.policyId = "mindustry-y:test-serpulo";
                policy.compatibilityId = "test-serpulo-v1";
                policy.mode = "sharedCampaign";
                state.planetPolicies.put("serpulo", policy);
                for(int i = 0; i < 3; i++){
                    SharedCampaignState.MemberState member = new SharedCampaignState.MemberState();
                    member.memberId = "member-" + i;
                    member.displayName = member.memberId;
                    state.members.put(member.memberId, member);
                }
            });

            @SuppressWarnings("unchecked")
            Iterator<?>[] held = new Iterator<?>[1];
            store.onCommit(commit -> {
                Iterator<?> iterator = commit.state().members.iterator();
                assertTrue(iterator.hasNext());
                iterator.next();
                held[0] = iterator;
            });

            SharedCampaignState returned = store.transact("owner", "test:copy-isolation", state -> state.displayName = "copy-isolated");
            assertEquals("copy-isolated", returned.displayName);
            assertNotNull(held[0]);
            // With the old implementation, the post-listener return-value deep copy iterated the exact same
            // ObjectMap and invalidated this observer-held Arc iterator with "#iterator() cannot be used nested".
            assertDoesNotThrow(() -> held[0].hasNext());
        }finally{
            deleteTree(directory);
        }
    }

    private static void deleteTree(Path root) throws Exception{
        if(root == null || !Files.exists(root)) return;
        try(var paths = Files.walk(root)){
            for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
