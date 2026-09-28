import arc.graphics.Color;
import arc.math.geom.Vec2;
import arc.util.Tmp;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;

/** Arc scratch objects used by parallel GameContexts must be isolated per worker thread. */
@Tag("shared-campaign-parallel")
public class TmpThreadIsolationTests{
    private static final List<String> accessors = List.of(
        "v1", "v2", "v3", "v4", "v5", "v6",
        "v31", "v32", "v33", "v34",
        "r1", "r2", "r3", "cr1", "cr2", "cr3", "t1",
        "c1", "c2", "c3", "c4", "p1", "p2", "p3",
        "tr1", "tr2", "m1", "m2", "m3", "m4", "bz2", "bz3"
    );

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void runtimeTmpAccessorsAreThreadLocalAndStableWithinAWorker() throws Exception{
        int workers = Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CyclicBarrier barrier = new CyclicBarrier(workers);
        Map<String, Set<Integer>> identities = new ConcurrentHashMap<>();
        for(String accessor : accessors) identities.put(accessor, ConcurrentHashMap.newKeySet());
        try{
            ArrayList<Future<?>> futures = new ArrayList<>();
            for(int worker = 0; worker < workers; worker++){
                final int value = worker + 1;
                futures.add(pool.submit(() -> {
                    Map<String, Object> beforeClear = new HashMap<>();
                    for(String accessor : accessors){
                        Method method = Tmp.class.getMethod(accessor);
                        Object first = method.invoke(null);
                        assertSame(first, method.invoke(null), accessor + " accessor must be stable within one worker thread");
                        beforeClear.put(accessor, first);
                        identities.get(accessor).add(System.identityHashCode(first));
                    }

                    Vec2 vec = Tmp.v1();
                    Color color = Tmp.c1();
                    vec.set(value, -value);
                    color.set(value / 16f, value / 32f, value / 64f, 1f);
                    barrier.await(5, TimeUnit.SECONDS);
                    assertEquals(value, vec.x, 0f, "another worker overwrote thread-local Tmp.v1");
                    assertEquals(-value, vec.y, 0f, "another worker overwrote thread-local Tmp.v1");
                    assertEquals(value / 16f, color.r, 0f, "another worker overwrote thread-local Tmp.c1");

                    Tmp.clearThreadLocal();
                    for(String accessor : accessors){
                        Object afterClear = Tmp.class.getMethod(accessor).invoke(null);
                        assertNotSame(beforeClear.get(accessor), afterClear,
                            accessor + " scratch survived clearThreadLocal on a retired worker");
                    }
                    Tmp.clearThreadLocal();
                    return null;
                }));
            }
            for(Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        }finally{
            pool.shutdownNow();
        }
        for(String accessor : accessors){
            assertEquals(workers, identities.get(accessor).size(), accessor + " was shared by different workers");
        }
    }

    @Test
    void productionSourcesDoNotUseProcessGlobalLegacyTmpFields() throws Exception{
        Path mindustry = findMindustryRoot();
        Path arc = findArcRoot(mindustry);

        String names = String.join("|", accessors.stream().sorted((a, b) -> Integer.compare(b.length(), a.length())).toList());
        Pattern legacy = Pattern.compile("\\bTmp\\.(?:" + names + ")\\b(?!\\s*\\()");
        List<String> failures = new ArrayList<>();
        auditTree(mindustry.resolve("core/src"), legacy, failures);
        auditTree(mindustry.resolve("server/src"), legacy, failures);
        auditTree(arc.resolve("arc-core/src"), legacy, failures);
        auditTree(arc.resolve("extensions"), legacy, failures);
        auditTree(arc.resolve("backends"), legacy, failures);
        assertTrue(failures.isEmpty(), () -> "process-global legacy Tmp scratch remains in production Java:\n" + String.join("\n", failures));
    }

    private static void auditTree(Path root, Pattern legacy, List<String> failures) throws IOException{
        if(!Files.isDirectory(root)) return;
        try(var paths = Files.walk(root)){
            paths.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.getFileName().toString().equals("Tmp.java"))
                .forEach(path -> {
                    try{
                        String code = stripNonCode(Files.readString(path, StandardCharsets.UTF_8));
                        Matcher matcher = legacy.matcher(code);
                        if(matcher.find()) failures.add(path + " -> " + matcher.group());
                    }catch(IOException error){
                        throw new UncheckedIOException(error);
                    }
                });
        }catch(UncheckedIOException error){
            throw error.getCause();
        }
    }

    /** Removes comments and literals while preserving line breaks, so architecture matching only sees Java code. */
    private static String stripNonCode(String source){
        StringBuilder out = new StringBuilder(source.length());
        int state = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 char, 5 text block
        for(int i = 0; i < source.length(); i++){
            char c = source.charAt(i), n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if(state == 0){
                if(c == '/' && n == '/'){ out.append("  "); i++; state = 1; }
                else if(c == '/' && n == '*'){ out.append("  "); i++; state = 2; }
                else if(c == '"' && i + 2 < source.length() && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"'){
                    out.append("   "); i += 2; state = 5;
                }else if(c == '"'){ out.append(' '); state = 3; }
                else if(c == '\''){ out.append(' '); state = 4; }
                else out.append(c);
            }else if(state == 1){
                if(c == '\n'){ out.append('\n'); state = 0; } else out.append(' ');
            }else if(state == 2){
                if(c == '*' && n == '/'){ out.append("  "); i++; state = 0; }
                else out.append(c == '\n' ? '\n' : ' ');
            }else if(state == 3 || state == 4){
                char end = state == 3 ? '"' : '\'';
                if(c == '\\' && i + 1 < source.length()){
                    out.append("  "); i++;
                }else if(c == end){ out.append(' '); state = 0; }
                else out.append(c == '\n' ? '\n' : ' ');
            }else{
                if(c == '"' && i + 2 < source.length() && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"'){
                    out.append("   "); i += 2; state = 0;
                }else out.append(c == '\n' ? '\n' : ' ');
            }
        }
        return out.toString();
    }

    private static Path findArcRoot(Path mindustry){
        for(String name : List.of("ArcSC", "Arc", "ArcY", "Arc-Y-18fd1e1")){
            Path path = mindustry.getParent().resolve(name);
            if(Files.isDirectory(path.resolve("arc-core/src"))) return path;
        }
        throw new AssertionError("cannot locate clean Arc source sibling next to " + mindustry);
    }

    private static Path findMindustryRoot(){
        Path current = Paths.get("").toAbsolutePath().normalize();
        for(int i = 0; i < 8 && current != null; i++, current = current.getParent()){
            if(Files.isDirectory(current.resolve("core/src")) && Files.isRegularFile(current.resolve("build.gradle"))) return current;
        }
        throw new AssertionError("cannot locate Mindustry-Y source root from " + Paths.get("").toAbsolutePath());
    }
}
