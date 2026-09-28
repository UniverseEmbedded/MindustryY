package mindustry.campaign.shared.api;

import arc.struct.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;

import java.nio.charset.*;
import java.security.*;
import java.util.*;

/**
 * Deterministic, versioned registry describing how each planet participates in a shared campaign.
 *
 * <p>The registry deliberately stores policy identity and compatibility separately from runtime callbacks. A campaign
 * therefore refuses to continue when an installed Mod silently changes strategic semantics, while still allowing
 * ordinary data-schema migrations through the shared-campaign extension API.</p>
 */
public class SharedCampaignPlanetRegistry{
    private final ObjectMap<String, ObjectMap<String, PlanetPolicy>> byPlanet = new ObjectMap<>();
    private final ObjectMap<String, PlanetRuntime> runtimes = new ObjectMap<>();
    private final ObjectMap<String, String> defaults = new ObjectMap<>();
    private boolean sealed;

    public synchronized void register(PlanetPolicy policy){
        register(policy, PlanetRuntime.none);
    }

    public synchronized void register(PlanetPolicy policy, PlanetRuntime runtime){
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(runtime, "runtime");
        if(sealed) throw new IllegalStateException("Shared campaign planet registry is sealed");
        validate(policy);
        if(runtime == PlanetRuntime.none && requiresRuntime(policy)){
            throw new IllegalArgumentException("Policy " + policy.id() + " declares custom or strategic-only semantics but has no PlanetRuntime");
        }
        ObjectMap<String, PlanetPolicy> policies = byPlanet.get(policy.planetName(), ObjectMap::new);
        if(policies.containsKey(policy.id())) throw new IllegalArgumentException("Duplicate planet policy " + policy.id());
        policies.put(policy.id(), policy);
        runtimes.put(policy.id(), runtime);
        if(policy.defaultPolicy()){
            String previous = defaults.put(policy.planetName(), policy.id());
            if(previous != null){
                defaults.put(policy.planetName(), previous);
                policies.remove(policy.id());
                runtimes.remove(policy.id());
                throw new IllegalArgumentException("Planet " + policy.planetName() + " already has a default policy " + previous);
            }
        }
    }

    public synchronized Seq<PlanetPolicy> policies(String planetName){
        ObjectMap<String, PlanetPolicy> policies = byPlanet.get(clean(planetName));
        if(policies == null) return new Seq<>();
        Seq<PlanetPolicy> result = policies.values().toSeq();
        result.sort(Comparator.comparing(PlanetPolicy::id));
        return result;
    }

    public synchronized PlanetPolicy resolve(String planetName, String requestedId){
        String planet = clean(planetName);
        ObjectMap<String, PlanetPolicy> policies = byPlanet.get(planet);
        if(policies == null || policies.isEmpty()) return unsupported(planet);
        String id = clean(requestedId);
        if(id.isBlank()) id = defaults.get(planet, "");
        PlanetPolicy policy = policies.get(id);
        if(policy == null) throw new IllegalArgumentException("Unknown shared campaign policy '" + id + "' for planet " + planet);
        return policy;
    }

    public synchronized PlanetPolicy resolve(SharedCampaignState state, String planetName){
        Objects.requireNonNull(state, "state");
        PlanetPolicyState persisted = state.planetPolicies.get(clean(planetName));
        if(persisted == null) throw new IllegalStateException("Campaign does not contain a policy for planet " + planetName);
        PlanetPolicy policy = resolve(planetName, persisted.policyId);
        validateCompatibility(persisted, policy);
        return policy;
    }

    /** Installs an immutable identity snapshot for every installed landable planet policy. */
    public synchronized void installInto(SharedCampaignState state, ObjectMap<String, String> overrides){
        Objects.requireNonNull(state, "state");
        state.planetPolicies.clear();
        Seq<String> planets = byPlanet.keys().toSeq().sort();
        for(String planet : planets){
            String override = overrides == null ? "" : overrides.get(planet, "");
            PlanetPolicy policy = resolve(planet, override);
            PlanetPolicyState stored = new PlanetPolicyState();
            stored.planetName = planet;
            stored.policyId = policy.id();
            stored.policyVersion = policy.version();
            stored.compatibilityId = policy.compatibilityId();
            stored.mode = policy.mode().name();
            stored.recommendedActiveActions = policy.recommendedActiveActions();
            stored.researchSharing = policy.researchSharing().name();
            stored.resourceOwnership = policy.resourceOwnership().name();
            stored.failureRule = policy.failureRule().name();
            stored.invasionRule = policy.invasionRule().name();
            state.planetPolicies.put(planet, stored);
        }
        if(!state.planetPolicies.containsKey(state.primaryPlanetName)){
            PlanetPolicy policy = unsupported(state.primaryPlanetName);
            PlanetPolicyState stored = new PlanetPolicyState();
            stored.planetName = state.primaryPlanetName;
            stored.policyId = policy.id();
            stored.policyVersion = policy.version();
            stored.compatibilityId = policy.compatibilityId();
            stored.mode = policy.mode().name();
            stored.recommendedActiveActions = policy.recommendedActiveActions();
            stored.researchSharing = policy.researchSharing().name();
            stored.resourceOwnership = policy.resourceOwnership().name();
            stored.failureRule = policy.failureRule().name();
            stored.invasionRule = policy.invasionRule().name();
            state.planetPolicies.put(stored.planetName, stored);
        }
    }

    public synchronized void validateCampaign(SharedCampaignState state){
        Objects.requireNonNull(state, "state");
        for(ObjectMap.Entry<String, PlanetPolicyState> entry : state.planetPolicies){
            PlanetPolicy policy = resolve(entry.key, entry.value.policyId);
            validateCompatibility(entry.value, policy);
        }
        PlanetPolicy primary = resolve(state, state.primaryPlanetName);
        if(primary.mode() == MultiplayerMode.unsupported) throw new IllegalStateException("Primary planet does not support shared campaigns: " + state.primaryPlanetName);
        if(!primary.supportsMultipleActions() && (state.multiFrontEnabled || state.maxActiveActions != 1)){
            throw new IllegalStateException("Campaign enables multiple actions for a single-action planet policy");
        }
    }


    /** Applies generic policy rules and the Mod-owned runtime validator before an action is allocated. */
    public synchronized void validateActionStart(SharedCampaignState state, String planetName, String sectorName, String missionId){
        PlanetPolicy policy = resolve(state, planetName);
        if(policy.mode() == MultiplayerMode.unsupported) throw new IllegalStateException("Planet does not support shared campaigns: " + planetName);
        if(policy.mode() == MultiplayerMode.strategicOnly) throw new IllegalStateException("Planet policy allows strategic settlement only: " + policy.id());
        if(policy.mode() == MultiplayerMode.missionInstances && (missionId == null || missionId.isBlank())){
            throw new IllegalStateException("Planet requires a registered mission instance: " + planetName + "/" + sectorName);
        }
        int occupied = 0;
        for(ActionState action : state.actions.values()){
            if(action.status.isLive() || action.status == ActionStatus.suspended || action.status == ActionStatus.preparing) occupied++;
        }
        if((!state.multiFrontEnabled || !policy.supportsMultipleActions() || policy.mode() == MultiplayerMode.singleLine) && occupied > 0){
            throw new IllegalStateException("Planet policy permits only one active or suspended action");
        }
        PlanetRuntime runtime = runtimes.get(policy.id(), PlanetRuntime.none);
        runtime.validateActionStart(new ActionStartContext(mindustry.campaign.shared.io.SharedCampaignStateCopy.copy(state), policy, clean(sectorName), clean(missionId)));
    }

    /** Validates a campaign-wide concurrency change against the selected primary policy and its runtime. */
    public synchronized void validateSettings(SharedCampaignState state, boolean multiFront, int maxActiveActions){
        PlanetPolicy policy = resolve(state, state.primaryPlanetName);
        if(!policy.supportsMultipleActions() && (multiFront || maxActiveActions != 1)){
            throw new IllegalArgumentException("Primary planet policy permits only one action");
        }
        runtimes.get(policy.id(), PlanetRuntime.none).validateSettings(new SettingsContext(mindustry.campaign.shared.io.SharedCampaignStateCopy.copy(state), policy, multiFront, maxActiveActions));
    }

    /** Executes a deterministic Mod policy hook inside the coordinator's authoritative transaction. */
    public synchronized void beforeActionCommit(SharedCampaignState state, ActionState action){
        PlanetPolicy policy = resolve(state, action.planetName);
        runtimes.get(policy.id(), PlanetRuntime.none).beforeActionCommit(new ActionMutationContext(state, policy, action));
    }

    public synchronized void afterActionCommit(SharedCampaignState state, ActionState action){
        PlanetPolicy policy = resolve(state, action.planetName);
        runtimes.get(policy.id(), PlanetRuntime.none).afterActionCommit(new ActionMutationContext(state, policy, action));
    }

    /** Compatibility hook: invokes every persisted planet runtime. New research paths should use the exact-planet overload. */
    public synchronized void beforeResearchCommit(SharedCampaignState state, String contentName){
        for(PlanetPolicyState persisted : state.planetPolicies.values()){
            beforeResearchCommit(state, persisted.planetName, contentName);
        }
    }

    /** Invokes only the policy whose technology-tree/resource scope is actually being researched. */
    public synchronized void beforeResearchCommit(SharedCampaignState state, String planetName, String contentName){
        PlanetPolicy policy = resolve(state, planetName);
        runtimes.get(policy.id(), PlanetRuntime.none).beforeResearchCommit(new ResearchMutationContext(state, policy, contentName));
    }

    /** Compatibility hook: invokes every persisted planet runtime. New research paths should use the exact-planet overload. */
    public synchronized void afterResearchCommit(SharedCampaignState state, String contentName, boolean completed){
        for(PlanetPolicyState persisted : state.planetPolicies.values()){
            afterResearchCommit(state, persisted.planetName, contentName, completed);
        }
    }

    /** Invokes only the policy whose technology-tree/resource scope is actually being researched. */
    public synchronized void afterResearchCommit(SharedCampaignState state, String planetName, String contentName, boolean completed){
        PlanetPolicy policy = resolve(state, planetName);
        runtimes.get(policy.id(), PlanetRuntime.none).afterResearchCommit(new ResearchMutationContext(state, policy, contentName, completed));
    }

    public synchronized void settleSuspendedSector(SharedCampaignState state, SectorState sector, long elapsedTicks){
        PlanetPolicy policy = resolve(state, sector.planetName);
        runtimes.get(policy.id(), PlanetRuntime.none).settleSuspendedSector(new SectorSettlementContext(state, policy, sector, elapsedTicks));
    }

    public synchronized InvasionDisposition invasionDisposition(SharedCampaignState state, SectorState sector, int targetWave){
        PlanetPolicy policy = resolve(state, sector.planetName);
        InvasionDisposition generic = switch(policy.invasionRule()){
            case disabled -> InvasionDisposition.ignore;
            case continuous -> InvasionDisposition.strategicSettlement;
            case onlineProtected, custom -> InvasionDisposition.requireAction;
        };
        return runtimes.get(policy.id(), PlanetRuntime.none).onInvasion(new InvasionContext(state, policy, sector, targetWave, generic));
    }

    public synchronized void seal(){ sealed = true; }
    public synchronized boolean sealed(){ return sealed; }

    private static void validateCompatibility(PlanetPolicyState persisted, PlanetPolicy installed){
        if(persisted.policyVersion > installed.version()){
            throw new IllegalStateException("Campaign policy " + persisted.policyId + " requires newer version " + persisted.policyVersion);
        }
        if(!Objects.equals(persisted.compatibilityId, installed.compatibilityId())){
            throw new IllegalStateException("Campaign policy compatibility mismatch for " + persisted.planetName + ": campaign="
                + persisted.compatibilityId + ", installed=" + installed.compatibilityId());
        }
        if(!Objects.equals(persisted.mode, installed.mode().name())) throw new IllegalStateException("Campaign policy mode changed for " + persisted.planetName);
        if(!Objects.equals(persisted.researchSharing, installed.researchSharing().name())) throw new IllegalStateException("Campaign research policy changed for " + persisted.planetName);
        if(!Objects.equals(persisted.resourceOwnership, installed.resourceOwnership().name())) throw new IllegalStateException("Campaign resource policy changed for " + persisted.planetName);
        if(!Objects.equals(persisted.failureRule, installed.failureRule().name())) throw new IllegalStateException("Campaign failure policy changed for " + persisted.planetName);
        if(!Objects.equals(persisted.invasionRule, installed.invasionRule().name())) throw new IllegalStateException("Campaign invasion policy changed for " + persisted.planetName);
    }


    private static boolean requiresRuntime(PlanetPolicy policy){
        return policy.mode() == MultiplayerMode.custom || policy.mode() == MultiplayerMode.strategicOnly
            || policy.researchSharing() == ResearchSharing.custom
            || policy.resourceOwnership() == ResourceOwnership.custom
            || policy.failureRule() == FailureRule.custom
            || policy.invasionRule() == InvasionRule.custom;
    }

    private static void validate(PlanetPolicy policy){
        if(clean(policy.ownerId()).isBlank() || !policy.ownerId().contains(":")) throw new IllegalArgumentException("Planet policy owner ID must be namespaced");
        if(clean(policy.id()).isBlank() || !policy.id().contains(":")) throw new IllegalArgumentException("Planet policy ID must be namespaced");
        if(clean(policy.planetName()).isBlank()) throw new IllegalArgumentException("Planet policy planet name is required");
        if(policy.version() < 1) throw new IllegalArgumentException("Planet policy version must be positive");
        if(clean(policy.compatibilityId()).isBlank()) throw new IllegalArgumentException("Planet policy compatibility identity is required");
        if(policy.mode() == null) throw new IllegalArgumentException("Planet policy mode is required");
        // Shared and perPlanet now both have authoritative resource-scope implementations. Custom research scope
        // still has no callback capable of supplying an eligibility/resource view, so accepting it would advertise a
        // semantic contract the coordinator and UI cannot honor.
        if(policy.researchSharing() == ResearchSharing.custom) throw new IllegalArgumentException("Research sharing mode is not implemented by Shared Campaign: " + policy.researchSharing());
        if(policy.resourceOwnership() != ResourceOwnership.perSector) throw new IllegalArgumentException("Resource ownership mode is not implemented by Shared Campaign: " + policy.resourceOwnership());
        if(policy.failureRule() == FailureRule.campaign || policy.failureRule() == FailureRule.custom) throw new IllegalArgumentException("Failure rule is not implemented by Shared Campaign: " + policy.failureRule());
        if(policy.recommendedActiveActions() < 1 || policy.recommendedActiveActions() > 32) throw new IllegalArgumentException("Recommended active action count is out of range");
        if(policy.mode() == MultiplayerMode.singleLine || policy.mode() == MultiplayerMode.strategicOnly || policy.mode() == MultiplayerMode.unsupported){
            if(policy.recommendedActiveActions() != 1) throw new IllegalArgumentException("Single-action policy must recommend one active action");
        }
    }

    public static String fingerprint(String ownerId, String id, int version, String compatibilityId, MultiplayerMode mode, int recommendedActions){
        return fingerprint(ownerId, id, version, compatibilityId, mode, recommendedActions,
            ResearchSharing.shared, ResourceOwnership.perSector, FailureRule.sectorOnly, InvasionRule.onlineProtected);
    }

    public static String fingerprint(String ownerId, String id, int version, String compatibilityId, MultiplayerMode mode, int recommendedActions,
                                     ResearchSharing research, ResourceOwnership resources, FailureRule failure, InvasionRule invasion){
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = (ownerId + "\n" + id + "\n" + version + "\n" + compatibilityId + "\n" + mode + "\n" + recommendedActions
                + "\n" + research + "\n" + resources + "\n" + failure + "\n" + invasion).getBytes(StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder();
            for(byte value : digest.digest(bytes)) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        }catch(NoSuchAlgorithmException error){ throw new AssertionError(error); }
    }

    private static PlanetPolicy unsupported(String planet){
        return new PlanetPolicy("mindustry-y:core", "mindustry-y:unsupported-" + (planet.isBlank() ? "unknown" : planet), planet,
            1, "mindustry-y:unsupported-v1", MultiplayerMode.unsupported, 1, true, false,
            ResearchSharing.shared, ResourceOwnership.perSector, FailureRule.sectorOnly, InvasionRule.disabled);
    }

    private static String clean(String value){ return value == null ? "" : value.trim(); }

    public interface PlanetRuntime{
        PlanetRuntime none = new PlanetRuntime(){};
        default void validateActionStart(ActionStartContext context){}
        default void validateSettings(SettingsContext context){}
        default void beforeActionCommit(ActionMutationContext context){}
        default void afterActionCommit(ActionMutationContext context){}
        default void beforeResearchCommit(ResearchMutationContext context){}
        default void afterResearchCommit(ResearchMutationContext context){}
        default void settleSuspendedSector(SectorSettlementContext context){}
        default InvasionDisposition onInvasion(InvasionContext context){ return context.defaultDisposition(); }
    }

    public record ActionStartContext(SharedCampaignState campaign, PlanetPolicy policy, String sectorName, String missionId){}
    public record SettingsContext(SharedCampaignState campaign, PlanetPolicy policy, boolean multiFrontEnabled, int maxActiveActions){}
    /** Mutable campaign/action references are valid only for the current authoritative transaction. */
    public record ActionMutationContext(SharedCampaignState campaign, PlanetPolicy policy, ActionState action){}
    public record ResearchMutationContext(SharedCampaignState campaign, PlanetPolicy policy, String contentName, boolean completed){
        public ResearchMutationContext(SharedCampaignState campaign, PlanetPolicy policy, String contentName){ this(campaign, policy, contentName, false); }
    }
    public record SectorSettlementContext(SharedCampaignState campaign, PlanetPolicy policy, SectorState sector, long elapsedTicks){}
    public record InvasionContext(SharedCampaignState campaign, PlanetPolicy policy, SectorState sector, int targetWave, InvasionDisposition defaultDisposition){}
    public enum InvasionDisposition{ignore, requireAction, strategicSettlement}

    public enum MultiplayerMode{singleLine, limitedMultiFront, missionInstances, strategicOnly, unsupported, custom}
    public enum ResearchSharing{shared, perPlanet, custom}
    public enum ResourceOwnership{perSector, sharedPool, custom}
    public enum FailureRule{sectorOnly, missionAttempt, campaign, custom}
    public enum InvasionRule{onlineProtected, continuous, disabled, custom}

    public record PlanetPolicy(
        String ownerId,
        String id,
        String planetName,
        int version,
        String compatibilityId,
        MultiplayerMode mode,
        int recommendedActiveActions,
        boolean defaultPolicy,
        boolean supportsMultipleActions,
        ResearchSharing researchSharing,
        ResourceOwnership resourceOwnership,
        FailureRule failureRule,
        InvasionRule invasionRule
    ){
        public PlanetPolicy{
            ownerId = clean(ownerId); id = clean(id); planetName = clean(planetName); compatibilityId = clean(compatibilityId);
            researchSharing = researchSharing == null ? ResearchSharing.shared : researchSharing;
            resourceOwnership = resourceOwnership == null ? ResourceOwnership.perSector : resourceOwnership;
            failureRule = failureRule == null ? FailureRule.sectorOnly : failureRule;
            invasionRule = invasionRule == null ? InvasionRule.onlineProtected : invasionRule;
        }
    }
}
