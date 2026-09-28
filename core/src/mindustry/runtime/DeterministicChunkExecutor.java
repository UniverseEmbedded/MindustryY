package mindustry.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Deterministic snapshot/compute/barrier helper for one authoritative {@link GameContext}.
 *
 * <p>The authoritative owner collects an immutable/read-only input snapshot first. This helper partitions that snapshot
 * into stable contiguous ranges, computes ranges on {@link RuntimeWorkerPool}, waits at a barrier, and returns results in
 * exactly the original input order. It never applies game mutations from worker threads. Callers must perform their
 * mutation/apply phase after this method returns on the authoritative owner lane.</p>
 *
 * <p>This is deliberately not a general parallel-stream wrapper: the ordering and owner contract are part of the API.
 * Small workloads stay serial to avoid making normal Mindustry ticks slower.</p>
 */
public final class DeterministicChunkExecutor{
    private static final int defaultMinParallelItems = Integer.getInteger("mindustry.runtime.chunk.minItems", 96);
    private static final int maxChunksPerContext = Integer.getInteger("mindustry.runtime.chunk.maxChunks", 4);

    private DeterministicChunkExecutor(){}

    public static <I, O> List<O> mapOrdered(Collection<? extends I> input, Function<? super I, ? extends O> compute){
        return mapOrdered(RuntimeContexts.requireCurrent(), "deterministic-chunk", input, defaultMinParallelItems, compute);
    }

    public static <I, O> List<O> mapOrdered(GameContext owner, String subsystem, Collection<? extends I> input,
                                             int minParallelItems, Function<? super I, ? extends O> compute){
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(compute, "compute");

        ArrayList<I> snapshot = new ArrayList<>(input);
        int size = snapshot.size();
        if(size == 0) return List.of();

        RuntimeWorkerPool workers = RuntimeWorkerPool.sharedCompute();
        int threshold = Math.max(1, minParallelItems);
        int chunks = Math.min(Math.min(workers.parallelism(), Math.max(1, maxChunksPerContext)),
            Math.max(1, (size + threshold - 1) / threshold));
        if(chunks <= 1 || size < threshold) return serial(owner, snapshot, compute);

        Object[] results = new Object[size];
        ArrayList<Future<?>> futures = new ArrayList<>(chunks);
        int submittedEnd = 0;
        try{
            for(int chunk = 0; chunk < chunks; chunk++){
                int from = chunk * size / chunks;
                int to = (chunk + 1) * size / chunks;
                if(from >= to) continue;
                final int start = from, end = to;
                futures.add(workers.submit(owner, subsystem, () -> {
                    for(int i = start; i < end; i++) results[i] = compute.apply(snapshot.get(i));
                }));
                submittedEnd = to;
            }
        }catch(RejectedExecutionException rejected){
            // Capacity pressure is not a correctness failure. Finish accepted ranges at the barrier and compute only
            // ranges that were never submitted on the owner lane; no input is evaluated twice concurrently.
            await(futures);
            final int tailStart = submittedEnd;
            try(RuntimeContexts.Scope ignored = RuntimeContexts.enter(owner)){
                for(int i = tailStart; i < size; i++) results[i] = compute.apply(snapshot.get(i));
            }
            return ordered(results);
        }

        await(futures);
        return ordered(results);
    }

    /** Parallel predicate compute with deterministic, input-order output. */
    public static <I> List<I> filterOrdered(GameContext owner, String subsystem, Collection<? extends I> input,
                                             int minParallelItems, Predicate<? super I> predicate){
        ArrayList<I> snapshot = new ArrayList<>(input);
        List<Boolean> keep = mapOrdered(owner, subsystem, snapshot, minParallelItems, predicate::test);
        ArrayList<I> result = new ArrayList<>();
        for(int i = 0; i < snapshot.size(); i++) if(Boolean.TRUE.equals(keep.get(i))) result.add(snapshot.get(i));
        return result;
    }

    private static <I, O> List<O> serial(GameContext owner, List<I> snapshot, Function<? super I, ? extends O> compute){
        ArrayList<O> result = new ArrayList<>(snapshot.size());
        try(RuntimeContexts.Scope ignored = RuntimeContexts.enter(owner)){
            for(I item : snapshot) result.add(compute.apply(item));
        }
        return result;
    }

    private static void await(List<Future<?>> futures){
        Throwable firstFailure = null;
        boolean interrupted = false;
        for(Future<?> future : futures){
            boolean drained = false;
            while(!drained){
                try{
                    future.get();
                    drained = true;
                }catch(InterruptedException interruption){
                    // A deterministic phase is a real barrier: remember the interruption but drain all already-started
                    // siblings before returning control to a caller that may dispose/reuse their GameContext state.
                    interrupted = true;
                    Thread.interrupted();
                }catch(ExecutionException execution){
                    if(firstFailure == null) firstFailure = execution.getCause();
                    drained = true;
                }catch(CancellationException cancelled){
                    if(firstFailure == null) firstFailure = cancelled;
                    drained = true;
                }
            }
        }
        if(interrupted){
            Thread.currentThread().interrupt();
            if(firstFailure == null) firstFailure = new InterruptedException("deterministic chunk phase interrupted");
        }
        if(firstFailure != null){
            if(firstFailure instanceof RuntimeException runtime) throw runtime;
            if(firstFailure instanceof Error error) throw error;
            throw new RuntimeException("deterministic chunk phase failed", firstFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <O> List<O> ordered(Object[] values){
        ArrayList<O> result = new ArrayList<>(values.length);
        for(Object value : values) result.add((O)value);
        return result;
    }
}
