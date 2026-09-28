package mindustry.runtime;

import arc.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Process-owned bounded scheduler for per-GameContext pathfinding lanes.
 *
 * <p>Path state remains owned by each Pathfinder/ControlPathfinder instance; only execution threads are shared.
 * Each periodic lane is rebound to its explicit GameContext and never overlaps with itself because
 * {@link ScheduledExecutorService#scheduleWithFixedDelay} schedules the next turn after the previous completes.</p>
 */
public final class RuntimePathExecutor implements AutoCloseable{
    public record Metrics(int parallelism, int registeredLanes, int activeWorkers, long executedTurns,
                          long rejected, long failedTurns){}

    public final class Handle implements AutoCloseable{
        private final GameContext owner;
        private final String subsystem;
        private final long ownerGeneration;
        private final AtomicBoolean closed = new AtomicBoolean();
        /** Serializes one lane turn with close(), so world-shaped state can be reset only after the active turn retires. */
        private final Object turnLock = new Object();
        private volatile ScheduledFuture<?> future;

        private Handle(GameContext owner, String subsystem){this.owner=owner;this.ownerGeneration=owner.generation();this.subsystem=subsystem;}
        public GameContext owner(){return owner;}
        public String subsystem(){return subsystem;}
        public long ownerGeneration(){return ownerGeneration;}
        public boolean closed(){return closed.get();}
        @Override public void close(){
            if(!closed.compareAndSet(false,true))return;
            ScheduledFuture<?> current=future;if(current!=null)current.cancel(false);
            // Wait for an already-running turn to leave the critical section before callers clear/reset Context-owned
            // path state. The monitor is re-entrant, so closing from the lane itself cannot deadlock.
            synchronized(turnLock){}
            handles.remove(this);
        }
    }

    private static final RuntimePathExecutor shared = new RuntimePathExecutor(
        Integer.getInteger("mindustry.runtime.path.parallelism", defaultParallelism()), "Mindustry-Runtime-Path-");

    private final ScheduledThreadPoolExecutor executor;
    private final Set<Handle> handles=ConcurrentHashMap.newKeySet();
    private final AtomicLong executed=new AtomicLong(), rejected=new AtomicLong(), failed=new AtomicLong();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final int parallelism;

    public static RuntimePathExecutor shared(){return shared;}

    private static int defaultParallelism(){
        return RuntimeExecutionBudget.defaultPathParallelism(Runtime.getRuntime().availableProcessors());
    }

    public RuntimePathExecutor(int parallelism,String threadPrefix){
        this.parallelism=Math.max(1,parallelism);
        String prefix=threadPrefix==null||threadPrefix.isBlank()?"Mindustry-Runtime-Path-":threadPrefix;
        AtomicInteger ids=new AtomicInteger();
        executor=new ScheduledThreadPoolExecutor(this.parallelism,r->{
            Thread thread=new Thread(r,prefix+ids.incrementAndGet());thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return RuntimeExecutionBudget.configureWorkerThread(thread);
        });
        executor.setRemoveOnCancelPolicy(true);
        // Scheduled lanes create workers on demand. Retire them when every runtime has been idle/closed so a lobby or
        // coordinator process does not permanently retain path-thread native stacks.
        executor.setKeepAliveTime(Math.max(1L, Long.getLong("mindustry.runtime.path.idleSeconds", 30L)), TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
    }

    public Handle schedule(GameContext owner,String subsystem,long intervalMillis,Runnable turn){
        Objects.requireNonNull(owner,"owner");Objects.requireNonNull(turn,"turn");
        if(closed.get()||owner.closed()){rejected.incrementAndGet();throw new RejectedExecutionException("path pool or owner closed: "+owner.id);}
        Handle handle=new Handle(owner,subsystem==null?"path":subsystem);
        handles.add(handle);
        try{
            long interval=Math.max(1L,intervalMillis);
            handle.future=executor.scheduleWithFixedDelay(()->{
                synchronized(handle.turnLock){
                    if(handle.closed.get()||owner.closed()||owner.generation()!=handle.ownerGeneration){handle.close();return;}
                    try{
                        RuntimeContexts.run(owner,turn);executed.incrementAndGet();
                    }catch(Throwable error){
                        failed.incrementAndGet();
                        // Preserve the legacy path threads' error-containment semantics: one bad turn is logged but does
                        // not silently kill the recurring lane. The owning runtime can still close the handle normally.
                        Log.err("Runtime path lane failed: @/@",owner.id,handle.subsystem);
                        Log.err(error);
                    }
                }
            },0L,interval,TimeUnit.MILLISECONDS);
            return handle;
        }catch(RejectedExecutionException error){
            handles.remove(handle);rejected.incrementAndGet();throw error;
        }
    }

    public void release(GameContext owner){
        if(owner==null)return;
        for(Handle handle:List.copyOf(handles))if(handle.owner==owner)handle.close();
    }

    public Metrics metrics(){return new Metrics(parallelism,handles.size(),executor.getActiveCount(),executed.get(),rejected.get(),failed.get());}

    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        for(Handle handle:List.copyOf(handles))handle.close();
        executor.shutdownNow();
    }
}
