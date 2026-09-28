package mindustry.campaign.shared.runtime;

import arc.util.*;
import mindustry.runtime.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Scheduler for embedded real Sector worlds.
 *
 * <p>Each registered {@link RuntimeLane} is a single authoritative lane: its tick and lifecycle teardown are
 * serialized by its owner. This scheduler may therefore run <em>different</em> GameContexts concurrently
 * without ever allowing one GameContext to tick twice at the same time.</p>
 *
 * <p>The process default is {@link Mode#auto}: bootstrap/generation remains serialized until every runtime reports
 * {@code parallelTickReady()}, then independent stable GameContexts use the bounded worker pool. Operators can still
 * force {@link Mode#serial} for diagnostics or {@link Mode#parallel} for explicit hosting policy.</p>
 */
public final class InProcessSectorScheduler implements AutoCloseable{
    public enum Mode{
        serial, parallel, auto;

        static Mode parse(String value){
            if(value == null || value.isBlank()) return serial;
            for(Mode mode : values()) if(mode.name().equalsIgnoreCase(value.trim())) return mode;
            throw new IllegalArgumentException("Unknown Shared Campaign in-process scheduler mode: " + value);
        }
    }

    public record Metrics(Mode configuredMode, int parallelism, int registeredRuntimes, long frames,
                          long scheduledRuntimeTicks, long parallelBatches, long totalFrameNanos,
                          long deadlineMisses, long skippedInFlightTicks, long rejectedOverloadTicks,
                          int peakConcurrentTicks, int queuedRuntimeTicks,
                          long frameP50Micros, long frameP95Micros, long frameP99Micros,
                          long tickP50Micros, long tickP95Micros, long tickP99Micros,
                          long queueP50Micros, long queueP95Micros, long queueP99Micros){}

    /** Minimal authoritative lane contract implemented by each embedded authoritative world. */
    public interface RuntimeLane{
        boolean parallelTickReady();
        boolean tickFromScheduler();
        void failFromScheduler(Throwable error);
    }

    private static final long frameNanos = 1_000_000_000L / 60L;
    private static final AtomicInteger workerIds = new AtomicInteger();
    private static final InProcessSectorScheduler shared = new InProcessSectorScheduler(
        Mode.parse(System.getProperty("sharedCampaign.inProcess.scheduler", "auto")),
        configuredParallelism(), true);

    private final CopyOnWriteArrayList<RuntimeLane> runtimes = new CopyOnWriteArrayList<>();
    private final Set<RuntimeLane> runtimeInFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<RuntimeLane, TickCost> runtimeTickCosts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService clock;
    private volatile ThreadPoolExecutor workers;
    private final Mode mode;
    private final int parallelism;
    /** Dynamic execution cap used by mobile thermal/memory governors; never exceeds configured parallelism. */
    private volatile int activeParallelismLimit;
    private final int autoCostWarmupSamples;
    private final int autoPromotionConfirmFrames;
    private final int autoDemotionConfirmFrames;
    private final long autoMinExpectedSavingsNanos;
    private int autoBenefitStreak;
    private int autoNoBenefitStreak;
    private boolean autoParallelPromoted;
    private volatile ScheduledFuture<?> task;
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong scheduledRuntimeTicks = new AtomicLong();
    private final AtomicLong parallelBatches = new AtomicLong();
    private final AtomicLong totalFrameNanos = new AtomicLong();
    private final AtomicLong deadlineMisses = new AtomicLong();
    private final AtomicLong skippedInFlightTicks = new AtomicLong();
    private final AtomicLong rejectedOverloadTicks = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LatencyWindow frameLatency = new LatencyWindow(2048);
    private final LatencyWindow tickLatency = new LatencyWindow(4096);
    private final LatencyWindow queueLatency = new LatencyWindow(4096);

    private InProcessSectorScheduler(Mode mode, int parallelism, boolean startClock){
        this.mode = Objects.requireNonNull(mode, "mode");
        this.parallelism = Math.max(1, parallelism);
        this.activeParallelismLimit = this.parallelism;
        this.autoCostWarmupSamples = Math.max(1, Integer.getInteger("sharedCampaign.inProcess.autoCostWarmupSamples", 8));
        this.autoPromotionConfirmFrames = Math.max(1, Integer.getInteger("sharedCampaign.inProcess.autoPromotionConfirmFrames", 10));
        this.autoDemotionConfirmFrames = Math.max(1, Integer.getInteger("sharedCampaign.inProcess.autoDemotionConfirmFrames", 8));
        this.autoMinExpectedSavingsNanos = TimeUnit.MICROSECONDS.toNanos(
            Math.max(0L, Long.getLong("sharedCampaign.inProcess.autoMinExpectedSavingsMicros", 850L)));
        clock = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "SharedCampaign-InProcess-Sectors"));
        // PARALLEL is an explicit operator policy, so its worker pool is eager. AUTO stays completely thread-free
        // until cost calibration actually promotes a batch; this avoids charging the common one/light-Context case
        // for idle worker creation and scheduler/GC noise when no task will ever be submitted.
        workers = mode == Mode.parallel ? newWorkerPool() : null;
        if(startClock) scheduleNextFrame(0L);
    }

    private void scheduleNextFrame(long delayNanos){
        if(closed.get()) return;
        task = clock.schedule(this::clockTurn, Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
    }

    private void clockTurn(){
        if(closed.get()) return;
        long started = System.nanoTime();
        dispatchScheduledFrame();
        long elapsed = System.nanoTime() - started;
        // Do not accumulate fixed-rate debt. A missed frame resumes immediately (with a small yield) rather than
        // replaying an arbitrary backlog of 60 Hz invocations and amplifying overload into a catch-up spiral.
        long delay = elapsed >= frameNanos ? TimeUnit.MILLISECONDS.toNanos(1L) : frameNanos - elapsed;
        scheduleNextFrame(delay);
    }

    private static Thread daemon(Runnable runnable, String name){
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return RuntimeExecutionBudget.configureWorkerThread(thread);
    }

    private static int configuredParallelism(){
        String configured = System.getProperty("sharedCampaign.inProcess.parallelism", "");
        if(!configured.isBlank()){
            try{ return Math.max(1, Integer.parseInt(configured)); }
            catch(NumberFormatException error){ throw new IllegalArgumentException("Invalid sharedCampaign.inProcess.parallelism: " + configured, error); }
        }
        return RuntimeExecutionBudget.defaultSectorParallelism(Runtime.getRuntime().availableProcessors());
    }

    public static InProcessSectorScheduler shared(){ return shared; }

    /** Creates an independently owned scheduler, primarily for explicit hosting configuration and differential tests. */
    public static InProcessSectorScheduler create(Mode mode, int parallelism){
        return new InProcessSectorScheduler(mode, parallelism, true);
    }

    static InProcessSectorScheduler createForTest(Mode mode, int parallelism, boolean startClock){
        return new InProcessSectorScheduler(mode, parallelism, startClock);
    }

    /** Starts the production-style scheduler clock after deterministic test setup has registered every lane. */
    void startClockForTest(){
        if(closed.get()) throw new RejectedExecutionException("In-process Sector scheduler is closed");
        if(task != null) throw new IllegalStateException("In-process Sector scheduler clock is already started");
        scheduleNextFrame(0L);
    }

    public void register(RuntimeLane runtime){
        if(runtime == null) throw new IllegalArgumentException("runtime is required");
        if(closed.get()) throw new RejectedExecutionException("In-process Sector scheduler is closed");
        if(!runtimes.contains(runtime)){
            runtimes.add(runtime);
            resetAutoPromotionState();
        }
    }

    public void unregister(RuntimeLane runtime){
        runtimes.remove(runtime);
        runtimeTickCosts.remove(runtime);
        resetAutoPromotionState();
    }
    public int size(){ return runtimes.size(); }
    public Mode mode(){ return mode; }
    public int parallelism(){ return parallelism; }
    public int activeParallelismLimit(){ return activeParallelismLimit; }

    /**
     * Dynamically reduces (or restores) outer-world concurrency without changing authoritative TPS. A limit of one
     * keeps every context on the scheduler lane; raising it re-enables bounded parallel batches when policy allows.
     */
    public void setActiveParallelismLimit(int limit){
        int clamped = Math.max(1, Math.min(parallelism, limit));
        if(activeParallelismLimit != clamped){
            activeParallelismLimit = clamped;
            resetAutoPromotionState();
        }
    }

    public Metrics metrics(){
        ThreadPoolExecutor pool = workers;
        return new Metrics(mode, parallelism, runtimes.size(), frames.get(), scheduledRuntimeTicks.get(),
            parallelBatches.get(), totalFrameNanos.get(), deadlineMisses.get(), skippedInFlightTicks.get(), rejectedOverloadTicks.get(),
            peakInFlight.get(), pool == null ? 0 : pool.getQueue().size(),
            micros(frameLatency.percentile(.50)), micros(frameLatency.percentile(.95)), micros(frameLatency.percentile(.99)),
            micros(tickLatency.percentile(.50)), micros(tickLatency.percentile(.95)), micros(tickLatency.percentile(.99)),
            micros(queueLatency.percentile(.50)), micros(queueLatency.percentile(.95)), micros(queueLatency.percentile(.99)));
    }

    /** Deterministic blocking hook for isolation/integration tests. The caller must avoid racing the scheduler clock. */
    public void tickOnce(){ tickAllBlocking(); }

    /** Production clock path: a slow runtime stays in-flight and is skipped on later frames instead of blocking siblings. */
    private void dispatchScheduledFrame(){
        if(closed.get()) return;
        long started = System.nanoTime();
        frames.incrementAndGet();
        RuntimeLane[] snapshot = runtimes.toArray(new RuntimeLane[0]);
        if(useParallel(snapshot)){
            int dispatched = 0;
            for(RuntimeLane runtime : snapshot){
                if(!runtimeInFlight.add(runtime)){
                    skippedInFlightTicks.incrementAndGet();
                    continue;
                }
                long queuedAt = System.nanoTime();
                try{
                    workers.execute(() -> {
                        try{ tickOne(runtime, queuedAt); }
                        finally{ runtimeInFlight.remove(runtime); }
                    });
                    dispatched++;
                }catch(RejectedExecutionException overloaded){
                    runtimeInFlight.remove(runtime);
                    rejectedOverloadTicks.incrementAndGet();
                }
            }
            if(dispatched > 0) parallelBatches.incrementAndGet();
        }else{
            // Bootstrap and explicit SERIAL mode stay blocking because legacy generation helpers are not parallel-safe.
            for(RuntimeLane runtime : snapshot) tickOne(runtime, System.nanoTime());
        }
        long elapsed = System.nanoTime() - started;
        totalFrameNanos.addAndGet(elapsed);
        frameLatency.record(elapsed);
        if(elapsed > frameNanos) deadlineMisses.incrementAndGet();
    }

    /** Blocking batch retained for deterministic explicit tickOnce() probes. */
    private void tickAllBlocking(){
        if(closed.get()) return;
        long started = System.nanoTime();
        frames.incrementAndGet();
        RuntimeLane[] snapshot = runtimes.toArray(new RuntimeLane[0]);
        if(useParallel(snapshot)){
            parallelBatches.incrementAndGet();
            // Amortize executor handoff across the frame: use at most one partition per active worker.
            // Per-lane Futures made 8/16-context p95 dominated by
            // queue/barrier overhead even when each authoritative tick itself was sub-millisecond.
            int batchWorkers = blockingBatchWorkers(snapshot);
            long queuedAt = System.nanoTime();
            ArrayList<Future<?>> futures = new ArrayList<>(Math.max(0, batchWorkers - 1));
            // tickOnce() is already a blocking owner-lane call, so let that caller do one deterministic
            // partition itself. Only the remaining partitions pay executor handoff/wakeup cost. Static
            // striding also removes the shared AtomicInteger contention from every tiny real-world lane.
            for(int worker = 1; worker < batchWorkers; worker++){
                final int partition = worker;
                futures.add(workers.submit(() -> {
                    for(int index = partition; index < snapshot.length; index += batchWorkers){
                        tickOne(snapshot[index], queuedAt);
                    }
                }));
            }
            for(int index = 0; index < snapshot.length; index += batchWorkers){
                tickOne(snapshot[index], queuedAt);
            }
            for(Future<?> future : futures){
                try{ future.get(); }
                catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    return;
                }catch(ExecutionException error){
                    Log.err("Shared Campaign in-process scheduler worker failed", error.getCause());
                }
            }
        }else{
            for(RuntimeLane runtime : snapshot) tickOne(runtime, System.nanoTime());
        }
        long elapsed = System.nanoTime() - started;
        totalFrameNanos.addAndGet(elapsed);
        frameLatency.record(elapsed);
        if(elapsed > frameNanos) deadlineMisses.incrementAndGet();
    }

    private boolean useParallel(RuntimeLane[] snapshot){
        // A single authoritative world has nothing to overlap with. Keeping it on the clock/owner lane avoids
        // executor handoff + queue latency and protects the one-Sector p95 regression budget.
        if(snapshot.length < 2 || activeParallelismLimit < 2) return false;
        if(mode != Mode.parallel && mode != Mode.auto) return false;

        // New-sector generation/bootstrap still crosses legacy process-shared content/campaign helpers. Do not let
        // two worlds execute that lifecycle transaction concurrently; once every runtime reports a stable loaded
        // world, ordinary authoritative ticks may overlap on the bounded worker pool.
        for(RuntimeLane runtime : snapshot){
            if(!runtime.parallelTickReady()) return false;
        }

        // PARALLEL is an explicit operator/testing policy and therefore bypasses the adaptive cost gate. AUTO first
        // learns a few inline samples per runtime, then only pays worker handoff/barrier overhead when the ideal
        // parallel critical path is expected to save a material amount of wall time. This avoids the real-world
        // light-Sector cliff where four ~100us ticks cost more to submit/wake/join than to execute serially.
        if(mode == Mode.parallel) return ensureWorkers() != null;
        boolean parallel = autoCostJustifiesParallel(snapshot);
        return parallel && ensureWorkers() != null;
    }

    private ThreadPoolExecutor newWorkerPool(){
        int queueCapacity = Math.max(16, parallelism * 8);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity), r -> daemon(r, "SharedCampaign-InProcess-Worker-" + workerIds.incrementAndGet()),
            new ThreadPoolExecutor.AbortPolicy());
        pool.prestartAllCoreThreads();
        return pool;
    }

    private ThreadPoolExecutor ensureWorkers(){
        ThreadPoolExecutor pool = workers;
        if(pool != null) return pool;
        synchronized(this){
            if(closed.get()) return null;
            if(workers == null) workers = newWorkerPool();
            return workers;
        }
    }

    boolean workerPoolStartedForTest(){ return workers != null; }

    private boolean autoCostJustifiesParallel(RuntimeLane[] snapshot){
        long serialEstimate = 0L, maxLaneEstimate = 0L;
        for(RuntimeLane runtime : snapshot){
            TickCost cost = runtimeTickCosts.get(runtime);
            if(cost == null || cost.samples() < autoCostWarmupSamples){
                resetAutoPromotionState();
                return false;
            }
            long estimate = cost.estimateNanos();
            serialEstimate = saturatedAdd(serialEstimate, estimate);
            maxLaneEstimate = Math.max(maxLaneEstimate, estimate);
        }
        int activeWorkers = Math.max(1, Math.min(activeParallelismLimit, snapshot.length));
        if(activeWorkers < 2){
            resetAutoPromotionState();
            return false;
        }
        long idealBalanced = (serialEstimate + activeWorkers - 1L) / activeWorkers;
        long idealParallel = Math.max(maxLaneEstimate, idealBalanced);
        long expectedSavings = Math.max(0L, serialEstimate - idealParallel);
        boolean beneficial = expectedSavings >= requiredAutoExpectedSavingsNanos(snapshot.length);

        // Do not flip AUTO into the worker pool on a short pathfinding/GC/JIT spike. Promotion requires sustained
        // evidence across several consecutive frames. Once promoted, a short dip is tolerated across a longer demotion window so AUTO does
        // not oscillate between inline and parallel execution when a many-Context batch briefly dips below the threshold. This hysteresis is deliberately
        // state-local to one scheduler; adding/removing a runtime resets it because the batch cost model changed.
        if(beneficial){
            autoNoBenefitStreak = 0;
            if(autoParallelPromoted) return true;
            int requiredPromotionFrames = requiredAutoPromotionFrames(snapshot.length);
            autoBenefitStreak = Math.min(requiredPromotionFrames, autoBenefitStreak + 1);
            if(autoBenefitStreak >= requiredPromotionFrames){
                autoParallelPromoted = true;
                autoBenefitStreak = 0;
                return true;
            }
            return false;
        }

        autoBenefitStreak = 0;
        if(!autoParallelPromoted){
            autoNoBenefitStreak = 0;
            return false;
        }
        autoNoBenefitStreak = Math.min(autoDemotionConfirmFrames, autoNoBenefitStreak + 1);
        if(autoNoBenefitStreak >= autoDemotionConfirmFrames){
            autoParallelPromoted = false;
            autoNoBenefitStreak = 0;
            return false;
        }
        return true;
    }

    private void resetAutoPromotionState(){
        autoBenefitStreak = 0;
        autoNoBenefitStreak = 0;
        autoParallelPromoted = false;
    }

    /**
     * Chooses the number of execution partitions for deterministic blocking batches. The caller is one partition,
     * so every additional partition costs a worker wakeup plus a barrier rendezvous. For light real-world lanes,
     * using every configured worker can therefore increase p95 under host/JVM contention even when parallel work is
     * semantically safe. AUTO selects the partition count with the lowest recent-cost prediction; explicit PARALLEL
     * continues to honor the configured parallelism exactly.
     */
    private int blockingBatchWorkers(RuntimeLane[] snapshot){
        ThreadPoolExecutor pool = workers;
        int maximum = Math.max(1, Math.min(Math.min(activeParallelismLimit, snapshot.length), pool == null ? parallelism : pool.getMaximumPoolSize()));
        if(mode != Mode.auto || maximum <= 2) return maximum;

        long serialEstimate = 0L, maxLaneEstimate = 0L;
        for(RuntimeLane runtime : snapshot){
            TickCost cost = runtimeTickCosts.get(runtime);
            if(cost == null || cost.samples() < autoCostWarmupSamples) return maximum;
            long estimate = cost.estimateNanos();
            serialEstimate = saturatedAdd(serialEstimate, estimate);
            maxLaneEstimate = Math.max(maxLaneEstimate, estimate);
        }

        long handoffPerExtraPartition = TimeUnit.MICROSECONDS.toNanos(
            Math.max(0L, Long.getLong("sharedCampaign.inProcess.blockingPartitionHandoffMicros", 500L)));
        int bestWorkers = 2;
        long bestCost = predictedBlockingBatchNanos(serialEstimate, maxLaneEstimate, bestWorkers, handoffPerExtraPartition);
        for(int candidate = 3; candidate <= maximum; candidate++){
            long cost = predictedBlockingBatchNanos(serialEstimate, maxLaneEstimate, candidate, handoffPerExtraPartition);
            if(cost < bestCost){
                bestCost = cost;
                bestWorkers = candidate;
            }
        }
        return bestWorkers;
    }

    private static long predictedBlockingBatchNanos(long serialEstimate, long maxLaneEstimate, int workers, long handoffPerExtraPartition){
        long balanced = (serialEstimate + workers - 1L) / workers;
        long compute = Math.max(maxLaneEstimate, balanced);
        return saturatedAdd(compute, handoffPerExtraPartition * Math.max(0L, workers - 1L));
    }

    private long requiredAutoExpectedSavingsNanos(int runtimeCount){
        // Keep the conservative calibrated threshold for 2-4 Context batches, where worker handoff can dominate.
        // Larger independent batches amortize that fixed cost across more lanes, so scale the minimum expected
        // savings down with batch size instead of requiring every 8/16-context batch to clear the same 850us bar.
        if(runtimeCount <= 4) return autoMinExpectedSavingsNanos;
        long scaled = (autoMinExpectedSavingsNanos * 4L + runtimeCount - 1L) / runtimeCount;
        return Math.max(TimeUnit.MICROSECONDS.toNanos(100L), Math.min(autoMinExpectedSavingsNanos, scaled));
    }
    private int requiredAutoPromotionFrames(int runtimeCount){
        // Worker handoff is easier to amortize as the independent batch grows. Keep the full confirmation window for
        // small 2-4 Context batches (where pathfinding spikes previously caused false promotion), but shorten it for
        // larger batches so 8/16 stable worlds do not miss real overlap merely because individual ticks are noisy.
        if(runtimeCount <= 4) return autoPromotionConfirmFrames;
        long scaled = ((long)autoPromotionConfirmFrames * 4L + runtimeCount - 1L) / runtimeCount;
        return Math.max(2, (int)Math.min(autoPromotionConfirmFrames, scaled));
    }

    private static long saturatedAdd(long left, long right){
        if(right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private void tickOne(RuntimeLane runtime, long queuedAt){
        long started = System.nanoTime();
        queueLatency.record(Math.max(0L, started - queuedAt));
        int active = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(active, Math::max);
        try{
            if(runtime.tickFromScheduler()) scheduledRuntimeTicks.incrementAndGet();
        }catch(Throwable error){
            runtime.failFromScheduler(error);
        }finally{
            long elapsed = System.nanoTime() - started;
            tickLatency.record(elapsed);
            runtimeTickCosts.computeIfAbsent(runtime, ignored -> new TickCost()).record(elapsed);
            if(elapsed > frameNanos) deadlineMisses.incrementAndGet();
            inFlight.decrementAndGet();
        }
    }


    /**
     * Per-runtime recent tick cost. AUTO uses a small rolling lower-median rather than an EWMA so the first cold
     * content/JIT frames cannot keep a lightweight Sector classified as expensive for dozens of later frames.
     * Runtime lanes are single-writer, while the scheduler clock may read the estimate concurrently.
     */
    private static final class TickCost{
        private final long[] recent = new long[8];
        private final long[] sorted = new long[8];
        private int cursor, size;
        private long totalSamples;
        // AUTO reads these once per lane per frame. Publish a cached robust estimate from the writer so the scheduler
        // decision path does not acquire two monitors, allocate an array and sort it again for every lane. The
        // authoritative tick writer remains synchronized; volatile publication preserves the same lower-median model.
        private volatile long publishedEstimateNanos = 1L;
        private volatile int publishedSamples;

        synchronized void record(long nanos){
            recent[cursor] = Math.max(1L, nanos);
            cursor = (cursor + 1) % recent.length;
            if(size < recent.length) size++;
            totalSamples++;

            System.arraycopy(recent, 0, sorted, 0, size);
            Arrays.sort(sorted, 0, size);
            publishedEstimateNanos = Math.max(1L, sorted[(size - 1) / 2]);
            // Publish samples last: once AUTO observes the warmup count, the corresponding estimate is visible too.
            publishedSamples = (int)Math.min(Integer.MAX_VALUE, totalSamples);
        }

        long estimateNanos(){ return publishedEstimateNanos; }

        int samples(){ return publishedSamples; }
    }

    /** Runs one full headless application turn for a concrete runtime. */
    static void tick(GameContext context){
        RuntimeContexts.run(context, () -> {
            context.drainPostedTasks();
            // A posted orderly-exit callback may dispose this embedded runtime after its final coordinator ACK.
            // Never continue the authoritative tick against already-disposed services/network state.
            if(context.closed()) return;
            Time.updateGlobal();
            context.asyncCore.begin();
            try{
                context.logic.update();
                context.netServer.update();
                SharedCampaignRuntimeState attached = SharedCampaignRuntimeState.find(context);
                if(attached != null) attached.tick();
            }finally{
                context.asyncCore.end();
            }
        });
    }


    private static long micros(long nanos){ return nanos <= 0L ? 0L : nanos / 1_000L; }

    /** Bounded recent-sample window; metrics reads sort a copy and never stall authoritative workers for long. */
    private static final class LatencyWindow{
        private final long[] samples;
        private int size, cursor;
        LatencyWindow(int capacity){ samples = new long[Math.max(32, capacity)]; }
        synchronized void record(long nanos){ samples[cursor] = Math.max(0L, nanos); cursor = (cursor + 1) % samples.length; if(size < samples.length) size++; }
        synchronized long percentile(double percentile){
            if(size == 0) return 0L;
            long[] copy = new long[size];
            if(size < samples.length) System.arraycopy(samples, 0, copy, 0, size);
            else{
                int tail = samples.length - cursor;
                System.arraycopy(samples, cursor, copy, 0, tail);
                System.arraycopy(samples, 0, copy, tail, cursor);
            }
            Arrays.sort(copy);
            int index = (int)Math.ceil(Math.max(0d, Math.min(1d, percentile)) * copy.length) - 1;
            return copy[Math.max(0, Math.min(copy.length - 1, index))];
        }
    }

    @Override public void close(){
        if(!closed.compareAndSet(false, true)) return;
        if(task != null) task.cancel(false);
        runtimes.clear();
        runtimeTickCosts.clear();
        runtimeInFlight.clear();
        clock.shutdownNow();
        ThreadPoolExecutor pool = workers;
        if(pool != null){
            pool.shutdownNow();
            try{ pool.awaitTermination(5L, TimeUnit.SECONDS); }
            catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); }
        }
    }
}
