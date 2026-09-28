package mindustry.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Process-owned bounded storage/encoding pool.
 *
 * <p>This pool is intentionally separate from gameplay compute/path work: slow filesystem parsing, hashing and
 * encoding must not consume latency-sensitive compute workers. Tasks still carry an explicit {@link GameContext}
 * owner/generation through {@link ContextTask}, so a late storage callback cannot execute against a recycled Sector.
 * The process pool is fixed-size; callers that need to submit a large deterministic batch should use
 * {@link #invokeAllOrdered(GameContext, String, List)}, which keeps only a bounded window in flight.</p>
 */
public final class RuntimeStorageExecutor{
    private static final int defaultParallelism = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private static final RuntimeWorkerPool shared = new RuntimeWorkerPool(
        Integer.getInteger("mindustry.runtime.storage.parallelism", defaultParallelism),
        Integer.getInteger("mindustry.runtime.storage.contextQuota", 8),
        "Mindustry-Runtime-Storage-");

    private RuntimeStorageExecutor(){}

    public static RuntimeWorkerPool pool(){ return shared; }
    public static int parallelism(){ return shared.parallelism(); }
    public static RuntimeWorkerPool.Metrics metrics(){ return shared.metrics(); }

    /** Submit one storage/encoding action without using the JVM common pool. */
    public static <T> Future<T> submit(GameContext owner, String subsystem, Callable<T> callable){
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(callable, "callable");
        ContextTask.CancellationToken token = new ContextTask.CancellationToken();
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();
        ContextTask envelope = ContextTask.of(owner, subsystem, ContextTask.Priority.background, null, token, () -> {
            ran.set(true);
            try{ value.set(callable.call()); }
            catch(Throwable error){ failure.set(error); }
        });
        Future<?> delegate = shared.submit(envelope);
        return new Future<>(){
            @Override public boolean cancel(boolean mayInterruptIfRunning){ token.cancel(); return delegate.cancel(mayInterruptIfRunning); }
            @Override public boolean isCancelled(){ return delegate.isCancelled(); }
            @Override public boolean isDone(){ return delegate.isDone(); }
            @Override public T get() throws InterruptedException, ExecutionException{
                delegate.get();
                return result();
            }
            @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException{
                delegate.get(timeout, unit);
                return result();
            }
            private T result() throws ExecutionException{
                if(delegate.isCancelled()) throw new CancellationException("storage task cancelled: " + owner.id + "/" + subsystem);
                Throwable error = failure.get();
                if(error != null) throw new ExecutionException(error);
                if(!ran.get()) throw new CancellationException("storage task became stale before execution: " + owner.id + "/" + subsystem);
                return value.get();
            }
        };
    }

    /**
     * Runs a large independent storage batch with bounded in-flight work and returns results in input order.
     * This blocking coordination helper is for startup/save preparation paths, not authoritative simulation ticks.
     */
    public static <T> List<T> invokeAllOrdered(GameContext owner, String subsystem, List<? extends Callable<T>> tasks) throws Exception{
        Objects.requireNonNull(tasks, "tasks");
        if(tasks.isEmpty()) return List.of();
        int window = Math.max(1, parallelism());
        ArrayList<T> out = new ArrayList<>(tasks.size());
        for(int base = 0; base < tasks.size(); base += window){
            int end = Math.min(tasks.size(), base + window);
            ArrayList<Future<T>> futures = new ArrayList<>(end - base);
            for(int i = base; i < end; i++) futures.add(submit(owner, subsystem, tasks.get(i)));
            try{
                for(Future<T> future : futures) out.add(future.get());
            }catch(Throwable failure){
                for(Future<T> future : futures) future.cancel(true);
                if(failure instanceof ExecutionException execution && execution.getCause() instanceof Exception exception) throw exception;
                if(failure instanceof Exception exception) throw exception;
                throw new RuntimeException(failure);
            }
        }
        return out;
    }
}
