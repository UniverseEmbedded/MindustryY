import arc.math.*;
import arc.math.geom.Vec3;
import arc.struct.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;

/** P18 gate ensuring Arc default random helpers honor the RNG bound to the current runtime thread. */
@Tag("shared-campaign-parallel")
public class ArcRandomContextIsolationTests{
    private static final Pattern directLegacyRand = Pattern.compile("Mathf\\.rand\\b(?!\\s*\\()", Pattern.MULTILINE);

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void defaultArcRandomHelpersRemainDeterministicAcrossConcurrentRuntimeBindings() throws Exception{
        long expectedA = stress(0x1122334455667788L, null);
        long expectedB = stress(0x7766554433221100L, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try{
            Future<Long> a = pool.submit(() -> stress(0x1122334455667788L, barrier));
            Future<Long> b = pool.submit(() -> stress(0x7766554433221100L, barrier));
            assertEquals(expectedA, a.get(8, TimeUnit.SECONDS));
            assertEquals(expectedB, b.get(8, TimeUnit.SECONDS));
        }finally{
            pool.shutdownNow();
        }
    }

    @Test
    void runtimeSourcesDoNotAccessLegacyMathfRandFieldDirectly() throws Exception{
        Path mindustry = locateMindustryRoot();
        Path arc = findArcRoot(mindustry).resolve("arc-core/src");
        List<String> offenders = new ArrayList<>();
        scanForDirectLegacyRand(mindustry.resolve("core/src"), null, offenders);
        scanForDirectLegacyRand(arc, arc.resolve("arc/math/Mathf.java"), offenders);
        assertTrue(offenders.isEmpty(), "runtime sources must use Mathf.rand() or a local Rand, not legacy Mathf.rand field: " + offenders);
    }

    @Test
    void bootstrapSeedPathsDoNotMutateProcessFallbackRand() throws Exception{
        Path root = locateMindustryRoot();
        String team = Files.readString(root.resolve("core/src/mindustry/game/Team.java"));
        assertTrue(team.contains("Rand teamRand = new Rand(8)"));
        assertFalse(team.contains("Mathf.rand.setSeed"));
    }

    private static long stress(long seed, CyclicBarrier barrier) throws Exception{
        Rand previous = Mathf.bindRand(new Rand(seed));
        try{
            Seq<Integer> seq = Seq.with(3, 5, 7, 11, 13, 17, 19, 23);
            IntSeq ints = IntSeq.with(2, 4, 6, 8, 10, 12, 14, 16);
            Vec3 vec = new Vec3();
            long hash = 0xcbf29ce484222325L;
            for(int i = 0; i < 4000; i++){
                if(barrier != null && (i & 63) == 0) barrier.await(5, TimeUnit.SECONDS);
                hash = mix(hash, seq.random());
                ints.shuffle();
                hash = mix(hash, ints.get(i & 7));
                vec.setToRandomDirection();
                hash = mix(hash, Float.floatToIntBits(vec.x));
                hash = mix(hash, Float.floatToIntBits(vec.y));
                hash = mix(hash, Float.floatToIntBits(vec.z));
                hash = mix(hash, Float.floatToIntBits(Mathf.random()));
            }
            return hash;
        }finally{
            Mathf.bindRand(previous);
        }
    }

    private static void scanForDirectLegacyRand(Path root, Path excluded, List<String> offenders) throws Exception{
        try(var paths = Files.walk(root)){
            for(Path path : (Iterable<Path>)paths.filter(p -> p.toString().endsWith(".java"))::iterator){
                if(excluded != null && path.normalize().equals(excluded.normalize())) continue;
                Matcher matcher = directLegacyRand.matcher(Files.readString(path));
                if(matcher.find()) offenders.add(root.relativize(path).toString());
            }
        }
    }

    private static Path findArcRoot(Path mindustry){
        for(String name : List.of("ArcSC", "Arc", "ArcY", "Arc-Y-18fd1e1")){
            Path path = mindustry.getParent().resolve(name);
            if(Files.isDirectory(path.resolve("arc-core/src"))) return path;
        }
        throw new AssertionError("cannot locate Arc source sibling next to " + mindustry);
    }

    private static Path locateMindustryRoot(){
        Path path = Paths.get("").toAbsolutePath().normalize();
        for(int i = 0; i < 8 && path != null; i++, path = path.getParent()){
            if(Files.isDirectory(path.resolve("core/src/mindustry")) && Files.isDirectory(path.resolve("tests/src/test/java"))) return path;
        }
        throw new AssertionError("Mindustry source root not found from " + Paths.get("").toAbsolutePath());
    }

    private static long mix(long hash, int value){
        hash ^= value & 0xffffffffL;
        return hash * 0x100000001b3L;
    }
}
