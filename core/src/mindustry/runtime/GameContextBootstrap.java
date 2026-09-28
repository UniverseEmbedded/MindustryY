package mindustry.runtime;

import arc.util.*;
import mindustry.*;
import mindustry.ai.*;
import mindustry.async.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.logic.*;
import mindustry.net.*;

import java.util.*;

/** Constructs a fully-owned embedded headless runtime after process-level Content/Mods have been initialized. */
public final class GameContextBootstrap{
    private GameContextBootstrap(){}

    public static GameContext headless(String id, RuntimeStorage storage){
        return headless(id, storage, () -> {});
    }

    public static GameContext headless(String id, RuntimeStorage storage, Runnable exitHandler){
        if(Vars.content == null || Vars.content.getContentMap() == null){
            throw new IllegalStateException("Process content must be initialized before creating an embedded runtime");
        }

        GameContext context = new GameContext(id);
        context.headless = true;
        context.exitHandler = exitHandler == null ? () -> {} : exitHandler;
        context.storage = Objects.requireNonNull(storage, "runtime storage");
        context.storage.prepare();

        RuntimeContexts.run(context, () -> {
            // Construction order mirrors the headless launcher, but mutable runtime objects are written only to owner.
            context.state = new GameState();
            context.waves = new Waves();
            context.collisions = new EntityCollisions();
            context.world = new World();
            // Embedded worlds are not owners of process profile persistence or strategic campaign turns.
            context.universe = new Universe(false, false);
            context.asyncCore = new AsyncCore();
            context.spawner = new WaveSpawner();
            context.indexer = new BlockIndexer();
            context.pathfinder = new Pathfinder();
            context.controlPath = new ControlPathfinder();
            context.fogControl = new FogControl();
            context.logicVars = new GlobalVars();
            context.logicVars.init();
            context.net = new Net(Vars.platform.getNet());
            context.logic = new Logic();
            context.netServer = new NetServer();

            // Embedded simulation advances in explicit deterministic ticks; desktop render delta must never leak in.
            Time.setDeltaProvider(() -> 1f);
            Time.setDelta(1f);
        });
        return context;
    }
}
