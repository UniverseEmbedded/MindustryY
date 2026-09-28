package mindustry.y.campaign.partition;

import org.junit.jupiter.api.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J1 product wiring: {@code World.loadSectorInternal} must plan through
 * {@link MapPlanProvider} before {@code loadGenerator}, not call the legacy
 * {@code loadGenerator(size, size, ...)} directly with {@code sector.getSize()}.
 * Source-level assertion only — compile/run not executed in this campaign step.
 */
public class WorldLoadSectorPartitionWiringTests{
    @Test
    void loadSectorInternalPlansThroughMapPlanProvider() throws Exception{
        String source = Files.readString(worldSource());
        // Note: donor writes this as two statements (provider then plan); assert the actual form.
        assertTrue(source.contains("MapPlanProvider provider = MapPlanProvider.forSector(sector);"),
            "loadSectorInternal must resolve a provider via MapPlanProvider.forSector");
        assertTrue(source.contains("MapPlan plan = provider.plan(sector);"),
            "loadSectorInternal must resolve plan via provider.plan");
        assertTrue(source.contains("loadGenerator(plan.width, plan.height,"),
            "loadGenerator must consume plan dimensions, not raw sector.getSize()");
        // Guard against accidental reversion to the pre-partition load path.
        assertFalse(source.contains("loadGenerator(sector.getSize(), sector.getSize()"),
            "legacy loadGenerator(size,size) would bypass J1 partition planning");
        int insert = source.indexOf("MapPlanProvider.forSector(sector)");
        int load = source.indexOf("loadGenerator(plan.width, plan.height");
        assertTrue(insert >= 0 && load > insert,
            "MapPlanProvider.forSector must run before loadGenerator in loadSectorInternal");
    }

    private static Path worldSource(){
        Path start = Paths.get("").toAbsolutePath();
        for(Path p = start; p != null; p = p.getParent()){
            if(Files.exists(p.resolve("core/src/mindustry/core/World.java"))){
                return p.resolve("core/src/mindustry/core/World.java");
            }
            if(Files.exists(p.resolve("Mindustry-Y-62dbbe3/core/src/mindustry/core/World.java"))){
                return p.resolve("Mindustry-Y-62dbbe3/core/src/mindustry/core/World.java");
            }
        }
        throw new IllegalStateException("World.java not found from: " + start);
    }
}
