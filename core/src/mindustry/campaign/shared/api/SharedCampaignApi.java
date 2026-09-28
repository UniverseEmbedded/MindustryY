package mindustry.campaign.shared.api;

import arc.struct.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;

import java.util.function.*;

/** Stable extension surface for shared campaigns. */
public interface SharedCampaignApi{
    int apiVersion = 4;

    /** Immutable copy of the currently authoritative campaign state. */
    SharedCampaignState state();

    /**
     * Returns a defensive copy of one registered extension's opaque durable payload. Core-owned campaign fields are
     * intentionally not exposed as mutable state to extensions. Missing payloads are returned as an empty byte array.
     */
    byte[] extensionState(String extensionId);

    /**
     * Atomically replaces only one registered extension namespace. The mutation receives a defensive copy and must
     * return the new opaque payload. Core campaign fields, other extension namespaces and ownership/lifecycle state
     * cannot be modified through this API. Mutation types must be namespaced and may not use the core
     * {@code mindustry-y:} namespace.
     */
    SharedCampaignState transactExtension(String actorId, String extensionId, String mutationType, UnaryOperator<byte[]> mutation);

    /** Registers an extension before a campaign is opened. */
    void register(SharedCampaignExtension extension);

    /** Current registered extensions in deterministic ID order. */
    Seq<SharedCampaignExtension> extensions();

    /** Versioned mission definitions available to the coordinator. */
    SharedCampaignMissionRegistry missionDefinitions();

    /** Declarative planet multiplayer policies, including Mod-provided campaign modes. */
    SharedCampaignPlanetRegistry planetPolicies();

    /** Namespaced UI contributions for campaign and MindustryY workspace surfaces. */
    SharedCampaignUiRegistry uiExtensions();

    /** Creates a verified restore point. Only the authoritative campaign process may call this method. */
    BackupState createRestorePoint(String actorId, String name, String reason, int keep);
}
