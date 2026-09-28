import arc.math.*;
import arc.math.geom.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** P18 gate for Arc math helpers that previously reused process-global mutable scratch. */
@Tag("shared-campaign-parallel")
public class ArcMathThreadIsolationTests{
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void geometryAndIntersectorScratchAreWorkerLocal() throws Exception{
        assertThreadLocalScratch(Geometry.class, "scratch");
        assertThreadLocalScratch(Intersector.class, "scratch");
        assertThreadLocalScratch(Vec3.class, "tmpMat");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void geometryAndIntersectorRemainStableUnderConcurrentUse() throws Exception{
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try{
            Future<Long> a = pool.submit(() -> stress(0x13579BDFL, barrier));
            Future<Long> b = pool.submit(() -> stress(0x2468ACE0L, barrier));
            long av = a.get(), bv = b.get();
            assertEquals(reference(0x13579BDFL), av, "Geometry/Intersector result changed under concurrent worker A");
            assertEquals(reference(0x2468ACE0L), bv, "Geometry/Intersector result changed under concurrent worker B");
        }finally{
            pool.shutdownNow();
        }
    }

    private static void assertThreadLocalScratch(Class<?> type, String fieldName) throws Exception{
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(null);
        assertTrue(value instanceof ThreadLocal, type.getName() + "." + fieldName + " must be ThreadLocal");
        @SuppressWarnings("unchecked") ThreadLocal<Object> local = (ThreadLocal<Object>)value;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
        try{
            Callable<Object[]> task = () -> {
                Object first = local.get();
                Object second = local.get();
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new Object[]{first, second};
            };
            Future<Object[]> fa = pool.submit(task), fb = pool.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            Object[] a = fa.get(), b = fb.get();
            assertSame(a[0], a[1], "ThreadLocal scratch must be stable inside one worker");
            assertSame(b[0], b[1], "ThreadLocal scratch must be stable inside one worker");
            assertNotSame(a[0], b[0], "workers must not share Arc math scratch identity");
        }finally{
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static long stress(long seed, CyclicBarrier barrier) throws Exception{
        Rand rand = new Rand(seed);
        long hash = 0xcbf29ce484222325L;
        for(int i = 0; i < 4000; i++){
            if((i & 31) == 0) barrier.await(5, TimeUnit.SECONDS);
            float x1 = rand.random(-1000f, 1000f), y1 = rand.random(-1000f, 1000f);
            float x2 = rand.random(-1000f, 1000f), y2 = rand.random(-1000f, 1000f);
            float px = rand.random(-1000f, 1000f), py = rand.random(-1000f, 1000f);
            Vec2 nearest = Geometry.raycastRect(x1, y1, x2, y2, -200f, -150f, 200f, 150f);
            float distance = Intersector.distanceSegmentPoint(x1, y1, x2, y2, px, py);
            boolean inside = Intersector.isInRegularPolygon(7, 12f, -9f, 80f, 17f, px * 0.05f, py * 0.05f);
            hash = mix(hash, Float.floatToIntBits(distance));
            hash = mix(hash, inside ? 1 : 0);
            hash = mix(hash, nearest == null ? 0 : Float.floatToIntBits(nearest.x));
            hash = mix(hash, nearest == null ? 0 : Float.floatToIntBits(nearest.y));
        }
        return hash;
    }

    private static long reference(long seed) throws Exception{
        CyclicBarrier noWait = new CyclicBarrier(1);
        return stress(seed, noWait);
    }

    private static long mix(long hash, int value){
        hash ^= value & 0xffffffffL;
        return hash * 0x100000001b3L;
    }
}
