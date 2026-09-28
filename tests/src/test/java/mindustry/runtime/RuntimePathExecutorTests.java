package mindustry.runtime;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class RuntimePathExecutorTests{
    @Test @Timeout(5)
    void multipleContextsShareBoundedWorkersAndRebindOwners() throws Exception{
        try(RuntimePathExecutor pool=new RuntimePathExecutor(2,"path-pool-test-")){
            GameContext a=new GameContext("path-a"),b=new GameContext("path-b"),c=new GameContext("path-c");
            CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1),thirdRan=new CountDownLatch(1);
            AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),wrong=new AtomicInteger();
            Runnable blocker=()->{int now=active.incrementAndGet();peak.accumulateAndGet(now,Math::max);if(RuntimeContexts.current()==null)wrong.incrementAndGet();entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{active.decrementAndGet();}};
            var ha=pool.schedule(a,"Pathfinder",1000,()->{if(RuntimeContexts.current()!=a)wrong.incrementAndGet();blocker.run();});
            var hb=pool.schedule(b,"Pathfinder",1000,()->{if(RuntimeContexts.current()!=b)wrong.incrementAndGet();blocker.run();});
            var hc=pool.schedule(c,"Pathfinder",1000,()->{if(RuntimeContexts.current()!=c)wrong.incrementAndGet();thirdRan.countDown();});
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(2,pool.metrics().parallelism());assertTrue(peak.get()<=2);
            release.countDown();assertTrue(thirdRan.await(2,TimeUnit.SECONDS));assertEquals(0,wrong.get());
            ha.close();hb.close();hc.close();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(pool.metrics().registeredLanes()!=0&&System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(0,pool.metrics().registeredLanes());
        }
    }

    @Test @Timeout(5)
    void recurringLaneNeverOverlapsItself(){
        try(RuntimePathExecutor pool=new RuntimePathExecutor(2,"path-pool-self-test-")){
            GameContext context=new GameContext("path-self");AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),turns=new AtomicInteger();
            var handle=pool.schedule(context,"Pathfinder",1,()->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{Thread.sleep(15);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{turns.incrementAndGet();active.decrementAndGet();}});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(turns.get()<3&&System.nanoTime()<deadline){try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
            handle.close();assertTrue(turns.get()>=3);assertEquals(1,peak.get());assertEquals(0L,pool.metrics().failedTurns());
        }
    }
}
