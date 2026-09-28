package mindustry.async;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.runtime.*;

import java.util.concurrent.*;

import static mindustry.Vars.*;

public class AsyncCore{
    //all processes to be executed each frame
    public final Seq<AsyncProcess> processes = Seq.with(
        mindustry.Vars.game().unitPhysics,
        mindustry.Vars.game().avoidance = new AvoidanceProcess()
    );

    //futures to be awaited
    private final Seq<Future<?>> futures = new Seq<>();

    private final RuntimeWorkerPool workers = RuntimeWorkerPool.sharedCompute();
    private final GameContext owner = RuntimeContexts.requireCurrent();

    public AsyncCore(){
        Events.on(WorldLoadEvent.class, e -> {
            complete();
            for(AsyncProcess p : processes){
                p.init();
            }
        });

        Events.on(ResetEvent.class, e -> {
            complete();
            for(AsyncProcess p : processes){
                p.reset();
            }
        });
    }

    public void begin(){
        if(mindustry.Vars.game().state.isPlaying()){
            //sync begin
            for(AsyncProcess p : processes){
                p.begin();
            }

            futures.clear();

            // Submit all pure/async phases to the process-owned bounded compute pool. Each task carries the explicit
            // GameContext owner; the pool rebinds it on the worker and rejects callbacks after context disposal.
            for(AsyncProcess p : processes){
                if(p.shouldProcess()){
                    if(RuntimeExecutionBudget.inlineAsyncProcesses()){
                        // On the Android host daemon, outer Sector parallelism is the useful concurrency layer.
                        // Running Physics/Avoidance inline prevents N sectors from recursively expanding into N*M CPU workers.
                        p.process();
                    }else{
                        futures.add(workers.submit(owner, p.getClass().getSimpleName(), p::process));
                    }
                }
            }
        }
    }

    public void end(){
        if(mindustry.Vars.game().state.isPlaying()){
            complete();

            //sync end (flush data)
            for(AsyncProcess p : processes){
                p.end();
            }
        }
    }

    /** Releases this runtime's outstanding async work without destroying the process-owned worker pool. */
    public void dispose(){
        complete();
        workers.release(owner);
    }

    private void complete(){
        //wait for all threads to stop processing
        for(var future : futures){
            try{
                future.get();
            }catch(Throwable t){
                throw new RuntimeException(t);
            }
        }

        //clear processed futures
        futures.clear();
    }
}
