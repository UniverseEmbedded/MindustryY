package mindustry.campaign.shared.legacy;

import arc.struct.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;

import java.util.*;

/**
 * Converts the latest historical MDT-Y durable state into the clean Shared Campaign schema.
 *
 * <p>This migration intentionally operates on the engine-independent state graph only. Product-specific binary
 * payloads have already been discarded by {@link LegacyMdtYSharedCampaignReader}; this step normalizes the remaining
 * multiplayer policy identities and makes the resulting state eligible for clean schema-26 persistence.</p>
 */
public final class LegacyMdtYMigration{
    private LegacyMdtYMigration(){}

    /**
     * Migrates an MDT-Y schema-25 state to clean schema 26 without mutating the caller's snapshot.
     * Older legacy schemas remain readable but require the historical ordered migration chain before this method.
     */
    public static SharedCampaignState toCleanSchema26(SharedCampaignState legacy){
        Objects.requireNonNull(legacy, "legacy");
        if(legacy.schema != 25){
            throw new IllegalArgumentException("Latest MDT-Y migration requires schema 25, got " + legacy.schema);
        }

        SharedCampaignState state = SharedCampaignStateCopy.copy(legacy);
        normalizePlanetPolicies(state);
        state.schema = SharedCampaignState.currentSchema;
        state.originDescription = appendMigrationNote(state.originDescription);
        state.validate();
        return state;
    }

    private static void normalizePlanetPolicies(SharedCampaignState state){
        ObjectSet<String> planets = new ObjectSet<>();
        if(state.primaryPlanetName != null && !state.primaryPlanetName.isBlank()) planets.add(state.primaryPlanetName);
        for(SectorState sector : state.sectors.values()) if(sector.planetName != null && !sector.planetName.isBlank()) planets.add(sector.planetName);
        for(ActionState action : state.actions.values()) if(action.planetName != null && !action.planetName.isBlank()) planets.add(action.planetName);
        for(MissionState mission : state.missions.values()) if(mission.planetName != null && !mission.planetName.isBlank()) planets.add(mission.planetName);

        ObjectMap<String, PlanetPolicyState> normalized = new ObjectMap<>();
        for(String planet : planets){
            PlanetPolicyState old = state.planetPolicies.get(planet);
            normalized.put(planet, cleanPolicy(planet, old, state.maxActiveActions));
        }
        state.planetPolicies = normalized;
    }

    private static PlanetPolicyState cleanPolicy(String planet, PlanetPolicyState old, int maxActiveActions){
        PlanetPolicyState policy = new PlanetPolicyState();
        policy.planetName = planet;

        if("serpulo".equals(planet)){
            policy.policyId = "vanilla:serpulo-shared";
            policy.compatibilityId = "vanilla:serpulo-shared:v1";
            policy.mode = "limitedMultiFront";
            policy.recommendedActiveActions = Math.max(1, Math.min(2, maxActiveActions));
            policy.researchSharing = "perPlanet";
            policy.resourceOwnership = "perSector";
            policy.failureRule = "sectorOnly";
            policy.invasionRule = "onlineProtected";
            return policy;
        }
        if("erekir".equals(planet)){
            policy.policyId = "vanilla:erekir-shared";
            policy.compatibilityId = "vanilla:erekir-shared:v1";
            policy.mode = "missionInstances";
            policy.recommendedActiveActions = Math.max(1, Math.min(2, maxActiveActions));
            policy.researchSharing = "perPlanet";
            policy.resourceOwnership = "perSector";
            policy.failureRule = "missionAttempt";
            policy.invasionRule = "disabled";
            return policy;
        }

        // Unknown/Mod planets are retained as campaign data rather than silently deleted. Their old product identity
        // is replaced by a neutral legacy-import identity; semantic strings are preserved when the clean schema knows
        // them, otherwise conservative custom values force a future extension/policy layer to make the decision.
        policy.policyId = "legacy-import:" + sanitizeIdentity(planet);
        policy.compatibilityId = policy.policyId + ":v1";
        policy.mode = old == null || old.mode == null || old.mode.isBlank() ? "custom" : old.mode;
        policy.recommendedActiveActions = old == null ? Math.max(1, maxActiveActions) : clampActions(old.recommendedActiveActions, maxActiveActions);
        policy.researchSharing = valid(old == null ? null : old.researchSharing, "shared", "perPlanet", "custom") ? old.researchSharing : "custom";
        policy.resourceOwnership = valid(old == null ? null : old.resourceOwnership, "perSector", "sharedPool", "custom") ? old.resourceOwnership : "custom";
        policy.failureRule = valid(old == null ? null : old.failureRule, "sectorOnly", "missionAttempt", "campaign", "custom") ? old.failureRule : "custom";
        policy.invasionRule = valid(old == null ? null : old.invasionRule, "onlineProtected", "continuous", "disabled", "custom") ? old.invasionRule : "custom";
        return policy;
    }

    private static int clampActions(int requested, int fallback){
        int value = requested <= 0 ? Math.max(1, fallback) : requested;
        return Math.max(1, Math.min(32, value));
    }

    private static boolean valid(String value, String... allowed){
        if(value == null) return false;
        for(String candidate : allowed) if(candidate.equals(value)) return true;
        return false;
    }

    private static String sanitizeIdentity(String value){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < value.length(); i++){
            char c = Character.toLowerCase(value.charAt(i));
            if((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') out.append(c);
            else out.append('-');
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }

    private static String appendMigrationNote(String description){
        String note = "Migrated from MDT-Y shared campaign schema 25 to clean schema 26";
        if(description == null || description.isBlank()) return note;
        if(description.contains(note)) return description;
        return description + "; " + note;
    }
}
