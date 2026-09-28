package mindustry.campaign.shared.mission;

import arc.struct.*;
import mindustry.campaign.shared.api.SharedCampaignMissionRegistry;
import mindustry.game.*;

/** Phase model derived from the objective flags and world-processor scripts embedded in the vanilla Erekir maps. */
public final class ErekirMissionCatalog{
    private ErekirMissionCatalog(){}

    /** Stable base-game definition recorded by the shared-campaign mission registry. */
    public record Definition(String sectorName, int sectorId) implements SharedCampaignMissionRegistry.MissionSpec{
        @Override public String compatibilityId(){ return "vanilla-erekir:" + sectorName + ":sector-" + sectorId + ":mission-runtime-1"; }
        @Override public String planetName(){ return "erekir"; }
        @Override public String displayName(){ return sectorName; }
        @Override public boolean freezeWhenEmpty(){ return true; }
    }

    public static String phase(String sectorName, long actionTick, ObjectSet<String> signals, ObjectSet<String> completedWorldEvents, Rules rules){
        return switch(sectorName){
            case "onset" -> signalPhase(signals,
                "openMap", "final-defense",
                "defDone", "counterattack",
                "defStart", "first-defense",
                "breachAmmo", "fortification",
                "setup");
            case "aegis" -> signals.contains("beginBuild") ? actionTick >= minutes(9) ? "reinforced-enemy-production" : "enemy-production" : "tungsten-logistics";
            case "lake" -> actionTick >= minutes(4) ? "enemy-production" : "naval-production";
            case "intersect" -> rules.attackMode ? "counterattack" : "wave-defense";
            case "atlas" -> actionTick >= minutes(4) ? "reinforced-enemy-production" : "assault";
            case "split" -> "payload-logistics";
            case "basin" -> completedWorldEvents.contains(eventId(sectorName, "nuke1")) ? "post-nuclear-strike" : signals.contains("nukeannounce") ? "nuclear-strike-warning" : "core-assault";
            case "marsh" -> signals.contains("setupComplete") ? "open-assault" : "industrialization";
            case "peaks" -> signals.contains("setupFinished") ? "enemy-production" : signals.contains("openMap") ? "expanded-front" : "tungsten-defense";
            case "ravine", "crevice", "karst" -> "wave-defense";
            case "caldera-erekir" -> actionTick >= minutes(5) ? "reinforced-enemy-production" : "assault";
            case "stronghold" -> signalPhase(signals,
                "units5", "final-enemy-production",
                "units4", "heavy-enemy-production",
                "beginAirProduction", "air-production",
                "units3", "advanced-enemy-production",
                "units2", "expanded-enemy-production",
                "expandMap", "expanded-front",
                "units1", "initial-enemy-production",
                "preparation");
            case "siege" -> signalPhase(signals,
                "u3", "final-enemy-production",
                "u2", "heavy-enemy-production",
                "u1", "advanced-enemy-production",
                "def", "expanded-front",
                "preparation");
            case "crossroads" -> signalPhase(signals,
                "u4", "assembler-production",
                "u3", "heavy-enemy-production",
                "u1", "expanded-enemy-production",
                "u2", "initial-enemy-production",
                "preparation");
            case "origin" -> signalPhase(signals,
                "u5", "final-enemy-production",
                "u4", "assembler-production",
                "u3", "heavy-enemy-production",
                "u2", "advanced-enemy-production",
                "u1", "initial-enemy-production",
                "preparation");
            default -> rules.attackMode ? "attack" : rules.waves ? "defense" : rules.objectives.any() ? "objectives" : "building";
        };
    }

    public static String eventId(String sectorName, String signal){ return "vanilla-erekir:" + sectorName + "/world-event/signal/" + signal; }
    private static long minutes(int value){ return value * 60L * 60L; }

    private static String signalPhase(ObjectSet<String> signals, String... values){
        for(int i = 0; i < values.length - 1; i += 2) if(signals.contains(values[i])) return values[i + 1];
        return values[values.length - 1];
    }
}
