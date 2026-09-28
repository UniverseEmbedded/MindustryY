import arc.*;
import arc.math.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Scheduling order must not change per-GameContext results when each Context receives the same local inputs. */
@Tag("shared-campaign-parallel")
public class RuntimeSchedulePermutationTests{
    static final class Ping{}
    private static final int steps = 200;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void forwardReverseBurstRandomAndConcurrentSchedulesAreEquivalent() throws Exception{
        Result reference = run(scheduleForward(), false);
        assertEquals(reference, run(scheduleReverse(), false), "reversing Context order changed local runtime state");
        assertEquals(reference, run(scheduleBurst(), false), "burst scheduling changed local runtime state");
        assertEquals(reference, run(scheduleRandom(), false), "seeded random scheduling changed local runtime state");
        assertEquals(reference, run(null, true), "concurrent Context execution changed local runtime state");
    }

    private static Result run(List<Character> schedule, boolean concurrent) throws Exception{
        GameContext a = context("A", 0xA11CEL);
        GameContext b = context("B", 0xB22CEL);
        Local la = new Local(), lb = new Local();
        bind(a, la); bind(b, lb);
        try{
            if(concurrent){
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try{
                    for(int i = 0; i < steps; i++){
                        Future<?> fa = pool.submit(() -> step(a, la));
                        Future<?> fb = pool.submit(() -> step(b, lb));
                        fa.get(); fb.get();
                    }
                }finally{ pool.shutdownNow(); }
            }else{
                for(char which : schedule) step(which == 'A' ? a : b, which == 'A' ? la : lb);
            }
            // drain delayed tasks that were intentionally scheduled on the last two local steps.
            for(int i = 0; i < 3; i++){ step(a, la); step(b, lb); }
            return new Result(snapshot(a, la), snapshot(b, lb));
        }finally{
            a.dispose(); b.dispose();
        }
    }

    private static GameContext context(String id, long seed){
        GameContext context = new GameContext(id, new GameState(), null);
        context.logicVars = new GlobalVars();
        context.mathRand.setSeed(seed ^ 0x6A09E667F3BCC909L);
        RuntimeContexts.run(context, () -> {
            context.logicVars.rand.setSeed(seed);
            Time.setTime(id.equals("A") ? 10f : 1000f);
            Time.setDelta(id.equals("A") ? 1f : 0.5f);
        });
        return context;
    }

    private static void bind(GameContext context, Local local){
        RuntimeContexts.run(context, () -> Events.on(Ping.class, ignored -> local.events++));
    }

    private static void step(GameContext context, Local local){
        RuntimeContexts.run(context, () -> {
            int index = local.steps++;
            if(index % 11 == 0) Time.run(2f, () -> local.delayed++);
            Events.fire(new Ping());
            int entity = EntityGroup.nextId();
            int random = context.logicVars.rand.nextInt(1_000_000);
            int mathRandom = Mathf.random(1_000_000);
            local.checksum = local.checksum * 0x9E3779B97F4A7C15L + ((long)entity << 32) + (random & 0xffffffffL);
            local.checksum = local.checksum * 0xD1B54A32D192ED03L + (mathRandom & 0xffffffffL);
            Time.update();
        });
    }

    private static Snapshot snapshot(GameContext context, Local local){
        final float[] time = new float[1];
        final int[] next = new int[1], pending = new int[1];
        RuntimeContexts.run(context, () -> {
            time[0] = Time.time();
            next[0] = context.lastEntityId();
            pending[0] = Time.current().pendingRuns();
        });
        return new Snapshot(local.steps, local.events, local.delayed, local.checksum, time[0], next[0], pending[0]);
    }

    private static List<Character> scheduleForward(){
        ArrayList<Character> out = new ArrayList<>(steps * 2);
        for(int i = 0; i < steps; i++){ out.add('A'); out.add('B'); }
        return out;
    }
    private static List<Character> scheduleReverse(){
        ArrayList<Character> out = new ArrayList<>(steps * 2);
        for(int i = 0; i < steps; i++){ out.add('B'); out.add('A'); }
        return out;
    }
    private static List<Character> scheduleBurst(){
        ArrayList<Character> out = new ArrayList<>(steps * 2);
        for(int i = 0; i < steps / 2; i++){ out.add('A'); out.add('A'); out.add('B'); out.add('B'); }
        return out;
    }
    private static List<Character> scheduleRandom(){
        ArrayList<Character> out = new ArrayList<>(steps * 2);
        for(int i = 0; i < steps; i++){ out.add('A'); out.add('B'); }
        Collections.shuffle(out, new Random(0x5EEDC0DEL));
        return out;
    }

    static final class Local{ int steps, events, delayed; long checksum; }
    record Snapshot(int steps, int events, int delayed, long checksum, float time, int nextEntityId, int pendingDelayed){}
    record Result(Snapshot a, Snapshot b){}
}
