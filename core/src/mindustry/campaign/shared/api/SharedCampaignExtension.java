package mindustry.campaign.shared.api;

import mindustry.campaign.shared.*;

/**
 * Versioned shared-campaign extension contract. IDs and compatibility identities are persisted and must remain stable.
 * Extension data is retained even while an optional extension is absent, allowing it to be reinstalled later without
 * destructive conversion.
 */
public interface SharedCampaignExtension{
    /** Reverse-DNS or Mod-prefixed stable ID. */
    String id();
    /** Extension-owned persistent data schema. */
    int schemaVersion();
    /** Lowest shared-campaign API understood by this extension. */
    default int minimumApiVersion(){ return SharedCampaignApi.apiVersion; }
    /** Highest shared-campaign API understood by this extension. */
    default int maximumApiVersion(){ return SharedCampaignApi.apiVersion; }
    /** Whether an existing campaign containing this extension state must refuse to open when the extension is missing. */
    default boolean requiredForLoad(){ return true; }
    /**
     * Identity for semantic compatibility that is independent from data migrations. Change it only when the extension's
     * campaign behavior can no longer safely continue with state created by the previous identity.
     */
    default String compatibilityId(){ return id() + ":compat-1"; }

    default void registerMissionDefinitions(MissionRegistry registry){}
    default void validateCampaign(SharedCampaignState state){}
    default void onCampaignOpened(SharedCampaignContext context){}
    default void onCampaignClosed(SharedCampaignContext context){}
    default void onCampaignCommitted(CampaignCommitEvent event){}
    default void onActionLifecycle(ActionLifecycleEvent event){}
    default void migrate(ExtensionMigration migration){}

    /**
     * Stable, declarative mission definition contract. The compatibility identity must describe mission semantics rather
     * than a runtime object identity; it is persisted through the registry fingerprint and therefore must change whenever
     * an existing in-progress mission can no longer continue safely.
     */
    interface MissionSpec{
        String compatibilityId();
        /** Stable planet binding. Blank means the definition is not tied to a single planet. */
        default String planetName(){ return ""; }
        /** Stable sector binding inside {@link #planetName()}. Blank means the definition is not tied to one sector. */
        default String sectorName(){ return ""; }
        /** Human-readable fallback used when no localized bundle entry is supplied. */
        default String displayName(){ return sectorName().isBlank() ? compatibilityId() : sectorName(); }
        /** Story missions normally freeze exactly when the action has no players. */
        default boolean freezeWhenEmpty(){ return true; }
        /** Additional authoritative start predicate. It is evaluated only for a new attempt, never for a paused resume. */
        default MissionAvailability availability(SharedCampaignState state){ return MissionAvailability.allow(); }
    }

    record MissionAvailability(boolean available, String reason){
        public MissionAvailability{ reason = reason == null ? "" : reason; }
        public static MissionAvailability allow(){ return new MissionAvailability(true, ""); }
        public static MissionAvailability deny(String reason){ return new MissionAvailability(false, reason); }
    }

    interface MissionRegistry{
        void register(String stableMissionId, int definitionVersion, MissionSpec definition);
    }

    interface SharedCampaignContext{
        SharedCampaignApi api();
        String campaignId();
        boolean authoritative();
    }

    interface ExtensionMigration{
        String extensionId();
        int fromVersion();
        int toVersion();
        byte[] input();
        void output(byte[] data);
    }

    record ActionLifecycleEvent(String campaignId, String actionId, String planetName, String sectorName, Phase phase, long authorityGeneration){
        public enum Phase{preparing, starting, running, suspending, suspended, failed, completed, incompatible}
    }

    /** Fired after the authoritative snapshot and audit journal have both been durably committed. */
    record CampaignCommitEvent(String campaignId, long fromRevision, long revision, String mutationType, String actorId){}
}
