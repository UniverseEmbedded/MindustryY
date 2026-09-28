import arc.util.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.ai.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.abilities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.runtime.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.ForceProjector.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.power.NuclearReactor.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.modules.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dynamic gameplay-level ownership checks for systems whose singleton/scratch implementation was changed by the
 * multi-GameContext refactor. These deliberately exercise real gameplay methods in two live contexts instead of only
 * checking that a static field was renamed to ThreadLocal/localState.
 */
@Tag("shared-campaign-parallel")
public class RuntimeGameplayFeatureIsolationTests{
    @BeforeAll
    static void content(){
        ApplicationTests.launchApplication(false);
        Vars.headless = true;
    }

    @Test
    void forceProjectorOpenHitBreakRecoverStateIsOwnedByItsRuntime(){
        GameContext a = runtime("shield-a"), b = runtime("shield-b");
        try{
            ForceBuild shieldA = call(a, () -> placeForceProjector(10, 10));
            ForceBuild shieldB = call(b, () -> placeForceProjector(10, 10));

            RuntimeContexts.run(a, () -> {
                shieldA.broken = false;
                shieldA.warmup = shieldA.radscl = 1f;
                shieldA.phaseHeat = 0f;
                assertTrue(shieldA.realRadius() > 1f, "opened shield must have a real radius");
                assertTrue(shieldA.absorbExplosion(shieldA.x, shieldA.y, 40f), "in-radius explosion must hit the shield");
                assertTrue(shieldA.buildup > 0f);
                assertEquals(1f, shieldA.hit, 0.0001f);

                shieldA.buildup = ((ForceProjector)Blocks.forceProjector).shieldHealth + 5f;
                shieldA.updateTile();
                assertTrue(shieldA.broken, "threshold buildup must break the shield");

                shieldA.buildup = 0f;
                shieldA.updateTile();
                assertFalse(shieldA.broken, "drained broken shield must recover");
            });

            RuntimeContexts.run(b, () -> {
                assertTrue(shieldB.broken, "sibling runtime inherited A's ForceProjector broken/open state");
                assertEquals(0f, shieldB.buildup, 0.0001f, "sibling runtime inherited A's ForceProjector buildup");
                assertEquals(0f, shieldB.hit, 0.0001f, "sibling runtime inherited A's ForceProjector hit flash");
            });
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void baseShieldScratchCannotAbsorbABulletFromAnotherRuntime(){
        GameContext a = runtime("base-shield-a"), b = runtime("base-shield-b");
        try{
            BaseShield.BaseShieldBuild shieldA = call(a, () -> placeBaseShield(10, 10));
            BaseShield.BaseShieldBuild shieldB = call(b, () -> placeBaseShield(10, 10));
            AtomicReference<Bullet> bulletA = new AtomicReference<>(), bulletB = new AtomicReference<>();

            RuntimeContexts.run(a, () -> {
                shieldA.smoothRadius = 120f;
                bulletA.set(spawnBullet(Team.crux, shieldA.x + 12f, shieldA.y));
                assertTrue(bulletA.get().isAdded());
                shieldA.updateTile();
                assertFalse(bulletA.get().isAdded(), "BaseShield must absorb an enemy bullet in its own runtime");
            });
            RuntimeContexts.run(b, () -> {
                shieldB.smoothRadius = 120f;
                bulletB.set(spawnBullet(Team.crux, shieldB.x + 12f, shieldB.y));
                assertTrue(bulletB.get().isAdded(), "B control bullet must exist before B shield update");
            });
            RuntimeContexts.run(a, shieldA::updateTile);
            RuntimeContexts.run(b, () -> assertTrue(bulletB.get().isAdded(),
                "A BaseShield callback scratch must not absorb B runtime's bullet"));
            RuntimeContexts.run(b, shieldB::updateTile);
            assertFalse(call(b, () -> bulletB.get().isAdded()));
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void forceFieldAbilityAbsorbsOnlyCurrentRuntimeBulletsAndRecoversIndependently(){
        GameContext a = runtime("force-field-a"), b = runtime("force-field-b");
        try{
            Unit unitA = call(a, () -> spawnUnit(UnitTypes.quasar, Team.sharded, 80f, 80f));
            Unit unitB = call(b, () -> spawnUnit(UnitTypes.quasar, Team.sharded, 80f, 80f));
            ForceFieldAbility abilityA = findAbility(unitA, ForceFieldAbility.class);
            ForceFieldAbility abilityB = findAbility(unitB, ForceFieldAbility.class);
            assertNotSame(abilityA, abilityB, "UnitType abilities must be copied per real unit");
            AtomicReference<Bullet> bulletB = new AtomicReference<>();

            RuntimeContexts.run(a, () -> {
                abilityA.created(unitA);
                abilityA.update(unitA); // radiusScale begins opening.
                abilityA.update(unitA);
                Bullet bullet = spawnBullet(Team.crux, unitA.x + 5f, unitA.y);
                float before = unitA.shield;
                abilityA.update(unitA);
                assertFalse(bullet.isAdded(), "ForceField must absorb its own runtime bullet");
                assertTrue(unitA.shield < before, "ForceField hit must consume shield health");
            });

            RuntimeContexts.run(b, () -> {
                abilityB.created(unitB);
                abilityB.update(unitB);
                abilityB.update(unitB);
                bulletB.set(spawnBullet(Team.crux, unitB.x + 5f, unitB.y));
            });
            RuntimeContexts.run(a, () -> abilityA.update(unitA));
            RuntimeContexts.run(b, () -> assertTrue(bulletB.get().isAdded(),
                "A real Quasar ForceField copy must not enumerate/absorb a B runtime bullet through its clone-shared scratch"));
            RuntimeContexts.run(b, () -> abilityB.update(unitB));
            assertFalse(call(b, () -> bulletB.get().isAdded()));
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void shieldArcAbilityRuntimeGroupsDoNotCrossConsumeBullets(){
        GameContext a = runtime("shield-arc-a"), b = runtime("shield-arc-b");
        try{
            Unit unitA = call(a, () -> spawnUnit(UnitTypes.tecta, Team.sharded, 80f, 80f));
            Unit unitB = call(b, () -> spawnUnit(UnitTypes.tecta, Team.sharded, 80f, 80f));
            ShieldArcAbility arcA = findAbility(unitA, ShieldArcAbility.class);
            ShieldArcAbility arcB = findAbility(unitB, ShieldArcAbility.class);
            assertNotSame(arcA, arcB, "UnitType ShieldArc abilities must be copied per real unit");
            AtomicReference<Bullet> bulletB = new AtomicReference<>();
            RuntimeContexts.run(a, () -> {
                arcA.created(unitA);
                // Tecta's actual ability is configured whenShooting=false and chanceDeflect=1.
                // Place the bullet on the real offset arc instead of assuming the shield is centered on the unit.
                for(int i = 0; i < 8; i++) arcA.update(unitA);
                float before = arcA.data;
                Bullet bullet = spawnShieldArcBullet(unitA, arcA);
                arcA.update(unitA);
                assertTrue(!bullet.isAdded() || bullet.team == Team.sharded,
                    "real Tecta ShieldArc must absorb or reflect an enemy bullet crossing its active arc");
                assertTrue(arcA.data < before, "handling the bullet must consume real ShieldArc capacity");
                if(bullet.isAdded()){
                    assertSame(unitA, bullet.owner, "Tecta's 100% deflect arc must transfer reflected bullet ownership");
                }
            });
            RuntimeContexts.run(b, () -> {
                arcB.created(unitB);
                for(int i = 0; i < 8; i++) arcB.update(unitB);
                bulletB.set(spawnShieldArcBullet(unitB, arcB));
            });
            RuntimeContexts.run(a, () -> arcA.update(unitA));
            RuntimeContexts.run(b, () -> {
                Bullet bullet = bulletB.get();
                assertTrue(bullet.isAdded(),
                    "A real Tecta ShieldArc copy must not consume a B runtime bullet through clone-shared callback scratch");
                assertEquals(Team.crux, bullet.team,
                    "A runtime ShieldArc must not reflect a bullet owned by another GameContext");
                float before = arcB.data;
                arcB.update(unitB);
                assertTrue(!bullet.isAdded() || bullet.team == Team.sharded,
                    "B ShieldArc must handle its own bullet after A leaves it untouched");
                assertTrue(arcB.data < before, "B ShieldArc capacity must be consumed only by B's own bullet");
            });
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void callbackScratchReleasesWorldReferencesAfterGameplayOperation() throws Exception{
        GameContext context = runtime("scratch-lifecycle");
        try{
            ForceBuild force = call(context, () -> placeForceProjector(10, 10));
            RuntimeContexts.run(context, () -> {
                force.broken = false;
                force.warmup = force.radscl = 1f;
                force.deflectBullets();
            });
            assertThreadLocalFieldsNull(ForceProjector.class, null, "shieldScratch", "paramBlock", "paramEntity");

            BaseShield.BaseShieldBuild base = call(context, () -> placeBaseShield(14, 10));
            RuntimeContexts.run(context, () -> { base.smoothRadius = 120f; base.updateTile(); });
            assertThreadLocalFieldsNull(BaseShield.class, null, "shieldScratch", "paramBuild");

            Unit quasar = call(context, () -> spawnUnit(UnitTypes.quasar, Team.sharded, 80f, 80f));
            ForceFieldAbility field = findAbility(quasar, ForceFieldAbility.class);
            RuntimeContexts.run(context, () -> { field.created(quasar); field.update(quasar); });
            assertThreadLocalFieldsNull(ForceFieldAbility.class, field, "scratch", "unit", "field");

            Unit tecta = call(context, () -> spawnUnit(UnitTypes.tecta, Team.sharded, 96f, 80f));
            ShieldArcAbility arc = findAbility(tecta, ShieldArcAbility.class);
            RuntimeContexts.run(context, () -> { arc.created(tecta); arc.update(tecta); });
            assertThreadLocalFieldsNull(ShieldArcAbility.class, arc, "scratch", "paramUnit", "paramField");
        }finally{ context.dispose(); }
    }

    @Test
    void powerGraphIdentityAndBufferedStateDoNotBleedAcrossContexts(){
        GameContext a = runtime("power-a"), b = runtime("power-b");
        try{
            RuntimeContexts.run(a, () -> {
                PowerGraph graphA = new PowerGraph();
                PowerGraph graphA2 = new PowerGraph();
                assertNotEquals(graphA.getID(), graphA2.getID());
            });
            RuntimeContexts.run(b, () -> {
                PowerGraph graphB = new PowerGraph();
                assertEquals(0, graphB.getID(), "power graph IDs must restart in an independent GameContext");
            });
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void realLiquidBlocksMoveDifferentLiquidsWithoutCrossRuntimeLeak(){
        GameContext a = runtime("liquid-block-a"), b = runtime("liquid-block-b");
        try{
            Building tankA = call(a, () -> placeLiquidLine(Liquids.water));
            Building tankB = call(b, () -> placeLiquidLine(Liquids.oil));
            for(int i = 0; i < 40; i++){
                RuntimeContexts.run(a, () -> updateBuildings(a));
                RuntimeContexts.run(b, () -> updateBuildings(b));
            }
            RuntimeContexts.run(a, () -> {
                assertTrue(tankA.liquids.get(Liquids.water) > 0.1f, "A liquid source did not move water through real blocks");
                assertEquals(0f, tankA.liquids.get(Liquids.oil), 0.001f, "B oil leaked into A tank");
            });
            RuntimeContexts.run(b, () -> {
                assertTrue(tankB.liquids.get(Liquids.oil) > 0.1f, "B liquid source did not move oil through real blocks");
                assertEquals(0f, tankB.liquids.get(Liquids.water), 0.001f, "A water leaked into B tank");
            });
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void realPowerTopologyAndGraphAccountingRemainRuntimeLocal(){
        GameContext a = runtime("power-block-a"), b = runtime("power-block-b");
        try{
            Building sourceA = call(a, RuntimeGameplayFeatureIsolationTests::placePowerSourceAndBattery);
            Building sourceB = call(b, RuntimeGameplayFeatureIsolationTests::placePowerSourceAndBattery);
            RuntimeContexts.run(a, () -> { sourceA.power.graph.update(); assertTrue(sourceA.power.graph.getLastPowerProduced() > 0f); });
            RuntimeContexts.run(b, () -> { sourceB.power.graph.update(); assertTrue(sourceB.power.graph.getLastPowerProduced() > 0f); });
            assertNotSame(call(a, () -> sourceA.power.graph), call(b, () -> sourceB.power.graph),
                "live power graphs must never be process-shared even when their per-runtime IDs match");
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void realPayloadConveyorTransfersOnlyItsOwnRuntimePayload(){
        GameContext a = runtime("payload-move-a"), b = runtime("payload-move-b");
        try{
            PayloadConveyor.PayloadConveyorBuild[] lineA = call(a, () -> placePayloadLine(10, 10));
            PayloadConveyor.PayloadConveyorBuild[] lineB = call(b, () -> placePayloadLine(10, 10));
            RuntimeContexts.run(a, () -> lineA[0].handlePayload(lineA[0], new mindustry.world.blocks.payloads.BuildPayload(Blocks.container, Team.sharded)));
            RuntimeContexts.run(b, () -> lineB[0].handlePayload(lineB[0], new mindustry.world.blocks.payloads.BuildPayload(Blocks.copperWall, Team.sharded)));
            for(int i = 0; i < 180; i++){
                RuntimeContexts.run(a, () -> updateBuildings(a));
                RuntimeContexts.run(b, () -> updateBuildings(b));
            }
            RuntimeContexts.run(a, () -> {
                assertTrue(lineA[1].item instanceof mindustry.world.blocks.payloads.BuildPayload);
                assertSame(Blocks.container, ((mindustry.world.blocks.payloads.BuildPayload)lineA[1].item).block());
            });
            RuntimeContexts.run(b, () -> {
                assertTrue(lineB[1].item instanceof mindustry.world.blocks.payloads.BuildPayload);
                assertSame(Blocks.copperWall, ((mindustry.world.blocks.payloads.BuildPayload)lineB[1].item).block());
            });
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void liquidModuleFlowStateIsInstanceOwnedAcrossRuntimes(){
        GameContext a = runtime("liquid-a"), b = runtime("liquid-b");
        try{
            LiquidModule moduleA = call(a, LiquidModule::new);
            LiquidModule moduleB = call(b, LiquidModule::new);
            RuntimeContexts.run(a, () -> {
                moduleA.add(Liquids.water, 40f);
                moduleA.add(Liquids.oil, 7f);
                assertEquals(40f, moduleA.get(Liquids.water), 0.001f);
            });
            RuntimeContexts.run(b, () -> {
                moduleB.add(Liquids.cryofluid, 9f);
                assertEquals(0f, moduleB.get(Liquids.water), 0.001f);
                assertEquals(9f, moduleB.get(Liquids.cryofluid), 0.001f);
            });
            RuntimeContexts.run(a, () -> assertEquals(0f, moduleA.get(Liquids.cryofluid), 0.001f));
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void payloadConveyorMovementStateIsWorldOwned(){
        GameContext a = runtime("payload-a"), b = runtime("payload-b");
        try{
            PayloadConveyor.PayloadConveyorBuild buildA = call(a, () -> placePayloadConveyor(10, 10));
            PayloadConveyor.PayloadConveyorBuild buildB = call(b, () -> placePayloadConveyor(10, 10));
            RuntimeContexts.run(a, () -> {
                buildA.progress = 0.75f;
                buildA.animation = 0.4f;
                buildA.blocked = true;
            });
            RuntimeContexts.run(b, () -> {
                assertEquals(0f, buildB.progress, 0.0001f);
                assertEquals(0f, buildB.animation, 0.0001f);
                assertFalse(buildB.blocked);
            });
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void nuclearReactorHeatAndExplosionEligibilityRemainRuntimeLocal(){
        GameContext a = runtime("reactor-a"), b = runtime("reactor-b");
        try{
            NuclearReactorBuild reactorA = call(a, () -> placeReactor(10, 10));
            NuclearReactorBuild reactorB = call(b, () -> placeReactor(10, 10));
            RuntimeContexts.run(a, () -> {
                reactorA.items.add(Items.thorium, 10);
                reactorA.heat = 0.8f;
                assertTrue(reactorA.shouldExplode());
            });
            RuntimeContexts.run(b, () -> {
                assertEquals(0, reactorB.items.get(Items.thorium));
                assertEquals(0f, reactorB.heat, 0.0001f);
                assertFalse(reactorB.shouldExplode());
            });
        }finally{ a.dispose(); b.dispose(); }
    }

    @Test
    void gameplayScenariosCanTickConcurrentlyWithoutCrossContextMutation() throws Exception{
        GameContext a = runtime("parallel-feature-a"), b = runtime("parallel-feature-b");
        try{
            ForceBuild shieldA = call(a, () -> placeForceProjector(10, 10));
            ForceBuild shieldB = call(b, () -> placeForceProjector(10, 10));
            shieldA.broken = shieldB.broken = false;
            shieldA.radscl = shieldB.radscl = shieldA.warmup = shieldB.warmup = 1f;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try{
                Future<?> fa = executor.submit(() -> RuntimeContexts.run(a, () -> {
                    await(start);
                    for(int i = 0; i < 200; i++) shieldA.absorbExplosion(shieldA.x, shieldA.y, 0.1f);
                }));
                Future<?> fb = executor.submit(() -> RuntimeContexts.run(b, () -> {
                    await(start);
                    for(int i = 0; i < 200; i++) shieldB.absorbExplosion(shieldB.x, shieldB.y, 0.2f);
                }));
                start.countDown();
                fa.get(10, TimeUnit.SECONDS); fb.get(10, TimeUnit.SECONDS);
            }finally{ executor.shutdownNow(); }
            assertEquals(40f, shieldA.buildup, 0.05f);
            assertEquals(80f, shieldB.buildup, 0.05f);
        }finally{ a.dispose(); b.dispose(); }
    }

    private static GameContext runtime(String id){
        GameContext context = new GameContext(id);
        context.headless = true;
        RuntimeContexts.run(context, () -> {
            context.state = new GameState();
            context.state.set(GameState.State.playing);
            context.state.rules.defaultTeam = Team.sharded;
            context.world = new World();
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

    private static ForceBuild placeForceProjector(int x, int y){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(x, y).setBlock(Blocks.forceProjector, Team.sharded);
        Vars.game().world.endMapLoad();
        return (ForceBuild)Vars.game().world.build(x, y);
    }

    private static BaseShield.BaseShieldBuild placeBaseShield(int x, int y){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(x, y).setBlock(Blocks.shieldProjector, Team.sharded);
        Vars.game().world.endMapLoad();
        return (BaseShield.BaseShieldBuild)Vars.game().world.build(x, y);
    }

    private static PayloadConveyor.PayloadConveyorBuild placePayloadConveyor(int x, int y){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(x, y).setBlock(Blocks.payloadConveyor, Team.sharded);
        Vars.game().world.endMapLoad();
        return (PayloadConveyor.PayloadConveyorBuild)Vars.game().world.build(x, y);
    }

    private static NuclearReactorBuild placeReactor(int x, int y){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(x, y).setBlock(Blocks.thoriumReactor, Team.sharded);
        Vars.game().world.endMapLoad();
        return (NuclearReactorBuild)Vars.game().world.build(x, y);
    }

    private static Building placeLiquidLine(Liquid liquid){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(5, 10).setBlock(Blocks.liquidSource, Team.sharded, 0);
        Vars.game().world.tile(6, 10).setBlock(Blocks.conduit, Team.sharded, 0);
        Vars.game().world.tile(8, 10).setBlock(Blocks.liquidTank, Team.sharded, 0);
        Vars.game().world.endMapLoad();
        Building source = Vars.game().world.build(5, 10), conduit = Vars.game().world.build(6, 10), tank = Vars.game().world.build(8, 10);
        source.configureAny(liquid);
        source.updateProximity(); conduit.updateProximity(); tank.updateProximity();
        updateBuildings(Vars.game());
        return tank;
    }

    private static Building placePowerSourceAndBattery(){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(8, 8).setBlock(Blocks.powerSource, Team.sharded, 0);
        Vars.game().world.tile(11, 8).setBlock(Blocks.battery, Team.sharded, 0);
        Vars.game().world.endMapLoad();
        Building source = Vars.game().world.build(8, 8), battery = Vars.game().world.build(11, 8);
        source.configureAny(battery.pos());
        source.updateProximity(); battery.updateProximity();
        return source;
    }

    private static PayloadConveyor.PayloadConveyorBuild[] placePayloadLine(int x, int y){
        Vars.game().world.beginMapLoad();
        Vars.game().world.tile(x, y).setBlock(Blocks.payloadConveyor, Team.sharded, 0);
        Vars.game().world.tile(x + 3, y).setBlock(Blocks.payloadConveyor, Team.sharded, 0);
        Vars.game().world.endMapLoad();
        var first = (PayloadConveyor.PayloadConveyorBuild)Vars.game().world.build(x, y);
        var second = (PayloadConveyor.PayloadConveyorBuild)Vars.game().world.build(x + 3, y);
        first.updateProximity(); second.updateProximity();
        return new PayloadConveyor.PayloadConveyorBuild[]{first, second};
    }

    private static void updateBuildings(GameContext context){
        Time.update();
        for(Tile tile : context.world.tiles){
            if(tile != null && tile.build != null && tile.isCenter()) tile.build.update();
        }
        Groups.current().bullet.updatePhysics();
    }

    private static Unit spawnUnit(Team team, float x, float y){
        return spawnUnit(UnitTypes.dagger, team, x, y);
    }

    private static Unit spawnUnit(UnitType type, Team team, float x, float y){
        Unit unit = type.create(team);
        unit.set(x, y);
        unit.add();
        return unit;
    }

    private static <T extends Ability> T findAbility(Unit unit, Class<T> type){
        for(Ability ability : unit.abilities()) if(type.isInstance(ability)) return type.cast(ability);
        throw new AssertionError("Unit " + unit.type.name + " has no " + type.getSimpleName());
    }

    private static Bullet spawnShieldArcBullet(Unit unit, ShieldArcAbility ability){
        float centerX = unit.x + arc.math.Angles.trnsx(unit.rotation - 90f, ability.x, ability.y);
        float centerY = unit.y + arc.math.Angles.trnsy(unit.rotation - 90f, ability.x, ability.y);
        float angle = unit.rotation + ability.angleOffset;
        float x = centerX + arc.math.Angles.trnsx(angle, ability.radius);
        float y = centerY + arc.math.Angles.trnsy(angle, ability.radius);
        Bullet bullet = spawnBullet(Team.crux, x, y);
        // Reflection requires a non-trivial velocity. Keep the real Duo bullet type but aim it through the arc.
        bullet.vel.trns(angle + 180f, Math.max(1f, bullet.vel.len()));
        Groups.current().bullet.updatePhysics();
        return bullet;
    }

    private static Bullet spawnBullet(Team team, float x, float y){
        Bullet bullet = ((mindustry.world.blocks.defense.turrets.ItemTurret)Blocks.duo).ammoTypes.get(Items.copper).create(null, team, x, y, 0f);
        if(!bullet.isAdded()) bullet.add();
        Groups.current().bullet.updatePhysics();
        return bullet;
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

    private static void assertThreadLocalFieldsNull(Class<?> owner, Object receiver, String threadLocalField, String... referenceFields) throws Exception{
        Field holderField = owner.getDeclaredField(threadLocalField);
        holderField.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<Object> holder = (ThreadLocal<Object>)holderField.get(receiver);
        Object scratch = holder.get();
        assertNotNull(scratch, "scratch should exist on the gameplay thread after exercising the callback");
        for(String name : referenceFields){
            Field field = scratch.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(scratch), () -> owner.getSimpleName() + " ThreadLocal retained world reference " + name);
        }
    }

    private static void await(CountDownLatch latch){
        try{ latch.await(); }catch(InterruptedException e){ Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
