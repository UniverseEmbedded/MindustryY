package mindustry.y.campaign.partition;

import mindustry.type.*;

/**
 * Cross-MapRegion boundary transaction (J4): source → target id transform plus
 * existence / adjacency / conservation checks, fail-closed.
 * <p>
 * <b>Scope:</b> same-sector WAFER die grid only. TRADITIONAL tables are empty so every
 * transfer fails with {@code ok == false} and {@code transferred == 0} — no silent
 * traditional path is opened here.
 * <p>
 * <b>Id transform</b> ({@link #transform}) is pure grid math: a single cardinal step
 * ({@code |dx|+|dy| == 1}) applied to the source id. It does not prove existence.
 * <p>
 * <b>Transfer</b> requires both endpoints registered and 4-adjacent; on success
 * {@code transferred + remaining == amount} with {@code remaining == 0}; on any
 * rejection {@code transferred == 0} and {@code remaining == amount} (nothing moves).
 * <p>
 * Multi-die {@code World} hosting / seamless unit teleport into another loaded map is
 * out of scope — see TODO in {@code World.loadSectorInternal}. This type is the
 * service-layer contract J4 needs; it does not drive load loops itself.
 */
public final class MapRegionCrossing{
    private final MapRegionAdjacency adjacency;

    public MapRegionCrossing(MapRegionAdjacency adjacency){
        if(adjacency == null){
            throw new IllegalArgumentException("adjacency == null");
        }
        this.adjacency = adjacency;
    }

    /**
     * Mode-aware factory: TRADITIONAL → empty adjacency (all transfers fail);
     * WAFER → register dies from {@link MapPlanProvider#plans(Sector)}.
     */
    public static MapRegionCrossing forSector(Sector sector){
        return new MapRegionCrossing(MapRegionAdjacency.forSector(sector));
    }

    public MapRegionAdjacency adjacency(){
        return adjacency;
    }

    /**
     * Pure id transform: {@code source + (dx, dy)} under a single cardinal step.
     * Same {@code sectorId} preserved. Does not check registration/existence.
     *
     * @throws IllegalArgumentException null source or non-cardinal step
     */
    public MapRegionId transform(MapRegionId source, int stepX, int stepY){
        if(source == null){
            throw new IllegalArgumentException("source == null");
        }
        if(Math.abs(stepX) + Math.abs(stepY) != 1){
            throw new IllegalArgumentException(
                "step must be a single cardinal unit, got (" + stepX + "," + stepY + ")");
        }
        return new MapRegionId(source.sectorId, source.gridX + stepX, source.gridY + stepY);
    }

    /**
     * Step convenience: transform then transfer. Illegal steps throw; illegal
     * topology/existence returns a failed result (fail-closed, not thrown).
     */
    public CrossingResult transferStep(MapRegionId source, int stepX, int stepY, int amount){
        MapRegionId target = transform(source, stepX, stepY);
        return transfer(source, target, amount);
    }

    /**
     * Full cross-boundary transaction between an explicit source and target.
     * Never partially applies: either {@code ok} with full amount, or reject with zero moved.
     */
    public CrossingResult transfer(MapRegionId source, MapRegionId target, int amount){
        if(source == null || target == null){
            return CrossingResult.reject(source, target, amount, "null endpoint");
        }
        if(amount <= 0){
            return CrossingResult.reject(source, target, amount, "amount must be positive");
        }
        if(adjacency.isEmpty()){
            return CrossingResult.reject(source, target, amount, "no cross-region adjacency (traditional or empty)");
        }
        if(!adjacency.isRegistered(source)){
            return CrossingResult.reject(source, target, amount, "source not registered");
        }
        if(!adjacency.isRegistered(target)){
            return CrossingResult.reject(source, target, amount, "target not registered");
        }
        if(source.sectorId != target.sectorId){
            return CrossingResult.reject(source, target, amount, "cross-sector transfer unsupported");
        }
        if(!adjacency.isAdjacent(source, target)){
            return CrossingResult.reject(source, target, amount, "endpoints not 4-adjacent");
        }
        return CrossingResult.accept(source, target, amount);
    }

    /**
     * Transfer only when the edge also exposes {@code channel} (minimal logistics gate).
     * Missing channel → reject with zero moved.
     */
    public CrossingResult transfer(MapRegionId source, MapRegionId target, int amount, MapRegionLogisticsChannel channel){
        CrossingResult base = transfer(source, target, amount);
        if(!base.ok){
            return base;
        }
        if(channel == null || !adjacency.supportsChannel(source, target, channel)){
            return CrossingResult.reject(source, target, amount, "channel not available on edge");
        }
        return base;
    }

    /** Outcome of one transfer attempt. Conservation: transferred + remaining == max(amount,0) semantics below. */
    public static final class CrossingResult{
        public final boolean ok;
        /** Human-readable reject reason; null on success. */
        public final String reason;
        public final MapRegionId source;
        public final MapRegionId target;
        /** Units that moved (0 on reject). */
        public final int transferred;
        /** Units left unmoved (== amount on reject; 0 on success). */
        public final int remaining;

        private CrossingResult(boolean ok, String reason, MapRegionId source, MapRegionId target, int transferred, int remaining){
            this.ok = ok;
            this.reason = reason;
            this.source = source;
            this.target = target;
            this.transferred = transferred;
            this.remaining = remaining;
        }

        static CrossingResult accept(MapRegionId source, MapRegionId target, int amount){
            return new CrossingResult(true, null, source, target, amount, 0);
        }

        static CrossingResult reject(MapRegionId source, MapRegionId target, int amount, String reason){
            int held = amount > 0 ? amount : 0;
            return new CrossingResult(false, reason, source, target, 0, held);
        }

        /** Conservation invariant used by tests and callers. */
        public boolean conserves(int amount){
            int expectedRemaining = amount > 0 ? amount : 0;
            return transferred + remaining == expectedRemaining
                && transferred >= 0
                && remaining >= 0
                && (ok ? (transferred == amount && remaining == 0) : (transferred == 0));
        }

        @Override
        public String toString(){
            return (ok ? "ok" : "reject(" + reason + ")")
                + " " + source + "->" + target
                + " moved=" + transferred + " remaining=" + remaining;
        }
    }
}
