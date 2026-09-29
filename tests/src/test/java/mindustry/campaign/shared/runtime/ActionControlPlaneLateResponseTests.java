package mindustry.campaign.shared.runtime;

import arc.files.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.io.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** Correlated responses that arrive after the coordinator timed out must not be reinterpreted as Action requests. */
class ActionControlPlaneLateResponseTests{
    @TempDir Path directory;

    @Test
    void lateCorrelatedResponseIsDroppedAndConnectionRemainsUsable() throws Exception{
        SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.resolve("state").toString()));
        store.open();
        CoordinatorCredentials credentials = new CoordinatorCredentials(new Fi(directory.resolve("credentials").toString()));
        String actionId = "late-response-action";
        String secret = credentials.createActionControlSecret(actionId);
        store.transact("test", "shared-campaign:test-late-response", state -> {
            state.ownerId = "owner";
            state.authorityHostId = "coordinator";
            state.authorityGeneration = 1L;
            MemberState owner = new MemberState();
            owner.memberId = "owner";
            owner.displayName = "Owner";
            state.members.put(owner.memberId, owner);
            PlanetPolicyState policy = new PlanetPolicyState();
            policy.planetName = "serpulo";
            policy.policyId = "test:serpulo";
            policy.compatibilityId = "test";
            state.planetPolicies.put(policy.planetName, policy);
            ActionState action = new ActionState();
            action.actionId = actionId;
            action.planetName = "serpulo";
            action.sectorName = "source";
            action.status = ActionStatus.starting;
            action.hostGeneration = 1L;
            action.runtimeIncarnation = 1L;
            state.actions.put(actionId, action);
        });

        ActionControlPlane control = new ActionControlPlane(new GameContext("late-response-test"), store, credentials,
            new SharedCampaignMissionRegistry(), 0, action -> false, null);
        control.start();
        try(ControlProtocol.Connection connection = ControlProtocol.Connection.connect(
            "127.0.0.1", control.port(), ControlProtocol.Role.actionHost, actionId, ControlProtocol.deriveKey(secret))){
            long helloId = connection.nextRequestId();
            connection.send(ControlProtocol.Type.actionHello, helloId,
                RuntimePayloads.encode(new RuntimePayloads.ActionHello(actionId, 1L, 1L, 12345, "")));
            assertEquals(ControlProtocol.Type.actionHello, connection.receive().type());
            assertEquals(ControlProtocol.Type.snapshotResponse, connection.receive().type());

            // No pending coordinator request owns this correlation ID anymore. This simulates a response that arrived
            // after the request future timed out and was removed from ActionControlPlane.pending.
            long staleId = 777L;
            connection.send(ControlProtocol.Type.researchPrepareResponse, staleId,
                RuntimePayloads.encode(new RuntimePayloads.ResearchPrepareResult("stale", true, false, "", new SectorSummary())));

            long pingId = connection.nextRequestId();
            connection.send(ControlProtocol.Type.ping, pingId, RuntimePayloads.encodeString("still-alive"));
            ControlProtocol.Frame pong = connection.receive();
            assertEquals(ControlProtocol.Type.pong, pong.type(),
                "late response must be dropped, not converted into an error frame that poisons the next request");
            assertEquals(pingId, pong.requestId());
        }finally{
            control.close();
            store.close();
        }
    }
}
