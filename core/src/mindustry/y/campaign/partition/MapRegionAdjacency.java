package mindustry.y.campaign.partition;

import java.util.*;

import mindustry.type.*;

/**
 * Same-sector {@link MapRegionId} 4-neighbor adjacency (J4).
 * <p>
 * <b>Registration is WAFER-only</b> via {@link #forSector(Sector)}:
 * TRADITIONAL planets get an empty table (no region ids, no edges).
 * WAFER registers every die present in {@link MapPlanProvider#plans(Sector)}.
 * <p>
 * <b>Adjacency rules (minimal, explicit):</b>
 * <ol>
 *   <li>Same {@code sectorId} only — this type never links across sectors.</li>
 *   <li>Grid 4-neighbor: {@code |dx| + |dy| == 1} (no diagonals, no wrap-around).</li>
 *   <li>Both endpoints must be registered dies of this sector's plan set.</li>
 *   <li>Edge-clipped dies ({@link MapPlan#edgeClipped}) participate like any other
 *       registered die when both neighbors exist; a die omitted from the plan set
 *       (polygon miss) has no adjacency at all.</li>
 *   <li>Self is never adjacent to itself.</li>
 * </ol>
 * Sector-level topology remains {@code Sector.near()} — not this type.
 * Multi-die {@code World} hosting is out of scope (TODO in {@code World.loadSectorInternal}).
 */
public final class MapRegionAdjacency{
    /** Unit step directions for grid 4-neighbor edges: +x, -x, +y, -y. */
    public static final int[][] CARDINAL_STEPS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private final int sectorId;
    private final SectorPartitionMode mode;
    private final Set<MapRegionId> registered;

    private MapRegionAdjacency(int sectorId, SectorPartitionMode mode, Set<MapRegionId> registered){
        this.sectorId = sectorId;
        this.mode = mode;
        this.registered = registered;
    }

    /**
     * Mode-aware factory (J4 registration point).
     * <ul>
     *   <li>TRADITIONAL → empty adjacency (no cross-region capability).</li>
     *   <li>WAFER → register all dies from {@code plans(sector)}.</li>
     *   <li>Unknown mode → {@link IllegalArgumentException} (fail-closed).</li>
     * </ul>
     */
    public static MapRegionAdjacency forSector(Sector sector){
        if(sector == null){
            throw new IllegalArgumentException("sector == null");
        }
        SectorPartitionMode mode = SectorPartitionMode.fromPlanet(sector.planet);
        switch(mode){
            case TRADITIONAL:
                return emptyTraditional(sector.id);
            case WAFER:
                MapPlanSet set = MapPlanProvider.forSector(sector).plans(sector);
                return fromPlanSet(sector.id, set);
            default:
                throw new IllegalArgumentException("Unknown sector partition mode: " + mode);
        }
    }

    /** Empty table for a traditional sector (no MapRegionId ever registered). */
    public static MapRegionAdjacency emptyTraditional(int sectorId){
        return new MapRegionAdjacency(sectorId, SectorPartitionMode.TRADITIONAL, Collections.emptySet());
    }

    /**
     * Build from an explicit plan set (WAFER / tests).
     * Plans without {@code regionId} contribute nothing (traditional-shaped plans → empty table).
     */
    public static MapRegionAdjacency fromPlanSet(int sectorId, MapPlanSet plans){
        if(plans == null){
            throw new IllegalArgumentException("plans == null");
        }
        Set<MapRegionId> ids = new HashSet<>();
        boolean anyWafer = false;
        for(MapPlan plan : plans.plans){
            if(plan != null && plan.regionId != null){
                anyWafer = true;
                if(plan.regionId.sectorId != sectorId){
                    throw new IllegalArgumentException(
                        "plan region " + plan.regionId + " does not belong to sector " + sectorId);
                }
                ids.add(plan.regionId);
            }
        }
        SectorPartitionMode mode = anyWafer ? SectorPartitionMode.WAFER : SectorPartitionMode.TRADITIONAL;
        return new MapRegionAdjacency(sectorId, mode, Collections.unmodifiableSet(ids));
    }

    public int sectorId(){
        return sectorId;
    }

    /** Partition mode this table was registered under. */
    public SectorPartitionMode mode(){
        return mode;
    }

    /** True when this table registers no dies (TRADITIONAL, or empty wafer plan set). */
    public boolean isEmpty(){
        return registered.isEmpty();
    }

    public int registeredCount(){
        return registered.size();
    }

    public boolean isRegistered(MapRegionId id){
        return id != null && registered.contains(id);
    }

    /** All registered dies (unmodifiable). */
    public Set<MapRegionId> registeredRegions(){
        return registered;
    }

    /**
     * Structural adjacency: both registered, same sector, Manhattan distance 1.
     * Edge-clipped registered dies are included; missing dies are not.
     */
    public boolean isAdjacent(MapRegionId a, MapRegionId b){
        if(a == null || b == null) return false;
        if(a.sectorId != b.sectorId || a.sectorId != sectorId) return false;
        if(a.equals(b)) return false;
        int manhattan = Math.abs(a.gridX - b.gridX) + Math.abs(a.gridY - b.gridY);
        if(manhattan != 1) return false;
        return registered.contains(a) && registered.contains(b);
    }

    /** Registered 4-neighbors of {@code id} (empty when unregistered / traditional). */
    public List<MapRegionId> neighbors(MapRegionId id){
        if(!isRegistered(id)){
            return Collections.emptyList();
        }
        List<MapRegionId> out = new ArrayList<>(4);
        for(int[] step : CARDINAL_STEPS){
            MapRegionId candidate = new MapRegionId(id.sectorId, id.gridX + step[0], id.gridY + step[1]);
            if(registered.contains(candidate)){
                out.add(candidate);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Optional minimal logistics channel mask on an edge (J4).
     * Traditional or non-adjacent edges support nothing (fail-closed).
     * Adjacent WAFER edges currently support every {@link MapRegionLogisticsChannel}
     * value — per-channel policy beyond that is deferred.
     */
    public Set<MapRegionLogisticsChannel> channels(MapRegionId a, MapRegionId b){
        if(!isAdjacent(a, b)){
            return Collections.emptySet();
        }
        return EnumSet.allOf(MapRegionLogisticsChannel.class);
    }

    public boolean supportsChannel(MapRegionId a, MapRegionId b, MapRegionLogisticsChannel channel){
        if(channel == null) return false;
        return channels(a, b).contains(channel);
    }
}
