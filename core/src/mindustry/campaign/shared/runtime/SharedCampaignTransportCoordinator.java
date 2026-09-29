package mindustry.campaign.shared.runtime;

import arc.struct.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;

import java.util.*;

/**
 * Coordinator-owned durable orchestration for non-legacy LaunchPad cargo.
 *
 * <p>The source Action has already removed the launched items before it emits a transport dispatch. Accepting a
 * dispatch therefore means first taking durable ownership of the cargo as an {@link TransportOrder}; only after that
 * durable hand-off may the source Action discard its outbound journal entry. Suspended destinations are later settled
 * by {@link SectorSuspensionService}. Running destinations are credited through the Action-side transport reservation
 * protocol, with a durable coordinator transaction making prepare/commit replay crash-safe and idempotent.</p>
 */
final class SharedCampaignTransportCoordinator{
    interface ActionEndpoint{
        boolean connected(String actionId);
        RuntimePayloads.TransportPrepareResult prepare(String actionId, RuntimePayloads.TransportPrepare request);
        RuntimePayloads.TransportDecisionResult decide(String actionId, RuntimePayloads.TransportDecision request);
    }

    private final SharedCampaignStore store;
    private final ActionEndpoint actions;
    private final Object mutex = new Object();

    SharedCampaignTransportCoordinator(SharedCampaignStore store, ActionEndpoint actions){
        this.store = Objects.requireNonNull(store, "store");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    RuntimePayloads.TransportDispatchResult dispatch(String sourceActionId, RuntimePayloads.TransportDispatch request){
        String dispatchId = request == null || request.dispatchId() == null ? "" : request.dispatchId();
        try{
            synchronized(mutex){
                // Ingress only transfers durable ownership to the coordinator. Do not synchronously call a destination
                // Action from the source Action's control-reader thread: two Actions dispatching toward each other could
                // otherwise occupy both readers while each waits for a response that those same readers must consume.
                recordDispatch(sourceActionId, request);
                return new RuntimePayloads.TransportDispatchResult(dispatchId, true, "");
            }
        }catch(Throwable failure){
            return new RuntimePayloads.TransportDispatchResult(dispatchId, false, message(failure));
        }
    }

    /** Replays in-doubt live credits and attempts any due coordinator-owned live destination orders. */
    void reconcile(){
        synchronized(mutex){
            SharedCampaignState snapshot = store.strategicSnapshotIfOpen();
            if(snapshot == null) return;

            Seq<String> active = new Seq<>();
            for(String id : snapshot.transportTransactions.keys()) {
                TransportTransaction tx = snapshot.transportTransactions.get(id);
                if(tx != null && (tx.status == TransportTransactionStatus.preparing || tx.status == TransportTransactionStatus.committed)) active.add(id);
            }
            active.sort();
            for(String transactionId : active){
                try{ reconcileTransaction(transactionId); }
                catch(Throwable failure){
                    Log.warn("Shared Campaign transport transaction @ remains pending: @", transactionId, message(failure));
                }
            }

            snapshot = store.strategicSnapshotIfOpen();
            if(snapshot == null) return;
            Seq<String> orders = snapshot.transports.select(order -> order.status == TransportStatus.inTransit && order.delivered < order.loaded)
                .map(order -> order.orderId).sort();
            for(String orderId : orders){
                try{ reconcileOrder(orderId); }
                catch(Throwable failure){
                    Log.warn("Shared Campaign live transport order @ remains pending: @", orderId, message(failure));
                }
            }
        }
    }

    private void recordDispatch(String sourceActionId, RuntimePayloads.TransportDispatch request){
        if(request == null) throw new IllegalArgumentException("Transport dispatch is required");
        String identity = clean(sourceActionId);
        if(identity.isBlank() || !identity.equals(clean(request.actionId()))) throw new SecurityException("Transport dispatch source Action mismatch");
        if(clean(request.dispatchId()).isBlank()) throw new IllegalArgumentException("Transport dispatch ID is required");
        if(clean(request.sourceSector()).isBlank() || clean(request.destinationSector()).isBlank()) throw new IllegalArgumentException("Transport dispatch routing is required");
        if(request.sourceSector().equals(request.destinationSector())) throw new IllegalArgumentException("Transport source and destination must differ");
        if(clean(request.itemName()).isBlank() || request.amount() <= 0) throw new IllegalArgumentException("Transport dispatch cargo is invalid");
        if(request.travelTurns() <= 0L) throw new IllegalArgumentException("Transport travel time must be positive");

        store.transact("action:" + identity, "shared-campaign:transport-dispatch", state -> {
            ActionState sourceAction = state.actions.get(identity);
            if(sourceAction == null || !sourceAction.status.isLive()) throw new SecurityException("Transport dispatch came from a non-live Action");
            String authoritativeSource = ActionAuthorityTransitions.sectorKey(sourceAction);
            if(!request.sourceSector().equals(authoritativeSource)) throw new SecurityException("Transport dispatch source sector mismatch");

            SectorState source = state.sectors.get(request.sourceSector());
            SectorState destination = state.sectors.get(request.destinationSector());
            if(source == null || !source.hasBase) throw new IllegalArgumentException("Transport source must be an established shared-campaign base");
            if(destination == null || !destination.hasBase) throw new IllegalArgumentException("Transport destination must be an established shared-campaign base");
            if(!Objects.equals(source.planetName, destination.planetName)) throw new IllegalArgumentException("Launch-pad logistics cannot cross planets");
            if(source.legacyLaunchPads) throw new IllegalStateException("Legacy LaunchPad exports must not use non-legacy transport dispatch");

            TransportOrder existing = order(state, request.dispatchId());
            if(existing != null){
                if(!same(existing, request)) throw new SecurityException("Transport dispatch ID was reused with different cargo");
                return;
            }

            TransportOrder created = new TransportOrder();
            created.orderId = request.dispatchId();
            created.sourceSector = request.sourceSector();
            created.destinationSector = request.destinationSector();
            created.itemName = request.itemName();
            created.amount = request.amount();
            // The Action's gameplay authority already removed this cargo before LaunchItemEvent fired.
            created.loaded = request.amount();
            created.delivered = 0;
            created.createdAt = Time.millis();
            created.etaCampaignTick = Math.addExact(state.campaignTick, request.travelTurns());
            created.status = TransportStatus.inTransit;
            state.transports.add(created);
        });
    }

    private void reconcileOrder(String orderId){
        SharedCampaignState snapshot = store.snapshot();
        TransportOrder order = order(snapshot, orderId);
        if(order == null || order.status != TransportStatus.inTransit || order.delivered >= order.loaded) return;

        // One in-doubt transaction owns this order's live credit edge until it reaches a terminal acknowledgement.
        TransportTransaction active = snapshot.transportTransactions.values().toSeq().find(tx -> orderId.equals(tx.orderId) &&
            (tx.status == TransportTransactionStatus.preparing || tx.status == TransportTransactionStatus.committed));
        if(active != null) return;

        ActionState destinationAction = liveActionFor(snapshot, order.destinationSector);
        if(destinationAction == null || destinationAction.status != ActionStatus.running || !actions.connected(destinationAction.actionId)) return;

        SectorState destination = snapshot.sectors.get(order.destinationSector);
        int remaining = order.loaded - order.delivered;
        if(destination == null || remaining <= 0) return;
        int capacity = Math.max(0, destination.summary.storageCapacity);
        int current = Math.max(0, destination.items.get(order.itemName, 0));
        // Avoid creating an unbounded stream of zero-apply transactions while the last authoritative summary is full.
        if(capacity > 0 && current >= capacity) return;

        String transactionId = UUID.randomUUID().toString();
        String actionId = destinationAction.actionId;
        store.transact("coordinator", "shared-campaign:transport-live-prepare", state -> {
            TransportOrder durableOrder = order(state, orderId);
            if(durableOrder == null || durableOrder.status != TransportStatus.inTransit || durableOrder.delivered >= durableOrder.loaded) return;
            if(state.transportTransactions.values().toSeq().contains(tx -> orderId.equals(tx.orderId) &&
                (tx.status == TransportTransactionStatus.preparing || tx.status == TransportTransactionStatus.committed))) return;
            ActionState live = liveActionFor(state, durableOrder.destinationSector);
            if(live == null || live.status != ActionStatus.running || !actionId.equals(live.actionId)) return;

            TransportTransaction tx = new TransportTransaction();
            tx.transactionId = transactionId;
            tx.orderId = orderId;
            tx.actionId = actionId;
            tx.itemName = durableOrder.itemName;
            tx.requested = durableOrder.loaded - durableOrder.delivered;
            tx.applied = 0;
            tx.status = TransportTransactionStatus.preparing;
            tx.createdAt = tx.updatedAt = Time.millis();
            state.transportTransactions.put(transactionId, tx);
        });

        if(store.snapshot().transportTransactions.containsKey(transactionId)) reconcileTransaction(transactionId);
    }

    private void reconcileTransaction(String transactionId){
        SharedCampaignState snapshot = store.snapshot();
        TransportTransaction tx = snapshot.transportTransactions.get(transactionId);
        if(tx == null || tx.status == TransportTransactionStatus.acknowledged || tx.status == TransportTransactionStatus.aborted) return;
        if(!actions.connected(tx.actionId)) return;

        if(tx.status == TransportTransactionStatus.preparing){
            RuntimePayloads.TransportPrepareResult prepared = actions.prepare(tx.actionId,
                new RuntimePayloads.TransportPrepare(tx.transactionId, tx.actionId, tx.itemName, tx.requested));
            if(!tx.transactionId.equals(prepared.transactionId())) throw new SecurityException("Transport prepare response transaction mismatch");
            if(!prepared.success()){
                String failure = prepared.error() == null || prepared.error().isBlank() ? "Live transport preparation failed" : prepared.error();
                store.transactCoalesced("coordinator", "shared-campaign:transport-live-deferred", state -> {
                    TransportTransaction durable = state.transportTransactions.get(transactionId);
                    if(durable != null && durable.status == TransportTransactionStatus.preparing){
                        durable.failureReason = failure;
                        durable.updatedAt = Time.millis();
                    }
                });
                return;
            }
            if(prepared.applied() < 0 || prepared.applied() > tx.requested) throw new SecurityException("Invalid transport prepare applied amount");
            if(prepared.summary() == null) throw new SecurityException("Successful transport prepare omitted its authoritative Sector summary");

            store.transact("coordinator", "shared-campaign:transport-live-commit", state -> {
                TransportTransaction durable = state.transportTransactions.get(transactionId);
                if(durable == null || durable.status != TransportTransactionStatus.preparing) return;
                TransportOrder durableOrder = order(state, durable.orderId);
                if(durableOrder == null) throw new IllegalStateException("Transport order disappeared while committing live credit");
                ActionState destinationAction = state.actions.get(durable.actionId);
                if(destinationAction == null || !durableOrder.destinationSector.equals(ActionAuthorityTransitions.sectorKey(destinationAction))){
                    throw new SecurityException("Transport destination Action changed while committing live credit");
                }

                durable.applied = prepared.applied();
                durable.status = TransportTransactionStatus.committed;
                durable.failureReason = "";
                durable.updatedAt = Time.millis();
                durableOrder.delivered = Math.addExact(durableOrder.delivered, prepared.applied());
                if(durableOrder.delivered > durableOrder.loaded) throw new IllegalStateException("Transport delivery exceeded loaded cargo");
                if(durableOrder.delivered == durableOrder.loaded) durableOrder.status = TransportStatus.delivered;
                SectorState destination = state.sectors.get(durableOrder.destinationSector);
                if(destination == null) throw new IllegalStateException("Transport destination sector disappeared");
                if(!prepared.summary().planetName.isBlank() && !Objects.equals(destination.planetName, prepared.summary().planetName)){
                    throw new SecurityException("Transport prepare summary planet mismatch");
                }
                ActionAuthorityTransitions.copySummaryToSector(destination, prepared.summary());
            });
            snapshot = store.snapshot();
            tx = snapshot.transportTransactions.get(transactionId);
            if(tx == null || tx.status != TransportTransactionStatus.committed) return;
        }

        if(tx.status == TransportTransactionStatus.committed){
            RuntimePayloads.TransportDecisionResult decision = actions.decide(tx.actionId,
                new RuntimePayloads.TransportDecision(tx.transactionId, true));
            if(!tx.transactionId.equals(decision.transactionId())) throw new SecurityException("Transport decision response transaction mismatch");
            if(!decision.success()) throw new IllegalStateException(decision.error() == null || decision.error().isBlank() ? "Live transport commit acknowledgement failed" : decision.error());
            store.transact("coordinator", "shared-campaign:transport-live-acknowledge", state -> {
                TransportTransaction durable = state.transportTransactions.get(transactionId);
                if(durable != null && durable.status == TransportTransactionStatus.committed){
                    durable.status = TransportTransactionStatus.acknowledged;
                    durable.failureReason = "";
                    durable.updatedAt = Time.millis();
                }
            });
        }
    }

    private static TransportOrder order(SharedCampaignState state, String orderId){
        return state.transports.find(value -> Objects.equals(orderId, value.orderId));
    }

    private static ActionState liveActionFor(SharedCampaignState state, String sectorKey){
        return state.actions.values().toSeq().find(action -> action.status.isLive() && sectorKey.equals(ActionAuthorityTransitions.sectorKey(action)));
    }

    private static boolean same(TransportOrder order, RuntimePayloads.TransportDispatch request){
        return Objects.equals(order.sourceSector, request.sourceSector()) && Objects.equals(order.destinationSector, request.destinationSector()) &&
            Objects.equals(order.itemName, request.itemName()) && order.amount == request.amount() && order.loaded == request.amount();
    }

    private static String clean(String value){ return value == null ? "" : value.trim(); }
    private static String message(Throwable failure){
        String value = failure == null ? "" : failure.getMessage();
        return value == null || value.isBlank() ? String.valueOf(failure) : value;
    }
}
