package mindustry.android.shared;

import android.content.*;
import android.os.*;
import arc.util.*;
import mindustry.campaign.shared.*;

/**
 * Process-lifetime bridge to the private Android Shared Campaign host.
 *
 * Hosting is deliberately lazy: installing the factory must not start a second ART process for ordinary game sessions.
 * Each live Action owns one claim; the final release unbinds so an idle :sharedhost can be reclaimed.
 */
public final class AndroidSharedHostClient{
    private static volatile Context appContext;
    private static boolean bound;
    private static boolean binding;
    private static int claims;

    private static final ServiceConnection connection = new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name, IBinder service){ connected(); }
        @Override public void onServiceDisconnected(ComponentName name){ disconnected(false); }
        @Override public void onBindingDied(ComponentName name){ disconnected(true); }
    };

    private AndroidSharedHostClient(){}

    public static synchronized void install(Context context){
        if(context == null) throw new IllegalArgumentException("Android context is required");
        appContext = context.getApplicationContext();
        // Do not BIND_AUTO_CREATE here: most Android sessions never host a Shared Campaign, and eagerly starting the
        // private authority process would waste a second ART heap/content footprint. The first real Action start binds it.
        SharedCampaignService.installPlatformRuntimeFactory(() -> new AndroidSharedHostSectorRuntimeFactory(appContext));
    }

    static synchronized void acquireHost(){
        if(appContext == null) throw new IllegalStateException("Android shared-host client is not installed");
        claims++;
        ensureBound();
    }

    static synchronized void releaseHost(){
        if(claims > 0) claims--;
        if(claims != 0 || appContext == null || (!bound && !binding)) return;
        try{ appContext.unbindService(connection); }catch(Throwable ignored){}
        bound = false;
        binding = false;
    }

    static synchronized void ensureBound(){
        Context context = appContext;
        if(context == null || claims <= 0 || bound || binding) return;
        try{
            Intent intent = new Intent(context, AndroidSharedCampaignHostService.class);
            int flags = Context.BIND_AUTO_CREATE;
            if(Build.VERSION.SDK_INT >= 14) flags |= Context.BIND_NOT_FOREGROUND;
            binding = true;
            if(!context.bindService(intent, connection, flags)) binding = false;
        }catch(Throwable error){
            binding = false;
            Log.warn("Unable to bind Android Shared Campaign host service: @", error.toString());
        }
    }

    private static synchronized void connected(){
        binding = false;
        bound = true;
        if(claims <= 0) releaseHost();
    }

    private static synchronized void disconnected(boolean rebind){
        bound = false;
        binding = false;
        if(rebind && claims > 0) ensureBound();
    }

    static synchronized int claimsForTesting(){ return claims; }

    static Context context(){
        Context context = appContext;
        if(context == null) throw new IllegalStateException("Android shared-host client is not installed");
        return context;
    }
}
