package mindustry.android.shared;

import android.content.*;
import arc.files.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.runtime.*;

/** Routes authoritative Android Actions to the app-private :sharedhost process rather than the GL process. */
public final class AndroidSharedHostSectorRuntimeFactory implements SectorRuntimeFactory{
    private final Context context;

    public AndroidSharedHostSectorRuntimeFactory(Context context){
        this.context = context.getApplicationContext();
    }

    @Override public SectorRuntime create(SharedCampaignState.ActionState action, Fi directory, String coordinatorHost, int coordinatorPort,
                                          String controlSecret, String joinSecret, Fi modsSource, SharedCampaignState.PersistenceProfile persistenceProfile){
        return new AndroidSharedHostSectorRuntime(context, action, directory, coordinatorHost, coordinatorPort, controlSecret, joinSecret, modsSource);
    }

    @Override public SectorRuntime.Backend expectedBackend(){ return SectorRuntime.Backend.inProcess; }
}
