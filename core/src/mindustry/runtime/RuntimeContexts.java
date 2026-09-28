package mindustry.runtime;

import arc.*;
import arc.math.*;
import arc.util.*;

import java.util.*;

/**
 * Runtime-context binding used during the global-singleton migration.
 *
 * The primary context preserves the historical single-world startup path. Explicit Sector schedulers bind a concrete
 * GameContext around every game-affecting task. The binding is not itself world state and must never be used to swap one
 * shared World object between Sectors.
 */
public final class RuntimeContexts{
    private static final GameContext primary = new GameContext("primary");
    private static final ThreadLocal<GameContext> current = new ThreadLocal<>();
    /** Retained so the primary lane's permanent simulation bindings are explicit instead of discarded scopes. */
    private static Time.Scope primaryTimeScope;
    private static Events.Scope primaryEventScope;

    static{
        // Arc stays engine-agnostic: Mindustry supplies the current runtime ownership resolver. Explicit Arc scopes
        // still override these defaults, which keeps tests/tools able to bind standalone Arc contexts.
        Events.setDefaultBusProvider(() -> requireCurrent().events);
        Time.setDefaultContextProvider(() -> requireCurrent().time);
    }

    private RuntimeContexts(){}

    public static GameContext primary(){
        return primary;
    }

    /**
     * Compatibility symbol retained for source/ABI stability. It is intentionally strict: callers without an explicit
     * runtime owner fail instead of silently mutating the primary world. Production code should use
     * {@link #requireCurrent()} or carry an explicit owner.
     */
    @Deprecated
    public static GameContext current(){
        return requireCurrent();
    }

    /** Returns the explicitly-bound runtime, or {@code null} when this thread has no runtime scope. */
    public static GameContext bound(){
        return current.get();
    }

    /**
     * Strict resolver for all game/runtime work. There is no application-thread or JVM-main fallback: every runtime
     * lane, including the primary application/test lane, must be explicitly bound.
     */
    public static GameContext requireCurrent(){
        GameContext value = current.get();
        if(value != null) return value;
        throw new IllegalStateException("No GameContext is explicitly bound to thread: " + Thread.currentThread().getName());
    }

    public static boolean hasExplicitCurrent(){
        return current.get() != null;
    }

    /**
     * Explicitly marks the calling application/test lane as the primary authoritative runtime. Production entrypoints and application lanes call
     * this before their first runtime access; {@code Vars.init()} repeats it idempotently. Headless test harnesses call it on their controlling test lane after bootstrapping.
     * Background threads must never call this as a substitute for capturing their real owner.
     */
    public static void bindPrimaryThread(){
        GameContext value = current.get();
        if(value != null && value != primary) throw new IllegalStateException("Cannot replace bound GameContext " + value.id + " with primary");
        current.set(primary);
        Mathf.bindRand(primary.mathRand);
        // GameContext.time and GameContext.events are final, so binding them once resolves to exactly the same objects
        // the implicit default providers would return below. The difference is cost: without the binding every
        // Time.delta()/Time.time()/Events.fire() on this lane pays a ThreadLocal miss, a volatile provider read and a
        // second ThreadLocal lookup. The scopes stay open for the lifetime of the lane; explicit nested scopes still
        // restore these bindings when they close.
        if(Time.current() != primary.time) primaryTimeScope = Time.enter(primary.time);
        if(Events.current() != primary.events) primaryEventScope = Events.enter(primary.events);
    }

    public static boolean isPrimary(){
        return requireCurrent() == primary;
    }

    public static Scope enter(GameContext context){
        Objects.requireNonNull(context);
        if(context.closed()) throw new java.util.concurrent.RejectedExecutionException("GameContext is closed: " + context.id);
        GameContext previous = current.get();
        Rand previousRand = Mathf.bindRand(context.mathRand);
        current.set(context);
        return new Scope(previous, previousRand);
    }

    public static void run(GameContext context, Runnable runnable){
        try(Scope ignored = enter(context)){
            runnable.run();
        }
    }


    /** Posts work back to the owning runtime. Primary UI work uses the Arc application queue; embedded runtimes use
     * their own simulation queue so ambient application-thread state can never select the wrong Sector. */
    public static void post(Runnable runnable){
        post(requireCurrent(), runnable);
    }

    public static void post(GameContext owner, Runnable runnable){
        Objects.requireNonNull(owner);
        Runnable captured = capture(owner, runnable);
        if(owner == primary && Core.app != null) Core.app.post(captured);
        else owner.post(captured);
    }

    /** Captures the current runtime so delayed/async work cannot accidentally run against another Sector. */
    public static Runnable capture(Runnable runnable){
        Objects.requireNonNull(runnable);
        return capture(requireCurrent(), runnable);
    }

    /**
     * Captures an explicit owner for callbacks that cross executor/network/application-thread boundaries.
     *
     * <p>The ownership check is deliberately repeated when the callback actually starts. A callback may have been
     * accepted by an executor while the owner was live and then sit in that executor until {@link GameContext#dispose()}
     * has entered its closing phase. Running it at that point would expose a half-disposed runtime graph. Teardown itself uses {@link #run(GameContext, Runnable)} directly and therefore remains able to enter the
     * closing context; ordinary captured application work fails closed.</p>
     */
    public static Runnable capture(GameContext owner, Runnable runnable){
        Objects.requireNonNull(owner);
        Objects.requireNonNull(runnable);
        long generation = owner.generation();
        return () -> {
            if(!owner.acceptingWork() || owner.generation() != generation){
                throw new java.util.concurrent.RejectedExecutionException("GameContext no longer accepts captured work: " + owner.id);
            }
            run(owner, runnable);
        };
    }

    public static final class Scope implements AutoCloseable{
        private final GameContext previous;
        private final Rand previousRand;
        private boolean closed;

        private Scope(GameContext previous, Rand previousRand){
            this.previous = previous;
            this.previousRand = previousRand;
        }

        @Override
        public void close(){
            if(closed) return;
            closed = true;
            if(previous == null) current.remove();
            else current.set(previous);
            Mathf.bindRand(previousRand);
        }
    }
}
