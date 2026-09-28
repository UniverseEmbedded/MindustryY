package mindustry.game;

import arc.*;
import arc.math.*;
import arc.struct.Bits;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.annotations.Annotations.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.io.SaveFileReader.*;
import mindustry.io.*;
import mindustry.world.meta.*;
import mindustry.runtime.*;

import java.io.*;
import java.util.concurrent.*;

import static mindustry.Vars.*;

public final class FogControl implements CustomChunk{
    private volatile int ww, wh;
    private static final int dynamicUpdateInterval = 1000 / 25; //25 FPS

    /** indexed by team */
    private volatile @Nullable FogData[] fog;

    private final LongSeq staticEvents = new LongSeq();
    private final LongSeq dynamicEventQueue = new LongSeq(), unitEventQueue = new LongSeq();
    /** access must be synchronized; accessed from both threads */
    private final LongSeq dynamicEvents = new LongSeq(100);

    private volatile @Nullable Future<?> staticFogTask;
    private volatile @Nullable Future<?> dynamicFogTask;

    private boolean justLoaded = false;
    private boolean loadedStatic = false;
    private int lastEntityUpdateIndex = 0;

    public FogControl(){
        Events.on(ResetEvent.class, e -> {
            stop();
        });

        Events.on(WorldLoadEvent.class, e -> {
            stop();

            loadedStatic = false;
            justLoaded = true;
            ww = mindustry.Vars.game().world.width();
            wh = mindustry.Vars.game().world.height();

            //all old buildings have static light scheduled around them
            if(mindustry.Vars.game().state.rules.fog && mindustry.Vars.game().state.rules.staticFog){
                pushStaticBlocks(true);
                //force draw all static stuff immediately
                updateStatic();

                loadedStatic = true;
            }
        });

        Events.on(TileChangeEvent.class, event -> {
            if(mindustry.Vars.game().state.rules.fog && event.tile.build != null && event.tile.isCenter() && !event.tile.build.team.isOnlyAI() && event.tile.block().flags.contains(BlockFlag.hasFogRadius)){
                var data = data(event.tile.team());
                if(data != null){
                    data.dynamicUpdated = true;
                }

                if(mindustry.Vars.game().state.rules.staticFog){
                    synchronized(staticEvents){
                        //TODO event per team?
                        pushEvent(FogEvent.get(event.tile.x, event.tile.y, Mathf.round(event.tile.build.fogRadius()), event.tile.build.team.id), false);
                    }
                }
            }
        });

        //on tile removed, dynamic fog goes away
        Events.on(TilePreChangeEvent.class, e -> {
            if(mindustry.Vars.game().state.rules.fog && e.tile.build != null && !e.tile.build.team.isOnlyAI() && e.tile.block().flags.contains(BlockFlag.hasFogRadius)){
                var data = data(e.tile.team());
                if(data != null){
                    data.dynamicUpdated = true;
                }
            }
        });

        //unit dead -> fog updates
        Events.on(UnitDestroyEvent.class, e -> {
            if(mindustry.Vars.game().state.rules.fog && fog[e.unit.team.id] != null){
                fog[e.unit.team.id].dynamicUpdated = true;
            }
        });

        SaveVersion.addCustomChunk("static-fog-data", this);
    }

    public @Nullable Bits getDiscovered(Team team){
        return fog == null || fog[team.id] == null ? null : fog[team.id].staticData;
    }

    public boolean isDiscovered(Team team, int x, int y){
        if(!mindustry.Vars.game().state.rules.staticFog || !mindustry.Vars.game().state.rules.fog || team == null || team.isAI()) return true;

        var data = getDiscovered(team);
        if(data == null) return false;
        if(x < 0 || y < 0 || x >= ww || y >= wh) return false;
        return data.get(x + y * ww);
    }

    public boolean isVisible(Team team, float x, float y){
        return isVisibleTile(team, World.toTile(x), World.toTile(y));
    }

    public boolean isVisibleTile(Team team, int x, int y){
        if(!mindustry.Vars.game().state.rules.fog || team == null || team.isAI()) return true;

        var data = data(team);
        if(data == null) return false;
        return data.read.get(Mathf.clamp(x, 0, ww - 1) + Mathf.clamp(y, 0, wh - 1) * ww);
    }

    public void resetFog(){
        fog = null;
    }

    @Nullable FogData data(Team team){
        return fog == null || fog[team.id] == null ? null : fog[team.id];
    }

    public void stop(){
        // Fog computation now runs on the process-bounded RuntimeWorkerPool. Drain in-flight tasks before clearing
        // context-owned buffers so a disposed/reloaded world cannot be mutated by a stale worker callback.
        awaitFogTask(staticFogTask, "static");
        awaitFogTask(dynamicFogTask, "dynamic");
        staticFogTask = null;
        dynamicFogTask = null;
        lastEntityUpdateIndex = 0;
        synchronized(staticEvents){ staticEvents.clear(); }
        synchronized(dynamicEvents){ dynamicEvents.clear(); }
        fog = null;
    }

    private void awaitFogTask(@Nullable Future<?> task, String lane){
        if(task == null) return;
        try{
            task.get(5L, TimeUnit.SECONDS);
        }catch(CancellationException ignored){
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
        }catch(ExecutionException error){
            Log.err("Fog @ worker failed during shutdown: @", lane);
            Log.err(error.getCause());
        }catch(TimeoutException timeout){
            task.cancel(true);
            Log.warn("Fog @ worker did not quiesce within shutdown grace period: @", lane);
        }
    }

    /** @param initial whether this is the initial update; if true, does not update renderer */
    void pushStaticBlocks(boolean initial){
        if(fog == null) fog = new FogData[256];

        synchronized(staticEvents){
            for(var build : Groups.current().build){
                if(build.block.flags.contains(BlockFlag.hasFogRadius)){
                    if(fog[build.team.id] == null){
                        fog[build.team.id] = new FogData();
                    }

                    pushEvent(FogEvent.get(build.tile.x, build.tile.y, Mathf.round(build.fogRadius()), build.team.id), initial);
                }
            }
        }
    }

    /** @param skipRender whether the event is passed to the fog renderer */
    void pushEvent(long event, boolean skipRender){
        if(!mindustry.Vars.game().state.rules.staticFog) return;

        staticEvents.add(event);
        if(!skipRender && runtimeVisualsEnabled() && FogEvent.team(event) == Vars.player.team().id){
            renderer.fog.handleEvent(event);
        }
    }

    public void forceUpdate(Team team, Building build){
        if(mindustry.Vars.game().state.rules.fog && fog[team.id] != null){
            fog[team.id].dynamicUpdated = true;

            if(mindustry.Vars.game().state.rules.staticFog){
                synchronized(staticEvents){
                    pushEvent(FogEvent.get(build.tile.x, build.tile.y, Mathf.round(build.fogRadius()), build.team.id), false);
                }
            }
        }
    }

    public void update(){
        if(fog == null){
            fog = new FogData[256];
        }

        //force update static
        if(mindustry.Vars.game().state.rules.staticFog && !loadedStatic){
            pushStaticBlocks(false);
            updateStatic();
            loadedStatic = true;
        }

        //clear to prepare for queuing fog radius from units and buildings
        dynamicEventQueue.clear();

        //update fog visibility manually
        if(mindustry.Vars.game().state.rules.fog && runtimeVisualsEnabled() && Groups.current().build.size() > 0){

            int size = Groups.current().build.size();
            int chunkSize = 5; //fraction of entity list to iterate each frame
            int chunks = Math.min(chunkSize, size);

            int iterated = Math.max(1, size / chunks);
            int steps = 0;
            int i = lastEntityUpdateIndex % size;

            while(steps < iterated){
                Groups.current().build.index(i).updateFogVisibility();

                steps ++;
                i ++;

                if(i >= size){
                    i = 0;
                }
            }

            lastEntityUpdateIndex = i;
        }

        for(var team : mindustry.Vars.game().state.teams.present){
            //AI teams do not have fog
            if(!team.team.isOnlyAI()){
                //separate for each team
                unitEventQueue.clear();

                FogData data = fog[team.team.id];

                if(data == null){
                    data = fog[team.team.id] = new FogData();
                }

                synchronized(staticEvents){
                    //TODO slow?
                    for(var unit : team.units){
                        int tx = unit.tileX(), ty = unit.tileY(), pos = tx + ty * ww;
                        if(unit.type.fogRadius <= 0f) continue;
                        long event = FogEvent.get(tx, ty, (int)unit.type.fogRadius, team.team.id);

                        //always update the dynamic events, but only *flush* the results when necessary?
                        unitEventQueue.add(event);

                        if(unit.lastFogPos != pos){
                            pushEvent(event, false);
                            unit.lastFogPos = pos;
                            data.dynamicUpdated = true;
                        }
                    }
                }

                //if it's time for an update, flush *everything* onto the update queue
                if(data.dynamicUpdated && Time.timeSinceMillis(data.lastDynamicMs) > dynamicUpdateInterval){
                    data.dynamicUpdated = false;
                    data.lastDynamicMs = Time.millis();

                    //add building updates
                    for(var build : mindustry.Vars.game().indexer.getFlagged(team.team, BlockFlag.hasFogRadius)){
                        dynamicEventQueue.add(FogEvent.get(build.tile.x, build.tile.y, Mathf.round(build.fogRadius()), build.team.id));
                    }

                    //add unit updates
                    dynamicEventQueue.addAll(unitEventQueue);
                }
            }
        }

        if(dynamicEventQueue.size > 0){
            //flush unit events over when something happens
            synchronized(dynamicEvents){
                dynamicEvents.clear();
                dynamicEvents.addAll(dynamicEventQueue);
            }
            dynamicEventQueue.clear();

            //force update so visibility doesn't have a pop-in
            if(justLoaded){
                updateDynamic(new Bits(256));
                justLoaded = false;
            }else{
                scheduleDynamicFog();
            }
        }

        if(mindustry.Vars.game().state.rules.staticFog && staticEvents.size > 0) scheduleStaticFog();
    }

    private void scheduleStaticFog(){
        Future<?> current = staticFogTask;
        if(current != null && !current.isDone()) return;
        GameContext owner = mindustry.Vars.game();
        try{
            staticFogTask = RuntimeWorkerPool.sharedCompute().submit(owner, "fog-static", this::updateStatic);
        }catch(RejectedExecutionException saturated){
            // Fog correctness beats background throughput under saturation; the authoritative lane is the safe fallback.
            updateStatic();
            staticFogTask = null;
        }
    }

    private void scheduleDynamicFog(){
        Future<?> current = dynamicFogTask;
        if(current != null && !current.isDone()) return;
        GameContext owner = mindustry.Vars.game();
        try{
            dynamicFogTask = RuntimeWorkerPool.sharedCompute().submit(owner, "fog-dynamic", () -> updateDynamic(new Bits(256)));
        }catch(RejectedExecutionException saturated){
            updateDynamic(new Bits(256));
            dynamicFogTask = null;
        }
    }

    void updateStatic(){

        //I really don't like synchronizing here, but there should be *some* performance benefit at least
        synchronized(staticEvents){
            int size = staticEvents.size;
            for(int i = 0; i < size; i++){
                long event = staticEvents.items[i];
                int x = FogEvent.x(event), y = FogEvent.y(event), rad = FogEvent.radius(event), team = FogEvent.team(event);
                var data = fog[team];
                if(data != null){
                    circle(data.staticData, x, y, rad);
                }
            }
            staticEvents.clear();
        }
    }

    void updateDynamic(Bits cleared){
        cleared.clear();

        //ugly sync
        synchronized(dynamicEvents){
            int size = dynamicEvents.size;

            //draw step
            for(int i = 0; i < size; i++){
                long event = dynamicEvents.items[i];
                int x = FogEvent.x(event), y = FogEvent.y(event), rad = FogEvent.radius(event), team = FogEvent.team(event);

                if(rad <= 0) continue;

                var data = fog[team];
                if(data != null){

                    //clear the buffer, since it is being re-drawn
                    if(!cleared.get(team)){
                        cleared.set(team);

                        data.write.clear();
                    }

                    //radius is always +1 to keep up with visuals
                    circle(data.write, x, y, rad + 1);
                }
            }
            dynamicEvents.clear();
        }

        //swap step, no need for synchronization or anything
        for(int i = 0; i < 256; i++){
            if(cleared.get(i)){
                var data = fog[i];

                //swap buffers, flushing the data that was just drawn
                Bits temp = data.read;
                data.read = data.write;
                data.write = temp;
            }
        }
    }

    @Override
    public void write(DataOutput stream) throws IOException{
        int used = 0;
        for(int i = 0; i < 256; i++){
            if(fog[i] != null) used ++;
        }

        stream.writeByte(used);
        stream.writeShort(mindustry.Vars.game().world.width());
        stream.writeShort(mindustry.Vars.game().world.height());

        for(int i = 0; i < 256; i++){
            if(fog[i] != null){
                stream.writeByte(i);
                Bits data = fog[i].staticData;
                int size = ww * wh;

                int pos = 0;
                while(pos < size){
                    int consecutives = 0;
                    boolean cur = data.get(pos);
                    while(consecutives < 127 && pos < size){
                        if(cur != data.get(pos)){
                            break;
                        }

                        consecutives ++;
                        pos ++;
                    }
                    int mask = (cur ? 0b1000_0000 : 0);
                    stream.write(mask | (consecutives));
                }
            }
        }
    }

    @Override
    public void read(DataInput stream) throws IOException{
        if(fog == null) fog = new FogData[256];

        int teams = stream.readUnsignedByte();
        int w = stream.readShort(), h = stream.readShort();
        int len = w * h;

        ww = w;
        wh = h;

        for(int ti = 0; ti < teams; ti++){
            int team = stream.readUnsignedByte();
            fog[team] = new FogData();

            int pos = 0;
            Bits bools = fog[team].staticData;

            while(pos < len){
                int data = stream.readByte() & 0xff;
                boolean sign = (data & 0b1000_0000) != 0;
                int consec = data & 0b0111_1111;

                if(sign){
                    bools.set(pos, pos + consec);
                    pos += consec;
                }else{
                    pos += consec;
                }
            }
        }

    }

    @Override
    public boolean shouldWrite(){
        return mindustry.Vars.game().state.rules.fog && mindustry.Vars.game().state.rules.staticFog && fog != null;
    }

    void circle(Bits arr, int x, int y, int radius){
        int f = 1 - radius;
        int ddFx = 1, ddFy = -2 * radius;
        int px = 0, py = radius;

        hline(arr, x, x, y + radius);
        hline(arr, x, x, y - radius);
        hline(arr, x - radius, x + radius, y);

        while(px < py){
            if(f >= 0){
                py--;
                ddFy += 2;
                f += ddFy;
            }
            px++;
            ddFx += 2;
            f += ddFx;
            hline(arr, x - px, x + px, y + py);
            hline(arr, x - px, x + px, y - py);
            hline(arr, x - py, x + py, y + px);
            hline(arr, x - py, x + py, y - px);
        }
    }

    void hline(Bits arr, int x1, int x2, int y){
        if(y < 0 || y >= wh) return;
        int tmp;

        if(x1 > x2){
            tmp = x1;
            x1 = x2;
            x2 = tmp;
        }

        if(x1 >= ww) return;
        if(x2 < 0) return;

        if(x1 < 0) x1 = 0;
        if(x2 >= ww) x2 = ww - 1;
        x2++;
        int off = y * ww;

        arr.set(off + x1, off + x2);
    }

    class FogData{
        /** dynamic double-buffered data for dynamic (live) coverage */
        volatile Bits read, write;
        /** static map exploration fog*/
        final Bits staticData;

        /** last dynamic update timestamp. */
        long lastDynamicMs = 0;
        /** if true, a dynamic fog update must be scheduled. */
        boolean dynamicUpdated = true;

        FogData(){
            int len = ww * wh;

            read = new Bits(len);
            write = new Bits(len);
            staticData = new Bits(len);
        }
    }

    @Struct
    class FogEventStruct{
        @StructField(16)
        int x;
        @StructField(16)
        int y;
        @StructField(16)
        int radius;
        @StructField(8)
        int team;
    }
}
