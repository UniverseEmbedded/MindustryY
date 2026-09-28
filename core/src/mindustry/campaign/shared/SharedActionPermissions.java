package mindustry.campaign.shared;

import mindustry.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.gen.*;

/** Capability checks for player-visible controls inside a Shared Campaign Action. */
public final class SharedActionPermissions{
    private SharedActionPermissions(){}

    /** Wave control is intentionally narrower than vanilla admin: owning the Campaign grants this one capability only. */
    public static boolean canControlWaves(SharedCampaignState state, String memberId){
        return state != null && memberId != null && !memberId.isBlank() && memberId.equals(state.ownerId);
    }

    public static boolean canControlWaves(Player player){
        if(player == null || Vars.game() == null) return false;
        var runtime = mindustry.campaign.shared.runtime.SharedCampaignRuntimeState.find(Vars.game());
        SharedCampaignService service = runtime == null ? null : runtime.component(SharedCampaignService.class);
        if(service == null || !service.sharedModeActive()) return false;
        SharedCampaignState state = service.strategicState();
        if(state == null) return false;

        if(Vars.game().net != null && Vars.game().net.server()){
            if(player.con == null) return false;
            var network = mindustry.campaign.shared.net.SharedCampaignNet.find(Vars.game());
            String memberId = network == null ? "" : network.authenticatedMemberId(player.con);
            return canControlWaves(state, memberId);
        }
        return canControlWaves(state, service.activeMemberId());
    }
}
