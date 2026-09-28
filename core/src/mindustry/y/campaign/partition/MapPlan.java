package mindustry.y.campaign.partition;

import arc.util.*;

/**
 * One planned map load unit for a sector.
 * TRADITIONAL mode uses exactly one plan with {@code width == height == sector.getSize()}
 * and {@code regionId == null}.
 * WAFER (J3) emits multiple die plans carrying {@link MapRegionId}; dimensions are the
 * die edge (not necessarily the full sector size). This type still has no rotation —
 * same-sector dies share one fixed orientation (R197 / Y端19).
 */
public final class MapPlan{
    public final int width;
    public final int height;
    /** Null for TRADITIONAL full-sector plans; non-null for WAFER die plans. */
    public final @Nullable MapRegionId regionId;
    /**
     * True when this WAFER die is cut by the sector polygon boundary
     * (edge die / 异形有效域). Always false for TRADITIONAL and for fully interior dies.
     */
    public final boolean edgeClipped;

    public MapPlan(int width, int height){
        this(width, height, null, false);
    }

    public MapPlan(int width, int height, @Nullable MapRegionId regionId, boolean edgeClipped){
        if(width <= 0 || height <= 0){
            throw new IllegalArgumentException("MapPlan dimensions must be positive: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.regionId = regionId;
        this.edgeClipped = edgeClipped;
    }

    /** Square plan used by traditional full-sector loading. */
    public static MapPlan square(int size){
        return new MapPlan(size, size);
    }

    public boolean isSquare(){
        return width == height;
    }

    /** WAFER die plans carry a {@link MapRegionId}; TRADITIONAL plans do not. */
    public boolean isWaferCell(){
        return regionId != null;
    }
}
