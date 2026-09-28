package mindustry.net;

import arc.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Guards runtime-scoped campaign research against destructive world-stream serialization. */
class NetworkIOCampaignResearchTests{
    @BeforeAll
    static void content(){
        TestBootstrap.ensureBaseContent();
        if(Core.bundle == null) Core.bundle = I18NBundle.createEmptyBundle();
    }

    @Test
    void runtimeScopedWorldWriteKeepsAuthoritativeResearch(){
        GameContext context = new GameContext("network-world-write-research");
        context.state = new GameState();
        context.world = new World();
        context.state.rules.sector = Planets.serpulo.sectors.get(Planets.serpulo.startSector);
        context.state.rules.researched.add(Blocks.sorter);

        try{
            RuntimeContexts.run(context, () -> {
                assertTrue(Blocks.sorter.unlockedNowHost(), "runtime precondition must read Sorter from live Rules.researched");

                Rules outbound = NetworkIO.rulesForWorldWrite();

                assertTrue(outbound.researched.contains(Blocks.sorter), "joining client must receive authoritative Action research");
                assertTrue(context.state.rules.researched.contains(Blocks.sorter), "serializing world data must not clear server research");
                assertTrue(Blocks.sorter.unlockedNowHost(), "server unlock semantics must remain unchanged after world serialization");
            });
        }finally{ context.dispose(); }
    }
}
