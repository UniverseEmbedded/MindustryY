import arc.graphics.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.graphics.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** P18 gate for general utility scratch that may be reached from concurrent GameContext workers. */
@Tag("shared-campaign-parallel")
public class ArcUtilityThreadIsolationTests{
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void utilityScratchIsThreadLocalByConstruction() throws Exception{
        assertThreadLocal(Strings.class, "fixedScratch");
        assertThreadLocal(Scaling.class, "temp");
        assertThreadLocal(Camera.class, "tmpVector");
        assertThreadLocal(InverseKinematics.class, "scratch");
        assertThreadLocal(Bench.class, "state");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void formattingScalingAndInverseKinematicsRemainDeterministicUnderConcurrency() throws Exception{
        long expectedA = stress(0x13579BDFL, null);
        long expectedB = stress(0x2468ACE0L, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try{
            Future<Long> a = pool.submit(() -> stress(0x13579BDFL, barrier));
            Future<Long> b = pool.submit(() -> stress(0x2468ACE0L, barrier));
            assertEquals(expectedA, a.get(8, TimeUnit.SECONDS));
            assertEquals(expectedB, b.get(8, TimeUnit.SECONDS));
        }finally{
            pool.shutdownNow();
        }
    }

    private static void assertThreadLocal(Class<?> type, String fieldName) throws Exception{
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        assertTrue(field.get(null) instanceof ThreadLocal, type.getName() + "." + fieldName + " must be ThreadLocal");
    }

    private static long stress(long seed, CyclicBarrier barrier) throws Exception{
        arc.math.Rand rand = new arc.math.Rand(seed);
        long hash = 0xcbf29ce484222325L;
        for(int i = 0; i < 3000; i++){
            if(barrier != null && (i & 31) == 0) barrier.await(5, TimeUnit.SECONDS);
            float value = rand.random(-9999f, 9999f);
            String fixed = Strings.fixed(value, i % 5);
            hash = mix(hash, fixed.hashCode());
            float sw = rand.random(1f, 500f), sh = rand.random(1f, 500f);
            float tw = rand.random(1f, 800f), th = rand.random(1f, 800f);
            Vec2 scaled = Scaling.values()[i % Scaling.values().length].apply(sw, sh, tw, th);
            hash = mix(hash, Float.floatToIntBits(scaled.x));
            hash = mix(hash, Float.floatToIntBits(scaled.y));
            Vec2 end = new Vec2(rand.random(-100f, 100f), rand.random(-100f, 100f));
            if(end.isZero()) end.set(1f, 1f);
            Vec2 out = new Vec2();
            boolean ok = InverseKinematics.solve(40f, 50f, end, (i & 1) == 0, out);
            hash = mix(hash, ok ? 1 : 0);
            hash = mix(hash, Float.floatToIntBits(out.x));
            hash = mix(hash, Float.floatToIntBits(out.y));
        }
        return hash;
    }

    private static long mix(long hash, int value){
        hash ^= value & 0xffffffffL;
        return hash * 0x100000001b3L;
    }
}
