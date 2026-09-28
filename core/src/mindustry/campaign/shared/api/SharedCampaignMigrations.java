package mindustry.campaign.shared.api;

import mindustry.campaign.shared.*;
import mindustry.campaign.shared.legacy.*;

/** Compatibility facade for the clean schema boundary. Historical MDT-Y schemas are decoded separately. */
public final class SharedCampaignMigrations{
    private SharedCampaignMigrations(){}
    public static SharedCampaignState migrate(SharedCampaignState state){
        if(state == null) throw new IllegalArgumentException("state");
        if(state.schema == SharedCampaignState.currentSchema) return state;
        if(state.schema == 26){
            state.persistenceProfile = SharedCampaignState.PersistenceProfile.highFrequencyWal;
            state.schema = SharedCampaignState.currentSchema;
            return state;
        }
        if(state.schema == 25){
            SharedCampaignState migrated = LegacyMdtYMigration.toCleanSchema26(state);
            migrated.persistenceProfile = SharedCampaignState.PersistenceProfile.highFrequencyWal;
            migrated.schema = SharedCampaignState.currentSchema;
            return migrated;
        }
        throw new IllegalStateException("Legacy schema " + state.schema + " requires the historical MDT-Y migration chain before the current clean schema");
    }
}
