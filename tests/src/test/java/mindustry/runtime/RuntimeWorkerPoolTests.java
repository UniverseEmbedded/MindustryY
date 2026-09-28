package mindustry.runtime;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("shared-campaign-parallel")
class RuntimeWorkerPoolTests{
    @Test
    @Timeout(10)
    void explicitOwnersAreReboundAndWorkerCountIsProcessBounded() throws Exception{
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(2, 2, "runtime-pool-test-")){
            GameContext a = new GameContext("pool-a"), b = new GameContext("pool-b"), c = new GameContext("pool-c");
            CountDownLatch entered = new CountDownLatch(3), release = new CountDownLatch(1);
            AtomicInteger wrongOwner = new AtomicInteger();
            Future<?> fa = pool.submit(a, "test", () -> awaitOwned(a, entered, release, wrongOwner));
            Future<?> fb = pool.submit(b, "test", () -> awaitOwned(b, entered, release, wrongOwner));
            Future<?> fc = pool.submit(c, "test", () -> awaitOwned(c, entered, release, wrongOwner));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime() < deadline && pool.metrics().activeWorkers() < 2) Thread.sleep(5L);
            assertTrue(pool.metrics().activeWorkers() <= 2, "workers must be bounded by the process pool, not context count");
            assertEquals(3L, pool.metrics().submitted());
            release.countDown();
            fa.get(3, TimeUnit.SECONDS); fb.get(3, TimeUnit.SECONDS); fc.get(3, TimeUnit.SECONDS);
            assertEquals(0, wrongOwner.get());
            assertEquals(3L, pool.metrics().completed());
        }
    }


    @Test
    @Timeout(3)
    void exhaustedContextQuotaRejectsWithoutBlockingAuthoritativeCaller() throws Exception{
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 1, "runtime-pool-backpressure-test-")){
            GameContext context = new GameContext("quota-owner");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Future<?> first = pool.submit(context, "long", () -> { entered.countDown(); try{ release.await(2, TimeUnit.SECONDS); }catch(InterruptedException e){ Thread.currentThread().interrupt(); }});
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertThrows(RejectedExecutionException.class, () -> pool.submit(context, "overflow", () -> {}));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 100L, "authoritative caller blocked on background quota for " + elapsedMillis + "ms");
            assertEquals(1L, pool.metrics().rejected());
            release.countDown();first.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void closedContextRejectsLateWork(){
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 1, "runtime-pool-close-test-")){
            GameContext context = new GameContext("closed-owner");
            context.dispose();
            assertThrows(RejectedExecutionException.class, () -> pool.submit(context, "late", () -> fail("late task ran")));
            assertEquals(1L, pool.metrics().rejected());
        }
    }


    @Test
    @Timeout(5)
    void contextTaskCarriesDeadlineCancellationPriorityAndGeneration(){
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 2, "runtime-pool-metadata-test-")){
            GameContext owner = new GameContext("metadata-owner");
            ContextTask.CancellationToken cancelled = new ContextTask.CancellationToken();
            cancelled.cancel();
            ContextTask cancelledTask = ContextTask.of(owner, "cancelled", ContextTask.Priority.background,
                java.time.Duration.ofSeconds(1), cancelled, () -> fail("cancelled task ran"));
            assertEquals(owner.generation(), cancelledTask.ownerGeneration());
            assertEquals(ContextTask.Priority.background, cancelledTask.priority());
            assertThrows(RejectedExecutionException.class, () -> pool.submit(cancelledTask));

            ContextTask expired = ContextTask.of(owner, "expired", ContextTask.Priority.latency,
                java.time.Duration.ZERO, new ContextTask.CancellationToken(), () -> fail("expired task ran"));
            assertThrows(RejectedExecutionException.class, () -> pool.submit(expired));
            assertEquals(2L, pool.metrics().rejected());
            assertEquals(1L, pool.metrics().cancelledToken());
            assertEquals(1L, pool.metrics().expiredDeadline());
        }
    }

    @Test
    @Timeout(5)
    void latencyPriorityOvertakesQueuedBackgroundWithoutBreakingOwnerBinding() throws Exception{
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 3, "runtime-pool-priority-test-")){
            GameContext blockerOwner = new GameContext("priority-blocker");
            GameContext backgroundOwner = new GameContext("priority-background");
            GameContext latencyOwner = new GameContext("priority-latency");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(2);
            java.util.List<String> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

            Future<?> blocker = pool.submit(blockerOwner, "blocker", () -> {
                entered.countDown();
                try{ release.await(2, TimeUnit.SECONDS); }catch(InterruptedException e){ Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            Future<?> background = pool.submit(ContextTask.of(backgroundOwner, "background", ContextTask.Priority.background,
                java.time.Duration.ofSeconds(2), new ContextTask.CancellationToken(), () -> {
                    assertSame(backgroundOwner, RuntimeContexts.current());
                    order.add("background"); finished.countDown();
                }));
            Future<?> latency = pool.submit(ContextTask.of(latencyOwner, "latency", ContextTask.Priority.latency,
                java.time.Duration.ofSeconds(2), new ContextTask.CancellationToken(), () -> {
                    assertSame(latencyOwner, RuntimeContexts.current());
                    order.add("latency"); finished.countDown();
                }));

            release.countDown();
            blocker.get(1, TimeUnit.SECONDS);
            latency.get(1, TimeUnit.SECONDS);
            background.get(1, TimeUnit.SECONDS);
            assertTrue(finished.await(100, TimeUnit.MILLISECONDS));
            assertEquals(java.util.List.of("latency", "background"), order);
        }
    }

    @Test
    @Timeout(5)
    void queuedCancellationAndDeadlinePreventLateExecution() throws Exception{
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 2, "runtime-pool-stale-test-")){
            GameContext blockerOwner = new GameContext("stale-blocker");
            GameContext cancelledOwner = new GameContext("stale-cancelled");
            GameContext expiredOwner = new GameContext("stale-expired");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();
            Future<?> blocker = pool.submit(blockerOwner, "blocker", () -> {
                entered.countDown();
                try{ release.await(2, TimeUnit.SECONDS); }catch(InterruptedException e){ Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            ContextTask.CancellationToken token = new ContextTask.CancellationToken();
            Future<?> cancelled = pool.submit(ContextTask.of(cancelledOwner, "cancelled-queued", ContextTask.Priority.normal,
                java.time.Duration.ofSeconds(2), token, ran::incrementAndGet));
            Future<?> expired = pool.submit(ContextTask.of(expiredOwner, "expired-queued", ContextTask.Priority.normal,
                java.time.Duration.ofMillis(20), new ContextTask.CancellationToken(), ran::incrementAndGet));
            token.cancel();
            Thread.sleep(40L);
            release.countDown();

            blocker.get(1, TimeUnit.SECONDS);
            cancelled.get(1, TimeUnit.SECONDS);
            expired.get(1, TimeUnit.SECONDS);
            assertEquals(0, ran.get());
            assertEquals(1L, pool.metrics().cancelledToken());
            assertEquals(1L, pool.metrics().expiredDeadline());
        }
    }

    @Test
    @Timeout(5)
    void globalOutstandingCapacityIsHardBoundedAndFailFast() throws Exception{
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 1, "runtime-pool-global-bound-test-")){
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            java.util.List<Future<?>> accepted = new java.util.ArrayList<>();
            GameContext firstOwner = new GameContext("global-0");
            accepted.add(pool.submit(firstOwner, "blocker", () -> {
                entered.countDown();
                try{ release.await(2, TimeUnit.SECONDS); }catch(InterruptedException e){ Thread.currentThread().interrupt(); }
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            // parallelism=1/contextQuota=1 => queueCapacity=4 and hard outstanding capacity=5 (1 active + 4 queued).
            for(int i = 1; i < 5; i++){
                GameContext owner = new GameContext("global-" + i);
                accepted.add(pool.submit(owner, "queued", () -> {}));
            }
            long started = System.nanoTime();
            assertThrows(RejectedExecutionException.class, () ->
                pool.submit(new GameContext("global-overflow"), "overflow", () -> {}));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 100L);
            assertTrue(pool.metrics().queuedTasks() <= pool.metrics().queueCapacity());

            release.countDown();
            for(Future<?> future : accepted) future.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(5)
    void heavyContextCannotFillQueueAheadOfSmallContextBeyondItsQuota() throws Exception{
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 2, "runtime-pool-fairness-test-")){
            GameContext heavy = new GameContext("heavy"), small = new GameContext("small");
            CountDownLatch firstEntered = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), smallRan = new CountDownLatch(1);
            Future<?> first = pool.submit(heavy, "heavy-1", () -> {
                firstEntered.countDown();
                try{ releaseFirst.await(2, TimeUnit.SECONDS); }catch(InterruptedException e){ Thread.currentThread().interrupt(); }
            });
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<?> second = pool.submit(heavy, "heavy-2", () -> {});
            assertThrows(RejectedExecutionException.class, () -> pool.submit(heavy, "heavy-overflow", () -> {}),
                "heavy owner must not monopolize more than its outstanding quota");
            Future<?> smallFuture = pool.submit(ContextTask.of(small, "latency-small", ContextTask.Priority.latency,
                java.time.Duration.ofSeconds(2), new ContextTask.CancellationToken(), smallRan::countDown));

            releaseFirst.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            smallFuture.get(1, TimeUnit.SECONDS);
            assertTrue(smallRan.await(100, TimeUnit.MILLISECONDS));
            assertTrue(pool.metrics().queuedTasks() <= pool.metrics().queueCapacity());
        }
    }

    @Test
    @Timeout(5)
    void cancellingRunningTaskDoesNotReleaseOwnerQuotaUntilCallableActuallyExits() throws Exception{
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 1, "runtime-pool-physical-cancel-test-")){
            GameContext owner = new GameContext("physical-cancel-owner");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
            Future<?> first = pool.submit(owner, "ignores-cancel", () -> {
                entered.countDown();
                try{
                    while(release.getCount() > 0){
                        try{ release.await(20, TimeUnit.MILLISECONDS); }
                        catch(InterruptedException ignored){}
                    }
                }finally{
                    exited.countDown();
                }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(first.cancel(false));
            assertThrows(RejectedExecutionException.class, () -> pool.submit(owner, "replacement-too-early", () -> {}),
                "cancelling a running Future must not release the physical owner quota while its callable is still executing");

            release.countDown();
            assertTrue(exited.await(1, TimeUnit.SECONDS));
            Future<?> replacement = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while(replacement == null && System.nanoTime() < deadline){
                try{ replacement = pool.submit(owner, "replacement-after-exit", () -> {}); }
                catch(RejectedExecutionException retry){ Thread.sleep(5L); }
            }
            assertNotNull(replacement, "owner quota must be released after physical callable exit");
            replacement.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(5)
    void closingContextRejectsNewWorkBeforeClosedFlagIsPublished() throws Exception{
        try(RuntimeWorkerPool pool = new RuntimeWorkerPool(1, 1, "runtime-pool-closing-test-")){
            GameContext owner = new GameContext("closing-owner");
            CountDownLatch disposeEntered = new CountDownLatch(1), allowDispose = new CountDownLatch(1);
            owner.localState("blocking-dispose", () -> new GameContext.LocalStateLifecycle(){
                @Override public void dispose(){
                    disposeEntered.countDown();
                    try{ allowDispose.await(2, TimeUnit.SECONDS); }
                    catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); }
                }
            });
            Thread disposer = new Thread(owner::dispose, "closing-owner-disposer");
            disposer.start();
            assertTrue(disposeEntered.await(1, TimeUnit.SECONDS));
            try{
                assertThrows(RejectedExecutionException.class, () -> pool.submit(owner, "late-during-close", () -> fail("closing owner task ran")),
                    "a context that has entered dispose must stop admitting new worker work before closed=true");
            }finally{
                allowDispose.countDown();
                disposer.join(1000L);
            }
            assertFalse(disposer.isAlive());
            assertTrue(owner.closed());
        }
    }

    private static void awaitOwned(GameContext expected, CountDownLatch entered, CountDownLatch release, AtomicInteger wrongOwner){
        if(RuntimeContexts.current() != expected) wrongOwner.incrementAndGet();
        entered.countDown();
        try{ release.await(3, TimeUnit.SECONDS); }
        catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); }
    }
}
