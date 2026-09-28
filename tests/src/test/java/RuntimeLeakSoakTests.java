import arc.*;
import mindustry.ai.*;
import mindustry.core.*;
import mindustry.net.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** P18 process-resource leak gate for repeated Context ownership of shared compute/path infrastructure. */
@Tag("shared-campaign-lifecycle")
@Tag("shared-campaign-leak")
public class RuntimeLeakSoakTests{
    private static Method pathfinderStart;

    @BeforeAll
    static void locatePathfinderStart() throws Exception{
        pathfinderStart = Pathfinder.class.getDeclaredMethod("start");
        pathfinderStart.setAccessible(true);
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void twoHundredContextCyclesReturnSharedPoolAndListenerResourcesToBaseline() throws Exception{
        RuntimePathExecutor pathPool = RuntimePathExecutor.shared();
        RuntimeWorkerPool compute = RuntimeWorkerPool.sharedCompute();
        int baselineLanes = pathPool.metrics().registeredLanes();
        int baselineQueued = compute.metrics().queuedTasks();
        int baselineComputeContexts = compute.metrics().trackedContexts();
        int baselineStorageContexts = RuntimeStorageExecutor.metrics().trackedContexts();
        int pathParallelism = pathPool.metrics().parallelism();

        for(int i = 0; i < 200; i++){
            GameContext context = new GameContext("leak-soak-" + i, new GameState(), null);
            RuntimeContexts.run(context, () -> {
                context.world = new World();
                context.net = new Net(new NoopNetProvider());
                context.pathfinder = new Pathfinder();
                try{
                    pathfinderStart.invoke(context.pathfinder);
                }catch(ReflectiveOperationException error){
                    throw new RuntimeException(error);
                }
            });

            assertEquals(baselineLanes + 1, pathPool.metrics().registeredLanes(), "cycle " + i + " did not register exactly one path lane");
            Future<?> work = compute.submit(context, "leak-soak", () -> {
                if(RuntimeContexts.current() != context) throw new AssertionError("compute worker lost owner binding");
            });
            Future<?> storageWork = RuntimeStorageExecutor.pool().submit(context, "leak-soak-storage", () -> {
                if(RuntimeContexts.current() != context) throw new AssertionError("storage worker lost owner binding");
            });
            work.get(5, TimeUnit.SECONDS);
            storageWork.get(5, TimeUnit.SECONDS);

            context.dispose();
            await(() -> pathPool.metrics().registeredLanes() == baselineLanes, 2_000L,
                "cycle " + i + " retained a shared path lane");
            await(() -> compute.metrics().queuedTasks() == baselineQueued && compute.metrics().activeWorkers() == 0, 2_000L,
                "cycle " + i + " retained compute work");
            await(() -> compute.metrics().trackedContexts() == baselineComputeContexts, 2_000L,
                "cycle " + i + " retained compute owner bookkeeping");
            await(() -> RuntimeStorageExecutor.metrics().trackedContexts() == baselineStorageContexts, 2_000L,
                "cycle " + i + " retained storage owner bookkeeping");
            assertEquals(0, context.events.listenerTypeCount(), "cycle " + i + " retained runtime listeners");
            assertEquals(0, context.pendingPostedTasks(), "cycle " + i + " retained posted callbacks");
            assertEquals(0, context.time.pendingRuns(), "cycle " + i + " retained delayed callbacks");
        }

        RuntimeMetrics.Snapshot after = RuntimeMetrics.snapshot();
        assertEquals(baselineLanes, pathPool.metrics().registeredLanes());
        assertEquals(baselineQueued, compute.metrics().queuedTasks());
        assertEquals(baselineComputeContexts, compute.metrics().trackedContexts());
        assertEquals(baselineStorageContexts, RuntimeStorageExecutor.metrics().trackedContexts());
        assertTrue(after.pathfinderThreads() <= pathParallelism,
            () -> "process path thread count escaped bounded pool: " + after.pathfinderThreads() + " > " + pathParallelism);
        assertTrue(after.runtimeWorkerThreads() <= compute.metrics().parallelism(),
            () -> "process compute thread count escaped bounded pool: " + after.runtimeWorkerThreads() + " > " + compute.metrics().parallelism());
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis, String message) throws Exception{
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(System.nanoTime() < deadline){
            if(condition.getAsBoolean()) return;
            Thread.sleep(5L);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    static final class NoopNetProvider implements Net.NetProvider{
        @Override public void connectClient(String ip, int port, Runnable success){ success.run(); }
        @Override public void sendClient(Object object, boolean reliable){}
        @Override public void disconnectClient(){}
        @Override public void discoverServers(arc.func.Cons<Host> callback, Runnable done){ done.run(); }
        @Override public void pingHost(String address, int port, arc.func.Cons<Host> valid, arc.func.Cons<Exception> failed){}
        @Override public void hostServer(int port){}
        @Override public Iterable<? extends NetConnection> getConnections(){ return List.of(); }
        @Override public void closeServer(){}
    }
}
