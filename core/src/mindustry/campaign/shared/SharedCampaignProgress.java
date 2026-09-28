package mindustry.campaign.shared;

import arc.*;
import arc.struct.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.content.*;
import mindustry.game.Objectives.*;
import mindustry.type.*;

/** Shared-state evaluation of vanilla technology and sector prerequisites. */
public final class SharedCampaignProgress{
    private SharedCampaignProgress(){}

    /** Stable identity within one planet: preset content name, otherwise the numeric planetary sector ID. */
    public static String sectorId(Sector sector){ return SharedCampaignSectors.sectorId(sector); }

    /** Globally stable sector identity used by authoritative maps, transport and research transactions. */
    public static String sectorKey(String planetName, String sectorId){ return SharedCampaignSectors.sectorKey(planetName, sectorId); }
    public static String sectorKey(Sector sector){ return SharedCampaignSectors.sectorKey(sector); }
    public static String planetFromKey(String sectorKey){ return SharedCampaignSectors.planetFromKey(sectorKey); }
    public static String sectorIdFromKey(String sectorKey){ return SharedCampaignSectors.sectorIdFromKey(sectorKey); }
    public static Sector findSectorKey(String sectorKey){ return SharedCampaignSectors.findSectorKey(sectorKey); }
    public static Sector findSector(String planetName, String sectorId){ return SharedCampaignSectors.findSector(planetName, sectorId); }

    /** True only for an explicit strategic Research transaction; discovery/auto-unlock does not satisfy this query. */
    public static boolean isExplicitlyResearched(SharedCampaignState state, String contentName){
        return state != null && contentName != null && state.researched.contains(contentName);
    }

    /** Durable authoritative discovery/automatic unlock. */
    public static boolean isDiscovered(SharedCampaignState state, String contentName){
        return state != null && contentName != null && state.discovered.contains(contentName);
    }

    /** Records an authority-side discovery/automatic unlock. */
    public static boolean recordDiscovery(SharedCampaignState state, String contentName){
        return state != null && contentName != null && !contentName.isBlank() && state.discovered.add(contentName);
    }

    /** Literal production history is deliberately distinct from discovery/unlock authority. */
    public static boolean isActuallyProduced(SharedCampaignState state, String contentName){
        return state != null && contentName != null && state.producedActual.contains(contentName);
    }

    public static boolean recordActualProduction(SharedCampaignState state, String contentName){
        return state != null && contentName != null && !contentName.isBlank() && state.producedActual.add(contentName);
    }

    /** Shared Campaign effective unlock: always-unlocked, explicit research, or authoritative discovery. */
    public static boolean isEffectivelyUnlocked(SharedCampaignState state, mindustry.ctype.UnlockableContent content){
        return state != null && content != null && (content.alwaysUnlocked || isExplicitlyResearched(state, content.name) || isDiscovered(state, content.name));
    }

    /** Compatibility name retained for callers that model vanilla Research/Produce objectives. */
    public static boolean researchUnlocked(SharedCampaignState state, mindustry.ctype.UnlockableContent content){
        return isEffectivelyUnlocked(state, content);
    }

    /** Whether a tech node's parent and all supported objectives are satisfied by authoritative shared state. */
    public static boolean researchSelectable(SharedCampaignState state, TechTree.TechNode node){
        if(state == null || node == null || node.content == null) return false;
        if(researchUnlocked(state, node.content)) return true;
        if(node.parent != null && !researchUnlocked(state, node.parent.content)) return false;
        for(Objective objective : node.objectives){
            SharedObjectiveEvaluator.Result result = SharedObjectiveEvaluator.evaluate(state, objective);
            if(!result.supported() || !result.complete()) return false;
        }
        return true;
    }



    /**
     * Applies vanilla {@code Control.checkAutoUnlocks()} semantics to authoritative Shared Campaign state.
     *
     * <p>Vanilla auto-unlocks zero-requirement nodes when their parent is unlocked and every objective is complete.
     * Shared Campaign cannot rely on one graphical client's process-wide profile, so the coordinator records those
     * non-research unlocks in the legacy {@code produced} set (the durable non-research half of effective unlocks).
     * The loop runs to a fixpoint because one auto-unlocked node may immediately enable another zero-cost child.</p>
     *
     * @return content names newly auto-unlocked during this pass.
     */
    public static Seq<String> applyAutomaticUnlocks(SharedCampaignState state){
        Seq<String> added = new Seq<>();
        if(state == null) return added;

        boolean changed;
        do{
            changed = false;
            for(TechTree.TechNode node : TechTree.all){
                if(node == null || node.content == null || node.requirements == null || node.requirements.length != 0) continue;
                if(researchUnlocked(state, node.content)) continue;
                if(node.parent != null && !researchUnlocked(state, node.parent.content)) continue;

                boolean objectivesReady = true;
                for(Objective objective : node.objectives){
                    SharedObjectiveEvaluator.Result result = SharedObjectiveEvaluator.evaluate(state, objective);
                    if(!result.supported() || !result.complete()){
                        objectivesReady = false;
                        break;
                    }
                }
                if(!objectivesReady) continue;

                if(recordDiscovery(state, node.content.name)){
                    added.add(node.content.name);
                    changed = true;
                }
            }
        }while(changed);

        return added;
    }

    /** Authoritative partial contribution for one item requirement of a tech node. */
    public static int researchContribution(SharedCampaignState state, TechTree.TechNode node, Item item){
        if(state == null || node == null || node.content == null || item == null) return 0;
        ResearchState progress = state.research.get(node.content.name);
        return progress == null ? 0 : Math.max(0, progress.contributed.get(item.name, 0));
    }

    /** Vanilla-equivalent research resource eligibility: owned bases count unless attacked and not being played. */
    public static boolean researchSectorEligible(SharedCampaignState state, SectorState sector){
        if(state == null || sector == null || !sector.hasBase) return false;
        if(!sector.attacked) return true;
        String key = sectorKey(sector.planetName, sector.sectorName);
        return state.actions.values().toSeq().contains(action -> action.status == ActionStatus.running && key.equals(sectorKey(action.planetName, action.sectorName)));
    }

    /**
     * Planets whose strategic bases contribute to one technology-tree root. This mirrors vanilla ResearchDialog's
     * rootPlanets calculation instead of treating every planet in a Shared Campaign as one undifferentiated pool.
     */
    public static Seq<Planet> researchPlanets(TechTree.TechNode node){
        Seq<Planet> out = new Seq<>();
        if(node == null) return out;
        TechTree.TechNode root = node.rootNode == null ? node : node.rootNode;
        for(Planet planet : mindustry.Vars.content.planets()){
            if(planet.techTree == root || root.planet == planet || node.planet == planet) out.addUnique(planet);
        }
        if(out.isEmpty() && node.planet != null) out.add(node.planet);
        return out;
    }

    public static boolean researchPlanetContains(TechTree.TechNode node, String planetName){
        if(planetName == null || planetName.isBlank()) return false;
        return researchPlanets(node).contains(planet -> planetName.equals(planet.name));
    }

    /**
     * Resolves the persisted research-sharing policy for a planet. Missing legacy metadata fails toward the narrower
     * per-planet scope; a UI/resource query must never silently widen itself to the whole campaign.
     */
    private static mindustry.campaign.shared.api.SharedCampaignPlanetRegistry.ResearchSharing researchSharing(SharedCampaignState state, String planetName){
        if(state == null || planetName == null || planetName.isBlank()) return mindustry.campaign.shared.api.SharedCampaignPlanetRegistry.ResearchSharing.perPlanet;
        PlanetPolicyState policy = state.planetPolicies.get(planetName);
        if(policy == null || policy.researchSharing == null || policy.researchSharing.isBlank()) return mindustry.campaign.shared.api.SharedCampaignPlanetRegistry.ResearchSharing.perPlanet;
        try{
            return mindustry.campaign.shared.api.SharedCampaignPlanetRegistry.ResearchSharing.valueOf(policy.researchSharing);
        }catch(IllegalArgumentException invalid){
            throw new IllegalStateException("Invalid Shared Campaign research policy for " + planetName + ": " + policy.researchSharing, invalid);
        }
    }

    /** Exact planet identities whose sectors may fund this research request under the persisted policy. */
    public static ObjectSet<String> researchScopePlanets(SharedCampaignState state, TechTree.TechNode node, String requestedPlanet){
        ObjectSet<String> out = new ObjectSet<>();
        if(state == null || node == null) return out;

        String requested = requestedPlanet == null ? "" : requestedPlanet.trim();
        if(requested.isBlank()){
            Seq<Planet> candidates = researchPlanets(node);
            if(candidates.isEmpty()) return out;
            requested = candidates.first().name;
        }
        if(!researchPlanetContains(node, requested)) return out;

        var sharing = researchSharing(state, requested);
        if(sharing == mindustry.campaign.shared.api.SharedCampaignPlanetRegistry.ResearchSharing.custom){
            throw new IllegalStateException("Custom Shared Campaign research scope requires a policy-owned implementation: " + requested);
        }
        if(sharing == mindustry.campaign.shared.api.SharedCampaignPlanetRegistry.ResearchSharing.shared){
            // Legacy/explicit campaign-wide sharing. Use only planets that actually have durable strategic state; a
            // registered-but-unused planet must not manufacture an empty/phantom research source.
            for(SectorState sector : state.sectors.values()) if(sector.planetName != null && !sector.planetName.isBlank()) out.add(sector.planetName);
        }else{
            out.add(requested);
        }
        return out;
    }

    /**
     * Returns the member's current live Action from authoritative presence, not the durable historical lastActionId.
     * Action participants are replaced from runtime heartbeats; lastActionId is only a reconnect/history hint and may
     * legitimately remain set after the member has left that Action.
     */
    public static ActionState currentMemberAction(SharedCampaignState state, String memberId, boolean includeSpectators){
        if(state == null || memberId == null || memberId.isBlank()) return null;
        ActionState best = null;
        int bestRank = -1;
        for(ActionState action : state.actions.values()){
            if(action == null || !action.status.isLive() || !action.participants.contains(memberId)) continue;
            if(!includeSpectators && action.spectators.contains(memberId)) continue;
            int rank = action.status == ActionStatus.running ? 3 : action.status == ActionStatus.starting ? 2 : 1;
            if(best == null || rank > bestRank || (rank == bestRank && action.updatedAt > best.updatedAt)){
                best = action;
                bestRank = rank;
            }
        }
        return best;
    }

    /** Current actively-played Action for research's vanilla "current sector consumed last" rule. */
    public static ActionState currentMemberPlayerAction(SharedCampaignState state, String memberId){
        ActionState action = currentMemberAction(state, memberId, false);
        return action != null && action.status == ActionStatus.running ? action : null;
    }

    /** Authoritative Shared sectors that may contribute to one exact technology-tree/planet request. */
    public static Seq<SectorState> researchEligibleSectors(SharedCampaignState state, TechTree.TechNode node, String requestedPlanet){
        Seq<SectorState> out = new Seq<>();
        if(state == null || node == null) return out;
        ObjectSet<String> planets = researchScopePlanets(state, node, requestedPlanet);
        for(SectorState sector : state.sectors.values()){
            if(planets.contains(sector.planetName) && researchSectorEligible(state, sector)) out.add(sector);
        }
        return out;
    }

    /** Compatibility overload: new product paths should pass the concrete planet selected by the user/request. */
    public static Seq<SectorState> researchEligibleSectors(SharedCampaignState state, TechTree.TechNode node){
        Seq<Planet> planets = researchPlanets(node);
        return researchEligibleSectors(state, node, planets.isEmpty() ? "" : planets.first().name);
    }

    /** Exact item totals that both Shared Research UI and coordinator debit planning are allowed to consume. */
    public static ItemSeq researchItems(SharedCampaignState state, TechTree.TechNode node, String requestedPlanet){
        ItemSeq out = new ItemSeq();
        if(state == null || node == null) return out;
        for(SectorState sector : researchEligibleSectors(state, node, requestedPlanet)){
            sector.items.each((name, amount) -> {
                Item item = mindustry.Vars.content.item(name);
                if(item != null && amount > 0) out.add(item, amount);
            });
        }
        return out;
    }

    public static ItemSeq researchItems(SharedCampaignState state, TechTree.TechNode node){
        Seq<Planet> planets = researchPlanets(node);
        return researchItems(state, node, planets.isEmpty() ? "" : planets.first().name);
    }

    /** Total strategic resources available to this exact technology-tree/planet request. */
    public static int researchAvailable(SharedCampaignState state, TechTree.TechNode node, String requestedPlanet, Item item){
        if(state == null || node == null || item == null) return 0;
        int total = 0;
        for(SectorState sector : researchEligibleSectors(state, node, requestedPlanet)){
            total += Math.max(0, sector.items.get(item.name, 0));
        }
        return total;
    }

    public static int researchAvailable(SharedCampaignState state, TechTree.TechNode node, Item item){
        Seq<Planet> planets = researchPlanets(node);
        return researchAvailable(state, node, planets.isEmpty() ? "" : planets.first().name, item);
    }

    /** Legacy campaign-wide helper retained for compatibility; new research product paths must use the TechNode scope. */
    public static int researchAvailable(SharedCampaignState state, Item item){
        if(state == null || item == null) return 0;
        int total = 0;
        for(SectorState sector : state.sectors.values()){
            if(researchSectorEligible(state, sector)) total += Math.max(0, sector.items.get(item.name, 0));
        }
        return total;
    }

    public static Availability missionAvailability(SharedCampaignState state, SectorPreset preset){
        if(preset == null) return new Availability(false, Seq.with(Core.bundle.get("sharedcampaign.prerequisite.missingpreset")));
        SectorState presetState = state.sectors.get(sectorKey(preset.sector));
        if(presetState != null && presetState.captured && presetState.hasBase) return new Availability(true, new Seq<>());
        TechTree.TechNode node = preset.techNode;
        if(node == null) return new Availability(preset.alwaysUnlocked, preset.alwaysUnlocked ? new Seq<>() : Seq.with(Core.bundle.get("sharedcampaign.prerequisite.notechnode")));
        Seq<String> missing = new Seq<>();
        for(var objective : node.objectives){
            SharedObjectiveEvaluator.Result evaluation = SharedObjectiveEvaluator.evaluate(state, objective);
            if(evaluation.complete()) continue;
            if(objective instanceof SectorComplete complete){
                missing.add(Core.bundle.format("sharedcampaign.prerequisite.complete", complete.preset.localizedName));
            }else if(objective instanceof OnSector on){
                missing.add(Core.bundle.format("sharedcampaign.prerequisite.base", on.preset.localizedName));
            }else if(objective instanceof Research research){
                missing.add(Core.bundle.format("sharedcampaign.prerequisite.research", research.content.localizedName));
            }else if(objective instanceof Produce produce){
                missing.add(Core.bundle.format("sharedcampaign.prerequisite.produce", produce.content.localizedName));
            }else{
                // OnPlanet and extension objectives use their own localized description. Unsupported objective kinds are
                // deliberately fail-closed instead of falling back to local single-player profile state.
                missing.add(objective.display());
            }
        }
        return new Availability(missing.isEmpty(), missing);
    }

    /** Whether the Shared authoritative state satisfies vanilla preset shielding. */
    public static boolean sectorShielded(SharedCampaignState state, Sector sector){
        if(state == null || sector == null || sector.preset == null || sector.preset.shieldSectors.isEmpty()) return false;
        return sector.preset.shieldSectors.contains(shield -> {
            SectorState value = state.sectors.get(sectorKey(shield));
            return value == null || !value.captured || !value.hasBase;
        });
    }

    /** Serpulo numbered-sector launchers require a Foundation-or-larger Core, matching SerpuloPlanetGenerator. */
    public static boolean numberedLaunchCoreEligible(SectorState source){
        if(source == null || source.summary == null || source.summary.coreType == null || source.summary.coreType.isBlank()) return false;
        var block = mindustry.Vars.content.block(source.summary.coreType);
        return block != null && block.size >= 4;
    }

    private static boolean usesSerpuloNumberedLaunchRule(Sector destination){
        return destination != null && destination.planet == Planets.serpulo && (destination.preset == null || !destination.preset.requireUnlock);
    }

    /** Vanilla-equivalent strategic launch-source eligibility for a destination. */
    public static boolean launchSourceEligible(SectorState source, Sector destination){
        if(source == null || destination == null || !source.hasBase || source.attacked || !destination.planet.name.equals(source.planetName)) return false;
        Sector sourceSector = findSector(source.planetName, source.sectorName);
        if(sourceSector == null) return false;
        if(usesSerpuloNumberedLaunchRule(destination)) return destination.near().contains(sourceSector) && numberedLaunchCoreEligible(source);
        if(destination.preset != null) return true;
        return destination.near().contains(sourceSector);
    }

    /** Shared-state equivalent of PlanetGenerator.findLaunchCandidate, without mutating local single-player saves. */
    public static Seq<SectorState> launchSources(SharedCampaignState state, Sector destination){
        Seq<SectorState> result = new Seq<>();
        if(state == null || destination == null) return result;
        for(SectorState source : state.sectors.values()){
            if(!destination.planet.name.equals(source.planetName) || !source.hasBase || source.attacked) continue;
            if(launchSourceEligible(source, destination)) result.add(source);
        }
        result.sort((a, b) -> {
            Sector as = findSector(a.planetName, a.sectorName), bs = findSector(b.planetName, b.sectorName);
            float ad = as == null ? Float.MAX_VALUE : as.tile.v.dst2(destination.tile.v);
            float bd = bs == null ? Float.MAX_VALUE : bs.tile.v.dst2(destination.tile.v);
            return Float.compare(ad, bd);
        });
        return result;
    }

    /**
     * Shared equivalent of vanilla PlanetDialog.canSelect() for strategic inspection. Enterability is deliberately
     * separate: a locked preset whose parent is known should still be selectable so the player can inspect its
     * missing objectives, even though {@link #sectorAvailability(SharedCampaignState, Sector)} remains false.
     */
    public static boolean sectorInspectable(SharedCampaignState state, Sector sector){
        if(state == null || sector == null) return false;
        SectorState existing = state.sectors.get(sectorKey(sector));
        if(existing != null || state.actions.values().toSeq().contains(action ->
            sector.planet.name.equals(action.planetName) && sectorId(sector).equals(action.sectorName))) return true;

        if(sector.id == sector.planet.startSector) return true;
        if(sector.preset != null && sector.preset.requireUnlock){
            TechTree.TechNode node = sector.preset.techNode;
            if(node == null || node.parent == null) return true;
            if(!researchUnlocked(state, node.parent.content)) return false;
            if(node.parent.content instanceof SectorPreset parentPreset){
                SectorState parent = state.sectors.get(sectorKey(parentPreset.sector));
                return parent != null && parent.hasBase;
            }
            return true;
        }
        return sectorAvailability(state, sector).available();
    }

    /** Whether a sector may be offered as a new shared-campaign action before launch resource validation. */
    public static Availability sectorAvailability(SharedCampaignState state, Sector sector){
        if(sector == null) return new Availability(false, Seq.with(Core.bundle.get("sharedcampaign.prerequisite.missingsector")));
        SectorState existing = state.sectors.get(sectorKey(sector));
        if(sectorShielded(state, sector)){
            Seq<String> missing = new Seq<>();
            for(Sector shield : sector.preset.shieldSectors){
                SectorState value = state.sectors.get(sectorKey(shield));
                if(value == null || !value.captured || !value.hasBase){
                    missing.add(Core.bundle.format("sharedcampaign.prerequisite.complete", shield.preset == null ? shield.name() : shield.preset.localizedName));
                }
            }
            return new Availability(false, missing);
        }
        // Existing fronts remain enterable for defense/recovery even if a rear link was later lost.
        if(existing != null && (existing.hasBase || existing.captured || !existing.saveRelativePath.isBlank())) return new Availability(true, new Seq<>());
        if(sector.preset != null){
            Availability preset = missionAvailability(state, sector.preset);
            if(preset.available()) return preset;
            //Non-Erekir presets may not use the same mission registry, but their vanilla technology objectives remain authoritative.
            return preset;
        }
        boolean anyBase = state.sectors.values().toSeq().contains(candidate -> sector.planet.name.equals(candidate.planetName) && candidate.hasBase);
        if(!anyBase && sector.id == sector.planet.startSector) return new Availability(true, new Seq<>());
        if(!sector.planet.allowLaunchToNumbered) return new Availability(false, Seq.with(Core.bundle.get("sharedcampaign.prerequisite.numbereddisabled")));
        return launchSources(state, sector).isEmpty() ? new Availability(false, Seq.with(Core.bundle.get("sharedcampaign.prerequisite.noadjacentbase"))) : new Availability(true, new Seq<>());
    }

    public record Availability(boolean available, Seq<String> missing){}
}
