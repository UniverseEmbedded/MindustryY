import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Wire regression: a durable SUSPENDING intent must be replayed when its Action reconnects after coordinator loss. */
@Tag("shared-campaign-suspend-recovery")
public class SharedCampaignSuspendReconciliationTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(true); }

    @TempDir Path temp;

    @Test
    @Timeout(20)
    void reconnectingSuspendingActionReceivesSuspendRequestWithoutUserRetry() throws Exception{
        Fi campaign = new Fi(temp.resolve("campaign").toFile());
        Fi mods = new Fi(temp.resolve("mods").toFile());
        mods.mkdirs();
        SharedCampaignService service = new SharedCampaignService(Vars.game(), mods);
        try{
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Suspend reconnect reconciliation";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = "serpulo";
            service.createLocal(campaign, options, "127.0.0.1", 0, 0);

            var coordinator = service.authority().coordinator();
            long generation = service.state().authorityGeneration;
            String actionId = "suspending-action";
            String secret = coordinator.credentials().createActionControlSecret(actionId);
            coordinator.store().transact("fixture", "test:inject-suspending-action", state -> {
                ActionState action = new ActionState();
                action.actionId = actionId;
                action.planetName = "serpulo";
                action.sectorName = "groundZero";
                action.status = ActionStatus.suspending;
                action.hostId = "coordinator";
                action.hostGeneration = generation;
                action.runtimeIncarnation = 1L;
                action.port = 12345;
                state.actions.put(actionId, action);
            });

            try(ControlProtocol.Connection action = ControlProtocol.Connection.connect(
                "127.0.0.1", coordinator.actionControlPort(), ControlProtocol.Role.actionHost, actionId, ControlProtocol.deriveKey(secret))){
                action.setReadTimeout(3_000);
                long helloId = action.nextRequestId();
                action.send(ControlProtocol.Type.actionHello, helloId,
                    RuntimePayloads.encode(new RuntimePayloads.ActionHello(actionId, generation, 1L, 12345, service.state().contentFingerprint)));

                ControlProtocol.Frame hello = action.receive();
                assertEquals(ControlProtocol.Type.actionHello, hello.type());
                assertEquals(helloId, hello.requestId());
                assertEquals(ControlProtocol.Type.snapshotResponse, action.receive().type());

                ControlProtocol.Frame replay = action.receive();
                assertEquals(ControlProtocol.Type.suspendActionRequest, replay.type(),
                    "SUSPENDING is a durable intent; reconnect must replay it automatically instead of waiting for the owner to click Suspend again");
                assertEquals(actionId, RuntimePayloads.decodeString(replay.payload()));
                action.send(ControlProtocol.Type.suspendActionResponse, replay.requestId(), new byte[0]);
            }
        }finally{
            service.close();
        }
    }
}
