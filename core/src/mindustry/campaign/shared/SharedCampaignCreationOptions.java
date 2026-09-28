package mindustry.campaign.shared;

import arc.files.*;
import arc.struct.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;
import mindustry.type.*;

import static mindustry.Vars.*;

/**
 * Validated creation request for a shared campaign. Runtime source paths are intentionally kept outside
 * {@link SharedCampaignState}; the durable state records only the origin kind and a human-readable source label.
 */
public class SharedCampaignCreationOptions{
    public CampaignOrigin origin = CampaignOrigin.newCampaign;
    public String displayName = "Shared Campaign";
    public String ownerId = "";
    /** Player-facing owner label; never used as an authentication identity. */
    public String ownerDisplayName = "";
    public String advertisedHost = "127.0.0.1";
    public int controlPort;
    public String primaryPlanetName = "serpulo";
    public boolean multiFrontEnabled = true;
    public int maxActiveActions = 2;
    public boolean freezeWhenEmpty = true;
    /** Desktop defaults to balanced batched WAL; dedicated servers explicitly select high-frequency WAL. */
    public PersistenceProfile persistenceProfile = PersistenceProfile.lowFrequencyWal;
    /** For single-player imports, include every campaign-capable planet instead of only the primary planet. */
    public boolean importAllPlanets = false;
    public InvitePolicy invitePolicy = InvitePolicy.members;
    /** Source data directory for a single-player import, or backup archive/directory for restoration. */
    public Fi source;
    public String sourceLabel = "";
    /** Explicit policy overrides keyed by stable planet content name. */
    public ObjectMap<String, String> planetPolicyIds = new ObjectMap<>();
    /** Optional non-durable progress sink for single-player import; never serialized into campaign state. */
    public SharedCampaignImporter.ProgressReporter progressReporter;

    public SharedCampaignCreationOptions validate(SharedCampaignPlanetRegistry policies){
        displayName = displayName == null || displayName.isBlank() ? "Shared Campaign" : displayName.trim();
        ownerId = ownerId == null ? "" : ownerId.trim();
        ownerDisplayName = ownerDisplayName == null ? "" : ownerDisplayName.trim();
        advertisedHost = advertisedHost == null || advertisedHost.isBlank() ? "127.0.0.1" : advertisedHost.trim();
        primaryPlanetName = primaryPlanetName == null ? "" : primaryPlanetName.trim();
        sourceLabel = sourceLabel == null ? "" : sourceLabel.trim();
        if(controlPort < 0 || controlPort > 65535) throw new IllegalArgumentException("Shared campaign control port is out of range");
        if(maxActiveActions < 1 || maxActiveActions > 32) throw new IllegalArgumentException("Maximum active actions must be between 1 and 32");
        if(invitePolicy == null) throw new IllegalArgumentException("Invite policy is required");
        if(persistenceProfile == null) throw new IllegalArgumentException("Persistence profile is required");
        if(origin == null) throw new IllegalArgumentException("Campaign origin is required");
        Planet primary = content.planet(primaryPlanetName);
        if(primary == null) throw new IllegalArgumentException("Unknown primary planet: " + primaryPlanetName);
        SharedCampaignPlanetRegistry.PlanetPolicy policy = policies.resolve(primaryPlanetName, planetPolicyIds.get(primaryPlanetName));
        if(policy.mode() == SharedCampaignPlanetRegistry.MultiplayerMode.unsupported){
            throw new IllegalArgumentException("Planet does not support shared campaigns: " + primary.localizedName);
        }
        if(!multiFrontEnabled) maxActiveActions = 1;
        if(origin == CampaignOrigin.backupRestore && (source == null || !source.exists() || source.isDirectory())){
            throw new IllegalArgumentException("Shared campaign backup archive does not exist");
        }
        if(origin == CampaignOrigin.singlePlayerImport && source != null && (!source.exists() || source.isDirectory())){
            throw new IllegalArgumentException("Single-player data export source must be an existing ZIP archive");
        }
        for(ObjectMap.Entry<String, String> entry : planetPolicyIds){
            if(content.planet(entry.key) == null) throw new IllegalArgumentException("Unknown planet policy target: " + entry.key);
            policies.resolve(entry.key, entry.value);
        }
        return this;
    }
}
