import arc.files.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression gate for the coordinator-monitor/store shutdown ordering. */
@Tag("shared-campaign-lifecycle")
public class SharedCampaignCoordinatorShutdownTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    @Timeout(20)
    void coordinatorMonitorTerminatesBeforeServiceClosesStore() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-monitor-close-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            service.createLocal(new Fi(directory.toString()), options(), "127.0.0.1", 0, freePort());
            ActionRuntimeCoordinator coordinator = service.authority().coordinator().actionRuntimes();
            assertNotNull(coordinator);
            ScheduledExecutorService monitor = monitorOf(coordinator);
            ScheduledExecutorService strategic = strategicMonitorOf(service.authority().coordinator());
            assertFalse(monitor.isShutdown());
            assertFalse(strategic.isShutdown());

            service.close();

            assertTrue(monitor.isShutdown(), "coordinator close must stop the periodic monitor before store teardown");
            assertTrue(monitor.awaitTermination(1, TimeUnit.SECONDS), "coordinator close must join the monitor rather than leave a stale store reader");
            assertTrue(monitor.isTerminated());
            assertTrue(strategic.isShutdown(), "strategic settlement monitor must stop before store teardown");
            assertTrue(strategic.awaitTermination(1, TimeUnit.SECONDS));
            assertTrue(strategic.isTerminated());
        }finally{
            service.close();
            deleteTree(directory);
        }
    }

    private static SharedCampaignCreationOptions options(){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = "Monitor shutdown"; options.ownerId = "owner"; options.ownerDisplayName = "Owner"; options.primaryPlanetName = "serpulo";
        return options;
    }

    private static ScheduledExecutorService strategicMonitorOf(SharedCampaignCoordinator coordinator) throws Exception{
        Field field = SharedCampaignCoordinator.class.getDeclaredField("strategicMonitor");
        field.setAccessible(true);
        return (ScheduledExecutorService)field.get(coordinator);
    }

    private static ScheduledExecutorService monitorOf(ActionRuntimeCoordinator coordinator) throws Exception{
        Field field = ActionRuntimeCoordinator.class.getDeclaredField("monitor");
        field.setAccessible(true);
        return (ScheduledExecutorService)field.get(coordinator);
    }

    private static int freePort() throws IOException{
        try(ServerSocket socket = new ServerSocket(0)){ socket.setReuseAddress(true); return socket.getLocalPort(); }
    }

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root)) return;
        try(var paths = Files.walk(root)){
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try{ Files.deleteIfExists(path); }catch(IOException error){ throw new UncheckedIOException(error); }
            });
        }catch(UncheckedIOException error){ throw error.getCause(); }
    }
}
