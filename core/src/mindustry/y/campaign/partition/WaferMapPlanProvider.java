package mindustry.y.campaign.partition;

import java.util.*;

import arc.math.geom.*;

import mindustry.type.*;

/**
 * J3 wafer die grid: fixed-orientation square cells on the sector local plane
 * ({@code sector.rect}), center-aligned so die {@code (0,0)} has its center at the
 * sector center. Same-sector cells never rotate relative to each other (R197 / Y端19).
 *
 * <p><b>Size strategy (explicit):</b> {@code cellEdge = max(1, sector.getSize() / cellsAcross)}
 * with {@code cellsAcross} odd (default {@link #DEFAULT_CELLS_ACROSS}). The die lattice spans
 * the same generation projection square {@code [-R, R]²} that {@code PlanetGenerator} uses
 * ({@code sector.rect.project}). Using {@code cellEdge == sector.getSize()} alone would
 * collapse WAFER to a single die identical in dimensions to TRADITIONAL — not used as default.
 * Physical Y端52 scale (die ≈ vanilla map, sector ≈ 10⁵ tiles) needs a larger local domain
 * than one generation square; that scale is deferred (see Findings).
 *
 * <p><b>Edge dies:</b> dies not fully inside the sector polygon are marked
 * {@link MapPlan#edgeClipped} (still emitted — incomplete dies are kept per Y端19/Y端52).
 * Dies that miss the polygon entirely are omitted.
 *
 * <p>{@link #plan(Sector)} returns the center die only — what the current single-World
 * {@code loadSectorInternal} loads. Full grid: {@link #plans(Sector)}. Multi-die runtime
 * hosting is not implemented here.
 */
public class WaferMapPlanProvider implements MapPlanProvider{
    /** Odd; center die exists at grid (0,0). */
    public static final int DEFAULT_CELLS_ACROSS = 3;

    private final int cellsAcross;

    public WaferMapPlanProvider(){
        this(DEFAULT_CELLS_ACROSS);
    }

    /**
     * @param cellsAcross odd number ≥ 1; even values are rejected (no single center column/row).
     */
    public WaferMapPlanProvider(int cellsAcross){
        if(cellsAcross < 1 || cellsAcross % 2 == 0){
            throw new IllegalArgumentException("cellsAcross must be a positive odd integer: " + cellsAcross);
        }
        this.cellsAcross = cellsAcross;
    }

    public int cellsAcross(){
        return cellsAcross;
    }

    /** Die edge length in tiles for a sector map of {@code sectorSize} tiles. */
    public int cellEdge(int sectorSize){
        if(sectorSize <= 0){
            throw new IllegalArgumentException("sectorSize must be positive: " + sectorSize);
        }
        return Math.max(1, sectorSize / cellsAcross);
    }

    public int cellEdge(Sector sector){
        if(sector == null){
            throw new IllegalArgumentException("sector == null");
        }
        return cellEdge(sector.getSize());
    }

    /** Center die for the single-World load path. */
    @Override
    public MapPlan plan(Sector sector){
        MapPlanSet set = plans(sector);
        if(set.primary.regionId == null || !set.primary.regionId.isCenter()){
            throw new IllegalStateException("WAFER primary plan must be the center die: " + set.primary.regionId);
        }
        return set.primary;
    }

    /** Full die grid, row-major in grid coordinates (gy ascending, gx ascending). */
    @Override
    public MapPlanSet plans(Sector sector){
        if(sector == null){
            throw new IllegalArgumentException("sector == null");
        }
        int size = sector.getSize();
        int edge = cellEdge(size);
        int n = cellsAcross / 2;
        int[][] polygon = sectorPolygon2D(sector);

        List<MapPlan> out = new ArrayList<>(cellsAcross * cellsAcross);
        for(int gy = -n; gy <= n; gy++){
            for(int gx = -n; gx <= n; gx++){
                int left = size / 2 + gx * edge - edge / 2;
                int bottom = size / 2 + gy * edge - edge / 2;
                int right = left + edge;
                int top = bottom + edge;
                Integer clip = classifyCell(sector, polygon, left, bottom, right, top, size);
                if(clip == null) continue; // fully outside polygon — omit
                MapRegionId id = new MapRegionId(sector.id, gx, gy);
                out.add(new MapPlan(edge, edge, id, clip == 1));
            }
        }
        if(out.isEmpty()){
            // Degenerate geometry: always emit at least the center die so load never becomes empty.
            out.add(new MapPlan(edge, edge, new MapRegionId(sector.id, 0, 0), true));
        }
        return MapPlanSet.of(out);
    }

    /**
     * @return 0 = fully inside, 1 = clipped (positive-area intersection with polygon),
     *         null = no intersection (omit die).
     */
    private static Integer classifyCell(Sector sector, int[][] polygon, int left, int bottom, int right, int top, int size){
        // Empty-corner special sectors (Sector constructor): treat the generation square as domain;
        // only the overlapping ring is edge-clipped by square bounds (conservative).
        if(polygon == null){
            boolean insideSquare = left >= 0 && bottom >= 0 && right <= size && top <= size;
            boolean intersects = left < size && bottom < size && right > 0 && top > 0;
            if(!intersects) return null;
            return insideSquare ? 0 : 1;
        }

        int cornersIn = 0;
        float[][] corners = {
            {left, bottom}, {right, bottom}, {right, top}, {left, top}
        };
        for(float[] c : corners){
            if(pointInConvexPolygon(c[0], c[1], polygon)) cornersIn++;
        }
        float cx = (left + right) * 0.5f, cy = (bottom + top) * 0.5f;
        boolean centerIn = pointInConvexPolygon(cx, cy, polygon);

        if(cornersIn == 4 && centerIn) return 0;
        if(cornersIn > 0 || centerIn) return 1;

        // Any polygon vertex inside the cell AABB?
        for(int[] p : polygon){
            if(p[0] >= left && p[0] <= right && p[1] >= bottom && p[1] <= top) return 1;
        }
        // Cheap AABB overlap when corners/center missed (cell may still straddle an edge).
        return polygonAabbOverlapsCell(polygon, left, bottom, right, top) ? 1 : null;
    }

    private static boolean polygonAabbOverlapsCell(int[][] polygon, int left, int bottom, int right, int top){
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for(int[] p : polygon){
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return left <= maxX && right >= minX && bottom <= maxY && top >= minY;
    }

    /**
     * Sector polygon vertices in domain tile coordinates {@code [0, size]²}
     * (same square {@code PlanetGenerator} projects). null when the sector has no corners.
     */
    static int[][] sectorPolygon2D(Sector sector){
        if(sector.tile == null || sector.tile.corners == null || sector.tile.corners.length == 0){
            return null;
        }
        int size = sector.getSize();
        float radius = sector.rect.radius;
        if(radius <= 0f) return null;
        int n = sector.tile.corners.length;
        int[][] poly = new int[n][2];
        for(int i = 0; i < n; i++){
            Vec3 world = sector.tile.corners[i].v;
            if(world == null) return null;
            // Same embedding as Sector.makeRect: unit-sphere corner scaled to planet radius.
            Vec3 p = world.cpy().setLength(sector.planet == null ? 1f : sector.planet.radius);
            Vec3 d = p.sub(sector.rect.center);
            // rect.project uses nx,ny ∈ [-1,1] with basis vectors of length radius.
            float nx = d.dot(sector.rect.right) / (radius * radius);
            float ny = d.dot(sector.rect.top) / (radius * radius);
            // Map [-1,1] → domain tile [0, size] (center = size/2).
            poly[i][0] = Math.round((nx * 0.5f + 0.5f) * size);
            poly[i][1] = Math.round((ny * 0.5f + 0.5f) * size);
        }
        return poly;
    }

    /**
     * Point-in-convex-polygon in domain tile coordinates.
     * Winding is auto-detected from the first non-degenerate edge cross product.
     */
    static boolean pointInConvexPolygon(float px, float py, int[][] poly){
        int sign = 0;
        int n = poly.length;
        for(int i = 0; i < n; i++){
            int j = (i + 1) % n;
            float cross = (poly[j][0] - poly[i][0]) * (py - poly[i][1])
                - (poly[j][1] - poly[i][1]) * (px - poly[i][0]);
            if(Math.abs(cross) < 1e-4f) continue;
            int s = cross > 0f ? 1 : -1;
            if(sign == 0){
                sign = s;
            }else if(s != sign){
                return false;
            }
        }
        // sign == 0 → all points collinear with query: treat as inside only if on segment bbox (rare).
        return true;
    }
}
