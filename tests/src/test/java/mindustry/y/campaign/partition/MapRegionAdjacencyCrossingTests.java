package mindustry.y.campaign.partition;

import mindustry.*;
import mindustry.content.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J4: same-sector MapRegion adjacency + cross-boundary transfer (WAFER-only).
 * Covers adjacency symmetry, TRADITIONAL empty table, id transform, and
 * fail-closed illegal transfers. Compile/run not executed in this campaign step.
 */
public class MapRegionAdjacencyCrossingTests{
    private static void withWafer(Planet planet, Runnable body){
        String previous = planet.sectorPartitionMode;
        planet.sectorPartitionMode = "WAFER";
        try{
            body.run();
        }finally{
            planet.sectorPartitionMode = previous;
        }
    }

    private static Sector firstSector(){
        TestBootstrap.ensureBaseContent();
        return Planets.serpulo.sectors.first();
    }

    // ---- adjacency: WAFER registration ----

    @Test
    void waferAdjacencyRegisteredFromPlanSet(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        withWafer(planet, () -> {
            Sector sector = planet.sectors.first();
            MapPlanSet plans = MapPlanProvider.forSector(sector).plans(sector);
            MapRegionAdjacency adj = MapRegionAdjacency.forSector(sector);

            assertEquals(SectorPartitionMode.WAFER, adj.mode());
            assertEquals(sector.id, adj.sectorId());
            assertFalse(adj.isEmpty(), "WAFER must register dies");
            assertEquals(plans.plans.stream().map(p -> p.regionId).filter(Objects::nonNull).distinct().count(),
                (long)adj.registeredCount(), "registration must match plan-set MapRegionIds");

            MapRegionId center = new MapRegionId(sector.id, 0, 0);
            assertTrue(adj.isRegistered(center));
            List<MapRegionId> n = adj.neighbors(center);
            assertFalse(n.isEmpty(), "center die has at least one 4-neighbor when present");
            for(MapRegionId nb : n){
                assertTrue(adj.isAdjacent(center, nb));
                assertEquals(1, Math.abs(center.gridX - nb.gridX) + Math.abs(center.gridY - nb.gridY),
                    "neighbor step must be Manhattan distance 1");
                assertEquals(sector.id, nb.sectorId);
            }
        });
    }

    @Test
    void adjacencyIsSymmetricOnWaferGrid(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        withWafer(planet, () -> {
            Sector sector = planet.sectors.first();
            MapRegionAdjacency adj = MapRegionAdjacency.forSector(sector);
            Set<MapRegionId> ids = adj.registeredRegions();
            for(MapRegionId a : ids){
                for(MapRegionId b : ids){
                    assertEquals(adj.isAdjacent(a, b), adj.isAdjacent(b, a),
                        "adjacency must be symmetric: " + a + " / " + b);
                }
                // neighbors() and isAdjacent must agree
                for(MapRegionId nb : adj.neighbors(a)){
                    assertTrue(adj.isAdjacent(a, nb));
                    assertTrue(adj.neighbors(nb).contains(a), "neighbor list must include reverse edge");
                }
                assertFalse(adj.isAdjacent(a, a), "self never adjacent");
            }
        });
    }

    @Test
    void edgeClippedDiesFollowSameFourNeighborRuleWhenPresent(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        withWafer(planet, () -> {
            Sector sector = planet.sectors.first();
            MapPlanSet plans = MapPlanProvider.forSector(sector).plans(sector);
            MapRegionAdjacency adj = MapRegionAdjacency.forSector(sector);

            Set<MapRegionId> present = new HashSet<>();
            boolean anyClipped = false;
            for(MapPlan plan : plans.plans){
                assertNotNull(plan.regionId);
                present.add(plan.regionId);
                if(plan.edgeClipped) anyClipped = true;
            }

            for(MapRegionId a : present){
                for(MapRegionId b : present){
                    int man = Math.abs(a.gridX - b.gridX) + Math.abs(a.gridY - b.gridY);
                    boolean expect = !a.equals(b) && man == 1;
                    assertEquals(expect, adj.isAdjacent(a, b),
                        "edge-clipped rule: pure 4-neighbor when both present; " + a + "/" + b);
                }
            }

            // Absent die (lattice hole outside plan set) must not join the table.
            MapRegionId missing = new MapRegionId(sector.id, 99, 99);
            assertFalse(adj.isRegistered(missing));
            assertFalse(adj.isAdjacent(new MapRegionId(sector.id, 0, 0), missing));
            assertTrue(adj.neighbors(missing).isEmpty());

            // Documented expectation on default serpulo geometry: at least one clipped die exists.
            // (Presence of clipped dies does not change the adjacency rule above.)
            if(!plans.plans.isEmpty()){
                assertTrue(anyClipped || plans.size() == 1,
                    "default wafer grid should mark edge dies when multi-die");
            }
        });
    }

    // ---- TRADITIONAL: no adjacency ----

    @Test
    void traditionalModeHasEmptyAdjacencyTable(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            planet.sectorPartitionMode = null;
            Sector sector = planet.sectors.first();
            MapRegionAdjacency adj = MapRegionAdjacency.forSector(sector);

            assertEquals(SectorPartitionMode.TRADITIONAL, adj.mode());
            assertTrue(adj.isEmpty(), "TRADITIONAL must register zero MapRegionIds");
            assertEquals(0, adj.registeredCount());
            assertTrue(adj.registeredRegions().isEmpty());
            assertFalse(adj.isRegistered(new MapRegionId(sector.id, 0, 0)));
            assertTrue(adj.neighbors(new MapRegionId(sector.id, 0, 0)).isEmpty());
            assertFalse(adj.isAdjacent(
                new MapRegionId(sector.id, 0, 0),
                new MapRegionId(sector.id, 1, 0)));
            assertFalse(adj.supportsChannel(
                new MapRegionId(sector.id, 0, 0),
                new MapRegionId(sector.id, 1, 0),
                MapRegionLogisticsChannel.UNIT));
        }finally{
            planet.sectorPartitionMode = previous;
        }
    }

    @Test
    void traditionalExplicitModeAlsoEmptyAdjacency(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            planet.sectorPartitionMode = "TRADITIONAL";
            Sector sector = planet.sectors.first();
            assertTrue(MapRegionAdjacency.forSector(sector).isEmpty());
        }finally{
            planet.sectorPartitionMode = previous;
        }
    }

    // ---- id transform ----

    @Test
    void transformAppliesSingleCardinalStepInSameSector(){
        TestBootstrap.ensureBaseContent();
        Sector sector = firstSector();
        MapRegionCrossing crossing = MapRegionCrossing.forSector(sector);

        MapRegionId source = new MapRegionId(sector.id, 0, 0);
        assertEquals(new MapRegionId(sector.id, 1, 0), crossing.transform(source, 1, 0));
        assertEquals(new MapRegionId(sector.id, -1, 0), crossing.transform(source, -1, 0));
        assertEquals(new MapRegionId(sector.id, 0, 1), crossing.transform(source, 0, 1));
        assertEquals(new MapRegionId(sector.id, 0, -1), crossing.transform(source, 0, -1));
        // sectorId never changes under transform
        assertEquals(sector.id, crossing.transform(new MapRegionId(sector.id, 2, -3), 0, 1).sectorId);

        assertThrows(IllegalArgumentException.class, () -> crossing.transform(null, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> crossing.transform(source, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> crossing.transform(source, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> crossing.transform(source, 2, 0));
    }

    // ---- cross-boundary transfer (WAFER legal path) ----

    @Test
    void legalAdjacentTransferMovesFullAmount(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        withWafer(planet, () -> {
            Sector sector = planet.sectors.first();
            MapRegionCrossing crossing = MapRegionCrossing.forSector(sector);
            MapRegionId center = new MapRegionId(sector.id, 0, 0);
            List<MapRegionId> n = crossing.adjacency().neighbors(center);
            Assumptions.assumeTrue(!n.isEmpty(), "center must have a neighbor on default grid");

            MapRegionId target = n.get(0);
            int stepX = target.gridX - center.gridX;
            int stepY = target.gridY - center.gridY;
            MapRegionCrossing.CrossingResult r = crossing.transferStep(center, stepX, stepY, 7);
            assertTrue(r.ok, () -> "expected accept: " + r);
            assertEquals(7, r.transferred);
            assertEquals(0, r.remaining);
            assertTrue(r.conserves(7));
            assertEquals(target, r.target);
            assertEquals(center, r.source);

            // channel overload also accepts on adjacent edge
            MapRegionCrossing.CrossingResult withChannel =
                crossing.transfer(center, target, 3, MapRegionLogisticsChannel.UNIT);
            assertTrue(withChannel.ok);
            assertTrue(withChannel.conserves(3));
        });
    }

    // ---- illegal transfers fail-closed ----

    @Test
    void illegalCrossingFailsClosedOnTraditional(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            planet.sectorPartitionMode = null;
            Sector sector = planet.sectors.first();
            MapRegionCrossing crossing = MapRegionCrossing.forSector(sector);

            MapRegionId a = new MapRegionId(sector.id, 0, 0);
            MapRegionId b = new MapRegionId(sector.id, 1, 0);
            MapRegionCrossing.CrossingResult r = crossing.transfer(a, b, 5);
            assertFalse(r.ok);
            assertEquals(0, r.transferred);
            assertEquals(5, r.remaining);
            assertTrue(r.conserves(5));
            assertNotNull(r.reason);

            MapRegionCrossing.CrossingResult step = crossing.transferStep(a, 1, 0, 5);
            assertFalse(step.ok);
            assertEquals(0, step.transferred);

            MapRegionCrossing.CrossingResult ch = crossing.transfer(a, b, 5, MapRegionLogisticsChannel.ITEM);
            assertFalse(ch.ok);
            assertEquals(0, ch.transferred);
        }finally{
            planet.sectorPartitionMode = previous;
        }
    }

    @Test
    void illegalCrossingFailsClosedOnWaferWhenNotAdjacentOrMissing(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        withWafer(planet, () -> {
            Sector sector = planet.sectors.first();
            MapRegionCrossing crossing = MapRegionCrossing.forSector(sector);
            MapRegionAdjacency adj = crossing.adjacency();
            assertFalse(adj.isEmpty());

            MapRegionId center = new MapRegionId(sector.id, 0, 0);
            assertTrue(adj.isRegistered(center));

            // diagonal / non-cardinal pair → not adjacent
            MapRegionId diagonal = new MapRegionId(sector.id, 1, 1);
            MapRegionCrossing.CrossingResult diag = crossing.transfer(center, diagonal, 4);
            if(adj.isRegistered(diagonal)){
                assertFalse(diag.ok, "diagonal must not be 4-adjacent");
                assertEquals(0, diag.transferred);
                assertTrue(diag.conserves(4));
            }else{
                assertFalse(diag.ok, "unregistered target must reject");
                assertEquals(0, diag.transferred);
            }

            // far unregistered target
            MapRegionId far = new MapRegionId(sector.id, 40, 40);
            MapRegionCrossing.CrossingResult farR = crossing.transfer(center, far, 2);
            assertFalse(farR.ok);
            assertEquals(0, farR.transferred);
            assertTrue(farR.conserves(2));

            // self transfer
            MapRegionCrossing.CrossingResult self = crossing.transfer(center, center, 1);
            assertFalse(self.ok);
            assertEquals(0, self.transferred);

            // non-positive amount
            MapRegionId neighbor = adj.neighbors(center).isEmpty() ? null : adj.neighbors(center).get(0);
            if(neighbor != null){
                assertFalse(crossing.transfer(center, neighbor, 0).ok);
                assertFalse(crossing.transfer(center, neighbor, -3).ok);
                assertEquals(0, crossing.transfer(center, neighbor, -3).transferred);
            }

            // cross-sector target (same grid coords, other sector id)
            MapRegionId otherSector = new MapRegionId(sector.id + 1, 0, 1);
            MapRegionCrossing.CrossingResult cross = crossing.transfer(center, otherSector, 6);
            assertFalse(cross.ok, "cross-sector must fail-closed");
            assertEquals(0, cross.transferred);
            assertTrue(cross.conserves(6));

            // null endpoints
            assertFalse(crossing.transfer(null, center, 1).ok);
            assertFalse(crossing.transfer(center, null, 1).ok);
        });
    }

    @Test
    void fromPlanSetRejectsForeignSectorRegionIds(){
        MapPlanSet foreign = MapPlanSet.of(Arrays.asList(
            new MapPlan(4, 4, new MapRegionId(99, 0, 0), false)));
        assertThrows(IllegalArgumentException.class,
            () -> MapRegionAdjacency.fromPlanSet(1, foreign));
    }

    @Test
    void nullSectorFailsClosedOnAdjacencyFactory(){
        assertThrows(IllegalArgumentException.class, () -> MapRegionAdjacency.forSector(null));
        assertThrows(IllegalArgumentException.class, () -> MapRegionCrossing.forSector(null));
        assertThrows(IllegalArgumentException.class, () -> new MapRegionCrossing(null));
        assertThrows(IllegalArgumentException.class, () -> MapRegionAdjacency.fromPlanSet(0, null));
    }

    @Test
    void logisticsChannelsEmptyWithoutAdjacency(){
        MapRegionAdjacency traditional = MapRegionAdjacency.emptyTraditional(7);
        MapRegionId a = new MapRegionId(7, 0, 0);
        MapRegionId b = new MapRegionId(7, 1, 0);
        assertTrue(traditional.channels(a, b).isEmpty());
        assertFalse(traditional.supportsChannel(a, b, MapRegionLogisticsChannel.UNIT));
        assertFalse(traditional.supportsChannel(a, b, null));

        MapRegionAdjacency wafer = MapRegionAdjacency.fromPlanSet(7, MapPlanSet.of(Arrays.asList(
            new MapPlan(4, 4, a, false),
            new MapPlan(4, 4, b, false))));
        assertTrue(wafer.isAdjacent(a, b));
        assertTrue(wafer.supportsChannel(a, b, MapRegionLogisticsChannel.UNIT));
        assertTrue(wafer.supportsChannel(a, b, MapRegionLogisticsChannel.ITEM));
        assertFalse(wafer.supportsChannel(a, new MapRegionId(7, 1, 1), MapRegionLogisticsChannel.ITEM));
    }
}
