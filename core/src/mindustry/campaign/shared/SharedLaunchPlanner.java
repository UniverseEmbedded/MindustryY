package mindustry.campaign.shared;

import arc.struct.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.type.*;
import mindustry.game.*;
import mindustry.world.blocks.storage.*;

import static mindustry.Vars.*;

/** Pure shared-campaign launch validation/planning; no durable inventory is mutated here. */
public final class SharedLaunchPlanner{
    private SharedLaunchPlanner(){}

    public record PreparedLaunch(RuntimePayloads.LaunchPlan plan, ObjectMap<String, Integer> costs,
                                 String sourceActionId, boolean firstLanding){}

    public static PreparedLaunch prepare(SharedCampaignState state, String planetName, String sectorName, RuntimePayloads.LaunchPlan requested){
        Sector destination = SharedCampaignProgress.findSector(planetName, sectorName);
        if(destination == null) throw new IllegalArgumentException("Unknown campaign sector: " + planetName + "/" + sectorName);
        RuntimePayloads.LaunchPlan launch = requested == null ? RuntimePayloads.LaunchPlan.empty() : requested;
        Seq<SectorState> candidates = new Seq<>();
        if(!launch.originSector().isBlank()){
            String key = SharedCampaignSectors.canonicalKey(launch.originSector());
            SectorState selected = state.sectors.get(key);
            if(selected == null) throw new IllegalArgumentException("Unknown launch origin: " + launch.originSector());
            candidates.add(selected);
        }else candidates.addAll(SharedCampaignProgress.launchSources(state, destination));

        if(candidates.isEmpty()){
            boolean hasBase = false;
            for(SectorState sector : state.sectors.values()) if(planetName.equals(sector.planetName) && sector.hasBase && !sector.attacked){ hasBase = true; break; }
            boolean firstLanding = !hasBase && (destination.id == destination.planet.startSector || destination.preset != null);
            if(!firstLanding) throw new IllegalStateException("No eligible launch origin for " + planetName + "/" + sectorName);
            return build(state, destination, null, launch, true);
        }

        RuntimeException last = null;
        for(SectorState source : candidates){
            try{
                if(!SharedCampaignProgress.launchSourceEligible(source, destination)) continue;
                return build(state, destination, source, launch, false);
            }catch(RuntimeException error){
                last = error;
                if(!launch.originSector().isBlank()) break;
            }
        }
        if(last != null) throw last;
        throw new IllegalStateException("No eligible launch origin for " + planetName + "/" + sectorName);
    }

    private static PreparedLaunch build(SharedCampaignState state, Sector destination, SectorState source,
                                        RuntimePayloads.LaunchPlan requested, boolean firstLanding){
        Planet planet = destination.planet;
        CoreBlock sourceCore = sourceCore(source, planet);
        Schematic loadout;
        if(!destination.allowLaunchSchematics()) loadout = planet.generator.defaultLoadout;
        else if(!requested.loadout().isBlank()) loadout = schematics.readBase64(requested.loadout());
        else{
            loadout = schematics.getDefaultLoadout(sourceCore);
            if(loadout == null) loadout = planet.generator.defaultLoadout;
        }
        if(loadout == null || !loadout.hasCore()) throw new IllegalStateException("No valid launch schematic is available");
        CoreBlock launchCore = loadout.findCore();
        if(source != null && launchCore.size > sourceCore.size) throw new IllegalStateException("Launch core is larger than the origin core");
        for(Schematic.Stile tile : loadout.tiles){
            if(tile.block == null || !tile.block.supportsEnv(planet.defaultEnv)) throw new IllegalStateException("Launch schematic contains unavailable block: " + (tile.block == null ? "null" : tile.block.name));
        }

        ObjectMap<String, Integer> resources = new ObjectMap<>();
        if(!destination.allowLaunchLoadout() && destination.preset != null){
            for(ItemStack stack : destination.preset.generator.map.rules().loadout){
                if(stack.item != null && !stack.item.hidden && stack.amount > 0) resources.put(stack.item.name, stack.amount);
            }
        }else resources.putAll(requested.resources());

        int capacity = (int)(planet.launchCapacityMultiplier * launchCore.itemCapacity);
        for(ObjectMap.Entry<String, Integer> entry : resources){
            Item item = content.item(entry.key);
            if(item == null || item.hidden) throw new IllegalArgumentException("Invalid launch resource: " + entry.key);
            if(entry.value == null || entry.value < 0 || entry.value > capacity) throw new IllegalArgumentException("Launch resource exceeds capacity for " + entry.key + ": " + entry.value + "/" + capacity);
        }

        ObjectMap<String, Integer> costs = new ObjectMap<>();
        if(source != null){
            for(ItemStack stack : loadout.requirements()) if(stack.amount > 0) costs.put(stack.item.name, Math.addExact(costs.get(stack.item.name, 0), stack.amount));
            for(ObjectMap.Entry<String, Integer> entry : resources) if(entry.value > 0) costs.put(entry.key, Math.addExact(costs.get(entry.key, 0), entry.value));
            for(ObjectMap.Entry<String, Integer> entry : costs){
                int available = source.items.get(entry.key, 0);
                if(available < entry.value) throw new IllegalStateException("Origin " + source.planetName + "/" + source.sectorName + " lacks " + entry.key + " (" + available + "/" + entry.value + ")");
            }
        }else if(!requested.resources().isEmpty() || !requested.loadout().isBlank() || !requested.originSector().isBlank()){
            throw new IllegalStateException("A free first landing must use the planet's authoritative default loadout and cannot specify an origin or extra resources");
        }

        String originKey = source == null ? "" : SharedCampaignSectors.sectorKey(source.planetName, source.sectorName);
        String sourceActionId = "";
        if(!originKey.isBlank()) for(ActionState action : state.actions.values()){
            if(action.status.isLive() && SharedCampaignSectors.sectorKey(action.planetName, action.sectorName).equals(originKey)){ sourceActionId = action.actionId; break; }
        }
        RuntimePayloads.LaunchPlan normalized = new RuntimePayloads.LaunchPlan(originKey, schematics.writeBase64(loadout), resources);
        return new PreparedLaunch(normalized, costs, sourceActionId, firstLanding);
    }

    private static CoreBlock sourceCore(SectorState source, Planet planet){
        if(source != null && source.summary != null && source.summary.coreType != null && !source.summary.coreType.isBlank()){
            var block = content.block(source.summary.coreType);
            if(block instanceof CoreBlock core) return core;
        }
        if(planet.defaultCore instanceof CoreBlock core) return core;
        throw new IllegalStateException("Planet has no campaign core: " + planet.name);
    }
}
