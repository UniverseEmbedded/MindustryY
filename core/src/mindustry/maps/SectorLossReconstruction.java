package mindustry.maps;

import arc.util.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;

import static mindustry.Vars.*;

/**
 * Vanilla campaign lost-sector reconstruction shared by the graphical Local Campaign flow and Shared Actions.
 *
 * The old failed world is loaded only long enough to apply {@link SectorDamage}, capture surviving/derelict
 * buildings and build plans, then a freshly generated sector receives that damaged history. This deliberately
 * contains no UI, save-slot or network behavior so every campaign runtime can reuse the same semantics.
 */
public final class SectorLossReconstruction{
    private SectorLossReconstruction(){}

    public record Snapshot(int spawnPosition, boolean wasCaptured, BlockPlan[] plans, Building[] buildings, Building[] derelicts){}

    /**
     * Captures the vanilla post-loss reconstruction state from the currently-loaded failed world.
     * Returns null when the saved sector metadata cannot safely be mapped to the current authored sector.
     */
    public static @Nullable Snapshot capture(Sector sector){
        if(sector == null || sector.info().spawnPosition == 0 || !sector.info().sectorDataMatches(sector)) return null;
        int spawnPos = sector.info().spawnPosition;
        Tile spawn = game().world.tile(spawnPos);
        if(spawn == null) return null;

        // SectorDamage expects a friendly core as the pathfinding destination, matching vanilla Control.playSector.
        spawn.setBlock(sector.planet.defaultCore, game().state.rules.defaultTeam);
        SectorDamage.apply(1f);

        return new Snapshot(
            spawnPos, sector.info().wasCaptured,
            game().state.rules.defaultTeam.data().plans.toArray(BlockPlan.class),
            game().state.rules.defaultTeam.data().buildings.<Building>toArray(Building.class),
            Team.derelict.data().buildings.<Building>toArray(Building.class)
        );
    }

    /** Restores a captured damaged history into a freshly-generated sector world. */
    public static void restore(Snapshot snapshot){
        if(snapshot == null) return;
        var teamData = game().state.rules.defaultTeam.data();
        if(game().state.rules.sector != null) game().state.rules.sector.info().wasCaptured = snapshot.wasCaptured();

        // Generated derelicts belong to the new authored world; vanilla replaces them with the prior failed world's
        // derelicts so re-entering a lost sector preserves the actual battlefield history.
        for(var generatedDerelict : Team.derelict.data().buildings.<Building>toArray(Building.class)){
            generatedDerelict.tile.remove();
        }

        for(var build : snapshot.derelicts()){
            Tile tile = game().world.tile(build.tileX(), build.tileY());
            if(tile != null && tile.build == null && Build.validPlace(build.block, Team.derelict, build.tileX(), build.tileY(), build.rotation, false, false)){
                tile.setBlock(build.block, Team.derelict, build.rotation, () -> build);
            }
        }

        // Power links/graphs reference entities from the old world and must not survive the reconstruction boundary.
        for(var build : snapshot.buildings()){
            if(build.power != null){
                build.power.graph = new PowerGraph();
                build.power.links.clear();
            }
        }

        for(var build : snapshot.buildings()){
            Tile tile = game().world.tile(build.tileX(), build.tileY());
            if(tile != null && tile.build == null && Build.validPlace(build.block, game().state.rules.defaultTeam, build.tileX(), build.tileY(), build.rotation, false, false)){
                build.addPlan(false, true);
                tile.setBlock(build.block, game().state.rules.defaultTeam, build.rotation, () -> build);
                build.changeTeam(Team.derelict);
                build.dropped();
            }
        }

        for(var build : snapshot.buildings()) if(build.isValid()) build.updateProximity();

        for(var plan : snapshot.plans()){
            var build = game().world.build(plan.x, plan.y);
            if(!(build != null && build.block == plan.block && build.tileX() == plan.x && build.tileY() == plan.y && build.team != game().state.rules.waveTeam)){
                teamData.plans.add(plan);
            }
        }
    }
}
