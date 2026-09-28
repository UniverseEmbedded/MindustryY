package mindustry.runtime;

import arc.*;
import arc.math.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.async.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.net.*;
import mindustry.type.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Mutable runtime state owned by one live Mindustry game instance.
 *
 * <p>The static {@code Vars} access layer remains a migration facade; mutable world/gameplay state is owned by a
 * {@code GameContext}. Feature layers may attach additional context-local state through {@link #localState(Object,
 * java.util.function.Supplier)} without introducing reverse dependencies into this package.</p>
 */
public final class GameContext{
    /** Optional lifecycle for context-local service bundles that own resources/listeners. */
    public interface LocalStateLifecycle{
        void dispose();
    }

    private static final AtomicLong generationSequence = new AtomicLong();

    public final String id;
    /** Monotonic process-local identity for async work; reused textual IDs never authorize stale callbacks. */
    private final long generation = generationSequence.incrementAndGet();

    public GameState state;
    public World world;
    public Universe universe;
    public Net net;
    public EntityCollisions collisions;
    public Waves waves;
    public AsyncCore asyncCore;
    public GlobalVars logicVars;
    public AvoidanceProcess avoidance;
    /** Unit physics is eagerly owned by the context, matching upstream Vars.unitPhysics non-null semantics. */
    public PhysicsProcess unitPhysics = new PhysicsProcess();
    public WaveSpawner spawner;
    public BlockIndexer indexer;
    public Pathfinder pathfinder;
    public ControlPathfinder controlPath;
    public FogControl fogControl;
    public Logic logic;
    public NetServer netServer;
    public NetClient netClient;
    public RuntimeStorage storage;

    /** A graphical process may still host headless contexts; this flag is therefore runtime-local. */
    public boolean headless;
    /** Runtime-owned orderly exit hook. */
    public Runnable exitHandler = () -> {};

    /** Opaque generated entity-group context; created lazily by generated Groups.current(). */
    public Object groups;
    private int lastEntityId;
    private int lightningSeed;
    private int powerGraphId;

    private final ConcurrentLinkedQueue<Runnable> postedTasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger gameplayAudioSuppressionDepth = new AtomicInteger();

    /** Runtime-local gameplay event bus; process/global observers are inherited through the parent bus. */
    public final Events.EventBus events = new Events.EventBus(Events.global());
    /** Runtime-local gameplay clock and delayed-task queue. */
    public final Time.Context time = new Time.Context();
    /** Context-owned general RNG used by Mathf helpers while this runtime is bound. */
    public final Rand mathRand = new Rand();

    /** Runtime-local campaign statistics keyed by their stat-owning Planet content object. */
    private final ConcurrentHashMap<Planet, CampaignStats> campaignStats = new ConcurrentHashMap<>();
    /** Mutable campaign summaries owned by this runtime. Sector content objects themselves are process-shared. */
    private final ConcurrentHashMap<Sector, SectorInfo> sectorInfos = new ConcurrentHashMap<>();
    /** Dynamic Sector threat is runtime state; Sector.threat remains primary/profile metadata. */
    private final ConcurrentHashMap<Sector, Float> sectorThreats = new ConcurrentHashMap<>();

    /** Subsystem-owned state that follows this context across worker-thread migration. */
    private final ConcurrentHashMap<Object, Object> localStates = new ConcurrentHashMap<>();

    public GameContext(String id){
        this.id = Objects.requireNonNull(id, "id");
    }

    public GameContext(String id, GameState state, World world){
        this(id);
        this.state = state;
        this.world = world;
    }

    public int pushGameplayAudioSuppression(){
        return gameplayAudioSuppressionDepth.incrementAndGet();
    }

    public int popGameplayAudioSuppression(){
        return gameplayAudioSuppressionDepth.updateAndGet(value -> Math.max(0, value - 1));
    }

    public void clearGameplayAudioSuppression(){
        gameplayAudioSuppressionDepth.set(0);
    }

    public boolean gameplayAudioSuppressed(){
        return gameplayAudioSuppressionDepth.get() > 0;
    }

    public int nextEntityId(){
        if(lastEntityId >= Integer.MAX_VALUE - 2) lastEntityId = 0;
        return lastEntityId++;
    }

    public void checkEntityId(int id){
        lastEntityId = Math.max(lastEntityId, id + 1);
    }

    public int lastEntityId(){
        return lastEntityId;
    }

    public int nextLightningSeed(){
        if(lightningSeed == Integer.MAX_VALUE) lightningSeed = 0;
        return lightningSeed++;
    }

    public int nextPowerGraphId(){
        if(powerGraphId == Integer.MAX_VALUE) powerGraphId = 0;
        return powerGraphId++;
    }

    @SuppressWarnings("unchecked")
    public <T> T localState(Object key, java.util.function.Supplier<? extends T> factory){
        Objects.requireNonNull(key, "local-state key");
        Objects.requireNonNull(factory, "local-state factory");
        return (T)localStates.computeIfAbsent(key, ignored -> Objects.requireNonNull(factory.get(), "local-state value"));
    }

    /** Returns an already-installed local state without creating one. */
    @SuppressWarnings("unchecked")
    public <T> T localStateIfPresent(Object key){
        Objects.requireNonNull(key, "local-state key");
        return (T)localStates.get(key);
    }

    public void clearLocalState(Object key){
        Object value = localStates.remove(key);
        if(value instanceof LocalStateLifecycle lifecycle){
            try{ lifecycle.dispose(); }catch(Throwable ignored){}
        }
    }

    public CampaignStats campaignStats(Planet planet){
        Planet owner = Objects.requireNonNull(planet, "planet").statParent != null ? planet.statParent : planet;
        return campaignStats.computeIfAbsent(owner, ignored -> new CampaignStats());
    }

    public void clearCampaignStats(Planet planet){
        Planet owner = Objects.requireNonNull(planet, "planet").statParent != null ? planet.statParent : planet;
        campaignStats.remove(owner);
    }

    /** Returns this runtime's mutable SectorInfo without consulting another live GameContext. */
    public SectorInfo sectorInfo(Sector sector){
        return sectorInfos.computeIfAbsent(Objects.requireNonNull(sector, "sector"), ignored -> new SectorInfo());
    }

    public void setSectorInfo(Sector sector, SectorInfo info){
        sectorInfos.put(Objects.requireNonNull(sector, "sector"), Objects.requireNonNull(info, "sector info"));
    }

    public void clearSectorInfo(Sector sector){
        sectorInfos.remove(sector);
    }

    public float sectorThreat(Sector sector, float fallback){
        return sectorThreats.getOrDefault(Objects.requireNonNull(sector, "sector"), fallback);
    }

    public void setSectorThreat(Sector sector, float value){
        sectorThreats.put(Objects.requireNonNull(sector, "sector"), value);
    }

    public void clearSectorThreat(Sector sector){
        sectorThreats.remove(sector);
    }

    /** Enqueues a game-affecting callback for this context's next simulation turn. */
    public void post(Runnable runnable){
        if(closing.get() || closed.get()) throw new RejectedExecutionException("GameContext is closed: " + id);
        postedTasks.add(Objects.requireNonNull(runnable));
    }

    /** Runs callbacks captured for this runtime. Must be invoked while this GameContext is bound. */
    public void drainPostedTasks(){
        for(Runnable task; (task = postedTasks.poll()) != null; ) task.run();
    }

    public int pendingPostedTasks(){ return postedTasks.size(); }
    public boolean closing(){ return closing.get(); }
    public boolean closed(){ return closed.get(); }
    public boolean acceptingWork(){ return !closing.get() && !closed.get(); }
    public long generation(){ return generation; }

    /**
     * Releases runtime-owned workers/listeners/tasks. Idempotent; late callbacks are rejected instead of being allowed
     * to mutate a recycled or sibling context.
     */
    public void dispose(){
        if(!closing.compareAndSet(false, true)) return;
        postedTasks.clear();
        RuntimeContexts.run(this, () -> {
            try{ if(fogControl != null) fogControl.stop(); }catch(Throwable ignored){}
            try{ if(asyncCore != null) asyncCore.dispose(); }catch(Throwable ignored){}
            try{ if(pathfinder != null) pathfinder.dispose(); }catch(Throwable ignored){}
            try{ if(controlPath != null) controlPath.dispose(); }catch(Throwable ignored){}
            try{ if(net != null) net.dispose(); }catch(Throwable ignored){}
            for(Object value : localStates.values()){
                if(value instanceof LocalStateLifecycle lifecycle){
                    try{ lifecycle.dispose(); }catch(Throwable ignored){}
                }
            }
            localStates.clear();
            campaignStats.clear();
            sectorInfos.clear();
            sectorThreats.clear();
            Events.clear();
            Time.clear();
        });
        RuntimeWorkerPool.sharedCompute().release(this);
        RuntimeStorageExecutor.pool().release(this);
        RuntimePathExecutor.shared().release(this);
        closed.set(true);
    }

    @Override
    public String toString(){
        return "GameContext{" + id + "}";
    }
}
