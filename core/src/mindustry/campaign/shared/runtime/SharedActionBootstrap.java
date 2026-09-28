package mindustry.campaign.shared.runtime;

import mindustry.runtime.*;

import java.util.*;

/** Product-side bridge that installs one Shared Campaign Action on a generic headless GameContext. */
public final class SharedActionBootstrap{
    private SharedActionBootstrap(){}

    public static GameContext createEmbedded(String contextId, ActionRuntimeConfig config, RuntimeStorage storage, Runnable exitHandler){
        Objects.requireNonNull(config, "action runtime config");
        if(!config.enabled()) throw new IllegalArgumentException("Embedded Shared Campaign runtime requires an enabled Action descriptor");
        GameContext context = GameContextBootstrap.headless(contextId, storage, exitHandler);
        try{
            RuntimeContexts.run(context, () -> installCurrent(config, exitHandler));
            return context;
        }catch(Throwable error){
            context.dispose();
            throw error;
        }
    }

    /** Installs the Action product layer into the currently-bound GameContext without changing generic runtime code. */
    public static SharedActionAgent installCurrent(ActionRuntimeConfig config, Runnable exitHandler){
        GameContext context = RuntimeContexts.requireCurrent();
        Objects.requireNonNull(config, "action runtime config");
        if(!config.enabled()) throw new IllegalArgumentException("Shared Campaign Action bootstrap requires an enabled descriptor");
        SharedCampaignRuntimeState state = SharedCampaignRuntimeState.install(context, config);
        SharedActionAgent existing = state.component(SharedActionAgent.class);
        if(existing != null) return existing;
        SharedActionAgent agent = new SharedActionAgent(config, exitHandler == null ? context.exitHandler : exitHandler);
        agent.init();
        return agent;
    }

    public static SharedActionAgent findAgent(GameContext context){
        SharedCampaignRuntimeState state = context == null ? null : SharedCampaignRuntimeState.find(context);
        return state == null ? null : state.component(SharedActionAgent.class);
    }
}
