package mindustry.campaign.shared.api;

import mindustry.campaign.shared.*;

/** Events fired through Arc's event bus for core and Mod integration. */
public final class SharedCampaignEvents{
    private SharedCampaignEvents(){}

    public record CampaignOpened(String campaignId, boolean authoritative){}
    public record CampaignClosed(String campaignId){}
    public record CampaignCommitted(String campaignId, long revision, String mutationType, String actorId){}
    /** A client or action process accepted a newer authoritative snapshot. */
    public record CampaignSnapshotUpdated(String campaignId, long revision){}
    /** Effective Shared Campaign content unlocks changed; UI caches that filter by unlock authority must refresh. */
    public record CampaignUnlocksChanged(String campaignId, long revision){}
    /** Synchronous coordinator hook invoked before any action-start mutation or resource debit. */
    public record BeforeActionStart(String campaignId, String actorId, String actionId, String planetName, String sectorName, boolean resume){}
    public record ActionChanged(String campaignId, String actionId, SharedCampaignState.ActionStatus status){}
    public record HostGenerationChanged(String campaignId, long authorityGeneration, String hostId){}
    public record MissionChanged(String campaignId, String missionId, long eventSequence){}
}
