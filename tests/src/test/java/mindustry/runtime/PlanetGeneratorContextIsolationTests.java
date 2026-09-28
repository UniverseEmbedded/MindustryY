package mindustry.runtime;

import mindustry.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.maps.generators.*;
import mindustry.net.*;
import mindustry.type.*;
import mindustry.world.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Protects process-shared PlanetGenerator scratch from concurrent GameContext sector loads. */
public class PlanetGeneratorContextIsolationTests{
    @BeforeAll
    static void bootstrap(){
        TestBootstrap.ensureBaseContent();
        if(Vars.customMapDirectory == null) Vars.customMapDirectory = new arc.files.Fi("build/tmp/planet-generator-context-isolation/maps");
    }

    static GameContext context(String id){
        GameContext context = new GameContext(id, new GameState(), new World());
        context.headless = true;
        context.net = new Net(null);
        return context;
    }

    static class ConcurrentProbeGenerator extends PlanetGenerator{
        final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();

        @Override public int getSectorSize(Sector sector){ return 8; }
        @Override public void addWeather(Sector sector, Rules rules){}
        @Override public void generate(Tiles tiles, Sector sector, WorldParams params){
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try{
                sleep(150);
                tiles.fill();
            }finally{
                active.decrementAndGet();
            }
        }
    }

    static class PostGenerateProbeGenerator extends PlanetGenerator{
        final CountDownLatch postStarted = new CountDownLatch(1), releasePost = new CountDownLatch(1);
        final AtomicBoolean postActive = new AtomicBoolean(), generateEnteredDuringPost = new AtomicBoolean();
        final AtomicInteger postCalls = new AtomicInteger();

        @Override public int getSectorSize(Sector sector){ return 8; }
        @Override public void addWeather(Sector sector, Rules rules){}
        @Override public void generate(Tiles tiles, Sector sector, WorldParams params){
            if(postActive.get()) generateEnteredDuringPost.set(true);
            tiles.fill();
        }
        @Override public void postGenerate(Tiles tiles){
            if(postCalls.getAndIncrement() == 0){
                postActive.set(true);
                postStarted.countDown();
                try{
                    assertTrue(releasePost.await(3, TimeUnit.SECONDS));
                }catch(InterruptedException e){
                    throw new RuntimeException(e);
                }finally{
                    postActive.set(false);
                }
            }
        }
    }

    @Test
    void processSharedPlanetGeneratorIsSerializedAcrossGameContexts() throws Exception{
        ConcurrentProbeGenerator generator = new ConcurrentProbeGenerator();
        Planet planet = new Planet("generator-isolation", null, 1f, 1);
        planet.generator = generator;
        Sector first = planet.sectors.first(), second = planet.sectors.size > 1 ? planet.sectors.get(1) : first;
        GameContext a = context("generator-a"), b = context("generator-b");
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try{
            Future<?> fa = pool.submit(() -> RuntimeContexts.run(a, () -> { await(start); a.world.loadSector(first); }));
            Future<?> fb = pool.submit(() -> RuntimeContexts.run(b, () -> { await(start); b.world.loadSector(second); }));
            fa.get(10, TimeUnit.SECONDS);
            fb.get(10, TimeUnit.SECONDS);
            assertEquals(1, generator.maxActive.get(), "process-shared PlanetGenerator entered concurrently across GameContexts");
        }finally{
            pool.shutdownNow(); a.dispose(); b.dispose();
        }
    }

    @Test
    void generatorOwnershipCoversPostGenerateLifecycle() throws Exception{
        PostGenerateProbeGenerator generator = new PostGenerateProbeGenerator();
        Planet planet = new Planet("generator-post-isolation", null, 1f, 1);
        planet.generator = generator;
        Sector first = planet.sectors.first(), second = planet.sectors.size > 1 ? planet.sectors.get(1) : first;
        GameContext a = context("generator-post-a"), b = context("generator-post-b");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try{
            Future<?> fa = pool.submit(() -> RuntimeContexts.run(a, () -> a.world.loadSector(first)));
            assertTrue(generator.postStarted.await(5, TimeUnit.SECONDS), "first Context never reached postGenerate");
            Future<?> fb = pool.submit(() -> RuntimeContexts.run(b, () -> b.world.loadSector(second)));
            sleep(200);
            assertFalse(generator.generateEnteredDuringPost.get(), "second Context entered generate while first Context still owned postGenerate");
            generator.releasePost.countDown();
            fa.get(10, TimeUnit.SECONDS);
            fb.get(10, TimeUnit.SECONDS);
        }finally{
            generator.releasePost.countDown(); pool.shutdownNow(); a.dispose(); b.dispose();
        }
    }

    static void await(CyclicBarrier barrier){
        try{ barrier.await(3, TimeUnit.SECONDS); }catch(Exception e){ throw new RuntimeException(e); }
    }
    static void sleep(long millis){
        try{ Thread.sleep(millis); }catch(InterruptedException e){ Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
