package mindustry.campaign.shared.runtime;

import arc.struct.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.defense.turrets.*;

import static mindustry.Vars.*;

/** Collects a conservative, serializable summary from a live action world. */
public final class SectorSummaryCollector{
    private SectorSummaryCollector(){}

    public static SectorSummary collect(){
        return collectStrategic();
    }

    /** Collects the bounded Shared Campaign strategic/runtime view used by state updates and transaction RPCs. */
    public static SectorSummary collectStrategic(){
        SectorSummary summary = new SectorSummary();
        summary.wave = mindustry.Vars.game().state.wave;
        summary.winWave = mindustry.Vars.game().state.rules.winWave;
        summary.waves = mindustry.Vars.game().state.rules.waves;
        summary.attackMode = mindustry.Vars.game().state.rules.attackMode;
        summary.coreCount = mindustry.Vars.game().state.rules.defaultTeam.cores().size;
        summary.unitCount = mindustry.Vars.game().state.rules.defaultTeam.data().units.size;
        summary.enemyCount = mindustry.Vars.game().state.enemies;
        summary.sampledAtTick = (long)mindustry.Vars.game().state.tick;
        summary.phase = mindustry.Vars.game().state.rules.objectives.any() ? "objectives" : mindustry.Vars.game().state.rules.attackMode ? "attack" : mindustry.Vars.game().state.rules.waves ? "defense" : "building";

        var core = mindustry.Vars.game().state.rules.defaultTeam.core();
        if(core != null){
            summary.storageCapacity = core.storageCapacity;
            summary.coreType = core.block.name;
            for(Item item : content.items()){
                int amount = mindustry.Vars.game().state.rules.defaultTeam.items().get(item);
                if(amount != 0) summary.items.put(item.name, amount);
            }
        }

        if(mindustry.Vars.game().state.rules.sector != null){
            var sector = mindustry.Vars.game().state.rules.sector;
            var info = sector.info();
            summary.planetName = sector.planet.name;
            summary.destinationSector = sectorKey(info.destination);
            summary.legacyLaunchPads = sector.planet.campaignRules.legacyLaunchPads;
            summary.attacked = sector.isAttacked();
            summary.hasSpawns = info.hasSpawns;
            summary.displayName = info.name == null ? "" : info.name;
            summary.icon = info.icon == null ? "" : info.icon;
            summary.contentIcon = contentIdentity(info.contentIcon);
            summary.resources.clear();
            for(UnlockableContent resource : info.resources) if(resource != null) summary.resources.add(contentIdentity(resource));
            summary.hasEnemyBase = sector.hasEnemyBase();
            summary.threat = sector.threat();
            summary.minutesCaptured = info.minutesCaptured;
            info.production.each((item, stat) -> { if(Math.abs(stat.mean) > 0.0001f) summary.productionPerSecond.put(item.name, stat.mean); });
            info.export.each((item, stat) -> { if(stat.mean > 0.0001f) summary.exportPerSecond.put(item.name, stat.mean); });
            info.imports.each((item, stat) -> { if(stat.mean > 0.0001f) summary.importPerSecond.put(item.name, stat.mean); });
        }

        float produced = 0f, consumed = 0f;
        ObjectSet<PowerGraph> graphs = new ObjectSet<>();
        for(Building build : mindustry.Vars.game().state.rules.defaultTeam.data().buildings){
            if(build.power != null && build.power.graph != null) graphs.add(build.power.graph);
        }
        for(PowerGraph graph : graphs){
            produced += graph.getPowerProduced();
            consumed += graph.getPowerNeeded();
        }
        summary.powerProduced = produced;
        summary.powerConsumed = consumed;
        calculateDefenseProfile(summary);

        return summary;
    }

    private static String contentIdentity(@Nullable UnlockableContent value){
        return value == null ? "" : value.getContentType().name() + ":" + value.name;
    }

    private static String sectorKey(Sector sector){
        if(sector == null || sector.planet == null) return "";
        String sectorId = sector.preset == null ? Integer.toString(sector.id) : sector.preset.name;
        return SharedCampaignState.sectorKey(sector.planet.name, sectorId);
    }

    /** Aggregate vanilla defensive durability/firepower; domain-specific Y combat models are intentionally excluded. */
    private static void calculateDefenseProfile(SectorSummary summary){
        final float[] aggregate = {0f};
        mindustry.Vars.game().state.rules.defaultTeam.data().buildings.each(build -> {
            float durability = build.maxHealth + build.block.armor * 20f;
            float strategic = build.block.hasPower ? 1.1f : 1f;
            if(build.block.group == mindustry.world.meta.BlockGroup.walls) strategic *= 1.5f;
            if(build.block instanceof Turret) strategic *= 2.5f;
            aggregate[0] += durability * strategic;
        });
        mindustry.Vars.game().state.rules.defaultTeam.data().units.each(unit ->
            aggregate[0] += unit.maxHealth + Math.max(0f, unit.type.dpsEstimate) * 60f);
        summary.defenseScore = aggregate[0];
    }
}
