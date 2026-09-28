package mindustry.runtime;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** P19/P21 repeatable acceptance evidence for the bounded process compute pool. */
@Tag("shared-campaign-parallel")
class RuntimeParallelPerformanceAcceptanceTests{
    private static final int contexts = 4;

    @Test
    @Timeout(10)
    void smallOwnerPhaseStaysInlineAndPaysNoWorkerDispatch(){
        GameContext owner = new GameContext("perf-inline-owner");
        try{
            long before = RuntimeWorkerPool.sharedCompute().metrics().submitted();
            List<Integer> input = List.of(1, 2, 3, 4, 5, 6, 7, 8);
            List<Integer>[] result = new List[1];
            AtomicReference<Thread> callbackThread = new AtomicReference<>();
            Thread ownerThread = Thread.currentThread();
            RuntimeContexts.run(owner, () -> result[0] = DeterministicChunkExecutor.mapOrdered(
                owner, "perf-inline", input, 32, value -> {
                    callbackThread.compareAndSet(null, Thread.currentThread());
                    return value * 3;
                }));
            long after = RuntimeWorkerPool.sharedCompute().metrics().submitted();

            assertEquals(List.of(3, 6, 9, 12, 15, 18, 21, 24), result[0]);
            assertSame(ownerThread, callbackThread.get(), "small phases must execute on the authoritative owner lane");
            assertEquals(before, after, "small phases must not dispatch to the process worker pool");
        }finally{
            owner.dispose();
        }
    }

    @Test
    @Timeout(20)
    void workerCountsProduceIdenticalResultsAndRemainProcessBounded() throws Exception{
        long iterations = Math.max(250_000L, calibrateIterations(TimeUnit.MILLISECONDS.toNanos(3)) / 2L);
        long[] reference = runOnce(1, iterations, "worker-diff-1");
        long[] two = runOnce(2, iterations, "worker-diff-2");
        long[] four = runOnce(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())), iterations, "worker-diff-4");
        assertArrayEquals(reference, two);
        assertArrayEquals(reference, four);
    }

    @Test
    @Timeout(20)
    void contextCountScalingKeepsWorkerThreadsBoundedAtOneTwoFourEightAndSixteen() throws Exception{
        int workers = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors()));
        for(int count : new int[]{1, 2, 4, 8, 16}){
            GameContext[] owners = new GameContext[count];
            for(int i = 0; i < count; i++) owners[i] = new GameContext("scale-" + count + "-" + i);
            try(RuntimeWorkerPool pool = new RuntimeWorkerPool(workers, 2, "runtime-scale-" + count + "-")){
                CountDownLatch release = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>(count);
                long[] values = new long[count];
                for(int i = 0; i < count; i++){
                    final int index = i;
                    futures.add(pool.submit(owners[i], "p19-scaling", () -> {
                        try{ release.await(2, TimeUnit.SECONDS); }
                        catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); return; }
                        values[index] = busyWork(250_000L, index + 17L);
                    }));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while(System.nanoTime() < deadline && pool.metrics().activeWorkers() < Math.min(workers, count)) Thread.sleep(2L);
                RuntimeWorkerPool.Metrics beforeRelease = pool.metrics();
                assertTrue(beforeRelease.activeWorkers() <= workers, "worker threads must stay process-bounded for " + count + " contexts");
                assertTrue(beforeRelease.queuedTasks() <= beforeRelease.queueCapacity());
                release.countDown();
                for(Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
                for(int i = 0; i < count; i++) assertEquals(busyWork(250_000L, i + 17L), values[i]);
            }finally{
                dispose(owners);
            }
        }
    }

    @Test
    @Timeout(30)
    void fourIndependentContextsHaveMaterialParallelThroughputOnMulticoreHost() throws Exception{
        int cpus = Runtime.getRuntime().availableProcessors();
        Assumptions.assumeTrue(cpus >= 4, "P19 material-speedup gate requires a >=4-core host");
        int workers = Math.min(4, cpus - 1);
        Assumptions.assumeTrue(workers >= 3, "P19 gate requires at least three bounded workers");

        long iterations = calibrateIterations(TimeUnit.MILLISECONDS.toNanos(8));
        GameContext[] owners = owners("perf-speedup-");
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(workers, 2, "runtime-perf-accept-")){
            // Warm both the compiled workload and every worker before taking samples.
            for(int i = 0; i < 3; i++) busyWork(iterations, i + 11L);
            parallelBatch(pool, owners, iterations);

            long[] serialSamples = new long[7];
            long[] parallelSamples = new long[7];
            long[] expected = new long[contexts];
            for(int sample = 0; sample < serialSamples.length; sample++){
                long started = System.nanoTime();
                for(int i = 0; i < contexts; i++) expected[i] = busyWork(iterations, seed(sample, i));
                serialSamples[sample] = System.nanoTime() - started;

                started = System.nanoTime();
                long[] actual = parallelBatch(pool, owners, iterations, sample);
                parallelSamples[sample] = System.nanoTime() - started;
                assertArrayEquals(expected, actual, "worker scheduling changed deterministic compute output");
            }

            long serialMedian = median(serialSamples);
            long parallelMedian = median(parallelSamples);
            double speedup = serialMedian / (double)Math.max(1L, parallelMedian);
            RuntimeWorkerPool.Metrics metrics = pool.metrics();

            assertEquals(workers, metrics.parallelism());
            assertTrue(metrics.activeWorkers() <= workers);
            assertTrue(metrics.queuedTasks() <= metrics.queueCapacity());
            // Keep this threshold intentionally below the ideal 3-4x speedup so noisy CI does not create a false red,
            // while still requiring a material (>15%) wall-clock improvement on the >=4-core acceptance host.
            assertTrue(parallelMedian * 100L <= serialMedian * 85L,
                () -> String.format(Locale.ROOT,
                    "four-context bounded parallel compute did not materially beat serial: serialMedian=%.3fms parallelMedian=%.3fms speedup=%.2fx workers=%d cpus=%d iterations=%d",
                    serialMedian / 1_000_000d, parallelMedian / 1_000_000d, speedup, workers, cpus, iterations));

            System.out.printf(Locale.ROOT,
                "P19-PERF contexts=4 cpus=%d workers=%d iterations=%d serialMedianMs=%.3f parallelMedianMs=%.3f speedup=%.2fx submitted=%d completed=%d%n",
                cpus, workers, iterations, serialMedian / 1_000_000d, parallelMedian / 1_000_000d, speedup,
                metrics.submitted(), metrics.completed());
        }finally{
            dispose(owners);
        }
    }

    private static long[] runOnce(int workers, long iterations, String prefix) throws Exception{
        GameContext[] owners = owners(prefix + "-");
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(workers, 2, prefix + "-thread-")){
            long[] values = parallelBatch(pool, owners, iterations, 0);
            RuntimeWorkerPool.Metrics metrics = pool.metrics();
            assertEquals(workers, metrics.parallelism());
            assertTrue(metrics.activeWorkers() <= workers);
            return values;
        }finally{
            dispose(owners);
        }
    }

    private static long[] parallelBatch(RuntimeWorkerPool pool, GameContext[] owners, long iterations) throws Exception{
        return parallelBatch(pool, owners, iterations, -1);
    }

    private static long[] parallelBatch(RuntimeWorkerPool pool, GameContext[] owners, long iterations, int sample) throws Exception{
        long[] values = new long[owners.length];
        List<Future<?>> futures = new ArrayList<>(owners.length);
        for(int i = 0; i < owners.length; i++){
            final int index = i;
            final long taskSeed = sample < 0 ? index + 1L : seed(sample, index);
            futures.add(pool.submit(owners[i], "p19-performance", () -> values[index] = busyWork(iterations, taskSeed)));
        }
        for(Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        return values;
    }

    private static GameContext[] owners(String prefix){
        GameContext[] result = new GameContext[contexts];
        for(int i = 0; i < result.length; i++) result[i] = new GameContext(prefix + i);
        return result;
    }

    private static void dispose(GameContext[] owners){
        for(GameContext owner : owners) if(owner != null && !owner.closed()) owner.dispose();
    }

    private static long calibrateIterations(long targetNanos){
        // Calibrate only after the workload is JIT-warm; cold compilation time would otherwise select a tiny
        // workload and turn the throughput assertion into a scheduler-noise benchmark.
        for(int warm = 0; warm < 4; warm++) busyWork(500_000L, 0x51eedL + warm);
        long iterations = 250_000L;
        for(int attempt = 0; attempt < 12; attempt++){
            long started = System.nanoTime();
            busyWork(iterations, 0x5eedL + attempt);
            long elapsed = System.nanoTime() - started;
            if(elapsed >= targetNanos) return iterations;
            if(iterations >= 64_000_000L) return iterations;
            iterations *= 2L;
        }
        return iterations;
    }

    private static long seed(int sample, int index){
        return 0x9e3779b97f4a7c15L ^ ((long)(sample + 1) << 32) ^ (index * 0x632be59bd9b4e019L);
    }

    private static long busyWork(long iterations, long seed){
        long value = seed;
        for(long i = 0; i < iterations; i++){
            value ^= value << 13;
            value ^= value >>> 7;
            value ^= value << 17;
            value += 0x9e3779b97f4a7c15L + i;
        }
        return value;
    }

    private static long median(long[] values){
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }
}
