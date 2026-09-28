package mindustry.campaign.shared.ui;

import mindustry.campaign.shared.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class SharedCampaignErekirBoardTests{
    @Test void hiddenWhenErekirDoesNotParticipate(){
        SharedCampaignState state = new SharedCampaignState();
        state.primaryPlanetName = "serpulo";
        assertFalse(SharedCampaignDialog.erekirBoardVisible(state));
    }

    @Test void visibleForPrimarySectorActionOrMission(){
        SharedCampaignState primary = new SharedCampaignState();
        primary.primaryPlanetName = "erekir";
        assertTrue(SharedCampaignDialog.erekirBoardVisible(primary));

        SharedCampaignState sector = new SharedCampaignState();
        sector.primaryPlanetName = "serpulo";
        SharedCampaignState.SectorState sectorState = new SharedCampaignState.SectorState();
        sectorState.planetName = "erekir";
        sector.sectors.put("erekir:onset", sectorState);
        assertTrue(SharedCampaignDialog.erekirBoardVisible(sector));

        SharedCampaignState action = new SharedCampaignState();
        action.primaryPlanetName = "serpulo";
        SharedCampaignState.ActionState actionState = new SharedCampaignState.ActionState();
        actionState.planetName = "erekir";
        action.actions.put("a", actionState);
        assertTrue(SharedCampaignDialog.erekirBoardVisible(action));

        SharedCampaignState mission = new SharedCampaignState();
        mission.primaryPlanetName = "serpulo";
        SharedCampaignState.MissionState missionState = new SharedCampaignState.MissionState();
        missionState.planetName = "erekir";
        mission.missions.put("m", missionState);
        assertTrue(SharedCampaignDialog.erekirBoardVisible(mission));
    }
}
