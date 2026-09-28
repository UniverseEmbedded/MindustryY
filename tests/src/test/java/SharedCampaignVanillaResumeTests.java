import arc.files.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.game.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Product gate for pure-vanilla transfer waking an existing suspended Action without inventing a fresh launch. */
@Tag("shared-campaign-backend-differential")
public class SharedCampaignVanillaResumeTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void vanillaTransferResumesSuspendedActionBeforePreparingDirectAdmission() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-vanilla-resume-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Vanilla resume";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = "serpulo";
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
                assertTrue(started.error() == null || started.error().isBlank(), started.error());
                awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));
                owner.suspendAction(started.actionId());
                SharedCampaignState suspended = awaitStatus(owner, started.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
                assertEquals(1L, suspended.actions.get(started.actionId()).runtimeIncarnation);

                String sourceId = "vanilla-source-action";
                service.authority().coordinator().store().transact("owner", "test:add-vanilla-source", state -> {
                    ActionState source = new ActionState();
                    source.actionId = sourceId;
                    source.planetName = "serpulo";
                    source.sectorName = "vanilla-source-fixture";
                    source.status = ActionStatus.running;
                    source.kind = ActionKind.baseBuilding;
                    source.hostId = "coordinator";
                    source.hostGeneration = state.authorityGeneration;
                    source.runtimeIncarnation = 1L;
                    source.leaseExpiresAt = Long.MAX_VALUE;
                    source.bindAddress = "127.0.0.1";
                    source.port = 65001;
                    source.launchCommitted = true;
                    state.actions.put(sourceId, source);
                });

                String platformUuid = new String(Base64Coder.encode(new byte[]{1,2,3,4,5,6,7,8}));
                RuntimePayloads.VanillaTransferRequest request = new RuntimePayloads.VanillaTransferRequest(
                    sourceId, started.actionId(), "owner", platformUuid, "203.0.113.55:44000", false);
                RuntimePayloads.VanillaTransferResult result = service.authority().coordinator().actionCommands().prepareVanillaTransfer(sourceId, request);
                assertTrue(result.error() == null || result.error().isBlank(), result.error());
                assertEquals(started.actionId(), result.actionId());
                assertTrue(result.port() > 0);
                assertTrue(result.expiresAt() > System.currentTimeMillis());

                SharedCampaignState resumed = awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));
                assertEquals(2L, resumed.actions.get(started.actionId()).runtimeIncarnation,
                    "vanilla transfer must resume the suspended logical Action rather than create a replacement");
                assertTrue(service.authority().coordinator().actionRuntimes().controlPlane().connected(started.actionId()));
            }finally{
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "vanilla resume test leaked an embedded runtime");
        }finally{
            deleteTree(directory);
        }
    }


    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void vanillaTransferCanLaunchFreshQualifiedSectorThroughAuthoritativeEconomics() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-vanilla-fresh-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Vanilla fresh launch";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = "serpulo";
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                Sector destination = SharedCampaignProgress.findSector("serpulo", "frozenForest");
                assertNotNull(destination);
                String destinationId = SharedCampaignSectors.sectorId(destination);
                String sourceId = "vanilla-source-action";
                service.authority().coordinator().store().transact("owner", "test:add-offline-launch-source", state -> {
                    SectorState strategic = new SectorState();
                    strategic.planetName = "serpulo";
                    strategic.sectorName = "groundZero";
                    strategic.hasBase = true;
                    strategic.captured = true;
                    strategic.summary = new SectorSummary();
                    strategic.summary.planetName = "serpulo";
                    strategic.summary.coreType = "core-foundation";
                    for(var item : Vars.content.items()) strategic.items.put(item.name, 100_000);
                    state.sectors.put(SharedCampaignSectors.sectorKey("serpulo", "groundZero"), strategic);
                    state.researched.add("junction");
                    state.researched.add("router");

                    ActionState source = new ActionState();
                    source.actionId = sourceId;
                    source.planetName = "serpulo";
                    source.sectorName = "vanilla-command-source";
                    source.status = ActionStatus.running;
                    source.kind = ActionKind.baseBuilding;
                    source.hostId = "coordinator";
                    source.hostGeneration = state.authorityGeneration;
                    source.runtimeIncarnation = 1L;
                    source.leaseExpiresAt = Long.MAX_VALUE;
                    source.bindAddress = "127.0.0.1";
                    source.port = 65001;
                    source.launchCommitted = true;
                    state.actions.put(sourceId, source);
                });

                String platformUuid = new String(Base64Coder.encode(new byte[]{8,7,6,5,4,3,2,1}));
                RuntimePayloads.VanillaTransferRequest request = new RuntimePayloads.VanillaTransferRequest(
                    sourceId, "serpulo:" + destinationId, "owner", platformUuid, "203.0.113.61:44000", false);
                RuntimePayloads.VanillaTransferResult result = service.authority().coordinator().actionCommands().prepareVanillaTransfer(sourceId, request);
                assertTrue(result.error() == null || result.error().isBlank(), result.error());
                assertFalse(result.actionId().isBlank());
                assertTrue(result.port() > 0);

                SharedCampaignState running = awaitStatus(owner, result.actionId(), ActionStatus.running, Duration.ofSeconds(30));
                ActionState action = running.actions.get(result.actionId());
                assertEquals("serpulo", action.planetName);
                assertEquals(destinationId, action.sectorName);
                assertTrue(action.launchCommitted, "fresh vanilla transfer must complete the same durable launch transaction as the normal control plane");
                assertEquals(SharedCampaignSectors.sectorKey("serpulo", "groundZero"), action.launchOriginSector);
                assertFalse(action.launchCosts.isEmpty(), "fresh-sector launch should preserve authoritative loadout/resource costs");

                SectorState debited = running.sectors.get(SharedCampaignSectors.sectorKey("serpulo", "groundZero"));
                assertNotNull(debited);
                for(var cost : action.launchCosts){
                    assertEquals(100_000 - cost.value, debited.items.get(cost.key, 0), "offline launch debit mismatch for " + cost.key);
                }
            }finally{
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "fresh vanilla launch test leaked an embedded runtime");
        }finally{
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void spectatorCannotCreateFreshSectorThroughVanillaTransfer() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-vanilla-fresh-spectator-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Vanilla spectator fresh guard";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = "serpulo";
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                String sourceId = "vanilla-source-action";
                service.authority().coordinator().store().transact("owner", "test:add-vanilla-source", state -> {
                    ActionState source = new ActionState();
                    source.actionId = sourceId;
                    source.planetName = "serpulo";
                    source.sectorName = "vanilla-command-source";
                    source.status = ActionStatus.running;
                    source.kind = ActionKind.baseBuilding;
                    source.hostId = "coordinator";
                    source.hostGeneration = state.authorityGeneration;
                    source.runtimeIncarnation = 1L;
                    source.leaseExpiresAt = Long.MAX_VALUE;
                    source.bindAddress = "127.0.0.1";
                    source.port = 65001;
                    source.launchCommitted = true;
                    state.actions.put(sourceId, source);
                });

                String platformUuid = new String(Base64Coder.encode(new byte[]{9,7,5,3,1,2,4,6}));
                RuntimePayloads.VanillaTransferRequest request = new RuntimePayloads.VanillaTransferRequest(
                    sourceId, "serpulo:groundZero", "owner", platformUuid, "203.0.113.62:44000", true);
                RuntimePayloads.VanillaTransferResult result = service.authority().coordinator().actionCommands().prepareVanillaTransfer(sourceId, request);
                assertFalse(result.error() == null || result.error().isBlank());
                assertTrue(result.error().toLowerCase(Locale.ROOT).contains("spectator"), result.error());
                assertEquals(1, service.state().actions.size, "spectator request must not create a destination Action");
            }finally{
                service.close();
            }
        }finally{
            deleteTree(directory);
        }
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) fail("action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(50L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
        return last;
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
