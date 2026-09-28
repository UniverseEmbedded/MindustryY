import arc.*;
import arc.util.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Lifecycle trip-wires for callbacks/tasks/listeners that must never outlive their owning GameContext. */
@Tag("shared-campaign-lifecycle")
public class RuntimeContextLifecycleTests{
    private record Ping(int value){}

    @Test
    void disposeClearsOwnedQueuesAndRejectsLateCallbacksWithoutTouchingSibling(){
        GameContext owner = new GameContext("lifecycle-owner");
        GameContext sibling = new GameContext("lifecycle-sibling");
        AtomicInteger ownerEvents = new AtomicInteger();
        AtomicInteger siblingEvents = new AtomicInteger();
        AtomicInteger delayed = new AtomicInteger();
        AtomicInteger posted = new AtomicInteger();
        AtomicInteger late = new AtomicInteger();

        RuntimeContexts.run(owner, () -> {
            Events.on(Ping.class, ping -> ownerEvents.incrementAndGet());
            Time.run(10f, delayed::incrementAndGet);
            owner.post(posted::incrementAndGet);
        });
        RuntimeContexts.run(sibling, () -> Events.on(Ping.class, ping -> siblingEvents.incrementAndGet()));
        Runnable capturedLate = RuntimeContexts.capture(owner, late::incrementAndGet);

        assertEquals(1, owner.pendingPostedTasks());
        assertEquals(1, owner.time.pendingRuns());
        owner.dispose();

        assertTrue(owner.closed());
        assertEquals(0, owner.pendingPostedTasks(), "dispose must drop queued authoritative work");
        assertEquals(0, owner.time.pendingRuns(), "dispose must clear delayed simulation work");
        assertEquals(0, posted.get(), "queued work must not run during dispose");
        assertEquals(0, delayed.get(), "delayed work must not run during dispose");
        assertThrows(RejectedExecutionException.class, () -> owner.post(() -> {}));
        assertThrows(RejectedExecutionException.class, capturedLate::run,
            "captured callbacks must fail closed after their owner has been disposed");
        assertEquals(0, late.get());

        RuntimeContexts.run(sibling, () -> Events.fire(new Ping(1)));
        assertEquals(0, ownerEvents.get(), "disposed owner's listener must not receive sibling events");
        assertEquals(1, siblingEvents.get(), "sibling runtime must remain live");
        sibling.dispose();
    }

    @Test
    void oneThousandCreateDisposeCyclesLeaveNoQueuedOrDelayedWork(){
        for(int i = 0; i < 1000; i++){
            GameContext context = new GameContext("dispose-cycle-" + i);
            RuntimeContexts.run(context, () -> {
                context.post(() -> fail("disposed queued task ran"));
                Time.run(5f, () -> fail("disposed delayed task ran"));
                Events.on(Ping.class, ignored -> fail("disposed listener ran"));
            });
            context.dispose();
            assertTrue(context.closed(), "cycle " + i);
            assertEquals(0, context.pendingPostedTasks(), "cycle " + i);
            assertEquals(0, context.time.pendingRuns(), "cycle " + i);
            assertThrows(RejectedExecutionException.class, () -> context.post(() -> {}), "cycle " + i);
        }
    }
}
