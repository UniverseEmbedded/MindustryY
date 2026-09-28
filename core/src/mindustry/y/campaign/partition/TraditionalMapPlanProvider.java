package mindustry.y.campaign.partition;

import mindustry.type.*;

/**
 * Traditional (pre-partition) plan: a single square map with
 * {@code width == height == sector.getSize()}, matching the live
 * {@code World.loadSectorInternal} contract {@code loadGenerator(size, size, ...)}.
 * <p>
 * This is the equivalence baseline for campaign partitioning; do not change dimensions
 * without an explicit compatibility decision.
 */
public class TraditionalMapPlanProvider implements MapPlanProvider{
    @Override
    public MapPlan plan(Sector sector){
        if(sector == null){
            throw new IllegalArgumentException("sector == null");
        }
        return MapPlan.square(sector.getSize());
    }
}
