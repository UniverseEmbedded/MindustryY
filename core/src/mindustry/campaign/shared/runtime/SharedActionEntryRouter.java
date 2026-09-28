package mindustry.campaign.shared.runtime;

import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.*;

/**
 * Bridges live Action runtime routes into the public Shared Campaign MYCS broker.
 *
 * <p>This class deliberately owns no campaign mutation policy. It turns authenticated Action join grants into broker
 * routes and forwards runtime lifecycle notifications. The outer coordinator may therefore host one public entry while
 * each Action keeps an ordinary ArcNet listener, and same-TCP hot-switch remains broker-owned.</p>
 */
public final class SharedActionEntryRouter implements ActionRuntimeCoordinator.Listener, Closeable{
    private final CoordinatorCredentials credentials;
    private final ActionSessionBroker broker;
    private final ActionRuntimeCoordinator.Listener delegate;

    public SharedActionEntryRouter(CoordinatorCredentials credentials){
        this(credentials, new ActionSessionBroker("shared-campaign-broker"), null);
    }

    public SharedActionEntryRouter(CoordinatorCredentials credentials, ActionRuntimeCoordinator.Listener delegate){
        this(credentials, new ActionSessionBroker("shared-campaign-broker"), delegate);
    }

    public SharedActionEntryRouter(CoordinatorCredentials credentials, ActionSessionBroker broker,
                                   ActionRuntimeCoordinator.Listener delegate){
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.delegate = delegate == null ? new ActionRuntimeCoordinator.Listener(){} : delegate;
    }

    public ActionSessionBroker broker(){ return broker; }

    public void controlBranch(ActionSessionBroker.ControlBranch branch){ broker.controlBranch(branch); }
    public void handleAccepted(SocketChannel channel){ broker.handleAccepted(channel); }
    public void handleAccepted(Socket socket){ broker.handleAccepted(socket); }
    public void handleAccepted(Socket socket, boolean synchronous){ broker.handleAccepted(socket, synchronous); }

    public ActionSessionBroker.Session beginHotSwitch(String memberId, String sessionId, String fromActionId, String toActionId){
        return broker.beginHotSwitch(memberId, sessionId, fromActionId, toActionId);
    }
    public boolean resumeHotSwitch(String memberId, String sessionId){ return broker.resumeHotSwitch(memberId, sessionId); }
    public boolean abortHotSwitch(String memberId, String sessionId){ return broker.abortHotSwitch(memberId, sessionId); }

    @Override public void routeAvailable(String actionId, int gamePort){
        String joinSecret = credentials.createActionJoinSecret(actionId);
        broker.registerActionRoute(actionId, gamePort, (routedActionId, token) -> {
            CoordinatorCredentials.JoinGrant grant = CoordinatorCredentials.verifyActionJoinTokenForRouting(
                joinSecret, token, routedActionId, System.currentTimeMillis());
            // Shared-entry sessions require a cryptographically authenticated campaign member. Legacy member-less
            // tokens remain valid for direct world endpoints, but cannot consume a broker session slot.
            return grant == null || grant.memberId() == null || grant.memberId().isBlank() ? null : grant.memberId();
        });
        delegate.routeAvailable(actionId, gamePort);
    }

    @Override public void routeUnavailable(String actionId){
        broker.unregisterActionRoute(actionId);
        delegate.routeUnavailable(actionId);
    }

    @Override public void snapshotCommitted(SharedCampaignState state){ delegate.snapshotCommitted(state); }
    @Override public void actionBecameRunning(ActionState action){ delegate.actionBecameRunning(action); }
    @Override public void actionStopped(ActionState action){
        broker.unregisterActionRoute(action.actionId);
        delegate.actionStopped(action);
    }
    @Override public void actionRecovered(ActionState action, String reason){
        if(action != null && !action.status.isLive()) broker.unregisterActionRoute(action.actionId);
        delegate.actionRecovered(action, reason);
    }

    @Override public void close(){ broker.close(); }
}
