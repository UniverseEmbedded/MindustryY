package mindustry.runtime;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeStorageExecutorTests{
    @Test
    @Timeout(10)
    void orderedBatchUsesSharedBoundedPoolAndPreservesOwner() throws Exception{
        GameContext owner = new GameContext("storage-batch-owner");
        Set<String> threads = ConcurrentHashMap.newKeySet();
        AtomicInteger wrongOwner = new AtomicInteger();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for(int i = 0; i < 17; i++){
            int value = i;
            tasks.add(() -> {
                threads.add(Thread.currentThread().getName());
                if(RuntimeContexts.current() != owner) wrongOwner.incrementAndGet();
                Thread.sleep((value % 3) * 2L);
                return value;
            });
        }
        List<Integer> result = RuntimeStorageExecutor.invokeAllOrdered(owner, "ordered-test", tasks);
        assertEquals(java.util.stream.IntStream.range(0, 17).boxed().toList(), result);
        assertEquals(0, wrongOwner.get());
        assertTrue(threads.size() <= RuntimeStorageExecutor.parallelism(), "storage workers must be process bounded");
        assertTrue(threads.stream().allMatch(name -> name.startsWith("Mindustry-Runtime-Storage-")));
    }

    @Test
    @Timeout(5)
    void closedOwnerMakesQueuedStorageTaskFailClosed() throws Exception{
        GameContext owner = new GameContext("storage-close-owner");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        // Saturate the storage pool with other owners so the target task is definitely queued.
        List<Future<Integer>> blockers = new ArrayList<>();
        for(int i = 0; i < RuntimeStorageExecutor.parallelism(); i++){
            GameContext blocker = new GameContext("storage-blocker-" + i);
            blockers.add(RuntimeStorageExecutor.submit(blocker, "blocker", () -> {
                entered.countDown(); release.await(3, TimeUnit.SECONDS); return 1;
            }));
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        AtomicBoolean ran = new AtomicBoolean();
        Future<Integer> late = RuntimeStorageExecutor.submit(owner, "late", () -> { ran.set(true); return 2; });
        owner.dispose();
        release.countDown();
        for(Future<Integer> blocker : blockers) assertEquals(1, blocker.get(2, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, () -> late.get(2, TimeUnit.SECONDS));
        assertFalse(ran.get());
    }
}
