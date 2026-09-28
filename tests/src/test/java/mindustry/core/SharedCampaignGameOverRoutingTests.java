package mindustry.core;

import mindustry.campaign.shared.net.*;
import mindustry.net.Packets.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** A connected Shared Campaign action must not use the ordinary restart/menu game-over destination. */
@Tag("shared-campaign-parallel")
public class SharedCampaignGameOverRoutingTests{
    @Test
    void completedActionConnectionRoutesGameOverBackToSharedCampaign(){
        GameContext context = new GameContext("shared-gameover-client");
        try{
            RuntimeContexts.run(context, () -> {
                SharedCampaignNet shared = SharedCampaignNet.install(context);
                shared.prepareJoin("action-123", "token", false);
                ConnectPacket packet = new ConnectPacket();
                shared.decorateConnectPacket(packet);
                assertFalse(Logic.shouldReturnToSharedCampaignLobby(), "admission preparation alone is not an active action");
                shared.finishClientActionConnection();
                assertEquals("action-123", shared.activeClientActionId());
                assertTrue(Logic.shouldReturnToSharedCampaignLobby());
                shared.clearClientActionConnection();
                assertFalse(Logic.shouldReturnToSharedCampaignLobby());
            });
        }finally{ context.dispose(); }
    }
}
