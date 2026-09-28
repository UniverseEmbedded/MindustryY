package mindustry.input;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** P18 gate: static Placement APIs must not share mutable normalization scratch across runtime/tooling threads. */
@Tag("shared-campaign-parallel")
class PlacementThreadIsolationTests{
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void normalizeAreaUsesIndependentPerThreadScratch() throws Exception{
        int iterations = 20_000;
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicInteger corruptions = new AtomicInteger();
        AtomicReference<Placement.NormalizeResult> aIdentity = new AtomicReference<>(), bIdentity = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try{
            Future<?> a = pool.submit(() -> runLane(start, iterations, 10, 20, 35, 22, aIdentity, corruptions));
            Future<?> b = pool.submit(() -> runLane(start, iterations, -40, -30, -38, -4, bIdentity, corruptions));
            a.get(8, TimeUnit.SECONDS);
            b.get(8, TimeUnit.SECONDS);
        }finally{
            pool.shutdownNow();
        }
        assertEquals(0, corruptions.get(), "concurrent Placement.normalizeArea calls corrupted another thread's result scratch");
        assertNotNull(aIdentity.get());
        assertNotNull(bIdentity.get());
        assertNotSame(aIdentity.get(), bIdentity.get(), "Placement scratch must be isolated per thread");
    }

    private static void runLane(CyclicBarrier start, int iterations, int x1, int y1, int x2, int y2,
                                AtomicReference<Placement.NormalizeResult> identity, AtomicInteger corruptions){
        try{ start.await(3, TimeUnit.SECONDS); }
        catch(Exception e){ throw new RuntimeException(e); }
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        for(int i = 0; i < iterations; i++){
            Placement.NormalizeResult result = Placement.normalizeArea(x1, y1, x2, y2, 0, false, 0);
            identity.compareAndSet(null, result);
            if(result != identity.get() || result.x != minX || result.x2 != maxX || result.y != minY || result.y2 != maxY){
                corruptions.incrementAndGet();
                return;
            }
            Thread.yield();
        }
    }
}
