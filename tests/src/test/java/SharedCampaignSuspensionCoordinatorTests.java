import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Integration gate proving the authoritative coordinator actually drives clean suspended-sector settlement. */
@Tag("shared-campaign-strategic")
public class SharedCampaignSuspensionCoordinatorTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    @Timeout(20)
    void coordinatorHonorsFreezeThenCommitsStrategicTurn() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-suspension-coordinator-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            SharedCampaignCreationOptions options = options();
            options.freezeWhenEmpty = true;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, freePort());
            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            coordinator.store().transact("owner", "shared-campaign:test-seed-suspended-sector", state -> {
                SectorState sector = new SectorState();
                sector.planetName = "serpulo";
                sector.sectorName = "strategic-test";
                sector.hasBase = true;
                sector.captured = true;
                sector.summary.storageCapacity = 1_000;
                sector.items.put("copper", 10);
                sector.productionPerSecond.put("copper", 1f);
                state.sectors.put(SharedCampaignProgress.sectorKey(sector.planetName, sector.sectorName), sector);
            });

            invokeSettle(coordinator, (long)Vars.turnDuration);
            SharedCampaignState frozen = coordinator.store().snapshot();
            assertEquals(0L, frozen.campaignTick, "empty frozen campaign must not accumulate strategic catch-up time");
            assertEquals(10, frozen.sectors.get("serpulo:strategic-test").items.get("copper", 0));

            coordinator.store().transact("owner", "shared-campaign:test-unfreeze", state -> state.freezeWhenEmpty = false);
            invokeSettle(coordinator, (long)Vars.turnDuration);
            SharedCampaignState advanced = coordinator.store().snapshot();
            assertEquals((long)Vars.turnDuration, advanced.campaignTick);
            assertEquals(130, advanced.sectors.get("serpulo:strategic-test").items.get("copper", 0));
            assertTrue(advanced.discovered.contains("copper"));
        }finally{
            service.close();
            deleteTree(directory);
        }
    }

    private static void invokeSettle(SharedCampaignCoordinator coordinator, long ticks) throws Exception{
        Method method = SharedCampaignCoordinator.class.getDeclaredMethod("settleNow", long.class);
        method.setAccessible(true);
        method.invoke(coordinator, ticks);
    }

    private static SharedCampaignCreationOptions options(){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = "Suspension coordinator";
        options.ownerId = "owner";
        options.ownerDisplayName = "Owner";
        options.primaryPlanetName = "serpulo";
        return options;
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
