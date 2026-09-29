package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.io.*;
import mindustry.runtime.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Product-level host runtime assembled from the clean Shared Campaign coordinator components. */
public final class SharedCampaignCoordinator implements Closeable{
    private final SharedCampaignStore store;
    private final CoordinatorCredentials credentials;
    private final SharedActionEntryRouter entryRouter;
    private final ActionRuntimeCoordinator actionRuntimes;
    private final CampaignActionCommands actionCommands;
    private final SharedCampaignTransportCoordinator transportCoordinator;
    private final CampaignClientControlPlane clientControl;
    private final SharedCampaignPlanetRegistry planetPolicies;
    private final SectorSuspensionService suspension;
    private final ScheduledExecutorService strategicMonitor;
    private final ExecutorService reconciliationExecutor;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long lastSettlementMillis;
    private static final long settlementIntervalMillis = Math.max(1_000L, Long.getLong("mindustry.sharedCampaign.settlementIntervalMs", 10_000L));

    public SharedCampaignCoordinator(GameContext owner, SharedCampaignStore store, Fi credentialDirectory, Fi actionRoot,
                                     String advertisedHost, int actionControlPort, int publicEntryPort, Fi modsSource,
                                     SharedCampaignMissionRegistry missions, SharedCampaignPlanetRegistry planetPolicies, ActionControlPlane.LossPolicy lossPolicy,
                                     SectorRuntimeFactory runtimeFactory){
        Objects.requireNonNull(owner, "owner");
        this.store = Objects.requireNonNull(store, "store");
        this.credentials = new CoordinatorCredentials(Objects.requireNonNull(credentialDirectory, "credentialDirectory"));
        AtomicInteger recoveryIds = new AtomicInteger();
        this.reconciliationExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(RuntimeContexts.capture(owner, task), "shared-campaign-reconcile-" + recoveryIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.entryRouter = new SharedActionEntryRouter(credentials, new ActionRuntimeCoordinator.Listener(){
            @Override public void snapshotCommitted(SharedCampaignState state){ scheduleReconciliation(null); }
            @Override public void routeAvailable(String actionId, int gamePort){ scheduleReconciliation(actionId); }
            @Override public void actionBecameRunning(SharedCampaignState.ActionState action){ scheduleReconciliation(action.actionId); }
            @Override public void actionStopped(SharedCampaignState.ActionState action){ scheduleReconciliation(action.actionId); }
        });
        this.actionRuntimes = new ActionRuntimeCoordinator(owner, store, credentials, actionRoot, advertisedHost,
            actionControlPort, modsSource, missions, planetPolicies, lossPolicy, runtimeFactory, entryRouter);
        this.actionCommands = new CampaignActionCommands(store, credentials, actionRuntimes, entryRouter, missions, planetPolicies);
        this.transportCoordinator = new SharedCampaignTransportCoordinator(store, new SharedCampaignTransportCoordinator.ActionEndpoint(){
            @Override public boolean connected(String actionId){ return actionRuntimes.controlPlane().connected(actionId); }
            @Override public RuntimePayloads.TransportPrepareResult prepare(String actionId, RuntimePayloads.TransportPrepare request){
                ControlProtocol.Frame frame = actionRuntimes.controlPlane().request(actionId, ControlProtocol.Type.transportPrepareRequest,
                    RuntimePayloads.encode(request), ControlProtocol.Type.transportPrepareResponse, 30_000L);
                return RuntimePayloads.transportPrepareResult(frame.payload());
            }
            @Override public RuntimePayloads.TransportDecisionResult decide(String actionId, RuntimePayloads.TransportDecision request){
                ControlProtocol.Frame frame = actionRuntimes.controlPlane().request(actionId, ControlProtocol.Type.transportDecisionRequest,
                    RuntimePayloads.encode(request), ControlProtocol.Type.transportDecisionResponse, 30_000L);
                return RuntimePayloads.transportDecisionResult(frame.payload());
            }
        });
        this.actionRuntimes.controlPlane().vanillaTransferHandler(actionCommands::prepareVanillaTransfer);
        this.actionRuntimes.controlPlane().transportDispatchHandler(transportCoordinator::dispatch);
        this.clientControl = new CampaignClientControlPlane(owner, store, credentials, actionCommands, planetPolicies, entryRouter, publicEntryPort);
        this.planetPolicies = Objects.requireNonNull(planetPolicies, "planetPolicies");
        if(!this.planetPolicies.sealed()) this.planetPolicies.seal();
        this.suspension = new SectorSuspensionService(this.planetPolicies);
        AtomicInteger settlementIds = new AtomicInteger();
        this.strategicMonitor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(RuntimeContexts.capture(owner, task), "shared-campaign-strategic-" + settlementIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start(){
        if(!running.compareAndSet(false, true)) return;
        try{
            planetPolicies.validateCampaign(store.snapshot());
            actionRuntimes.start();
            actionCommands.reconcileLaunchTransactions();
            actionCommands.reconcileResearchTransactions();
            actionCommands.reconcileSuspendingActions();
            clientControl.start();
            lastSettlementMillis = Time.millis();
            strategicMonitor.scheduleWithFixedDelay(this::settleSafely, settlementIntervalMillis, settlementIntervalMillis, TimeUnit.MILLISECONDS);
        }catch(Throwable failure){
            running.set(false);
            try{ clientControl.close(); }catch(Throwable ignored){}
            try{ actionRuntimes.close(); }catch(Throwable ignored){}
            strategicMonitor.shutdownNow();
            throw failure;
        }
    }



    private void scheduleReconciliation(String actionId){
        try{
            reconciliationExecutor.execute(() -> {
                if(!running.get() || !store.isOpen()) return;
                try{
                    CampaignActionCommands commands = actionCommands;
                    if(commands != null && actionId != null && !actionId.isBlank()){
                        // Route callbacks may run on the Action control reader. Keep request/response work off that
                        // reader, and only replay already-terminal durable decisions here. Restart-only PREPARING
                        // convergence runs synchronously once in start(); doing it on delayed callbacks could abort a
                        // brand-new transaction that did not exist when the callback was queued.
                        commands.reconcileLaunchTransactionsForSource(actionId);
                        commands.reconcileResearchTransactionsForAction(actionId);
                        commands.reconcileSuspend(actionId);
                    }
                    SharedCampaignTransportCoordinator transport = transportCoordinator;
                    if(transport != null) transport.reconcile();
                }catch(Throwable failure){
                    if(running.get() && store.isOpen()) Log.err("Shared Campaign reconciliation failed", failure);
                }
            });
        }catch(RejectedExecutionException ignored){
            if(running.get()) throw ignored;
        }
    }

    /** Runs one coordinator-owned strategic tick. Package-visible for deterministic acceptance tests. */
    void settleNow(long elapsedCampaignTicks){
        if(!store.isOpen()) return;
        // Live-side transport 2PC recovery is a control-plane concern, not campaign-time simulation.
        transportCoordinator.reconcile();
        if(elapsedCampaignTicks <= 0L) return;
        SharedCampaignState snapshot = store.strategicSnapshotIfOpen();
        if(snapshot == null) return;
        boolean anyPlayers = snapshot.actions.values().toSeq().contains(action -> action.connectedPlayers > 0);
        if((snapshot.freezeWhenEmpty && !anyPlayers) || !suspension.needsAdvance(snapshot)) return;
        store.transactCoalesced("coordinator", "shared-campaign:settle-suspended-sectors", state -> suspension.advance(state, elapsedCampaignTicks));
    }

    private void settleSafely(){
        if(!running.get()) return;
        long now = Time.millis();
        long previous = lastSettlementMillis;
        lastSettlementMillis = now;
        long elapsedTicks = Math.max(0L, (now - previous) * 60L / 1000L);
        try{ settleNow(elapsedTicks); }
        catch(Throwable failure){
            if(running.get() && store.isOpen()) Log.err("Shared Campaign suspended-sector settlement failed", failure);
        }
    }

    public boolean isRunning(){ return running.get(); }
    public SharedCampaignStore store(){ return store; }
    public CoordinatorCredentials credentials(){ return credentials; }
    public SharedActionEntryRouter entryRouter(){ return entryRouter; }
    public ActionRuntimeCoordinator actionRuntimes(){ return actionRuntimes; }
    public CampaignActionCommands actionCommands(){ return actionCommands; }
    public CampaignClientControlPlane clientControl(){ return clientControl; }
    public int actionControlPort(){ return actionRuntimes.controlPort(); }
    public int publicEntryPort(){ return clientControl.port(); }

    @Override public synchronized void close(){
        if(!running.compareAndSet(true, false)) return;
        strategicMonitor.shutdownNow();
        reconciliationExecutor.shutdownNow();
        try{ clientControl.close(); }finally{
            try{ actionRuntimes.close(); }finally{
                try{ strategicMonitor.awaitTermination(5L, TimeUnit.SECONDS); }catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); }
                try{ reconciliationExecutor.awaitTermination(5L, TimeUnit.SECONDS); }catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); }
            }
        }
    }
}
