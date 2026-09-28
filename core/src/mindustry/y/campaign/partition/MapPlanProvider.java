package mindustry.y.campaign.partition;

import mindustry.type.*;

/**
 * Resolves how a sector is partitioned into map load units before generation.
 * <p>
 * J1 insertion point for {@code World.loadSectorInternal}: always call
 * {@link #forSector(Sector)} then {@link #plan(Sector)} before {@code loadGenerator}.
 * TRADITIONAL returns a single size×size plan (legacy-equivalent).
 * WAFER (J3) returns the center die via {@link #plan(Sector)} and the full grid via
 * {@link #plans(Sector)} — never a silent traditional full-sector plan.
 * Multi-die World hosting remains TODO (half-done); see {@code World.loadSectorInternal}.
 */
public interface MapPlanProvider{
    /**
     * Primary plan for a single-{@code World} load.
     * TRADITIONAL: the only plan ({@code size×size}).
     * WAFER: the center die {@code (0,0)} only — not the full grid.
     */
    MapPlan plan(Sector sector);

    /**
     * Full ordered plan list for this sector.
     * Default: single-element set around {@link #plan(Sector)} (TRADITIONAL).
     */
    default MapPlanSet plans(Sector sector){
        return MapPlanSet.of(plan(sector));
    }

    /**
     * Mode-aware provider factory. Source: {@link SectorPartitionMode#fromPlanet(Planet)}.
     * Missing field → TRADITIONAL; unknown name → {@link IllegalArgumentException}.
     */
    static MapPlanProvider forSector(Sector sector){
        if(sector == null){
            throw new IllegalArgumentException("sector == null");
        }
        SectorPartitionMode mode = SectorPartitionMode.fromPlanet(sector.planet);
        switch(mode){
            case TRADITIONAL:
                return new TraditionalMapPlanProvider();
            case WAFER:
                // J3 sole insertion point: wafer die-array provider (center plan + full plans()).
                // Still not a full multi-map campaign load — World loads primary only (TODO J4+).
                return new WaferMapPlanProvider();
            default:
                // defensive: enum is closed, but never default-succeed an unknown mode
                throw new IllegalArgumentException("Unknown sector partition mode: " + mode);
        }
    }
}
