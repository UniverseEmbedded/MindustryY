package mindustry.campaign.shared;

import mindustry.campaign.shared.SharedCampaignState.*;

/** Durable state transitions for Shared Campaign launch resource debits. */
public final class SharedLaunchTransactions{
    private SharedLaunchTransactions(){}

    public static LaunchTransaction begin(SharedCampaignState state, String transactionId, String actionId, String actorId,
                                          SharedLaunchPlanner.PreparedLaunch prepared, long now){
        if(transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("transactionId is required");
        if(state.launchTransactions.containsKey(transactionId)) throw new IllegalStateException("Duplicate launch transaction: " + transactionId);
        LaunchTransaction tx = new LaunchTransaction();
        tx.transactionId = transactionId; tx.actionId = actionId; tx.actorId = actorId == null ? "" : actorId;
        tx.originSector = prepared.plan().originSector(); tx.sourceActionId = prepared.sourceActionId(); tx.costs.putAll(prepared.costs());
        tx.status = LaunchTransactionStatus.preparing; tx.createdAt = tx.updatedAt = now;
        state.launchTransactions.put(transactionId, tx);
        return tx;
    }

    /** Applies an offline/coordinator-owned source debit and transitions PREPARING -> PREPARED. */
    public static void prepareOffline(SharedCampaignState state, String transactionId, long now){
        LaunchTransaction tx = require(state, transactionId);
        if(tx.status == LaunchTransactionStatus.prepared) return;
        if(tx.status != LaunchTransactionStatus.preparing) throw new IllegalStateException("Launch transaction is not preparing: " + tx.status);
        if(!tx.sourceActionId.isBlank()) throw new IllegalStateException("Live launch origin requires Action-side prepare");
        if(tx.originSector.isBlank() && !tx.costs.isEmpty()) throw new IllegalStateException("Launch costs have no origin sector");
        if(!tx.originSector.isBlank()) debit(requireSector(state, tx.originSector), tx.costs);
        tx.status = LaunchTransactionStatus.prepared; tx.updatedAt = now; tx.failureReason = "";
    }

    /** Accepts the authoritative post-reservation summary from a live origin Action and marks PREPARED. */
    public static void prepareLive(SharedCampaignState state, String transactionId, SectorSummary preparedSummary, long now){
        LaunchTransaction tx = require(state, transactionId);
        if(tx.status == LaunchTransactionStatus.prepared) return;
        if(tx.status != LaunchTransactionStatus.preparing) throw new IllegalStateException("Launch transaction is not preparing: " + tx.status);
        if(tx.sourceActionId.isBlank()) throw new IllegalStateException("Launch origin is not live");
        if(tx.originSector.isBlank()) throw new IllegalStateException("Live launch origin has no sector");
        if(preparedSummary == null) throw new IllegalArgumentException("Prepared live summary is required");
        SectorState origin = requireSector(state, tx.originSector);
        origin.summary = preparedSummary.strategicCopy();
        origin.items.clear(); origin.items.putAll(preparedSummary.items);
        tx.status = LaunchTransactionStatus.prepared; tx.updatedAt = now; tx.failureReason = "";
    }

    public static void commit(SharedCampaignState state, String transactionId, long now){
        LaunchTransaction tx = require(state, transactionId);
        if(tx.status == LaunchTransactionStatus.committed) return;
        if(tx.status != LaunchTransactionStatus.prepared) throw new IllegalStateException("Launch transaction is not prepared: " + tx.status);
        ActionState action = state.actions.get(tx.actionId);
        if(action == null) throw new IllegalStateException("Launch Action disappeared: " + tx.actionId);
        tx.status = LaunchTransactionStatus.committed; tx.updatedAt = now; tx.failureReason = ""; action.launchCommitted = true;
    }

    /**
     * Aborts the durable launch debit. The coordinator always restores its strategic origin mirror after PREPARED;
     * a live source Action independently rolls back its world reservation when it receives the matching ABORT.
     */
    public static void abort(SharedCampaignState state, String transactionId, String reason, long now){
        LaunchTransaction tx = require(state, transactionId);
        if(tx.status == LaunchTransactionStatus.committed) throw new IllegalStateException("Committed launch cannot be aborted");
        if(tx.status == LaunchTransactionStatus.aborted) return;
        if(tx.status == LaunchTransactionStatus.prepared && !tx.originSector.isBlank()) refund(requireSector(state, tx.originSector), tx.costs);
        tx.status = LaunchTransactionStatus.aborted; tx.updatedAt = now; tx.failureReason = reason == null ? "" : reason;
    }

    private static LaunchTransaction require(SharedCampaignState state, String id){ LaunchTransaction tx = state.launchTransactions.get(id); if(tx == null) throw new IllegalArgumentException("Unknown launch transaction: " + id); return tx; }
    private static SectorState requireSector(SharedCampaignState state, String key){ SectorState sector = state.sectors.get(key); if(sector == null) throw new IllegalStateException("Launch origin disappeared: " + key); return sector; }
    private static void debit(SectorState sector, arc.struct.ObjectMap<String,Integer> costs){
        for(arc.struct.ObjectMap.Entry<String,Integer> e : costs){ int have=sector.items.get(e.key,0); if(have<e.value) throw new IllegalStateException("Launch origin resources changed before commit: "+e.key); int left=have-e.value; sector.items.put(e.key,left); sector.summary.items.put(e.key,left); }
    }
    private static void refund(SectorState sector, arc.struct.ObjectMap<String,Integer> costs){
        for(arc.struct.ObjectMap.Entry<String,Integer> e : costs){ int restored=Math.addExact(sector.items.get(e.key,0),e.value); sector.items.put(e.key,restored); sector.summary.items.put(e.key,restored); }
    }
}
