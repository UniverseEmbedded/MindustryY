package mindustry.campaign.shared;

import mindustry.campaign.shared.SharedCampaignState.PersistenceProfile;

/** Product persistence presets shared by authority storage, Action runtimes, UI, and dedicated-server controls. */
public final class SharedCampaignPersistence{
    private SharedCampaignPersistence(){}

    public record Policy(
        boolean walEnabled,
        boolean walImmediateSync,
        long walFlushIntervalMillis,
        long checkpointIntervalMillis,
        long walMaxBytes,
        int walMaxRecords,
        int actionAutosaveSeconds
    ){}

    public static Policy policy(PersistenceProfile profile){
        PersistenceProfile selected = profile == null ? PersistenceProfile.lowFrequencyWal : profile;
        return switch(selected){
            case highFrequencyWal -> new Policy(
                true, true, 0L,
                overrideLong("high.checkpointMs", 45_000L, 5_000L),
                overrideLong("high.walMaxBytes", 256L * 1024L, 8L * 1024L),
                overrideInt("high.walMaxRecords", 64, 4),
                overrideInt("high.actionAutosaveSeconds", 60, 15)
            );
            case lowFrequencyWal -> new Policy(
                true, false,
                overrideLong("low.walFlushMs", 15_000L, 1_000L),
                overrideLong("low.checkpointMs", 5L * 60_000L, 10_000L),
                overrideLong("low.walMaxBytes", 1024L * 1024L, 8L * 1024L),
                overrideInt("low.walMaxRecords", 256, 4),
                overrideInt("low.actionAutosaveSeconds", 5 * 60, 15)
            );
            case traditional -> new Policy(
                false, false, 0L,
                overrideLong("traditional.checkpointMs", 5L * 60_000L, 10_000L),
                0L, 0,
                overrideInt("traditional.actionAutosaveSeconds", 5 * 60, 15)
            );
        };
    }

    private static long overrideLong(String suffix, long fallback, long minimum){
        return Math.max(minimum, Long.getLong("mindustry.sharedCampaign.persistence." + suffix, fallback));
    }

    private static int overrideInt(String suffix, int fallback, int minimum){
        return Math.max(minimum, Integer.getInteger("mindustry.sharedCampaign.persistence." + suffix, fallback));
    }
}
