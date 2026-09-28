import arc.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.runtime.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicDisplay.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dynamic ownership checks for transient/gameplay registries that historically lived behind process globals.
 * Every test intentionally uses identical tile coordinates in two GameContexts so a re-shared cache/registry fails
 * loudly instead of being hidden by different positions.
 */
@Tag("shared-campaign-parallel")
public class RuntimeTransientFeatureIsolationTests{
    @BeforeAll
    static void content(){
        ApplicationTests.launchApplication(false);
        Vars.headless = true;
    }

    @Test
    void fireRegistryAndLifetimeAreWorldOwned(){
        GameContext a = runtime("fire-a"), b = runtime("fire-b");
        try{
            RuntimeContexts.run(a, () -> {
                Tile tile = Vars.game().world.tile(10, 10);
                Fires.create(tile);
                assertNotNull(Fires.get(tile));
                assertTrue(Fires.has(10, 10));
                Fires.extinguish(tile, 75f);
                assertTrue(Fires.get(tile).time > 0f);
            });
            RuntimeContexts.run(b, () -> {
                Tile tile = Vars.game().world.tile(10, 10);
                assertNull(Fires.get(tile), "B inherited A's tile fire registry");
                assertFalse(Fires.has(10, 10));
                Fires.create(tile);
                assertNotNull(Fires.get(tile));
                assertEquals(0f, Fires.get(tile).time, 0.0001f, "B inherited A fire lifetime");
            });
            RuntimeContexts.run(a, () -> assertTrue(Fires.get(Vars.game().world.tile(10, 10)).time > 0f));
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void puddleRegistryLiquidAndAmountAreWorldOwned(){
        GameContext a = runtime("puddle-a"), b = runtime("puddle-b");
        try{
            RuntimeContexts.run(a, () -> {
                Tile tile = Vars.game().world.tile(10, 10);
                Puddles.deposit(tile, Liquids.oil, 22f);
                Puddle puddle = Puddles.get(tile);
                assertNotNull(puddle);
                assertSame(Liquids.oil, puddle.liquid);
                assertEquals(22f, puddle.amount, 0.001f);
            });
            RuntimeContexts.run(b, () -> {
                Tile tile = Vars.game().world.tile(10, 10);
                assertNull(Puddles.get(tile), "B inherited A's puddle registry");
                Puddles.deposit(tile, Liquids.water, 9f);
                Puddle puddle = Puddles.get(tile);
                assertNotNull(puddle);
                assertSame(Liquids.water, puddle.liquid);
                assertEquals(9f, puddle.amount, 0.001f);
            });
            RuntimeContexts.run(a, () -> assertSame(Liquids.oil, Puddles.get(Vars.game().world.tile(10, 10)).liquid));
        }finally{ a.dispose(); b.dispose(); }
    }


    @Test
    void unitDeathRemovalAndEventAreRuntimeLocal(){
        GameContext a = runtime("unit-death-a"), b = runtime("unit-death-b");
        try{
            AtomicInteger eventsA = new AtomicInteger(), eventsB = new AtomicInteger();
            Unit unitA = call(a, () -> {
                Events.on(UnitDestroyEvent.class, event -> eventsA.incrementAndGet());
                Unit unit = UnitTypes.dagger.create(Team.crux); unit.set(80f, 80f); unit.add(); return unit;
            });
            Unit unitB = call(b, () -> {
                Events.on(UnitDestroyEvent.class, event -> eventsB.incrementAndGet());
                Unit unit = UnitTypes.dagger.create(Team.crux); unit.set(80f, 80f); unit.add(); return unit;
            });
            assertEquals(unitA.id, unitB.id, "independent runtime entity IDs should intentionally overlap for this isolation test");

            RuntimeContexts.run(a, unitA::kill);
            RuntimeContexts.run(a, () -> {
                assertFalse(unitA.isAdded(), "A unit must be removed by the real UnitComp death path");
                assertEquals(1, eventsA.get(), "A runtime must emit exactly one UnitDestroyEvent");
            });
            RuntimeContexts.run(b, () -> {
                assertTrue(unitB.isAdded(), "A unit death removed the same numeric entity ID from B runtime");
                assertFalse(unitB.dead(), "A unit death leaked dead state into B runtime");
                assertEquals(0, eventsB.get(), "A UnitDestroyEvent leaked into B runtime's event bus");
            });

            RuntimeContexts.run(b, unitB::kill);
            assertEquals(1, eventsB.get(), "B runtime must independently emit its own UnitDestroyEvent");
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void logicDisplayRegistryIndicesRestartPerRuntime(){
        GameContext a = runtime("display-a"), b = runtime("display-b");
        try{
            LogicDisplayBuild a0 = call(a, () -> placeDisplay(9, 9));
            LogicDisplayBuild a1 = call(a, () -> placeDisplay(12, 9));
            LogicDisplayBuild b0 = call(b, () -> placeDisplay(9, 9));
            assertEquals(0, a0.index);
            assertEquals(1, a1.index);
            assertEquals(0, b0.index, "secondary runtime inherited primary/other display registry index");

            RuntimeContexts.run(a, a0::remove);
            RuntimeContexts.run(b, () -> assertEquals(0, b0.index, "removing A display rewrote B display registry"));
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void regenProjectorMendStateCannotApplyToSamePositionInSiblingRuntime(){
        GameContext a = runtime("regen-a"), b = runtime("regen-b");
        try{
            RegenProjector.RegenProjectorBuild regenA = call(a, () -> placeRegen(8, 8));
            RegenProjector.RegenProjectorBuild regenB = call(b, () -> placeRegen(8, 8));
            Building wallA = call(a, () -> placeDamagedWall(10, 8, 30f));
            Building wallB = call(b, () -> placeDamagedWall(10, 8, 30f));

            RuntimeContexts.run(a, () -> {
                regenA.targets.clear();
                regenA.targets.add(wallA);
                regenA.lastChange = Vars.game().world.tileChanges;
                regenA.efficiency = 1f;
                regenA.updateTile();
                assertTrue(wallA.health > 30f, "A regen projector did not exercise real healing path");
            });
            RuntimeContexts.run(b, () -> {
                regenB.targets.clear();
                regenB.lastChange = Vars.game().world.tileChanges;
                regenB.efficiency = 1f;
                regenB.updateTile();
                assertEquals(30f, wallB.health, 0.001f,
                    "A runtime's position-keyed RegenProjector mend cache healed B world");
            });
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void staticFogDiscoveryIsOwnedByFogControlAndWorld(){
        GameContext a = runtime("fog-a"), b = runtime("fog-b");
        try{
            // CoreShard reveals most of a 32x32 test world, so use a real small-radius fog block.
            // Build before WorldLoadEvent so FogControl's initial static scan observes the authoritative building.
            prepareFog(a, 3, 3);
            prepareFog(b, 28, 28);
            RuntimeContexts.run(a, () -> {
                assertTrue(Vars.game().fogControl.isDiscovered(Team.sharded, 3, 3), "A fog revealer must discover its own tile");
                assertFalse(Vars.game().fogControl.isDiscovered(Team.sharded, 28, 28), "far A tile should remain undiscovered");
            });
            RuntimeContexts.run(b, () -> {
                assertTrue(Vars.game().fogControl.isDiscovered(Team.sharded, 28, 28), "B fog revealer must discover its own tile");
                assertFalse(Vars.game().fogControl.isDiscovered(Team.sharded, 3, 3),
                    "B inherited A's static fog discovery bitmap");
            });
            RuntimeContexts.run(a, () -> {
                assertTrue(Vars.game().fogControl.isDiscovered(Team.sharded, 3, 3));
                assertFalse(Vars.game().fogControl.isDiscovered(Team.sharded, 28, 28),
                    "B's discovery must not mutate A's static fog bitmap");
            });
        }finally{ a.dispose(); b.dispose(); }
    }

    private static GameContext runtime(String id){
        GameContext context = new GameContext(id, new GameState(), new World());
        context.headless = true;
        RuntimeContexts.run(context, () -> {
            context.state.set(GameState.State.playing);
            context.state.rules.defaultTeam = Team.sharded;
            context.state.rules.fire = true;
            context.world.resize(32, 32);
            context.world.tiles.fill();
            context.net = new Net(null);
            context.indexer = new BlockIndexer();
            Groups.current().clear();
            Groups.resize(0f, 0f, context.world.width() * Vars.tilesize, context.world.height() * Vars.tilesize);
            Time.setDelta(1f);
        });
        return context;
    }

    private static void prepareFog(GameContext context, int x, int y){
        RuntimeContexts.run(context, () -> {
            context.state.rules.fog = true;
            context.state.rules.staticFog = true;
            context.fogControl = new FogControl();
            Vars.game().world.beginMapLoad();
            Vars.game().world.tile(x, y).setBlock(Blocks.beamNode, Team.sharded);
            Vars.game().world.endMapLoad();
            Events.fire(new WorldLoadEvent());
        });
    }

    private static LogicDisplayBuild placeDisplay(int x, int y){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(x, y).setBlock(Blocks.logicDisplay, Team.sharded);
        Vars.game().world.endMapLoad();
        return (LogicDisplayBuild)Vars.game().world.build(x, y);
    }

    private static RegenProjector.RegenProjectorBuild placeRegen(int x, int y){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(x, y).setBlock(Blocks.regenProjector, Team.sharded);
        Vars.game().world.endMapLoad();
        return (RegenProjector.RegenProjectorBuild)Vars.game().world.build(x, y);
    }

    private static Building placeDamagedWall(int x, int y, float health){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(x, y).setBlock(Blocks.copperWall, Team.sharded);
        Vars.game().world.endMapLoad();
        Building build = Vars.game().world.build(x, y);
        build.health = health;
        return build;
    }

    private static <T> T call(GameContext context, java.util.concurrent.Callable<T> supplier){
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RuntimeContexts.run(context, () -> {
            try{ value.set(supplier.call()); }catch(Throwable t){ failure.set(t); }
        });
        if(failure.get() != null){
            if(failure.get() instanceof RuntimeException runtime) throw runtime;
            if(failure.get() instanceof Error error) throw error;
            throw new RuntimeException(failure.get());
        }
        return value.get();
    }
}
