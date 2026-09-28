package mindustry.runtime;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Adversarial shutdown races for process-owned runtime pools. */
@Tag("shared-campaign-adversarial")
public class RuntimeAdversarialShutdownTests{

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void queuedComputeTaskCannotRunAfterOwnerDisposes() throws Exception{
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 4, "Adversarial-Compute-")){
            GameContext blockerOwner = new GameContext("adversarial-blocker");
            GameContext closingOwner = new GameContext("adversarial-closing");
            CountDownLatch blockerEntered = new CountDownLatch(1), releaseBlocker = new CountDownLatch(1);
            AtomicInteger ranAfterClose = new AtomicInteger();

            Future<?> blocker = pool.submit(blockerOwner, "blocker", () -> {
                blockerEntered.countDown();
                awaitLatch(releaseBlocker);
            });
            assertTrue(blockerEntered.await(3, TimeUnit.SECONDS));

            Future<?> queued = pool.submit(closingOwner, "must-not-run", ranAfterClose::incrementAndGet);
            closingOwner.dispose();
            releaseBlocker.countDown();
            blocker.get(3, TimeUnit.SECONDS);
            queued.get(3, TimeUnit.SECONDS); // Future completes normally, but semantic body must be skipped.

            assertEquals(0, ranAfterClose.get(), "queued work executed after its GameContext was disposed");
            await(() -> pool.metrics().queuedTasks() == 0 && pool.metrics().activeWorkers() == 0, 2_000L);
            assertTrue(pool.metrics().cancelledClosedContext() >= 1,
                "closed-owner cancellation must be observable in pool metrics");
            blockerOwner.dispose();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void closingPathHandleWaitsForInFlightTurnAndPreventsAnyLaterTurn() throws Exception{
        try(RuntimePathExecutor paths = new RuntimePathExecutor(1, "Adversarial-Path-")){
            GameContext owner = new GameContext("adversarial-path-owner");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger turns = new AtomicInteger();
            RuntimePathExecutor.Handle handle = paths.schedule(owner, "path-race", 1L, () -> {
                turns.incrementAndGet();
                entered.countDown();
                awaitLatch(release);
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));

            ExecutorService closer = Executors.newSingleThreadExecutor();
            try{
                Future<?> closeFuture = closer.submit(handle::close);
                Thread.sleep(50L);
                assertFalse(closeFuture.isDone(), "path handle closed before the active turn retired");
                release.countDown();
                closeFuture.get(3, TimeUnit.SECONDS);
                int afterClose = turns.get();
                Thread.sleep(75L);
                assertEquals(afterClose, turns.get(), "a recurring path turn ran after handle.close() completed");
                assertTrue(handle.closed());
                assertEquals(0, paths.metrics().registeredLanes());
            }finally{
                closer.shutdownNow();
                owner.dispose();
            }
        }
    }

    private static void awaitLatch(CountDownLatch latch){
        try{
            if(!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for adversarial latch");
        }catch(InterruptedException error){
            Thread.currentThread().interrupt();
            throw new RuntimeException(error);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception{
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(System.nanoTime() < deadline){
            if(condition.getAsBoolean()) return;
            Thread.sleep(5L);
        }
        assertTrue(condition.getAsBoolean());
    }
}
