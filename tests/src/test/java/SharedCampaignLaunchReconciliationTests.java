import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Durable crash-window gate for fresh launch PREPARING/PREPARED reconciliation. */
@Tag("shared-campaign-launch-recovery")
public class SharedCampaignLaunchReconciliationTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(true);
        if(Vars.schematics == null){ Vars.schematics = new mindustry.game.Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void authorityStartupConvergesLaunchCrashWindowsExactlyOnce() throws Exception{
        Path root = Files.createTempDirectory("shared-campaign-launch-reconcile-");
        Fi campaignDir = new Fi(root.resolve("campaign").toString());
        String originKey = SharedCampaignSectors.sectorKey("serpulo", "groundZero");
        try{
            // First create a normal campaign so all product metadata/credentials are real, then close authority and
            // inject the four durable crash windows as if the host died between launch orchestration steps.
            SharedCampaignService create = new SharedCampaignService(Vars.game(), new Fi(root.resolve("mods-create").toString()));
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Launch reconciliation";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = "serpulo";
            create.createLocal(campaignDir, options, "127.0.0.1", 0, 0);
            create.close();

            try(SharedCampaignStore store = new SharedCampaignStore(campaignDir)){
                store.open();
                store.transact("fixture", "test:inject-launch-crash-windows", state -> {
                    SectorState origin = new SectorState();
                    origin.planetName = "serpulo";
                    origin.sectorName = "groundZero";
                    origin.hasBase = true;
                    origin.captured = true;
                    origin.items.put("copper", 100);
                    origin.summary.items.put("copper", 100);
                    state.sectors.put(originKey, origin);

                    // Crash after durable BEGIN but before any coordinator-owned debit. This must ABORT without refund.
                    SharedLaunchTransactions.begin(state, "tx-preparing", "action-preparing", "owner",
                        launch(originKey, "", 10), System.currentTimeMillis());

                    // Crash after offline PREPARE followed by a failed runtime. The debit must be refunded exactly once.
                    addAction(state, "action-failed", ActionStatus.failed, originKey, false);
                    SharedLaunchTransactions.begin(state, "tx-failed", "action-failed", "owner",
                        launch(originKey, "", 10), System.currentTimeMillis());
                    SharedLaunchTransactions.prepareOffline(state, "tx-failed", System.currentTimeMillis());

                    // Crash after PREPARE while destination is already RUNNING. Resources must remain consumed and the
                    // transaction must COMMIT, otherwise reopening the host would create a free expedition.
                    addAction(state, "action-running", ActionStatus.running, originKey, false);
                    SharedLaunchTransactions.begin(state, "tx-running", "action-running", "owner",
                        launch(originKey, "", 10), System.currentTimeMillis());
                    SharedLaunchTransactions.prepareOffline(state, "tx-running", System.currentTimeMillis());

                    // STARTING is ambiguous on restart: a surviving child JVM may still reconnect. Keep PREPARED until
                    // runtime lease recovery decides whether the Action lived or failed.
                    addAction(state, "action-starting", ActionStatus.starting, originKey, false);
                    SharedLaunchTransactions.begin(state, "tx-starting", "action-starting", "owner",
                        launch(originKey, "", 10), System.currentTimeMillis());
                    SharedLaunchTransactions.prepareOffline(state, "tx-starting", System.currentTimeMillis());
                });
            }

            try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
                SharedCampaignService service = new SharedCampaignService(Vars.game(), new Fi(root.resolve("mods-open").toString()));
                try{
                    service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                    SharedCampaignState opened = service.openLocal(campaignDir, "owner", "127.0.0.1", 0, 0);
                    assertEquals(LaunchTransactionStatus.aborted, opened.launchTransactions.get("tx-preparing").status);
                    assertEquals(LaunchTransactionStatus.aborted, opened.launchTransactions.get("tx-failed").status);
                    assertEquals(LaunchTransactionStatus.committed, opened.launchTransactions.get("tx-running").status);
                    assertEquals(LaunchTransactionStatus.prepared, opened.launchTransactions.get("tx-starting").status);
                    assertTrue(opened.actions.get("action-running").launchCommitted);
                    assertFalse(opened.actions.get("action-starting").launchCommitted);
                    // Three PREPARE debits occurred. FAILED refunded one; RUNNING and STARTING remain consumed.
                    assertEquals(80, opened.sectors.get(originKey).items.get("copper", -1));

                    service.authority().coordinator().actionCommands().reconcileLaunchTransactions();
                    SharedCampaignState repeated = service.state();
                    assertEquals(80, repeated.sectors.get(originKey).items.get("copper", -1),
                        "idempotent reconciliation double-refunded or double-debited resources");

                    // Once ordinary runtime recovery decides STARTING failed, reconciliation must refund that one and
                    // remain idempotent on every later callback/restart.
                    service.authority().coordinator().store().transact("fixture", "test:starting-runtime-failed", state -> {
                        ActionState action = state.actions.get("action-starting");
                        action.status = ActionStatus.failed;
                        action.failureReason = "synthetic crash";
                        action.connectedPlayers = 0;
                        action.connectedSpectators = 0;
                        action.participants.clear();
                        action.spectators.clear();
                    });
                    service.authority().coordinator().actionCommands().reconcileLaunchTransactions();
                    SharedCampaignState failed = service.state();
                    assertEquals(LaunchTransactionStatus.aborted, failed.launchTransactions.get("tx-starting").status);
                    assertEquals(90, failed.sectors.get(originKey).items.get("copper", -1));
                    service.authority().coordinator().actionCommands().reconcileLaunchTransactions();
                    assertEquals(90, service.state().sectors.get(originKey).items.get("copper", -1));
                }finally{
                    service.close();
                }
                assertEquals(0, scheduler.size());
            }
        }finally{
            deleteTree(root);
        }
    }


    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void liveOriginTerminalDecisionsAreReplayedAndClearDurableReservations() throws Exception{
        Path root = Files.createTempDirectory("shared-campaign-launch-live-replay-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Live launch decision replay";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = "serpulo";
                options.maxActiveActions = 2;
                service.createLocal(new Fi(root.resolve("campaign").toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();
                RuntimePayloads.StartResult sourceStart = owner.startAction("serpulo", "groundZero", "");
                assertTrue(sourceStart.error() == null || sourceStart.error().isBlank(), sourceStart.error());
                awaitStatus(owner, sourceStart.actionId(), ActionStatus.running, 30_000L);

                CampaignActionCommands commands = service.authority().coordinator().actionCommands();
                ActionControlPlane control = service.authority().coordinator().actionRuntimes().controlPlane();
                String sourceId = sourceStart.actionId();
                String originKey = SharedCampaignSectors.sectorKey("serpulo", "groundZero");
                InProcessSectorRuntime sourceRuntime = (InProcessSectorRuntime)service.authority().coordinator().actionRuntimes().runtime(sourceId);
                assertNotNull(sourceRuntime);
                mindustry.runtime.RuntimeContexts.run(sourceRuntime.context(), () -> {
                    var core = mindustry.Vars.game().state.rules.defaultTeam.core();
                    assertNotNull(core);
                    core.items.set(mindustry.content.Items.copper, 100);
                });

                // PREPARING crash window: Action has already persisted the reservation, coordinator has not yet
                // committed PREPARED. Reconciliation must choose ABORT and actually remove the Action sidecar entry.
                String abortTx = "live-abort-replay";
                service.authority().coordinator().store().transact("owner", "test:live-launch-begin", state ->
                    SharedLaunchTransactions.begin(state, abortTx, "uncreated-destination", "owner",
                        launch(originKey, sourceId, 1), System.currentTimeMillis()));
                RuntimePayloads.ResearchPrepareResult abortPrepared = prepare(control, sourceId, abortTx, 1);
                assertTrue(abortPrepared.success(), abortPrepared.error());
                commands.reconcileLaunchTransactions();
                assertEquals(LaunchTransactionStatus.aborted, service.state().launchTransactions.get(abortTx).status);
                // If ABORT was not replayed, ActionResearchReservations rejects this transaction ID because its
                // durable amount was 1 rather than 2. Success proves the old reservation was removed.
                RuntimePayloads.ResearchPrepareResult abortProbe = prepare(control, sourceId, abortTx, 2);
                assertTrue(abortProbe.success(), "ABORT replay left the old live reservation behind: " + abortProbe.error());
                assertTrue(decide(control, sourceId, abortTx, false).success());

                // COMMITTED decision-loss window: coordinator has durably committed the launch but the Action has not
                // received COMMIT yet. Reconciliation must replay COMMIT idempotently and clear the reservation.
                String commitTx = "live-commit-replay";
                RuntimePayloads.ResearchPrepareResult commitPrepared = prepare(control, sourceId, commitTx, 1);
                assertTrue(commitPrepared.success(), commitPrepared.error());
                String targetId = "committed-destination";
                service.authority().coordinator().store().transact("owner", "test:live-launch-committed", state -> {
                    addAction(state, targetId, ActionStatus.running, originKey, false);
                    SharedLaunchTransactions.begin(state, commitTx, targetId, "owner", launch(originKey, sourceId, 1), System.currentTimeMillis());
                    SharedLaunchTransactions.prepareLive(state, commitTx, commitPrepared.summary(), System.currentTimeMillis());
                    SharedLaunchTransactions.commit(state, commitTx, System.currentTimeMillis());
                });
                commands.reconcileLaunchTransactions();
                assertEquals(LaunchTransactionStatus.committed, service.state().launchTransactions.get(commitTx).status);
                RuntimePayloads.ResearchPrepareResult commitProbe = prepare(control, sourceId, commitTx, 2);
                assertTrue(commitProbe.success(), "COMMIT replay left the old live reservation behind: " + commitProbe.error());
                assertTrue(decide(control, sourceId, commitTx, false).success());

                // Repeated convergence is intentionally harmless; Action-side decision on an absent reservation is
                // also success, while this coordinator lifetime suppresses already-acknowledged terminal replays.
                commands.reconcileLaunchTransactions();
            }finally{
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size());
        }finally{
            deleteTree(root);
        }
    }

    private static SharedLaunchPlanner.PreparedLaunch launch(String originKey, String sourceActionId, int copper){
        ObjectMap<String, Integer> costs = new ObjectMap<>();
        costs.put("copper", copper);
        return new SharedLaunchPlanner.PreparedLaunch(
            new RuntimePayloads.LaunchPlan(originKey, "", new ObjectMap<>()), costs, sourceActionId, false);
    }

    private static void addAction(SharedCampaignState state, String id, ActionStatus status, String originKey, boolean committed){
        ActionState action = new ActionState();
        action.actionId = id;
        action.planetName = "serpulo";
        action.sectorName = id;
        action.status = status;
        action.hostId = "coordinator";
        action.hostGeneration = status.isLive() ? state.authorityGeneration : 0L;
        action.runtimeIncarnation = 1L;
        action.leaseExpiresAt = status.isLive() ? Long.MAX_VALUE : 0L;
        action.launchOriginSector = originKey;
        action.launchCosts.put("copper", 10);
        action.launchCommitted = committed;
        state.actions.put(id, action);
    }


    private static RuntimePayloads.ResearchPrepareResult prepare(ActionControlPlane control, String actionId, String transactionId, int copper){
        ObjectMap<String, Integer> amounts = new ObjectMap<>();
        amounts.put("copper", copper);
        ControlProtocol.Frame frame = control.request(actionId, ControlProtocol.Type.researchPrepareRequest,
            RuntimePayloads.encode(new RuntimePayloads.ResearchPrepare(transactionId, actionId, amounts)),
            ControlProtocol.Type.researchPrepareResponse, 30_000L);
        return RuntimePayloads.researchPrepareResult(frame.payload());
    }

    private static RuntimePayloads.ResearchDecisionResult decide(ActionControlPlane control, String actionId, String transactionId, boolean commit){
        ControlProtocol.Frame frame = control.request(actionId, ControlProtocol.Type.researchDecisionRequest,
            RuntimePayloads.encode(new RuntimePayloads.ResearchDecision(transactionId, commit)),
            ControlProtocol.Type.researchDecisionResponse, 30_000L);
        return RuntimePayloads.researchDecisionResult(frame.payload());
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, long timeoutMillis) throws Exception{
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) fail("Action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(50L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("Action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
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
