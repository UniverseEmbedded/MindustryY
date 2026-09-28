package mindustry.ai;

import arc.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.net.*;
import mindustry.runtime.*;
import mindustry.core.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for the historical WorldLoad listener amplification / pathfinder-worker explosion. */
@Tag("shared-campaign-lifecycle")
public class ControlPathfinderLifecycleTests{
    @Test
    void repeatedWorldLoadsReuseOneListenerSetAndOneBoundedPoolLane(){
        RuntimePathExecutor pool = RuntimePathExecutor.shared();
        int baselineLanes = pool.metrics().registeredLanes();

        GameContext context = new GameContext("control-path-worldload", new GameState(), null);
        RuntimeContexts.run(context, () -> {
            context.world = new World();
            context.net = new Net(null);
            context.controlPath = new ControlPathfinder();
        });

        ControlPathfinder original = context.controlPath;
        int worldLoadListeners = context.events.listenerCount(WorldLoadEvent.class);
        int updateListeners = context.events.listenerCount(EventType.Trigger.update);
        assertTrue(worldLoadListeners > 0, "control pathfinder must own a WorldLoad listener");
        assertTrue(updateListeners > 0, "control pathfinder must own an update listener");
        assertNull(original.pathHandle, "constructor must not start path work before the first WorldLoad");
        assertEquals(baselineLanes, pool.metrics().registeredLanes(), "constructor must only register listeners, not a path lane");

        ControlPathfinder.PathRequest staleRequest = null;
        long firstRequestId = original.nextRequestId(), secondRequestId = original.nextRequestId();
        assertTrue(firstRequestId > 0L && secondRequestId > firstRequestId, "path request ids must be monotonic within the pathfinder incarnation");
        long previousWorldRevision = original.worldRevision(), previousPathGeneration = original.pathGeneration();
        for(int i = 0; i < 32; i++){
            RuntimeContexts.run(context, () -> Events.fire(new WorldLoadEvent()));
            assertTrue(original.worldRevision() > previousWorldRevision, "WorldLoad must advance world revision on cycle " + i);
            assertTrue(original.pathGeneration() > previousPathGeneration, "WorldLoad must advance path generation on cycle " + i);
            previousWorldRevision = original.worldRevision(); previousPathGeneration = original.pathGeneration();
            if(i == 0){
                ControlPathfinder.PathRequest[] created = new ControlPathfinder.PathRequest[1];
                RuntimeContexts.run(context, () -> created[0] = new ControlPathfinder.PathRequest(null, Team.sharded.id, 0, 0, context.generation(), original.pathGeneration(), original.worldRevision(), 1L));
                staleRequest = created[0];
                ControlPathfinder.PathRequest currentRequest = staleRequest;
                RuntimeContexts.run(context, () -> assertTrue(original.requestCurrent(currentRequest)));
            }else if(i == 1){
                assertNotNull(staleRequest);
                ControlPathfinder.PathRequest previousRequest = staleRequest;
                RuntimeContexts.run(context, () -> assertFalse(original.requestCurrent(previousRequest), "request from the previous world/path incarnation must be rejected"));
            }
            assertSame(original, context.controlPath, "WorldLoad must reset the runtime-owned instance, not register a new listener set");
            assertEquals(worldLoadListeners, context.events.listenerCount(WorldLoadEvent.class), "WorldLoad listeners grew on cycle " + i);
            assertEquals(updateListeners, context.events.listenerCount(EventType.Trigger.update), "update listeners grew on cycle " + i);
            assertNotNull(original.pathHandle, "world reset must leave one active path lane");
            assertFalse(original.pathHandle.closed(), "path lane should be active after reset cycle " + i);
            assertEquals(baselineLanes + 1, pool.metrics().registeredLanes(), "path lanes amplified on cycle " + i);
        }

        long processWorkers = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().startsWith("Mindustry-Runtime-Path-"))
            .count();
        assertTrue(processWorkers <= pool.metrics().parallelism(), "process path workers must remain bounded by pool parallelism");

        context.dispose();
        assertTrue(context.closed());
        assertNull(original.pathHandle, "dispose must synchronously retire this Context's lane");
        assertEquals(baselineLanes, pool.metrics().registeredLanes(), "dispose must unregister exactly this Context's lane");
        assertEquals(0, context.events.listenerTypeCount(), "dispose must clear runtime-owned listeners");
    }
}
