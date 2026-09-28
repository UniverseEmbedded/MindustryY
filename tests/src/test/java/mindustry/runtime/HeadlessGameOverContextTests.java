package mindustry.runtime;

import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Guards embedded/headless GameContexts against accidental process-local player/UI dependencies. */
@Tag("shared-campaign-parallel")
public class HeadlessGameOverContextTests{
    @Test
    void headlessGameOverDoesNotRequireProcessLocalPlayer(){
        GameContext context = new GameContext("headless-game-over");
        context.headless = true;
        context.state = new GameState();
        context.state.rules = new Rules();
        context.state.rules.defaultTeam = Team.sharded;
        context.state.wave = 7;

        Player previous = mindustry.Vars.player;
        try{
            mindustry.Vars.player = null;
            RuntimeContexts.run(context, () -> {
                assertDoesNotThrow(() -> Logic.gameOver(Team.sharded));
                assertTrue(context.state.won);
                assertEquals(7, context.state.stats.wavesLasted);

                assertDoesNotThrow(() -> Logic.gameOver(Team.crux));
                assertFalse(context.state.won);
            });
        }finally{
            mindustry.Vars.player = previous;
            context.dispose();
        }
    }
}
