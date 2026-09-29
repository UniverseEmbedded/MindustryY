package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for the coordinator half of non-legacy LaunchPad transport. */
class SharedCampaignTransportCoordinatorTests{
    @TempDir Path directory;
    private SharedCampaignPlanetRegistry policies;
    private SharedCampaignStore store;
    private FakeEndpoint endpoint;
    private SharedCampaignTransportCoordinator coordinator;
    private String sourceKey, destinationKey;

    @BeforeEach
    void setup(){
        TestBootstrap.ensureBaseContent();
        policies = new SharedCampaignPlanetRegistry();
        VanillaPlanetPolicies.register(policies);
        policies.seal();
        store = new SharedCampaignStore(new Fi(directory.toString()));
        store.open();
        endpoint = new FakeEndpoint();
        initialize(false);
        coordinator = new SharedCampaignTransportCoordinator(store, endpoint);
    }

    @AfterEach
    void close(){ if(store != null) store.close(); }

    @Test
    void suspendedDestinationDispatchIsDurableAndIdempotentWithoutDoubleDebitingSource(){
        RuntimePayloads.TransportDispatch dispatch = dispatch("dispatch-1", 20);

        RuntimePayloads.TransportDispatchResult first = coordinator.dispatch("source-action", dispatch);
        RuntimePayloads.TransportDispatchResult replay = coordinator.dispatch("source-action", dispatch);

        assertTrue(first.accepted());
        assertTrue(replay.accepted());
        SharedCampaignState state = store.snapshot();
        assertEquals(1, state.transports.size);
        TransportOrder order = state.transports.first();
        assertEquals("dispatch-1", order.orderId);
        assertEquals(20, order.loaded, "source gameplay already removed the launched cargo");
        assertEquals(0, order.delivered);
        assertEquals(TransportStatus.inTransit, order.status);
        assertEquals(30, state.sectors.get(sourceKey).items.get("silicon", 0), "coordinator must not debit a live source again");
        assertEquals(0, endpoint.prepareCalls);
    }

    @Test
    void liveDestinationPrepareCommitAndReplayCreditExactlyOnce(){
        makeDestinationLive();
        endpoint.connected.add("destination-action");
        endpoint.prepareResults.add(success("", 20, 20));

        RuntimePayloads.TransportDispatch dispatch = dispatch("dispatch-live", 20);
        assertTrue(coordinator.dispatch("source-action", dispatch).accepted());
        assertEquals(0, endpoint.prepareCalls, "dispatch ingress must not block its source control reader on destination 2PC");
        coordinator.reconcile();
        assertTrue(coordinator.dispatch("source-action", dispatch).accepted(), "source journal replay must be idempotent");

        SharedCampaignState state = store.snapshot();
        TransportOrder order = state.transports.first();
        assertEquals(TransportStatus.delivered, order.status);
        assertEquals(20, order.delivered);
        assertEquals(20, state.sectors.get(destinationKey).items.get("silicon", 0));
        assertEquals(1, endpoint.prepareCalls, "replayed dispatch must not prepare a second credit");
        assertEquals(1, endpoint.decisionCalls);
        assertEquals(1, state.transportTransactions.size);
        assertEquals(TransportTransactionStatus.acknowledged, state.transportTransactions.values().next().status);
    }

    @Test
    void transientLivePrepareFailureStillAcceptsDurableCargoAndReplaysLater(){
        makeDestinationLive();
        endpoint.connected.add("destination-action");
        endpoint.prepareResults.add(new RuntimePayloads.TransportPrepareResult("", false, "temporary failure", 0, null));
        endpoint.prepareResults.add(success("", 20, 20));

        RuntimePayloads.TransportDispatchResult accepted = coordinator.dispatch("source-action", dispatch("dispatch-retry", 20));
        assertTrue(accepted.accepted(), "once the order is durable, a transient live credit failure must not strand source cargo");
        assertTrue(store.snapshot().transportTransactions.isEmpty(), "durable ingress and downstream 2PC are intentionally decoupled");

        coordinator.reconcile(); // first prepare attempt fails but leaves a replayable transaction
        SharedCampaignState pending = store.snapshot();
        assertEquals(TransportStatus.inTransit, pending.transports.first().status);
        assertEquals(TransportTransactionStatus.preparing, pending.transportTransactions.values().next().status);
        assertEquals(0, pending.sectors.get(destinationKey).items.get("silicon", 0));

        coordinator.reconcile();

        SharedCampaignState recovered = store.snapshot();
        assertEquals(TransportStatus.delivered, recovered.transports.first().status);
        assertEquals(20, recovered.sectors.get(destinationKey).items.get("silicon", 0));
        assertEquals(TransportTransactionStatus.acknowledged, recovered.transportTransactions.values().next().status);
        assertEquals(2, endpoint.prepareCalls);
        assertEquals(1, endpoint.decisionCalls);
    }

    @Test
    void lostCommitAcknowledgementReplaysDecisionWithoutPreparingOrCreditingTwice(){
        makeDestinationLive();
        endpoint.connected.add("destination-action");
        endpoint.prepareResults.add(success("", 20, 20));
        endpoint.decisionResults.add(new RuntimePayloads.TransportDecisionResult("", false, "lost commit acknowledgement"));
        endpoint.decisionResults.add(new RuntimePayloads.TransportDecisionResult("", true, ""));

        assertTrue(coordinator.dispatch("source-action", dispatch("dispatch-decision-retry", 20)).accepted());
        coordinator.reconcile();

        SharedCampaignState committed = store.snapshot();
        assertEquals(TransportStatus.delivered, committed.transports.first().status);
        assertEquals(20, committed.sectors.get(destinationKey).items.get("silicon", 0));
        assertEquals(TransportTransactionStatus.committed, committed.transportTransactions.values().next().status);
        assertEquals(1, endpoint.prepareCalls);
        assertEquals(1, endpoint.decisionCalls);

        coordinator.reconcile();

        SharedCampaignState acknowledged = store.snapshot();
        assertEquals(TransportTransactionStatus.acknowledged, acknowledged.transportTransactions.values().next().status);
        assertEquals(20, acknowledged.sectors.get(destinationKey).items.get("silicon", 0));
        assertEquals(1, endpoint.prepareCalls, "a committed transaction must never prepare a second destination credit");
        assertEquals(2, endpoint.decisionCalls);
    }

    @Test
    void dispatchIdentityAndCargoMismatchAreRejectedBeforeDurableAcceptance(){
        RuntimePayloads.TransportDispatch wrongSource = new RuntimePayloads.TransportDispatch(
            "source-action", "bad-source", destinationKey, sourceKey, "silicon", 20, 1L);
        RuntimePayloads.TransportDispatchResult rejected = coordinator.dispatch("source-action", wrongSource);
        assertFalse(rejected.accepted());
        assertTrue(store.snapshot().transports.isEmpty());

        RuntimePayloads.TransportDispatch original = dispatch("same-id", 20);
        assertTrue(coordinator.dispatch("source-action", original).accepted());
        RuntimePayloads.TransportDispatch changed = new RuntimePayloads.TransportDispatch(
            "source-action", "same-id", sourceKey, destinationKey, "silicon", 21, 1L);
        assertFalse(coordinator.dispatch("source-action", changed).accepted());
        assertEquals(1, store.snapshot().transports.size);
    }

    private void initialize(boolean destinationLive){
        store.transact("test", "shared-campaign:test-init-transport", state -> {
            state.ownerId = "owner";
            state.authorityHostId = "coordinator";
            state.freezeWhenEmpty = false;
            MemberState owner = new MemberState();
            owner.memberId = "owner";
            owner.displayName = "Owner";
            state.members.put(owner.memberId, owner);
            policies.installInto(state, null);

            SectorState source = sector("source");
            SectorState destination = sector("destination");
            source.items.put("silicon", 30); source.summary.items.put("silicon", 30);
            source.destinationSector = key(destination); source.summary.destinationSector = key(destination);
            state.sectors.put(key(source), source);
            state.sectors.put(key(destination), destination);
            sourceKey = key(source);
            destinationKey = key(destination);

            state.actions.put("source-action", action("source-action", source));
            if(destinationLive) state.actions.put("destination-action", action("destination-action", destination));
        });
    }

    private void makeDestinationLive(){
        store.transact("test", "shared-campaign:test-live-destination", state -> {
            if(!state.actions.containsKey("destination-action")) state.actions.put("destination-action", action("destination-action", state.sectors.get(destinationKey)));
        });
    }

    private RuntimePayloads.TransportDispatch dispatch(String id, int amount){
        return new RuntimePayloads.TransportDispatch("source-action", id, sourceKey, destinationKey, "silicon", amount, 1L);
    }

    private RuntimePayloads.TransportPrepareResult success(String ignoredTransaction, int applied, int itemTotal){
        SectorSummary summary = new SectorSummary();
        summary.planetName = "serpulo";
        summary.coreCount = 1;
        summary.storageCapacity = 100;
        summary.items.put("silicon", itemTotal);
        // The fake fills the actual transaction ID from the incoming request.
        return new RuntimePayloads.TransportPrepareResult(ignoredTransaction, true, "", applied, summary);
    }

    private static SectorState sector(String name){
        SectorState sector = new SectorState();
        sector.planetName = "serpulo";
        sector.sectorName = name;
        sector.hasBase = true;
        sector.captured = true;
        sector.summary.planetName = sector.planetName;
        sector.summary.coreCount = 1;
        sector.summary.storageCapacity = 100;
        return sector;
    }

    private static ActionState action(String id, SectorState sector){
        ActionState action = new ActionState();
        action.actionId = id;
        action.planetName = sector.planetName;
        action.sectorName = sector.sectorName;
        action.status = ActionStatus.running;
        action.hostGeneration = 1L;
        action.runtimeIncarnation = 1L;
        action.summary = sector.summary.strategicCopy();
        return action;
    }

    private static String key(SectorState sector){ return SharedCampaignProgress.sectorKey(sector.planetName, sector.sectorName); }

    private static final class FakeEndpoint implements SharedCampaignTransportCoordinator.ActionEndpoint{
        final ObjectSet<String> connected = new ObjectSet<>();
        final Seq<RuntimePayloads.TransportPrepareResult> prepareResults = new Seq<>();
        final Seq<RuntimePayloads.TransportDecisionResult> decisionResults = new Seq<>();
        int prepareCalls, decisionCalls;

        @Override public boolean connected(String actionId){ return connected.contains(actionId); }

        @Override public RuntimePayloads.TransportPrepareResult prepare(String actionId, RuntimePayloads.TransportPrepare request){
            prepareCalls++;
            RuntimePayloads.TransportPrepareResult configured = prepareResults.isEmpty()
                ? new RuntimePayloads.TransportPrepareResult(request.transactionId(), true, "", request.amount(), summary(request.amount()))
                : prepareResults.remove(0);
            return new RuntimePayloads.TransportPrepareResult(request.transactionId(), configured.success(), configured.error(), configured.applied(), configured.summary());
        }

        @Override public RuntimePayloads.TransportDecisionResult decide(String actionId, RuntimePayloads.TransportDecision request){
            decisionCalls++;
            RuntimePayloads.TransportDecisionResult configured = decisionResults.isEmpty()
                ? new RuntimePayloads.TransportDecisionResult(request.transactionId(), true, "")
                : decisionResults.remove(0);
            return new RuntimePayloads.TransportDecisionResult(request.transactionId(), configured.success(), configured.error());
        }

        private static SectorSummary summary(int amount){
            SectorSummary summary = new SectorSummary();
            summary.planetName = "serpulo";
            summary.coreCount = 1;
            summary.storageCapacity = 100;
            summary.items.put("silicon", amount);
            return summary;
        }
    }
}
