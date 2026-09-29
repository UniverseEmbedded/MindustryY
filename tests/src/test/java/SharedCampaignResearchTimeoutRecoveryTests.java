import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** A timed-out PREPARE may still run later on the Action; the coordinator must retain and replay ABORT. */
public class SharedCampaignResearchTimeoutRecoveryTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(true); if(Vars.schematics == null){ Vars.schematics = new mindustry.game.Schematics(); Vars.schematics.load(); } }

    @Test
    @Timeout(60)
    void timedOutPrepareThatExecutesLateIsRefundedExactlyOnce() throws Exception{
        String oldUiTest = System.getProperty("mindustry.uiTest");
        String oldTimeout = System.getProperty("mindustry.sharedCampaign.researchPrepareTimeoutMillis");
        System.setProperty("mindustry.uiTest", "true");
        System.setProperty("mindustry.sharedCampaign.researchPrepareTimeoutMillis", "200");
        Path root = Files.createTempDirectory("shared-research-timeout-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.ownerId = "owner"; options.ownerDisplayName = "Owner"; options.displayName = "Research timeout";
                options.primaryPlanetName = Planets.serpulo.name; options.maxActiveActions = 2;
                service.createLocal(new Fi(root.resolve("campaign").toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();
                RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
                assertTrue(started.error() == null || started.error().isBlank(), started.error());
                awaitStatus(owner, started.actionId(), ActionStatus.running, 30_000L);

                TechTree.TechNode node = TechTree.all.find(candidate -> candidate.content != null && !candidate.content.alwaysUnlocked
                    && candidate.requirements != null && candidate.requirements.length > 0
                    && SharedCampaignProgress.researchPlanetContains(candidate, Planets.serpulo.name));
                assertNotNull(node);
                String sectorKey = SharedCampaignSectors.sectorKey("serpulo", "groundZero");
                InProcessSectorRuntime runtime = (InProcessSectorRuntime)service.authority().coordinator().actionRuntimes().runtime(started.actionId());
                assertNotNull(runtime);

                ObjectMap<String, Integer> before = new ObjectMap<>();
                RuntimeContexts.run(runtime.context(), () -> {
                    var core = Vars.game().state.rules.defaultTeam.core();
                    assertNotNull(core);
                    for(ItemStack requirement : node.requirements){
                        int amount = requirement.amount + 50;
                        core.items.set(requirement.item, amount);
                        before.put(requirement.item.name, amount);
                    }
                });
                service.authority().coordinator().store().transact("owner", "test:seed-live-research", state -> {
                    for(TechTree.TechNode parent = node.parent; parent != null; parent = parent.parent) state.researched.add(parent.content.name);
                    SectorState sector = state.sectors.get(sectorKey);
                    assertNotNull(sector, "running Action must already have an authoritative sector");
                    sector.hasBase = true; sector.captured = true;
                    for(ItemStack requirement : node.requirements){
                        int amount = requirement.amount + 50;
                        sector.items.put(requirement.item.name, amount);
                        sector.summary.items.put(requirement.item.name, amount);
                    }
                });

                runtime.setManualTicksForTesting(true);
                SharedCampaignClient requestClient = owner;
                Future<Throwable> result = caller.submit(() -> {
                    try{ requestClient.research(node.content.name, Planets.serpulo.name); return null; }
                    catch(Throwable error){ return error; }
                });

                ResearchTransaction aborted = awaitAborted(service, 5_000L);
                assertNotNull(aborted);
                assertTrue(aborted.preparedActions.contains(started.actionId()),
                    "timeout leaves PREPARE delivery uncertain, so the contacted Action must remain in durable ABORT replay state");

                // First manual tick lets the already-queued PREPARE debit and persist after the coordinator timed out.
                // The Action reader can then consume the queued ABORT; a following tick executes/refunds that decision.
                for(int i = 0; i < 20 && !result.isDone(); i++){
                    runtime.tickManuallyForTesting(1);
                    Thread.sleep(25L);
                }
                Throwable failure = result.get(5L, TimeUnit.SECONDS);
                assertNotNull(failure, "the timed-out research request must remain a failure to its caller");

                RuntimeContexts.run(runtime.context(), () -> {
                    var core = Vars.game().state.rules.defaultTeam.core();
                    for(ObjectMap.Entry<String, Integer> entry : before){
                        Item item = Vars.content.item(entry.key);
                        assertNotNull(item);
                        assertEquals(entry.value.intValue(), core.items.get(item),
                            "late PREPARE must be compensated by the durable ABORT replay");
                    }
                });
            }finally{
                caller.shutdownNow();
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
        }finally{
            deleteTree(root);
            restore("mindustry.uiTest", oldUiTest);
            restore("mindustry.sharedCampaign.researchPrepareTimeoutMillis", oldTimeout);
        }
    }

    private static ResearchTransaction awaitAborted(SharedCampaignService service, long timeoutMillis) throws Exception{
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(System.nanoTime() < deadline){
            for(ResearchTransaction tx : service.state().researchTransactions.values()) if(tx.status == ResearchTransactionStatus.aborted) return tx;
            Thread.sleep(20L);
        }
        return null;
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, long timeoutMillis) throws Exception{
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == expected) return last;
            Thread.sleep(25L);
        }
        fail("Action " + actionId + " did not reach " + expected + "; last=" + (last == null ? "null" : last.actions.get(actionId).status));
        return null;
    }

    private static void restore(String key, String value){ if(value == null) System.clearProperty(key); else System.setProperty(key, value); }

    private static void deleteTree(Path path) throws IOException{
        if(path == null || !Files.exists(path)) return;
        try(var walk = Files.walk(path)){ walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try{ Files.deleteIfExists(p); }catch(IOException ignored){} }); }
    }
}
