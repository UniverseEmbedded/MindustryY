package mindustry.android.shared;

import android.content.*;
import android.os.*;
import arc.*;
import arc.backend.android.*;
import arc.files.*;
import arc.mock.*;
import arc.struct.*;
import arc.util.*;

/** Minimal Android Application backend for a headless private service process. */
final class AndroidSharedHostApplication implements Application{
    private final Context context;
    private final Seq<ApplicationListener> listeners = new Seq<>();
    private final TaskQueue runnables = new TaskQueue();
    private final MockGraphics graphics = new MockGraphics();
    private volatile boolean running = true;
    private Thread mainThread;

    AndroidSharedHostApplication(Context context, ApplicationListener listener){
        this.context = context.getApplicationContext();
        if(listener != null) listeners.add(listener);
        Core.settings = new HostReadOnlySettings();
        Core.app = this;
        Core.files = new ServiceFiles(this.context);
        Core.audio = new MockAudio();
        Core.graphics = graphics;
        Core.input = new MockInput();
        mainThread = new Thread(this::loop, "Mindustry-Android-SharedHost");
        mainThread.setDaemon(true);
        mainThread.start();
    }

    private void loop(){
        try{
            synchronized(listeners){ for(ApplicationListener listener : listeners) listener.init(); }
            long next = Time.nanos();
            while(running){
                runnables.run();
                graphics.incrementFrameId();
                defaultUpdate();
                synchronized(listeners){ for(ApplicationListener listener : listeners) listener.update(); }
                graphics.updateTime();
                next += 16_666_667L;
                long sleep = next - Time.nanos();
                if(sleep > 0) Threads.sleep(sleep / 1_000_000L, (int)(sleep % 1_000_000L));
                else next = Time.nanos();
            }
        }catch(Throwable error){
            Log.err("Android Shared Campaign host application failed", error);
        }finally{
            synchronized(listeners){
                for(ApplicationListener listener : listeners){
                    try{ listener.pause(); }catch(Throwable ignored){}
                    try{ listener.dispose(); }catch(Throwable ignored){}
                }
            }
            try{ dispose(); }catch(Throwable ignored){}
        }
    }

    @Override public Seq<ApplicationListener> getListeners(){ return listeners; }
    @Override public ApplicationType getType(){ return ApplicationType.android; }
    @Override public int getVersion(){ return Build.VERSION.SDK_INT; }
    @Override public long getNativeHeap(){ return android.os.Debug.getNativeHeapAllocatedSize(); }
    @Override public Thread getMainThread(){ return mainThread; }
    @Override public String getClipboardText(){ return null; }
    @Override public void setClipboardText(String text){}
    @Override public void post(Runnable runnable){ runnables.post(runnable); }
    @Override public void exit(){ post(() -> running = false); }

    /**
     * Shares the graphical process data directory for identical Mod/Content discovery, but never writes its settings.
     * Two Android processes concurrently rotating settings.bin/backups is unsafe and an Action must not mutate the
     * player's local profile as a side effect of hosting.
     */
    private static final class HostReadOnlySettings extends Settings{
        @Override public synchronized void loadValues(){
            Throwable primaryFailure = null;
            Fi primary = getSettingsFile();
            if(primary.exists()){
                try{
                    super.loadValues(primary);
                    return;
                }catch(Throwable error){
                    primaryFailure = error;
                }
            }
            Fi backup = getBackupSettingsFile();
            if(backup.exists()){
                try{
                    super.loadValues(backup);
                    return;
                }catch(Throwable error){
                    if(primaryFailure != null) error.addSuppressed(primaryFailure);
                    throw new ArcRuntimeException("Unable to read Shared Campaign host settings snapshot", error);
                }
            }
            if(primaryFailure != null) throw new ArcRuntimeException("Unable to read Shared Campaign host settings snapshot", primaryFailure);
        }

        @Override public synchronized void forceSave(){ modified = false; }
        @Override public synchronized void manualSave(){ modified = false; }
        @Override public synchronized void autosave(){ modified = false; }
        @Override public synchronized void saveValues(){ modified = false; }
    }

    private static final class ServiceFiles extends AndroidFiles{
        private final Context context;
        ServiceFiles(Context context){
            super(context.getAssets(), context.getFilesDir().getAbsolutePath());
            this.context = context;
        }
        @Override public String getCachePath(){ return context.getCacheDir().getAbsolutePath(); }
    }
}
