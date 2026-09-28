import org.junit.jupiter.api.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the mdt-y Shared Research product contract against another partial/approximate port. */
public class SharedCampaignResearchProductSurfaceTests{
    @Test
    void strategicResearchUsesVanillaDialogWithoutTreatingCoordinatorConnectionAsGameplay() throws Exception{
        String research = source("core/src/mindustry/ui/dialogs/ResearchDialog.java");
        String router = source("core/src/mindustry/campaign/shared/ui/SharedCampaignUiRouter.java");
        String dialog = source("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java");

        assertTrue(research.contains("private boolean sharedStrategicPresentation"));
        assertTrue(research.contains("public void showSharedCampaign"));
        assertTrue(research.contains("sharedStrategicPresentation || sharedCampaign.sharedModeActive()"));
        assertFalse(research.contains("sharedCampaign.remoteConnected()"), "control-plane connectivity must not select Shared gameplay research semantics");
        assertFalse(research.contains("sharedCampaign.connectedToCoordinator()"), "control-plane connectivity must not select Shared gameplay research semantics");
        assertTrue(router.contains("service.sharedModeActive()"));
        assertTrue(router.contains("ui.research.showSharedCampaign(planet)"));
        assertTrue(dialog.contains("@Override public void research(Planet planet){ SharedCampaignUiRouter.showSharedStrategicResearch(planet); }"));
        assertFalse(dialog.contains("private void showSharedResearch()"), "do not reintroduce a second fake Shared research presentation");
    }

    @Test
    void sharedResearchCarriesExactPlanetIdentityThroughTheAuthorityContract() throws Exception{
        String research = source("core/src/mindustry/ui/dialogs/ResearchDialog.java");
        String service = source("core/src/mindustry/campaign/shared/SharedCampaignService.java");
        String client = source("core/src/mindustry/campaign/shared/runtime/SharedCampaignClient.java");
        String commands = source("core/src/mindustry/campaign/shared/runtime/CampaignActionCommands.java");

        assertTrue(research.contains("SharedCampaignProgress.researchItems(campaign, lastNode, planet == null ? \"\" : planet.name)"));
        assertTrue(research.contains("sharedCampaign.requestResearch(node.content.name, requestPlanetName)"));
        assertTrue(service.contains("requestResearch(String contentName, String planetName)"));
        assertTrue(client.contains("new RuntimePayloads.ResearchRequest(contentName, planetName)"));
        assertTrue(commands.contains("SharedCampaignProgress.researchEligibleSectors(state, node, researchPlanet)"));
    }

    @Test
    void sharedSnapshotResearchUiMutationsStayOnApplicationLane() throws Exception{
        String research = source("core/src/mindustry/ui/dialogs/ResearchDialog.java");
        int event = research.indexOf("Events.on(SharedCampaignEvents.CampaignSnapshotUpdated.class");
        int post = research.indexOf("Core.app.post(() ->", event);
        int visible = research.indexOf("if(!isShown() || !sharedResearchMode()) return", post);
        assertTrue(event >= 0 && post > event && visible > post,
            "snapshot push may come from network/research workers; Scene2D state must be touched only after posting to the application lane");
    }

    @Test
    void researchDialogExposesStableSelectorsUsedByRealUiJourneyTests() throws Exception{
        String research = source("core/src/mindustry/ui/dialogs/ResearchDialog.java");
        assertTrue(research.contains("name = \"researchDialog\""));
        assertTrue(research.contains("name(\"research.root.\" + node.content.name)"));
        assertTrue(research.contains("button.name = \"research.node.\" + node.node.content.name"),
            "real Shared Research UI journeys require stable per-node selectors, not coordinate guessing");
    }

    private static String source(String relative) throws Exception{ return Files.readString(locate(relative)); }

    private static Path locate(String relative){
        Path cursor = Path.of("").toAbsolutePath();
        for(int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()){
            Path candidate = cursor.resolve(relative);
            if(Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate project source " + relative);
    }
}
