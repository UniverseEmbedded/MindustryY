package mindustry.y.campaign.partition;

import java.util.*;

/**
 * Ordered collection of {@link MapPlan}s for one sector load decision.
 * TRADITIONAL always yields exactly one plan (legacy path).
 * WAFER yields the full die grid; {@link #primary} is the center die that the
 * current single-{@code World} {@code loadSectorInternal} path loads (J3 half-done:
 * multi-die runtime loop is TODO — see World.loadSectorInternal).
 */
public final class MapPlanSet{
    public final List<MapPlan> plans;
    /** Plan that a single-World loader should materialize first (center die for WAFER). */
    public final MapPlan primary;

    private MapPlanSet(List<MapPlan> plans, MapPlan primary){
        if(plans == null || plans.isEmpty()){
            throw new IllegalArgumentException("MapPlanSet requires at least one plan");
        }
        if(primary == null || !plans.contains(primary)){
            throw new IllegalArgumentException("primary must be one of plans");
        }
        this.plans = Collections.unmodifiableList(plans);
        this.primary = primary;
    }

    /** Single-plan set (TRADITIONAL / default). */
    public static MapPlanSet of(MapPlan single){
        if(single == null){
            throw new IllegalArgumentException("single == null");
        }
        return new MapPlanSet(Collections.singletonList(single), single);
    }

    /**
     * Multi-plan set; primary defaults to the center die {@code (0,0)} when present,
     * otherwise the first plan.
     */
    public static MapPlanSet of(List<MapPlan> plans){
        if(plans == null || plans.isEmpty()){
            throw new IllegalArgumentException("plans is empty");
        }
        MapPlan primary = null;
        for(MapPlan plan : plans){
            if(plan != null && plan.regionId != null && plan.regionId.isCenter()){
                primary = plan;
                break;
            }
        }
        if(primary == null) primary = plans.get(0);
        return new MapPlanSet(new ArrayList<>(plans), primary);
    }

    public int size(){
        return plans.size();
    }
}
