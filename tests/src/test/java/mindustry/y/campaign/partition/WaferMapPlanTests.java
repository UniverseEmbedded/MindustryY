package mindustry.y.campaign.partition;

import mindustry.*;
import mindustry.content.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J3 wafer die-array tests: center alignment, same orientation (no per-cell rotation),
 * plan counts, MapRegionId uniqueness, edge-clip flags, and TRADITIONAL still one plan.
 * Compile/run not executed in this campaign step — assertions are source-level only until verified.
 */
public class WaferMapPlanTests{
    private static Planet withWafer(Planet planet){
        String previous = planet.sectorPartitionMode;
        planet.sectorPartitionMode = "WAFER";
        return planet;
    }

    private static void restore(Planet planet, String previous){
        planet.sectorPartitionMode = previous;
    }

    private static Sector firstSector(Planet planet){
        TestBootstrap.ensureBaseContent();
        return planet.sectors.first();
    }

    @Test
    void traditionalStillExactlyOnePlan(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            planet.sectorPartitionMode = null;
            Sector sector = planet.sectors.first();
            MapPlanProvider provider = MapPlanProvider.forSector(sector);
            MapPlan plan = provider.plan(sector);
            MapPlanSet set = provider.plans(sector);
            assertEquals(1, set.size(), "TRADITIONAL must remain a single-plan path");
            assertEquals(1, set.plans.size());
            // plan() builds a fresh immutable value per call; identity cannot hold across two calls.`n            assertEquals(plan.width, set.primary.width, "TRADITIONAL primary must describe the single full-sector plan");`n            assertEquals(plan.height, set.primary.height, "TRADITIONAL primary must describe the single full-sector plan");
            assertNull(plan.regionId, "TRADITIONAL plan has no MapRegionId");
            assertFalse(plan.edgeClipped);
            assertEquals(sector.getSize(), plan.width);
            assertEquals(sector.getSize(), plan.height);
        }finally{
            restore(planet, previous);
        }
    }

    @Test
    void caseWAFERReturnsWaferProviderNotUOE(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            withWafer(planet);
            Sector sector = planet.sectors.first();
            MapPlanProvider provider = MapPlanProvider.forSector(sector);
            assertTrue(provider instanceof WaferMapPlanProvider, "expected WaferMapPlanProvider but was " + provider.getClass().getName());
            MapPlan center = provider.plan(sector);
            assertNotNull(center, "J3: WAFER plan() must yield the center die, not throw");
            assertTrue(center.isWaferCell());
            assertNotNull(center.regionId);
            assertTrue(center.regionId.isCenter());
        }finally{
            restore(planet, previous);
        }
    }

    @Test
    void centerDieAlignedToSectorCenter(){
        TestBootstrap.ensureBaseContent();
        Sector sector = firstSector(Planets.serpulo);
        WaferMapPlanProvider provider = new WaferMapPlanProvider();
        MapPlan center = provider.plan(sector);

        assertEquals(sector.id, center.regionId.sectorId);
        assertEquals(0, center.regionId.gridX, "center die gridX must be 0");
        assertEquals(0, center.regionId.gridY, "center die gridY must be 0");
        assertFalse(center.edgeClipped, "center die must be a full interior square");
        assertEquals(provider.cellEdge(sector), center.width);
        assertEquals(provider.cellEdge(sector), center.height);
    }

    @Test
    void planCountMatchesOddCellGrid(){
        TestBootstrap.ensureBaseContent();
        Sector sector = firstSector(Planets.serpulo);

        for(int across : new int[]{1, 3, 5}){
            WaferMapPlanProvider provider = new WaferMapPlanProvider(across);
            MapPlanSet set = provider.plans(sector);
            // Full lattice always emitted unless a die misses the polygon entirely;
            // for default geometry over the generation square all across×across dies intersect.
            assertEquals(across * across, set.size(),
                "expected full " + across + "x" + across + " die grid, got " + set.size());
            assertTrue(set.primary.regionId.isCenter());

            Set<MapRegionId> ids = new HashSet<>();
            for(MapPlan plan : set.plans){
                assertNotNull(plan.regionId, "every wafer plan must carry MapRegionId");
                assertTrue(ids.add(plan.regionId), "duplicate MapRegionId: " + plan.regionId);
                assertEquals(provider.cellEdge(sector), plan.width);
                assertEquals(provider.cellEdge(sector), plan.height);
                assertTrue(plan.isSquare());
            }
        }
    }

    @Test
    void sameOrientationNoPerCellRotation(){
        TestBootstrap.ensureBaseContent();
        Sector sector = firstSector(Planets.serpulo);
        WaferMapPlanProvider provider = new WaferMapPlanProvider();
        MapPlanSet set = provider.plans(sector);

        // Same orientation is structural: MapPlan has no rotation field; all dies share
        // one sector.rect basis and one cellEdge. Grid indices form a regular lattice.
        int edge = provider.cellEdge(sector);
        Set<Integer> seenX = new TreeSet<>(), seenY = new TreeSet<>();
        for(MapPlan plan : set.plans){
            assertEquals(edge, plan.width, "all dies share one fixed edge/orientation");
            assertEquals(edge, plan.height);
            seenX.add(plan.regionId.gridX);
            seenY.add(plan.regionId.gridY);
        }
        int across = provider.cellsAcross();
        int n = across / 2;
        for(int g = -n; g <= n; g++){
            assertTrue(seenX.contains(g), "missing gridX " + g + " (same-axis alignment)");
            assertTrue(seenY.contains(g), "missing gridY " + g);
        }
        assertEquals(across, seenX.size());
        assertEquals(across, seenY.size());
    }

    @Test
    void edgeDiesMarkedClippedWhenStraddlingBoundary(){
        TestBootstrap.ensureBaseContent();
        Sector sector = firstSector(Planets.serpulo);
        WaferMapPlanProvider provider = new WaferMapPlanProvider();
        MapPlanSet set = provider.plans(sector);

        MapPlan center = set.primary;
        assertFalse(center.edgeClipped);

        boolean anyEdgeFlagged = false;
        for(MapPlan plan : set.plans){
            if(plan.regionId.isCenter()) continue;
            // Ring dies on a pentagon/hexagon generation square always leave the polygon
            // or sit on the boundary → must be flagged edgeClipped when present.
            if(plan.edgeClipped) anyEdgeFlagged = true;
        }
        assertTrue(anyEdgeFlagged,
            "non-center dies on the generation square must include at least one edge-clipped die");
    }

    @Test
    void cellsAcrossMustBeOddPositive(){
        assertThrows(IllegalArgumentException.class, () -> new WaferMapPlanProvider(0));
        assertThrows(IllegalArgumentException.class, () -> new WaferMapPlanProvider(-1));
        assertThrows(IllegalArgumentException.class, () -> new WaferMapPlanProvider(2));
        assertThrows(IllegalArgumentException.class, () -> new WaferMapPlanProvider(4));
        assertEquals(1, new WaferMapPlanProvider(1).cellsAcross());
        assertEquals(3, new WaferMapPlanProvider().cellsAcross());
    }

    @Test
    void cellEdgeStrategyIsSizeDividedByCellsAcross(){
        TestBootstrap.ensureBaseContent();
        Sector sector = firstSector(Planets.serpulo);
        int size = sector.getSize();
        WaferMapPlanProvider provider = new WaferMapPlanProvider(3);
        assertEquals(Math.max(1, size / 3), provider.cellEdge(sector));
        // Not degenerate to traditional full-sector single die under default cellsAcross=3
        // unless size < 3 (degenerate tiny sectors).
        if(size >= 3){
            assertNotEquals(size, provider.cellEdge(sector),
                "default cellEdge must not collapse to full traditional size");
        }
    }

    @Test
    void nullSectorFailsClosedOnPlanAndPlans(){
        WaferMapPlanProvider provider = new WaferMapPlanProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.plan(null));
        assertThrows(IllegalArgumentException.class, () -> provider.plans(null));
        assertThrows(IllegalArgumentException.class, () -> provider.cellEdge((Sector)null));
        assertThrows(IllegalArgumentException.class, () -> provider.cellEdge(0));
        assertThrows(IllegalArgumentException.class, () -> provider.cellEdge(-3));
    }

    @Test
    void mapPlanSetRejectsEmptyOrNullPrimaryMembership(){
        assertThrows(IllegalArgumentException.class, () -> MapPlanSet.of((MapPlan)null));
        assertThrows(IllegalArgumentException.class, () -> MapPlanSet.of(java.util.List.of()));
        MapPlan a = new MapPlan(4, 4);
        MapPlan b = new MapPlan(4, 4);
        // primary must be one of plans — synthetic primary not in list is rejected by of(list)
        // (of(list) picks center or first; empty/null still fail closed).
        MapPlanSet set = MapPlanSet.of(java.util.List.of(a, b));
        assertEquals(2, set.size());
        assertSame(a, set.primary, "without regionId, primary defaults to first plan");
    }

    @Test
    void plansPrimaryIsCenterDieForWafer(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            withWafer(planet);
            Sector sector = planet.sectors.first();
            MapPlanProvider provider = MapPlanProvider.forSector(sector);
            MapPlanSet set = provider.plans(sector);
            assertTrue(set.primary.regionId.isCenter());
            assertEquals(sector.id, set.primary.regionId.sectorId);
            // plan() must be the same primary die value (fresh provider each forSector call — not identity).
            MapPlan center = provider.plan(sector);
            assertEquals(set.primary.regionId, center.regionId);
            assertEquals(set.primary.width, center.width);
            assertEquals(set.primary.height, center.height);
            assertFalse(center.edgeClipped);
        }finally{
            restore(planet, previous);
        }
    }
}
