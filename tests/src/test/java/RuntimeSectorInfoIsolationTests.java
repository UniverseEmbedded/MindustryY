import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.runtime.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Ensures Sector public APIs do not bypass the GameContext-owned SectorInfo accessor. */
@Tag("shared-campaign-parallel")
public class RuntimeSectorInfoIsolationTests{
    @Test
    void secondaryContextsObserveTheirOwnSectorInfoThroughPublicApis(){
        TestBootstrap.ensureBaseContent();
        Sector sector = Planets.serpulo.sectors.first();
        SectorInfo legacy = sector.info;
        var legacySave = sector.save;
        GameContext a = new GameContext("sector-info-a", new GameState(), new World());
        GameContext b = new GameContext("sector-info-b", new GameState(), new World());
        try{
            sector.info = new SectorInfo();
            sector.info.name = "primary-profile";
            sector.info.hasCore = false;

            SectorInfo ai = a.sectorInfo(sector);
            ai.name = "runtime-a";
            ai.hasCore = true;
            ai.waves = false;
            ai.attack = false;
            ai.items.set(Items.copper, 7);

            SectorInfo bi = b.sectorInfo(sector);
            bi.name = "runtime-b";
            bi.hasCore = true;
            bi.waves = false;
            bi.attack = false;
            bi.items.set(Items.copper, 11);

            RuntimeContexts.run(a, () -> {
                assertSame(ai, sector.info());
                assertEquals("runtime-a", sector.name());
                assertTrue(sector.hasSave());
                assertTrue(sector.hasBase());
                assertTrue(sector.isCaptured());
                assertEquals(7, sector.items().get(Items.copper));
                sector.setName("runtime-a-renamed");
                assertEquals("runtime-a-renamed", ai.name);
                assertEquals("primary-profile", sector.info.name);
            });

            RuntimeContexts.run(b, () -> {
                assertSame(bi, sector.info());
                assertEquals("runtime-b", sector.name());
                assertEquals(11, sector.items().get(Items.copper));
                sector.clearInfo();
                assertNotSame(bi, sector.info());
                assertNull(sector.info().name);
                assertTrue(sector.info().hasCore, "clearing restores a fresh SectorInfo with vanilla defaults");
            });

            RuntimeContexts.run(a, () -> {
                assertEquals("runtime-a-renamed", sector.name(), "clearing sibling context must not reset this context");
                assertEquals(7, sector.items().get(Items.copper));
            });
            assertEquals("primary-profile", sector.info.name, "secondary context mutation must not touch primary profile info");
        }finally{
            a.dispose();
            b.dispose();
            sector.info = legacy;
            sector.save = legacySave;
        }
    }
}
