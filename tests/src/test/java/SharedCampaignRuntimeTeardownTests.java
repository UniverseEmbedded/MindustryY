import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.game.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Product gate: orderly coordinator teardown must stop Action agents instead of making them reconnect. */
public class SharedCampaignRuntimeTeardownTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void orderlyCloseStopsEmbeddedAgentBeforeControlPlane() throws Exception{
        Path directory = Files.createTempDirectory("shared-runtime-teardown-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            SharedActionAgent agent;
            Thread controlThread;
            ActionControlPlane control;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Runtime teardown";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = "serpulo";
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
                assertTrue(started.error() == null || started.error().isBlank(), started.error());
                awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                SectorRuntime runtime = service.authority().coordinator().actionRuntimes().runtime(started.actionId());
                assertTrue(runtime instanceof InProcessSectorRuntime, "expected embedded runtime");
                GameContext context = ((InProcessSectorRuntime)runtime).context();
                agent = SharedActionBootstrap.findAgent(context);
                assertNotNull(agent);
                control = service.authority().coordinator().actionRuntimes().controlPlane();
                awaitConnected(control, started.actionId(), Duration.ofSeconds(10));
                controlThread = (Thread)field(SharedActionAgent.class, "controlThread").get(agent);
                assertNotNull(controlThread);
                assertTrue(controlThread.isAlive());

                if(owner != null){ owner.close(); owner = null; }
                service.close();

                controlThread.join(5_000L);
                assertFalse(controlThread.isAlive(), "orderly service close must terminate the embedded Action control thread");
                assertFalse(((AtomicBoolean)field(SharedActionAgent.class, "running").get(agent)).get(),
                    "Action agent must be marked stopped during orderly coordinator teardown");
                assertFalse(control.isRunning(), "control plane must be closed after runtimes are stopped");
                assertEquals(0, scheduler.size(), "orderly teardown leaked an embedded Action runtime");
            }finally{
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
        }finally{
            try(var walk = Files.walk(directory)){
                walk.sorted(Comparator.reverseOrder()).forEach(path -> { try{ Files.deleteIfExists(path); }catch(IOException ignored){} });
            }
        }
    }

    private static Field field(Class<?> type, String name) throws Exception{
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void awaitConnected(ActionControlPlane control, String actionId, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(control.connected(actionId)) return;
            Thread.sleep(25L);
        }
        fail("Action control connection did not become ready");
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) fail("action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(40L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
        return last;
    }
}
