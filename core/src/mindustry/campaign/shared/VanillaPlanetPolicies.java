package mindustry.campaign.shared;

import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.api.SharedCampaignPlanetRegistry.*;
import mindustry.content.*;

/** Canonical shared-campaign behavior for the two vanilla campaign planets. */
public final class VanillaPlanetPolicies{
    private VanillaPlanetPolicies(){}

    public static void register(SharedCampaignPlanetRegistry registry){
        registry.register(new PlanetPolicy(
            "mindustry-y:core", "mindustry-y:serpulo-limited-multifront", Planets.serpulo.name, 2,
            "mindustry-y:serpulo-limited-multifront-v2", MultiplayerMode.limitedMultiFront, 2, true, true,
            ResearchSharing.perPlanet, ResourceOwnership.perSector, FailureRule.sectorOnly, InvasionRule.onlineProtected
        ));
        registry.register(new PlanetPolicy(
            "mindustry-y:core", "mindustry-y:erekir-shared-missions", Planets.erekir.name, 2,
            "mindustry-y:erekir-shared-missions-v2", MultiplayerMode.missionInstances, 2, true, true,
            ResearchSharing.perPlanet, ResourceOwnership.perSector, FailureRule.missionAttempt, InvasionRule.disabled
        ));
    }
}
