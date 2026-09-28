package mindustry.campaign.shared.ui;

import mindustry.campaign.shared.*;
import mindustry.type.*;

import static mindustry.Vars.*;

/** Routes vanilla campaign navigation to Shared Campaign-aware products while the primary client is inside an Action. */
public final class SharedCampaignUiRouter{
    private SharedCampaignUiRouter(){}

    /** True only for a live Shared Action. Merely keeping the coordinator/control-plane connection open is not gameplay. */
    public static boolean active(){
        try{
            SharedCampaignService service = SharedCampaignService.find(game());
            return service != null && service.sharedModeActive();
        }catch(Throwable ignored){
            return false;
        }
    }

    /** Compatibility alias used by older call sites/tests. */
    public static boolean activeAction(){ return active(); }

    public static boolean strategicContextActive(){
        if(active()) return true;
        return ui != null && ui.sharedCampaignDialog != null && ui.sharedCampaignDialog.strategicSurfaceVisible();
    }

    public static void showPlanet(){
        if(active() && ui != null && ui.sharedCampaignDialog != null) ui.sharedCampaignDialog.showPlanetFromAction();
        else ui.planet.show();
    }

    public static void showResearch(){
        if(active()){
            Planet planet = state != null && state.rules != null && state.rules.sector != null ? state.rules.sector.planet : null;
            showSharedStrategicResearch(planet);
        }else ui.research.show();
    }

    /** Explicit strategic entry used by the Shared 3D planet page even when no real-time Action is joined. */
    public static void showSharedStrategicResearch(Planet planet){
        if(ui != null && ui.research != null) ui.research.showSharedCampaign(planet);
    }

    public static void showSharedStrategicResearch(){ showSharedStrategicResearch(null); }

    public static void leaveActionAndShowLobby(){
        if(ui != null && ui.sharedCampaignDialog != null) ui.sharedCampaignDialog.leaveActionAndShowLobby();
    }

    public static void showDatabase(){
        ui.database.show();
    }
}
