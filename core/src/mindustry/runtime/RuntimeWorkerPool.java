package mindustry.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Process-owned bounded worker pool for runtime-local pure/async compute.
 *
 * <p>Every task has an explicit {@link GameContext} owner. The owner is rebound on the worker thread before execution
 * and a closed owner rejects/abandons queued work, so a background callback cannot mutate a recycled sibling Sector.
 * A per-context permit budget prevents one busy Sector from filling the global queue and starving smaller contexts.</p>
 */
public final class RuntimeWorkerPool implements AutoCloseable{
    public record Metrics(int parallelism, int queueCapacity, int contextQuota, int activeWorkers, int queuedTasks, long submitted, long completed,
                          long rejected, long cancelledClosedContext, long cancelledToken, long expiredDeadline, long staleGeneration,
                          int trackedContexts){}

    private static final RuntimeWorkerPool sharedCompute = new RuntimeWorkerPool(
        Integer.getInteger("mindustry.runtime.compute.parallelism", defaultParallelism()),
        Integer.getInteger("mindustry.runtime.compute.contextQuota", 4),
        "Mindustry-Runtime-Compute-");

    private final ThreadPoolExecutor executor;
    private final int parallelism, contextQuota, queueCapacity;
    private final ConcurrentHashMap<GameContext, Semaphore> permits = new ConcurrentHashMap<>();
    /** Global outstanding-task admission bound. PriorityBlockingQueue itself is unbounded, so this semaphore is the hard cap. */
    private final Semaphore globalAdmission;
    private final AtomicLong enqueueSequence = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong(), completed = new AtomicLong(), rejected = new AtomicLong(), cancelledClosed = new AtomicLong(),
        cancelledToken = new AtomicLong(), expiredDeadline = new AtomicLong(), staleGeneration = new AtomicLong();
    private final AtomicInteger workerIds = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public static RuntimeWorkerPool sharedCompute(){ return sharedCompute; }

    private static int defaultParallelism(){
        // Do not create O(host CPU) workers on large CI/server hosts. P19/P21 benchmark gates can override this
        // explicitly, while the production default remains process-bounded and conservative.
        return RuntimeExecutionBudget.defaultComputeParallelism(Runtime.getRuntime().availableProcessors());
    }

    public RuntimeWorkerPool(int parallelism, int contextQuota, String threadPrefix){
        this.parallelism = Math.max(1, parallelism);
        this.contextQuota = Math.max(1, contextQuota);
        String prefix = threadPrefix == null || threadPrefix.isBlank() ? "Mindustry-Runtime-Worker-" : threadPrefix;
        this.queueCapacity = Math.max(this.parallelism * 2, this.parallelism * this.contextQuota * 4);
        this.globalAdmission = new Semaphore(this.parallelism + this.queueCapacity, true);
        long idleSeconds = Math.max(1L, Long.getLong("mindustry.runtime.compute.idleSeconds", 30L));
        executor = new ThreadPoolExecutor(this.parallelism, this.parallelism, idleSeconds, TimeUnit.SECONDS,
            new PriorityBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, prefix + workerIds.incrementAndGet());
                thread.setDaemon(true);
                return RuntimeExecutionBudget.configureWorkerThread(thread);
            });
        // Keep idle processes cheap: workers are created on first demand and retire after a bounded idle period.
        // GameContext state remains independent; only process-owned execution threads are elastic.
        executor.allowCoreThreadTimeOut(true);
    }

    /** Submits one explicitly-owned task. Backpressure is bounded per context rather than growing per Sector. */
    public Future<?> submit(GameContext owner, String subsystem, Runnable runnable){
        return submit(ContextTask.of(owner, subsystem, runnable));
    }

    /** Submits an explicitly-owned task envelope carrying generation/deadline/priority/cancellation metadata. */
    public Future<?> submit(ContextTask task){
        Objects.requireNonNull(task, "task");
        GameContext owner = task.owner();
        ensureEligible(task);
        if(closed.get()){
            rejected.incrementAndGet();
            throw new RejectedExecutionException("runtime worker pool is closed: " + owner.id);
        }

        Semaphore quota = permits.computeIfAbsent(owner, ignored -> new Semaphore(contextQuota, true));
        // Never block an authoritative game/scheduler thread on background capacity. Per-context outstanding work is
        // bounded, so one heavy Sector cannot fill the process queue ahead of a latency-sensitive small Sector.
        if(!quota.tryAcquire()){
            rejected.incrementAndGet();
            throw new RejectedExecutionException("runtime worker context quota exhausted: " + owner.id);
        }
        boolean globalPermit = false;
        try{
            ensureEligible(task);
            if(!globalAdmission.tryAcquire()){
                rejected.incrementAndGet();
                throw new RejectedExecutionException("runtime worker global capacity exhausted: " + owner.id);
            }
            globalPermit = true;
            submitted.incrementAndGet();
            QueuedTask queued = new QueuedTask(task, enqueueSequence.incrementAndGet(), quota);
            executor.execute(queued);
            return queued;
        }catch(RejectedExecutionException error){
            quota.release();
            if(globalPermit) globalAdmission.release();
            // ensureEligible()/capacity checks have already counted semantic/global rejection; executor shutdown has not.
            if(task.eligible() && !closed.get() && globalPermit) rejected.incrementAndGet();
            throw error;
        }
    }


    /** Priority-aware FutureTask. Higher semantic priority runs first; FIFO is preserved within one priority. */
    private final class QueuedTask extends FutureTask<Void> implements Comparable<QueuedTask>{
        private final ContextTask task;
        private final long sequence;
        private final Semaphore ownerQuota;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private QueuedTask(ContextTask task, long sequence, Semaphore ownerQuota){
            super(() -> {
                if(eligibleAtExecution(task)) RuntimeContexts.run(task.owner(), task);
            }, null);
            this.task = task;
            this.sequence = sequence;
            this.ownerQuota = ownerQuota;
        }

        @Override public int compareTo(QueuedTask other){
            int byPriority = Integer.compare(priorityRank(other.task.priority()), priorityRank(task.priority()));
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }

        @Override public void run(){
            if(!started.compareAndSet(false, true)) return;
            try{
                super.run();
            }finally{
                releasePhysicalPermits();
            }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning){
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if(cancelled) executor.remove(this);
            return cancelled;
        }

        @Override protected void done(){
            // FutureTask marks a running Future cancelled before its callable has physically returned. Releasing quota
            // here would admit replacement CPU work concurrently with a cancelled-but-still-running task. Queued tasks
            // never enter run(), so they release immediately; running tasks release from run()'s finally block.
            if(!started.get()) releasePhysicalPermits();
        }

        private void releasePhysicalPermits(){
            if(!released.compareAndSet(false, true)) return;
            completed.incrementAndGet();
            ownerQuota.release();
            globalAdmission.release();
            GameContext owner = task.owner();
            if(!owner.acceptingWork() && ownerQuota.availablePermits() == contextQuota) permits.remove(owner, ownerQuota);
        }
    }

    private static int priorityRank(ContextTask.Priority priority){
        return switch(priority){
            case latency -> 2;
            case normal -> 1;
            case background -> 0;
        };
    }

    private void ensureEligible(ContextTask task){
        String reason = null;
        if(task.cancelled()){
            cancelledToken.incrementAndGet(); reason = "task cancellation token is closed";
        }else if(task.expired()){
            expiredDeadline.incrementAndGet(); reason = "task deadline expired";
        }else if(!task.owner().acceptingWork()){
            cancelledClosed.incrementAndGet(); reason = "task owner is closing or closed";
        }else if(task.owner().generation() != task.ownerGeneration()){
            staleGeneration.incrementAndGet(); reason = "task owner generation is stale";
        }
        if(reason != null){
            rejected.incrementAndGet();
            throw new RejectedExecutionException(reason + ": " + task.owner().id + "/" + task.subsystem());
        }
    }

    private boolean eligibleAtExecution(ContextTask task){
        if(task.cancelled()){ cancelledToken.incrementAndGet(); return false; }
        if(task.expired()){ expiredDeadline.incrementAndGet(); return false; }
        if(!task.owner().acceptingWork()){ cancelledClosed.incrementAndGet(); return false; }
        if(task.owner().generation() != task.ownerGeneration()){ staleGeneration.incrementAndGet(); return false; }
        return true;
    }

    public Metrics metrics(){
        return new Metrics(parallelism, queueCapacity, contextQuota, executor.getActiveCount(), executor.getQueue().size(), submitted.get(), completed.get(),
            rejected.get(), cancelledClosed.get(), cancelledToken.get(), expiredDeadline.get(), staleGeneration.get(), permits.size());
    }

    public int parallelism(){ return parallelism; }

    /** Drops bookkeeping once a context has disposed. Already-running tasks remain responsible for normal completion. */
    public void release(GameContext owner){
        Semaphore quota = permits.get(owner);
        if(quota != null && quota.availablePermits() == contextQuota) permits.remove(owner, quota);
    }

    @Override public void close(){
        if(!closed.compareAndSet(false, true)) return;
        for(Runnable queued : executor.shutdownNow()){
            if(queued instanceof Future<?> future) future.cancel(false);
        }
        permits.clear();
    }
}
