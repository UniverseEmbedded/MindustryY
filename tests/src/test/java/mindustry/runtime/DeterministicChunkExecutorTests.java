package mindustry.runtime;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("shared-campaign-parallel")
class DeterministicChunkExecutorTests{
    @Test
    @Timeout(10)
    void workerComputeKeepsOwnerAndInputOrder(){
        GameContext owner = new GameContext("ordered-chunk-test");
        try{
            List<Integer> input = IntStream.range(0, 384).boxed().toList();
            AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), wrongOwner = new AtomicInteger();
            List<Integer>[] output = new List[1];
            RuntimeContexts.run(owner, () -> output[0] = DeterministicChunkExecutor.mapOrdered(owner, "chunk-test", input, 32, value -> {
                if(RuntimeContexts.current() != owner) wrongOwner.incrementAndGet();
                int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try{ LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(150)); }
                finally{ active.decrementAndGet(); }
                return value * 31 + 7;
            }));

            assertEquals(0, wrongOwner.get(), "every worker must be rebound to the explicit GameContext");
            assertEquals(input.size(), output[0].size());
            for(int i = 0; i < input.size(); i++) assertEquals(input.get(i) * 31 + 7, output[0].get(i));
            if(RuntimeWorkerPool.sharedCompute().parallelism() > 1) assertTrue(peak.get() > 1, "large pure phases should use more than one worker");
        }finally{
            owner.dispose();
        }
    }

    @Test
    @Timeout(10)
    void repeatedParallelPassIsBitForBitDeterministic(){
        GameContext owner = new GameContext("repeatable-chunk-test");
        try{
            List<Long> input = LongStream.range(0, 512).boxed().toList();
            List<Long>[] first = new List[1], second = new List[1];
            RuntimeContexts.run(owner, () -> {
                first[0] = DeterministicChunkExecutor.mapOrdered(owner, "repeat-a", input, 16, DeterministicChunkExecutorTests::mix);
                second[0] = DeterministicChunkExecutor.mapOrdered(owner, "repeat-b", input, 16, DeterministicChunkExecutorTests::mix);
            });
            assertEquals(first[0], second[0]);
        }finally{
            owner.dispose();
        }
    }


    @Test
    @Timeout(10)
    void explicitOwnerIsBoundEvenWhenWorkloadFallsBackToSerial(){
        GameContext owner = new GameContext("serial-explicit-owner"), ambient = new GameContext("serial-ambient-owner");
        try{
            AtomicInteger wrongOwner = new AtomicInteger();
            List<Integer>[] result = new List[1];
            RuntimeContexts.run(ambient, () -> result[0] = DeterministicChunkExecutor.mapOrdered(owner, "serial-owner", List.of(1, 2, 3), 96, value -> {
                if(RuntimeContexts.current() != owner) wrongOwner.incrementAndGet();
                return value * 2;
            }));
            assertEquals(0, wrongOwner.get(), "explicit owner semantics must not change at the parallel threshold");
            assertEquals(List.of(2, 4, 6), result[0]);
        }finally{
            owner.dispose(); ambient.dispose();
        }
    }

    @Test
    @Timeout(10)
    void exceptionalParallelPhaseDoesNotReturnUntilSiblingWorkersArePhysicallyQuiescent() throws Exception{
        Assumptions.assumeTrue(RuntimeWorkerPool.sharedCompute().parallelism() > 1);
        GameContext owner = new GameContext("exception-barrier-owner");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch siblingEntered = new CountDownLatch(1), releaseSibling = new CountDownLatch(1), siblingExited = new CountDownLatch(1);
        try{
            List<Integer> input = IntStream.range(0, 384).boxed().toList();
            Future<?> phase = caller.submit(() -> RuntimeContexts.run(owner, () ->
                DeterministicChunkExecutor.mapOrdered(owner, "exception-barrier", input, 32, value -> {
                    if(value == 0){
                        try{ assertTrue(siblingEntered.await(2, TimeUnit.SECONDS)); }
                        catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                        throw new IllegalStateException("expected chunk failure");
                    }
                    if(value >= 96 && siblingEntered.getCount() > 0){
                        siblingEntered.countDown();
                        try{
                            while(releaseSibling.getCount() > 0){
                                try{ releaseSibling.await(20, TimeUnit.MILLISECONDS); }
                                catch(InterruptedException ignored){}
                            }
                        }finally{
                            siblingExited.countDown();
                        }
                    }
                    return value;
                })));
            assertTrue(siblingEntered.await(2, TimeUnit.SECONDS));
            Thread.sleep(80L);
            assertFalse(phase.isDone(), "phase must not escape its barrier while a sibling worker still executes");
            releaseSibling.countDown();
            assertTrue(siblingExited.await(2, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> phase.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }finally{
            releaseSibling.countDown();
            caller.shutdownNow();
            owner.dispose();
        }
    }

    private static long mix(long value){
        long x = value + 0x9e3779b97f4a7c15L;
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }
}
