import arc.*;
import arc.math.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.entities.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P18 mutation gate: deliberate owner-local mutations must not alter a sibling GameContext, and the gate itself must
 * detect an intentionally re-shared owner object instead of merely executing two contexts without a negative control.
 */
@Tag("shared-campaign-parallel")
@Tag("shared-campaign-mutation")
public class RuntimeMutationIsolationGateTests{
    static final class Ping{}
    static final class Local{ int value; }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void ownerLocalWorldStateGroupsTimeEventsRngAndLocalStateDoNotCross() throws Exception{
        GameContext a = context("mutation-a", 0x51A7E11L, 3, 4);
        GameContext b = context("mutation-b", 0x51A7E11L, 5, 6);
        AtomicInteger eventA = new AtomicInteger(), eventB = new AtomicInteger();
        AtomicInteger delayedA = new AtomicInteger(), delayedB = new AtomicInteger();
        try{
            RuntimeContexts.run(a, () -> Events.on(Ping.class, ignored -> eventA.incrementAndGet()));
            RuntimeContexts.run(b, () -> Events.on(Ping.class, ignored -> eventB.incrementAndGet()));

            // Mutate A heavily while B is idle. All touched state is part of a formerly process-global surface.
            RuntimeContexts.run(a, () -> {
                a.state.wave = 77;
                a.state.gameOver = true;
                a.state.rules.buildSpeedMultiplier = 3.25f;
                a.world.resize(11, 13);
                a.localState(Local.class, Local::new).value = 1234;
                EntityGroup.checkNextId(900);
                for(int i = 0; i < 256; i++) Mathf.random(1_000_000);
                Time.setTime(400f);
                Time.setDelta(2f);
                Time.run(1f, delayedA::incrementAndGet);
                Events.fire(new Ping());
                Time.update();
            });

            RuntimeContexts.run(b, () -> {
                assertEquals(1, b.state.wave, "B wave inherited A mutation");
                assertFalse(b.state.gameOver, "B gameOver inherited A mutation");
                assertEquals(1f, b.state.rules.buildSpeedMultiplier, 0.0001f, "B Rules inherited A mutation");
                assertEquals(5, b.world.width(), "B world width inherited A resize");
                assertEquals(6, b.world.height(), "B world height inherited A resize");
                assertEquals(0, b.localState(Local.class, Local::new).value, "B localState inherited A value");
                assertEquals(0, EntityGroup.nextId(), "B entity allocator inherited A counter");

                Rand reference = new Rand(0x51A7E11L);
                for(int i = 0; i < 64; i++){
                    assertEquals(reference.nextInt(1_000_001), Mathf.random(1_000_000), "B RNG advanced with A");
                }

                assertEquals(20f, Time.time(), 0.0001f, "B time changed while A advanced");
                assertEquals(0, delayedB.get());
                Events.fire(new Ping());
            });

            assertEquals(1, eventA.get(), "B event leaked to A listeners");
            assertEquals(1, eventB.get(), "A event leaked to B listeners");
            assertEquals(1, delayedA.get());

            // Reverse direction after B has consumed RNG/events/entity IDs; A must keep its own sentinels.
            RuntimeContexts.run(b, () -> {
                b.state.wave = 9;
                b.world.resize(7, 8);
                b.localState(Local.class, Local::new).value = 55;
                Time.setTime(33f);
                Time.setDelta(1f);
                Time.run(1f, delayedB::incrementAndGet);
                Time.update();
            });
            RuntimeContexts.run(a, () -> {
                assertEquals(77, a.state.wave);
                assertTrue(a.state.gameOver);
                assertEquals(11, a.world.width());
                assertEquals(13, a.world.height());
                assertEquals(1234, a.localState(Local.class, Local::new).value);
                assertEquals(402f, Time.time(), 0.0001f);
            });
            assertEquals(1, delayedB.get());
        }finally{
            a.dispose();
            b.dispose();
        }
    }

    @Test
    void negativeControlDetectsDeliberatelyResharedOwners(){
        GameContext a = context("mutation-negative-a", 7L, 2, 2);
        GameContext b = context("mutation-negative-b", 8L, 3, 3);
        try{
            // Mutation testing negative control: prove this gate turns red if a future refactor re-shares owner state.
            b.state = a.state;
            b.world = a.world;
            b.groups = a.groups;
            AssertionError failure = assertThrows(AssertionError.class, () -> assertDistinctOwners(a, b));
            assertTrue(failure.getMessage().contains("re-shared"));
        }finally{
            a.dispose();
            b.dispose();
        }
    }

    private static GameContext context(String id, long seed, int width, int height){
        GameContext context = new GameContext(id, new GameState(), null);
        context.mathRand.setSeed(seed);
        RuntimeContexts.run(context, () -> {
            context.world = new World();
            context.world.resize(width, height);
            Groups.current(); // force generated group ownership to be materialized before the negative-control probe.
            Time.setTime(id.endsWith("b") ? 20f : 100f);
            Time.setDelta(1f);
        });
        return context;
    }

    private static void assertDistinctOwners(GameContext a, GameContext b){
        assertNotSame(a.state, b.state, "re-shared GameState owner");
        assertNotSame(a.world, b.world, "re-shared World owner");
        assertNotSame(a.groups, b.groups, "re-shared Groups owner");
        assertNotSame(a.events, b.events, "re-shared Events owner");
        assertNotSame(a.time, b.time, "re-shared Time owner");
        assertNotSame(a.mathRand, b.mathRand, "re-shared RNG owner");
    }
}
