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
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Wire-level regression for Action -> coordinator LaunchPad dispatch frames. */
class ActionControlPlaneTransportDispatchTests{
    @TempDir Path directory;

    @Test
    void authenticatedActionDispatchIsAcceptedAndCorrelated() throws Exception{
        SharedCampaignStore store = new SharedCampaignStore(new Fi(directory.resolve("state").toString()));
        store.open();
        CoordinatorCredentials credentials = new CoordinatorCredentials(new Fi(directory.resolve("credentials").toString()));
        String actionId = "source-action";
        String secret = credentials.createActionControlSecret(actionId);
        store.transact("test", "shared-campaign:test-control-dispatch", state -> {
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

        AtomicReference<RuntimePayloads.TransportDispatch> seen = new AtomicReference<>();
        ActionControlPlane control = new ActionControlPlane(new GameContext("transport-control-test"), store, credentials,
            new SharedCampaignMissionRegistry(), 0, action -> false, null);
        control.transportDispatchHandler((identity, request) -> {
            assertEquals(actionId, identity);
            seen.set(request);
            return new RuntimePayloads.TransportDispatchResult(request.dispatchId(), true, "");
        });
        control.start();

        try(ControlProtocol.Connection connection = ControlProtocol.Connection.connect(
            "127.0.0.1", control.port(), ControlProtocol.Role.actionHost, actionId, ControlProtocol.deriveKey(secret))){
            long helloId = connection.nextRequestId();
            connection.send(ControlProtocol.Type.actionHello, helloId,
                RuntimePayloads.encode(new RuntimePayloads.ActionHello(actionId, 1L, 1L, 12345, "")));
            ControlProtocol.Frame hello = connection.receive();
            assertEquals(ControlProtocol.Type.actionHello, hello.type());
            assertEquals(helloId, hello.requestId());
            assertEquals(ControlProtocol.Type.snapshotResponse, connection.receive().type());

            RuntimePayloads.TransportDispatch dispatch = new RuntimePayloads.TransportDispatch(
                actionId, "dispatch-wire", "serpulo/source", "serpulo/destination", "silicon", 20, 1L);
            long requestId = connection.nextRequestId();
            connection.send(ControlProtocol.Type.transportDispatchRequest, requestId, RuntimePayloads.encode(dispatch));
            ControlProtocol.Frame response = connection.receive();

            assertEquals(ControlProtocol.Type.transportDispatchResponse, response.type());
            assertEquals(requestId, response.requestId());
            RuntimePayloads.TransportDispatchResult result = RuntimePayloads.transportDispatchResult(response.payload());
            assertTrue(result.accepted());
            assertEquals("dispatch-wire", result.dispatchId());
            assertNotNull(seen.get());
            assertEquals("silicon", seen.get().itemName());
        }finally{
            control.close();
            store.close();
        }
    }
}
