package mindustry.campaign.shared.runtime;

import mindustry.runtime.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Shared Campaign's context-local attachment root.
 *
 * <p>The generic Mindustry runtime knows only {@link GameContext#localState(Object, java.util.function.Supplier)}.
 * Shared Campaign owns this bundle and hangs its Action identity plus service/network/agent components from it,
 * preserving the dependency direction {@code campaign.shared -> mindustry.runtime}.</p>
 */
public final class SharedCampaignRuntimeState implements GameContext.LocalStateLifecycle{
    private static final Object key = SharedCampaignRuntimeState.class;

    /** Product-owned per-tick hook installed by the Action service/agent layer. */
    public interface TickHook{
        void update();
        default boolean parallelTickReady(){ return true; }
    }

    private final GameContext owner;
    private volatile ActionRuntimeConfig actionRuntime;
    private final ConcurrentHashMap<Class<?>, Object> components = new ConcurrentHashMap<>();
    private volatile boolean disposed;

    private SharedCampaignRuntimeState(GameContext owner, ActionRuntimeConfig actionRuntime){
        this.owner = Objects.requireNonNull(owner, "owner");
        this.actionRuntime = actionRuntime == null ? ActionRuntimeConfig.disabled() : actionRuntime;
    }

    public static SharedCampaignRuntimeState install(GameContext context){
        return install(context, ActionRuntimeConfig.disabled());
    }

    public static SharedCampaignRuntimeState install(GameContext context, ActionRuntimeConfig actionRuntime){
        Objects.requireNonNull(context, "context");
        SharedCampaignRuntimeState state = context.localState(key, () -> new SharedCampaignRuntimeState(context, actionRuntime));
        state.setActionRuntime(actionRuntime);
        return state;
    }

    public static SharedCampaignRuntimeState get(GameContext context){
        return install(context, ActionRuntimeConfig.disabled());
    }

    public static SharedCampaignRuntimeState find(GameContext context){
        Objects.requireNonNull(context, "context");
        return context.localStateIfPresent(key);
    }

    public void tick(){
        TickHook hook = component(TickHook.class);
        if(hook != null) hook.update();
    }

    public boolean parallelTickReady(){
        TickHook hook = component(TickHook.class);
        return hook == null || hook.parallelTickReady();
    }

    public GameContext owner(){ return owner; }
    public ActionRuntimeConfig actionRuntime(){ return actionRuntime; }
    public boolean actionEnabled(){ return actionRuntime != null && actionRuntime.enabled(); }

    /**
     * Installs runtime identity once. Re-reading an identical descriptor is harmless; changing a live Action identity
     * inside one GameContext is rejected so stale callbacks cannot silently become authoritative for another Action.
     */
    public synchronized void setActionRuntime(ActionRuntimeConfig next){
        ensureOpen();
        next = next == null ? ActionRuntimeConfig.disabled() : next;
        if(actionRuntime != null && actionRuntime.enabled() && next.enabled() && !actionRuntime.equals(next)){
            throw new IllegalStateException("Cannot replace Action runtime identity in GameContext " + owner.id);
        }
        if(actionRuntime == null || !actionRuntime.enabled() || next.enabled()) actionRuntime = next;
    }

    public <T> T component(Class<T> type){
        Objects.requireNonNull(type, "component type");
        Object value = components.get(type);
        return value == null ? null : type.cast(value);
    }

    public <T> T attach(Class<T> type, T component){
        Objects.requireNonNull(type, "component type");
        Objects.requireNonNull(component, "component");
        if(!type.isInstance(component)) throw new IllegalArgumentException("Component is not an instance of " + type.getName());
        ensureOpen();
        Object previous = components.putIfAbsent(type, component);
        if(previous != null && previous != component) throw new IllegalStateException("Shared Campaign component already installed: " + type.getName());
        return type.cast(previous == null ? component : previous);
    }

    public <T> T detach(Class<T> type){
        Object value = components.remove(Objects.requireNonNull(type, "component type"));
        return value == null ? null : type.cast(value);
    }

    public boolean disposed(){ return disposed; }

    @Override
    public synchronized void dispose(){
        if(disposed) return;
        disposed = true;
        ArrayList<Object> values = new ArrayList<>(components.values());
        components.clear();
        // One component may intentionally be registered under both its concrete API and a capability interface.
        // Dispose each object identity once; registration aliases must never cause double-close side effects.
        Set<Object> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        for(Object value : values){
            if(value == null || !closed.add(value)) continue;
            try{
                if(value instanceof AutoCloseable closeable) closeable.close();
            }catch(Exception ignored){}
        }
        actionRuntime = ActionRuntimeConfig.disabled();
    }

    private void ensureOpen(){
        if(disposed) throw new IllegalStateException("Shared Campaign runtime state is disposed for " + owner.id);
    }
}
