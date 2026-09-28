package mindustry.campaign.shared;

import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.game.Objectives.*;

/** Single authoritative interpretation of vanilla campaign objectives against Shared Campaign state. */
public final class SharedObjectiveEvaluator{
    private SharedObjectiveEvaluator(){}

    public static Result evaluate(SharedCampaignState state, Objective objective){
        if(state == null || objective == null) return new Result(false, false);
        if(objective instanceof Research research){
            // Vanilla Research.complete() delegates to content.unlockedHost(), so authoritative discovery/auto-unlock
            // satisfies this objective just like explicit research.
            return new Result(true, research.content != null && SharedCampaignProgress.researchUnlocked(state, research.content));
        }
        if(objective instanceof Produce produce){
            // Vanilla Produce.complete() also delegates to content.unlockedHost(); its label differs, not its predicate.
            return new Result(true, produce.content != null && SharedCampaignProgress.researchUnlocked(state, produce.content));
        }
        if(objective instanceof SectorComplete complete){
            if(complete.preset == null || complete.preset.sector == null) return new Result(true, false);
            SectorState sector = state.sectors.get(SharedCampaignProgress.sectorKey(complete.preset.sector));
            return new Result(true, sector != null && sector.captured && sector.hasBase);
        }
        if(objective instanceof OnSector on){
            if(on.preset == null || on.preset.sector == null) return new Result(true, false);
            SectorState sector = state.sectors.get(SharedCampaignProgress.sectorKey(on.preset.sector));
            return new Result(true, sector != null && sector.hasBase);
        }
        if(objective instanceof OnPlanet on){
            if(on.planet == null) return new Result(true, false);
            for(SectorState sector : state.sectors.values()) if(on.planet.name.equals(sector.planetName) && sector.hasBase) return new Result(true, true);
            return new Result(true, false);
        }
        return new Result(false, false);
    }

    public static boolean complete(SharedCampaignState state, Objective objective){
        Result result = evaluate(state, objective);
        return result.supported && result.complete;
    }

    public record Result(boolean supported, boolean complete){}
}
