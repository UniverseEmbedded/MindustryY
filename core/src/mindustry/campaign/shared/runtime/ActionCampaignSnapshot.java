package mindustry.campaign.shared.runtime;

import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.net.*;

import java.util.*;

/**
 * Action-local read-only projection of the coordinator's authoritative Shared Campaign snapshot.
 *
 * <p>This deliberately replaces the old dependency from {@code SharedActionAgent} to the full coordinator/client
 * service. It accepts only monotonic revisions for one campaign identity and exposes defensive copies to callers;
 * hot objective/research checks use the narrow scalar helpers below instead of copying the whole campaign graph.</p>
 */
public final class ActionCampaignSnapshot implements SharedCampaignNet.CampaignStateSource{
    private SharedCampaignState snapshot;

    /** Accepts a newer authoritative snapshot and returns the revision now visible to this Action. */
    public synchronized long accept(SharedCampaignState next){
        Objects.requireNonNull(next, "authoritative snapshot");
        SharedCampaignState owned = SharedCampaignCodec.copy(next);
        if(snapshot != null){
            if(!Objects.equals(snapshot.campaignId, owned.campaignId)){
                throw new IllegalArgumentException("Cannot replace Action campaign identity " + snapshot.campaignId + " with " + owned.campaignId);
            }
            if(owned.revision < snapshot.revision) return snapshot.revision;
            if(owned.authorityGeneration < snapshot.authorityGeneration) return snapshot.revision;
        }
        snapshot = owned;
        return owned.revision;
    }

    @Override
    public synchronized SharedCampaignState state(){
        return snapshot == null ? null : SharedCampaignCodec.copy(snapshot);
    }

    public synchronized long revision(){ return snapshot == null ? -1L : snapshot.revision; }
    public synchronized String campaignId(){ return snapshot == null ? "" : snapshot.campaignId; }
    public synchronized long authorityGeneration(){ return snapshot == null ? 0L : snapshot.authorityGeneration; }

    public synchronized boolean researched(String contentName){
        return snapshot != null && contentName != null && snapshot.researched.contains(contentName);
    }

    public synchronized boolean unlocked(String contentName){
        return snapshot != null && contentName != null && SharedCampaignState.effectiveUnlocked(snapshot.researched, snapshot.discovered, contentName);
    }

    public synchronized MissionState mission(String missionId){
        if(snapshot == null || missionId == null || missionId.isBlank()) return null;
        MissionState mission = snapshot.missions.get(missionId);
        if(mission == null) return null;
        SharedCampaignState wrapper = new SharedCampaignState();
        wrapper.missions.put(missionId, mission);
        return SharedCampaignCodec.copy(wrapper).missions.get(missionId);
    }

    public synchronized void clear(){ snapshot = null; }
}
