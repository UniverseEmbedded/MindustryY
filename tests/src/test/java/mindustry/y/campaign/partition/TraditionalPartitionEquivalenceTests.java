package mindustry.y.campaign.partition;

import mindustry.*;
import mindustry.content.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J1 traditional-partition equivalence:
 * default mode is TRADITIONAL; a single traditional plan matches sector.getSize()×getSize().
 * Compile/run not executed in this campaign step — assertions are source-level only until verified.
 */
public class TraditionalPartitionEquivalenceTests{
    @Test
    void missingModeParsesAsTraditional(){
        assertEquals(SectorPartitionMode.TRADITIONAL, SectorPartitionMode.parse(null));
        assertEquals(SectorPartitionMode.TRADITIONAL, SectorPartitionMode.parse(""));
        assertEquals(SectorPartitionMode.TRADITIONAL, SectorPartitionMode.parse("   "));
        assertEquals(SectorPartitionMode.TRADITIONAL, SectorPartitionMode.fromPlanet(null));
    }

    @Test
    void knownModeNamesParse(){
        assertEquals(SectorPartitionMode.TRADITIONAL, SectorPartitionMode.parse("TRADITIONAL"));
        assertEquals(SectorPartitionMode.WAFER, SectorPartitionMode.parse("WAFER"));
        assertEquals(SectorPartitionMode.WAFER, SectorPartitionMode.parse("wafer"));
    }

    @Test
    void unknownModeFailsClosed(){
        assertThrows(IllegalArgumentException.class, () -> SectorPartitionMode.parse("CUBE"));
        assertThrows(IllegalArgumentException.class, () -> SectorPartitionMode.parse("TRADITIONALX"));
        // known token with surrounding junk must not match a partial name
        assertThrows(IllegalArgumentException.class, () -> SectorPartitionMode.parse("WAFER_MAP"));
        // surrounding whitespace is trimmed before exact known-name match
        assertEquals(SectorPartitionMode.WAFER, SectorPartitionMode.parse("  WAFER "));
    }

    @Test
    void defaultPlanetFieldIsNullMeansTraditional(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            planet.sectorPartitionMode = null;
            assertEquals(SectorPartitionMode.TRADITIONAL, SectorPartitionMode.fromPlanet(planet));
            Sector sector = planet.sectors.first();
            MapPlan plan = MapPlanProvider.forSector(sector).plan(sector);
            int size = sector.getSize();
            assertEquals(size, plan.width, "traditional plan width must equal legacy sector.getSize()");
            assertEquals(size, plan.height, "traditional plan height must equal legacy sector.getSize()");
            assertTrue(plan.isSquare());
        }finally{
            planet.sectorPartitionMode = previous;
        }
    }

    @Test
    void traditionalProviderMatchesLegacySizeTimesSize(){
        TestBootstrap.ensureBaseContent();
        Sector sector = Planets.serpulo.sectors.first();
        MapPlan plan = new TraditionalMapPlanProvider().plan(sector);
        assertEquals(sector.getSize(), plan.width);
        assertEquals(sector.getSize(), plan.height);
        // equivalence with pre-partition loadGenerator(size, size, ...)
        assertEquals(plan.width, plan.height);
        assertEquals(MapPlan.square(sector.getSize()).width, plan.width);
        assertEquals(MapPlan.square(sector.getSize()).height, plan.height);
    }

    @Test
    void traditionalExplicitModeStillSinglePlan(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            planet.sectorPartitionMode = "TRADITIONAL";
            Sector sector = planet.sectors.first();
            MapPlan plan = MapPlanProvider.forSector(sector).plan(sector);
            assertEquals(sector.getSize(), plan.width);
            assertEquals(sector.getSize(), plan.height);
        }finally{
            planet.sectorPartitionMode = previous;
        }
    }

    @Test
    void waferModeReturnsCenterDieNotSilentTraditionalFullSector(){
        // J3: WaferMapPlanProvider replaced the pre-J3 UnsupportedOperationException stub.
        // Traditional suite only guards the "not silent full-sector traditional" invariant;
        // full die-grid / MapRegionId contracts live in WaferMapPlanTests.
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            planet.sectorPartitionMode = "WAFER";
            assertEquals(SectorPartitionMode.WAFER, SectorPartitionMode.fromPlanet(planet));
            Sector sector = planet.sectors.first();
            MapPlanProvider provider = MapPlanProvider.forSector(sector);
            assertTrue(provider instanceof WaferMapPlanProvider, "expected WaferMapPlanProvider but was " + provider.getClass().getName());
            MapPlan plan = provider.plan(sector);
            assertTrue(plan.isWaferCell(), "WAFER plan must carry MapRegionId, not a traditional plan");
            assertNotNull(plan.regionId);
            assertTrue(plan.regionId.isCenter(), "plan() is the center die for single-World load");
            // Default cellEdge = max(1, size/3) must not collapse to traditional size×size.
            if(sector.getSize() >= 3){
                assertNotEquals(sector.getSize(), plan.width,
                    "WAFER center die must not equal full traditional sector size");
                assertNotEquals(sector.getSize(), plan.height);
            }
            assertEquals(MapPlanProvider.forSector(sector).plans(sector).primary.regionId, plan.regionId);
        }finally{
            planet.sectorPartitionMode = previous;
        }
    }

    @Test
    void mapPlanRejectsNonPositiveDimensions(){
        assertThrows(IllegalArgumentException.class, () -> MapPlan.square(0));
        assertThrows(IllegalArgumentException.class, () -> MapPlan.square(-1));
        assertThrows(IllegalArgumentException.class, () -> new MapPlan(0, 4));
        assertThrows(IllegalArgumentException.class, () -> new MapPlan(4, -2));
        MapPlan ok = new MapPlan(4, 6);
        assertEquals(4, ok.width);
        assertEquals(6, ok.height);
        assertFalse(ok.isSquare());
        assertFalse(ok.isWaferCell(), "raw MapPlan without regionId is not a wafer cell");
        assertFalse(ok.edgeClipped);
        assertTrue(MapPlan.square(4).isSquare());
    }

    @Test
    void traditionalPlansSetIsExactlyOnePrimaryPlan(){
        TestBootstrap.ensureBaseContent();
        Sector sector = Planets.serpulo.sectors.first();
        MapPlanProvider provider = MapPlanProvider.forSector(sector);
        MapPlanSet set = provider.plans(sector);
        assertEquals(1, set.size(), "TRADITIONAL plans() must be a single-element set");
        assertSame(set.primary, set.plans.get(0));
        // plan() and plans() each build a fresh MapPlan — compare dimensions, not identity.
        MapPlan plan = provider.plan(sector);
        assertEquals(plan.width, set.primary.width);
        assertEquals(plan.height, set.primary.height);
        assertEquals(sector.getSize(), set.primary.width);
        assertEquals(sector.getSize(), set.primary.height);
        assertNull(set.primary.regionId, "TRADITIONAL plan has no MapRegionId");
        assertFalse(set.primary.edgeClipped);
        assertThrows(IllegalArgumentException.class, () -> MapPlanSet.of((MapPlan)null));
        assertThrows(IllegalArgumentException.class, () -> MapPlanSet.of(java.util.List.of()));
    }

    @Test
    void nullSectorFailsClosed(){
        assertThrows(IllegalArgumentException.class, () -> MapPlanProvider.forSector(null));
        assertThrows(IllegalArgumentException.class, () -> new TraditionalMapPlanProvider().plan(null));
    }

    @Test
    void unknownPlanetModeFailsClosedAtProviderSelection(){
        TestBootstrap.ensureBaseContent();
        Planet planet = Planets.serpulo;
        String previous = planet.sectorPartitionMode;
        try{
            planet.sectorPartitionMode = "NOT_A_REAL_MODE";
            Sector sector = planet.sectors.first();
            assertThrows(IllegalArgumentException.class, () -> MapPlanProvider.forSector(sector));
        }finally{
            planet.sectorPartitionMode = previous;
        }
    }
}
