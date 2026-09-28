package mindustry.runtime;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Explicit metadata envelope for work that may leave a GameContext owner lane.
 *
 * <p>The owner object and its monotonic incarnation token are captured together; a reused textual context ID is not
 * sufficient authority. Optional deadline/priority/cancellation metadata is kept on every queued task so process-owned
 * pools can reject stale work without consulting ambient {@code Vars.game()} state.</p>
 */
public final class ContextTask implements Runnable{
    public enum Priority{ background, normal, latency }

    public static final class CancellationToken{
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public boolean cancel(){ return cancelled.compareAndSet(false, true); }
        public boolean cancelled(){ return cancelled.get(); }
    }

    private final GameContext owner;
    private final long ownerGeneration;
    private final String subsystem;
    private final Priority priority;
    private final long deadlineNanos;
    private final CancellationToken cancellation;
    private final Runnable body;

    private ContextTask(GameContext owner, String subsystem, Priority priority, long deadlineNanos,
                        CancellationToken cancellation, Runnable body){
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ownerGeneration = owner.generation();
        this.subsystem = subsystem == null || subsystem.isBlank() ? "runtime" : subsystem;
        this.priority = priority == null ? Priority.normal : priority;
        this.deadlineNanos = Math.max(0L, deadlineNanos);
        this.cancellation = cancellation == null ? new CancellationToken() : cancellation;
        this.body = Objects.requireNonNull(body, "body");
    }

    public static ContextTask of(GameContext owner, String subsystem, Runnable body){
        return new ContextTask(owner, subsystem, Priority.normal, 0L, new CancellationToken(), body);
    }

    public static ContextTask of(GameContext owner, String subsystem, Priority priority, Duration deadlineFromNow,
                                 CancellationToken cancellation, Runnable body){
        long deadline = deadlineFromNow == null ? 0L : saturatingAdd(System.nanoTime(), Math.max(0L, deadlineFromNow.toNanos()));
        return new ContextTask(owner, subsystem, priority, deadline, cancellation, body);
    }

    public GameContext owner(){ return owner; }
    public long ownerGeneration(){ return ownerGeneration; }
    public String subsystem(){ return subsystem; }
    public Priority priority(){ return priority; }
    public long deadlineNanos(){ return deadlineNanos; }
    public CancellationToken cancellation(){ return cancellation; }

    public boolean staleOwner(){ return owner.closed() || owner.generation() != ownerGeneration; }
    public boolean expired(){ return deadlineNanos != 0L && System.nanoTime() > deadlineNanos; }
    public boolean cancelled(){ return cancellation.cancelled(); }
    public boolean eligible(){ return !staleOwner() && !expired() && !cancelled(); }

    @Override public void run(){ body.run(); }

    private static long saturatingAdd(long left, long right){
        long result = left + right;
        if(((left ^ result) & (right ^ result)) < 0) return Long.MAX_VALUE;
        return result;
    }
}
