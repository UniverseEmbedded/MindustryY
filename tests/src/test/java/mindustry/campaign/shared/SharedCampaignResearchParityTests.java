package mindustry.campaign.shared;

import arc.files.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.campaign.shared.io.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.net.Packets.*;
import mindustry.runtime.*;
import mindustry.campaign.shared.net.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.lang.reflect.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** Behavioral parity for mdt-y Shared Research runtime projection semantics. */
public class SharedCampaignResearchParityTests{
    @TempDir Path temp;

    @BeforeAll static void bootstrap(){ TestBootstrap.ensureBaseContent(); }

    @Test
    void coordinatorPresenceIsNotSharedGameplayButLiveActionIs(){
        GameContext context = new GameContext("shared-research-mode", new GameState(), new World());
        try{
            RuntimeContexts.run(context, () -> {
                SharedCampaignService service = SharedCampaignService.install(context, new Fi(temp.resolve("mods").toFile()));
                SharedCampaignNet net = SharedCampaignNet.install(context);

                assertFalse(service.sharedModeActive(), "installing Shared Campaign/control-plane state must not hijack Local Campaign UI");
                net.prepareJoin("action-1", "token", false);
                net.decorateConnectPacket(new ConnectPacket());
                assertFalse(service.sharedModeActive(), "prepared admission is not yet live Shared gameplay");

                net.finishClientActionConnection();
                assertTrue(service.sharedModeActive(), "completed Shared Action connection must activate Shared gameplay semantics");

                net.clearClientActionConnection();
                assertFalse(service.sharedModeActive(), "leaving the Action must release Shared gameplay semantics even if product state remains installed");
            });
        }finally{
            context.dispose();
        }
    }

    @Test
    void authoritativeUnlocksProjectOnlyIntoLiveSharedActionRules() throws Exception{
        GameContext context = new GameContext("shared-research-projection", new GameState(), new World());
        try{
            RuntimeContexts.run(context, () -> {
                try{
                    SharedCampaignService service = SharedCampaignService.install(context, new Fi(temp.resolve("mods2").toFile()));
                    context.state.rules.researched.add(Items.copper);

                    SharedCampaignState lobbySnapshot = new SharedCampaignState();
                    lobbySnapshot.campaignId = "projection";
                    lobbySnapshot.revision = 1L;
                    lobbySnapshot.discovered.add(Items.lead.name);
                    acceptSnapshot(service, lobbySnapshot);

                    assertTrue(service.sharedUnlocked(Items.lead.name), "control-plane cache should still learn authoritative unlocks");
                    assertTrue(context.state.rules.researched.contains(Items.copper), "background Shared snapshot must preserve Local Campaign research");
                    assertFalse(context.state.rules.researched.contains(Items.lead), "background Shared snapshot must not project into Local Campaign rules");

                    SharedCampaignNet net = SharedCampaignNet.install(context);
                    net.prepareJoin("action-1", "token", false);
                    net.decorateConnectPacket(new ConnectPacket());
                    net.finishClientActionConnection();

                    SharedCampaignState actionSnapshot = SharedCampaignStateCopy.copy(lobbySnapshot);
                    actionSnapshot.revision = 2L;
                    actionSnapshot.researched.add(Blocks.conveyor.name);
                    acceptSnapshot(service, actionSnapshot);

                    assertTrue(context.state.rules.researched.contains(Items.lead), "live Action rules must restore discovered/produced unlocks");
                    assertTrue(context.state.rules.researched.contains(Blocks.conveyor), "live Action rules must restore researched unlocks");
                    assertTrue(Blocks.conveyor.unlockedHost(), "vanilla host-side unlock query must agree with the Shared Action runtime");
                }catch(Exception failure){
                    throw new RuntimeException(failure);
                }
            });
        }finally{
            context.dispose();
        }
    }

    @Test
    void discoveredUnlockDoesNotMasqueradeAsExplicitResearchObjective() throws Exception{
        GameContext context = new GameContext("shared-research-objective-distinction", new GameState(), new World());
        try{
            RuntimeContexts.run(context, () -> {
                try{
                    SharedCampaignService service = SharedCampaignService.install(context, new Fi(temp.resolve("mods3").toFile()));
                    SharedCampaignNet net = SharedCampaignNet.install(context);
                    net.prepareJoin("action-1", "token", false);
                    net.decorateConnectPacket(new ConnectPacket());
                    net.finishClientActionConnection();

                    SharedCampaignState discoveredOnly = new SharedCampaignState();
                    discoveredOnly.campaignId = "objective-distinction";
                    discoveredOnly.revision = 1L;
                    discoveredOnly.discovered.add(Blocks.conveyor.name);
                    acceptSnapshot(service, discoveredOnly);

                    assertTrue(service.sharedUnlocked(Blocks.conveyor.name), "discovered content is effectively unlocked");
                    assertFalse(service.sharedResearched(Blocks.conveyor.name), "discovered content is not explicit research");
                    assertFalse(new MapObjectives.ResearchObjective(Blocks.conveyor).update(),
                        "ResearchObjective must not complete from discovery/production alone");
                    assertTrue(new MapObjectives.ProduceObjective(Blocks.conveyor).update(),
                        "ProduceObjective follows the effective Shared unlock set");

                    SharedCampaignState researched = SharedCampaignStateCopy.copy(discoveredOnly);
                    researched.revision = 2L;
                    researched.researched.add(Blocks.conveyor.name);
                    acceptSnapshot(service, researched);
                    assertTrue(service.sharedResearched(Blocks.conveyor.name));
                    assertTrue(new MapObjectives.ResearchObjective(Blocks.conveyor).update(),
                        "ResearchObjective must complete after authoritative explicit research");
                }catch(Exception failure){
                    throw new RuntimeException(failure);
                }
            });
        }finally{
            context.dispose();
        }
    }

    private static void acceptSnapshot(SharedCampaignService service, SharedCampaignState state) throws Exception{
        Method method = SharedCampaignService.class.getDeclaredMethod("acceptRemoteSnapshot", SharedCampaignState.class);
        method.setAccessible(true);
        method.invoke(service, state);
    }
}
