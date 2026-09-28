package mindustry.y.campaign.partition;

import arc.util.*;

import mindustry.type.*;

/**
 * Campaign sector map partition strategy.
 * <p>
 * TRADITIONAL: one full-sector square map of {@code sector.getSize()}×{@code sector.getSize()} —
 * equivalent to the pre-partition {@code World.loadSectorInternal} path.
 * WAFER: reserved for multi-die square maps inside a sector (J3). Enum may exist before the
 * wafer provider is implemented; loading must fail closed rather than silently degrade.
 */
public enum SectorPartitionMode{
    /** Legacy single full-sector map. Default when no mode is configured. */
    TRADITIONAL,
    /** Sector-internal wafer of square maps. Not implemented yet — see {@link MapPlanProvider#forSector(Sector)}. */
    WAFER;

    /**
     * Fail-closed parse of a raw mode name.
     * null / blank → {@link #TRADITIONAL} (live loadSector default).
     * Unknown non-empty names → {@link IllegalArgumentException}.
     */
    public static SectorPartitionMode parse(@Nullable String raw){
        if(raw == null) return TRADITIONAL;
        String key = raw.trim();
        if(key.isEmpty()) return TRADITIONAL;
        for(SectorPartitionMode mode : values()){
            if(mode.name().equalsIgnoreCase(key)) return mode;
        }
        // fail-closed: never invent or fall back to TRADITIONAL for an explicit unknown token
        throw new IllegalArgumentException("Unknown sector partition mode: " + raw);
    }

    /** Mode for a planet; null planet or unset field defaults to TRADITIONAL. */
    public static SectorPartitionMode fromPlanet(@Nullable Planet planet){
        if(planet == null) return TRADITIONAL;
        return parse(planet.sectorPartitionMode);
    }
}
