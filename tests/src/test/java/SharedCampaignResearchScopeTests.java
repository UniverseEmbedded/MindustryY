import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Resource-scope matrix for Shared Campaign research across planets and attacked live sectors. */
public class SharedCampaignResearchScopeTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    void perPlanetAndSharedPoliciesSelectTheExpectedDurableBases(){
        TechTree.TechNode node = TechTree.all.find(candidate -> candidate.content != null && candidate.requirements != null
            && candidate.requirements.length > 0 && SharedCampaignProgress.researchPlanetContains(candidate, Planets.serpulo.name));
        assertNotNull(node);

        Sector serpulo = Planets.serpulo.sectors.get(Planets.serpulo.startSector);
        Sector erekir = Planets.erekir.sectors.get(Planets.erekir.startSector);
        SharedCampaignState state = new SharedCampaignState();
        state.sectors.put(SharedCampaignProgress.sectorKey(serpulo), base(serpulo));
        state.sectors.put(SharedCampaignProgress.sectorKey(erekir), base(erekir));

        state.planetPolicies.put(Planets.serpulo.name, policy(Planets.serpulo.name, "perPlanet"));
        var local = SharedCampaignProgress.researchEligibleSectors(state, node, Planets.serpulo.name);
        assertEquals(1, local.size);
        assertEquals(Planets.serpulo.name, local.first().planetName);

        state.planetPolicies.get(Planets.serpulo.name).researchSharing = "shared";
        var shared = SharedCampaignProgress.researchEligibleSectors(state, node, Planets.serpulo.name);
        assertEquals(2, shared.size, "explicit shared research must include durable bases on both campaign planets");
        assertTrue(shared.contains(sector -> Planets.serpulo.name.equals(sector.planetName)));
        assertTrue(shared.contains(sector -> Planets.erekir.name.equals(sector.planetName)));
    }

    @Test
    void attackedBaseContributesOnlyWhileItsActionIsActuallyRunning(){
        TechTree.TechNode node = TechTree.all.find(candidate -> candidate.content != null && candidate.requirements != null
            && candidate.requirements.length > 0 && SharedCampaignProgress.researchPlanetContains(candidate, Planets.serpulo.name));
        assertNotNull(node);
        Sector serpulo = Planets.serpulo.sectors.get(Planets.serpulo.startSector);
        String key = SharedCampaignProgress.sectorKey(serpulo);

        SharedCampaignState state = new SharedCampaignState();
        SectorState base = base(serpulo);
        base.attacked = true;
        state.sectors.put(key, base);
        state.planetPolicies.put(Planets.serpulo.name, policy(Planets.serpulo.name, "perPlanet"));

        assertTrue(SharedCampaignProgress.researchEligibleSectors(state, node, Planets.serpulo.name).isEmpty(),
            "an attacked offline base must not fund research");

        ActionState action = new ActionState();
        action.actionId = "live-action";
        action.planetName = serpulo.planet.name;
        action.sectorName = SharedCampaignProgress.sectorId(serpulo);
        action.status = ActionStatus.running;
        state.actions.put(action.actionId, action);
        assertEquals(1, SharedCampaignProgress.researchEligibleSectors(state, node, Planets.serpulo.name).size,
            "the attacked base may fund research while its authoritative Action is running");

        action.status = ActionStatus.suspended;
        assertTrue(SharedCampaignProgress.researchEligibleSectors(state, node, Planets.serpulo.name).isEmpty(),
            "a suspended attacked Action must stop contributing resources");
    }

    @Test
    void invalidRequestedPlanetCannotWidenResearchScope(){
        TechTree.TechNode node = TechTree.all.find(candidate -> candidate.content != null && candidate.requirements != null
            && candidate.requirements.length > 0 && SharedCampaignProgress.researchPlanetContains(candidate, Planets.serpulo.name)
            && !SharedCampaignProgress.researchPlanetContains(candidate, Planets.erekir.name));
        assertNotNull(node, "expected a Serpulo-only research node");
        SharedCampaignState state = new SharedCampaignState();
        state.planetPolicies.put(Planets.erekir.name, policy(Planets.erekir.name, "shared"));
        assertTrue(SharedCampaignProgress.researchScopePlanets(state, node, Planets.erekir.name).isEmpty(),
            "a client may not select an unrelated planet to gain campaign-wide research scope");
    }

    private static SectorState base(Sector sector){
        SectorState out = new SectorState();
        out.planetName = sector.planet.name;
        out.sectorName = SharedCampaignProgress.sectorId(sector);
        out.hasBase = true;
        out.captured = true;
        return out;
    }

    private static PlanetPolicyState policy(String planet, String sharing){
        PlanetPolicyState out = new PlanetPolicyState();
        out.planetName = planet;
        out.policyId = "test:" + planet;
        out.compatibilityId = "test-" + planet;
        out.researchSharing = sharing;
        return out;
    }
}
