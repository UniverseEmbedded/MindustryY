package mindustry.campaign.shared.runtime;

import mindustry.campaign.shared.SharedCampaignState.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Pure durable-state guard for resume-vs-relaunch routing. */
public class CampaignStartDispositionTests{
    @Test
    void retainedLostSaveIsReconstructionInputNotResumeState(){
        SectorState lost = new SectorState();
        lost.planetName = "serpulo";
        lost.sectorName = "groundZero";
        lost.saveRelativePath = "actions/lost/config/saves/action.msav";
        lost.hasBase = false;
        lost.captured = false;
        assertTrue(CampaignActionCommands.requiresLostSectorRelaunch(lost));
    }

    @Test
    void authoritativeBaseSaveRemainsResumable(){
        SectorState base = new SectorState();
        base.saveRelativePath = "actions/base/config/saves/action.msav";
        base.hasBase = true;
        assertFalse(CampaignActionCommands.requiresLostSectorRelaunch(base));

        SectorState captured = new SectorState();
        captured.saveRelativePath = "actions/captured/config/saves/action.msav";
        captured.captured = true;
        assertFalse(CampaignActionCommands.requiresLostSectorRelaunch(captured));
    }

    @Test
    void absentSaveIsFreshLandingNotLostReconstruction(){
        assertFalse(CampaignActionCommands.requiresLostSectorRelaunch(null));
        SectorState fresh = new SectorState();
        fresh.planetName = "serpulo";
        fresh.sectorName = "groundZero";
        assertFalse(CampaignActionCommands.requiresLostSectorRelaunch(fresh));
    }
}
