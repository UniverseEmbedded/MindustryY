package mindustry.android.shared;

import android.app.*;
import android.content.*;
import android.os.*;
import arc.*;
import arc.files.*;
import arc.util.*;
import mindustry.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;
import mindustry.net.*;
import mindustry.runtime.*;
import mindustry.ui.*;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Private process host for Android authoritative Shared Campaign Actions.
 * The graphical process remains a normal client/coordinator; all real World ticks and their GC live here.
 */
public final class AndroidSharedCampaignHostService extends Service{
    public static final String actionStart = "mindustry.y.sharedhost.START";
    public static final String actionStop = "mindustry.y.sharedhost.STOP";
    public static final String extraRequestPath = "requestPath";
    public static final String extraActionId = "actionId";
    public static final String extraStatusPath = "statusPath";

    private final ConcurrentHashMap<String, Hosted> runtimes = new ConcurrentHashMap<>();
    private final ExecutorService commands = Executors.newSingleThreadExecutor(r -> daemonBackground(r, "Mindustry-SharedHost-Commands", Thread.NORM_PRIORITY - 1));
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> daemonBackground(r, "Mindustry-SharedHost-Monitor", Thread.MIN_PRIORITY));
    private final Binder binder = new Binder();
    private final AtomicBoolean bootstrapStarted = new AtomicBoolean();
    private final CountDownLatch bootstrapReady = new CountDownLatch(1);
    private volatile Throwable bootstrapFailure;
    private volatile AndroidSharedHostApplication application;
    private volatile int executionParallelism = 2;
    private volatile String executionPressureReason = "";

    private record Hosted(InProcessSectorRuntime runtime, File statusFile){}

    @Override public void onCreate(){
        super.onCreate();
        // This property is process-local. It is deliberately never set in AndroidLauncher's graphical process.
        System.setProperty(RuntimeExecutionBudget.androidHostDaemonProperty, "true");
        System.setProperty("sharedCampaign.inProcess.scheduler", System.getProperty("sharedCampaign.inProcess.scheduler", "auto"));
        System.setProperty("sharedCampaign.inProcess.parallelism", System.getProperty("sharedCampaign.inProcess.parallelism", "2"));
        System.setProperty("mindustry.runtime.compute.parallelism", System.getProperty("mindustry.runtime.compute.parallelism", "1"));
        System.setProperty("mindustry.runtime.path.parallelism", System.getProperty("mindustry.runtime.path.parallelism", "1"));
        monitor.scheduleWithFixedDelay(this::publishLiveness, 1L, 2L, TimeUnit.SECONDS);
    }

    @Override public IBinder onBind(Intent intent){ return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId){
        if(intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if(actionStart.equals(action)){
            String requestPath = intent.getStringExtra(extraRequestPath);
            if(requestPath != null) commands.execute(() -> startRequest(new File(requestPath)));
        }else if(actionStop.equals(action)){
            String actionId = intent.getStringExtra(extraActionId);
            String statusPath = intent.getStringExtra(extraStatusPath);
            if(actionId != null) commands.execute(() -> stopRuntime(actionId, statusPath == null ? null : new File(statusPath)));
        }
        return START_NOT_STICKY;
    }

    private void startRequest(File requestFile){
        AndroidSharedHostRuntimeRequest request;
        File status = new File(requestFile.getParentFile(), "android-host-status.txt");
        try{
            request = AndroidSharedHostRuntimeRequest.read(requestFile);
            AndroidSharedHostStatus.write(status, AndroidSharedHostStatus.accepted, Integer.MIN_VALUE, "");
            ensureBootstrapped();
            refreshExecutionBudget();
            if(runtimes.containsKey(request.action.actionId)) throw new IllegalStateException("Action is already hosted: " + request.action.actionId);
            if(executionParallelism <= 1 && !executionPressureReason.isBlank() && !runtimes.isEmpty()){
                throw new IOException("Android host is under resource pressure (" + executionPressureReason + "); suspend an active Sector or wait for the device to cool/recover memory before starting another Action");
            }
            Fi directory = new Fi(request.directory);
            Fi mods = request.modsSource.isBlank() ? null : new Fi(request.modsSource);
            InProcessSectorRuntime runtime = new InProcessSectorRuntime(request.action, directory, request.coordinatorHost,
                request.coordinatorPort, request.controlSecret, request.joinSecret, mods);
            Fi source = request.sourceSave.isBlank() ? null : new Fi(request.sourceSave);
            runtime.start(source);
            runtimes.put(request.action.actionId, new Hosted(runtime, status));
            AndroidSharedHostStatus.write(status, AndroidSharedHostStatus.running, Integer.MIN_VALUE, runtime.diagnostics());
        }catch(Throwable error){
            AndroidSharedHostStatus.write(status, AndroidSharedHostStatus.failed, 1, error.toString());
            Log.err("Failed to start Android Shared Campaign Action", error);
            stopIfIdle();
        }
    }

    private void ensureBootstrapped() throws Exception{
        if(bootstrapStarted.compareAndSet(false, true)){
            application = new AndroidSharedHostApplication(getApplicationContext(), new ApplicationListener(){
                @Override public void init(){
                    try{
                        bootstrapCore();
                    }catch(Throwable error){
                        bootstrapFailure = error;
                    }finally{
                        bootstrapReady.countDown();
                    }
                }
            });
        }
        if(!bootstrapReady.await(90L, TimeUnit.SECONDS)) throw new IOException("Timed out initializing Android Shared Campaign host process");
        if(bootstrapFailure != null) throw new IOException("Android Shared Campaign host bootstrap failed", bootstrapFailure);
    }

    private void bootstrapCore(){
        RuntimeContexts.bindPrimaryThread();
        Context context = getApplicationContext();
        File external = context.getExternalFilesDir(null);
        if(external == null) external = context.getFilesDir();
        Core.settings.setDataDirectory(new Fi(external));
        Vars.loadLocales = false;
        Vars.headless = true;
        // The coordinator on Android is always the graphical app process, which resolves mod support strictly.
        // This headless authority must resolve identically before Vars.init() loads mods, otherwise the
        // enabled-mod set/content registry/content fingerprint diverge and every actionHello is rejected with
        // "Content fingerprint mismatch". Headless Mods normally relax support checks, so restore the graphical
        // process' strict selection semantics before Vars.init() discovers the enabled content set.
        System.setProperty("mindustryY.sharedCampaign.strictModSupport", "true");
        Vars.platform = new AndroidSharedHostPlatform(context);
        Vars.loadSettings();
        SharedCampaignRuntimeState.install(Vars.game(), ActionRuntimeConfig.disabled());
        Vars.game().net = new Net(Vars.platform.getNet());
        Vars.init();
        Vars.schematics = new Schematics();

        UI.loadColors();
        Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent();
        Vars.mods.loadScripts();
        Vars.content.createModContent();
        Vars.content.init();
        // This branch has no extra Y3D/Oxygen packet registries; vanilla Net registration is already complete.
        Vars.schematics.load();
        if(Vars.mods.hasContentErrors()) throw new IllegalStateException("Installed Mod content has errors; Android shared host cannot start authoritative Actions");
        Vars.bases.load();
        Vars.mods.eachClass(Mod::init);
        Events.fire(new ServerLoadEvent());
        refreshExecutionBudget();
        Log.info("Android Shared Campaign host process initialized; sectorParallelism=@ (active=@), computeParallelism=@, pathParallelism=@",
            InProcessSectorScheduler.shared().parallelism(), InProcessSectorScheduler.shared().activeParallelismLimit(),
            RuntimeWorkerPool.sharedCompute().parallelism(), RuntimePathExecutor.shared().metrics().parallelism());
    }

    private void publishLiveness(){
        refreshExecutionBudget();
        for(var entry : runtimes.entrySet()){
            String actionId = entry.getKey();
            Hosted hosted = entry.getValue();
            try{
                if(hosted.runtime.isAlive()){
                    AndroidSharedHostStatus.write(hosted.statusFile, AndroidSharedHostStatus.running, Integer.MIN_VALUE, hosted.runtime.diagnostics());
                }else{
                    int exit = hosted.runtime.exitCode();
                    String state = exit == 0 ? AndroidSharedHostStatus.stopped : AndroidSharedHostStatus.failed;
                    AndroidSharedHostStatus.write(hosted.statusFile, state, exit, hosted.runtime.diagnostics());
                    runtimes.remove(actionId, hosted);
                    stopIfIdle();
                }
            }catch(Throwable error){
                AndroidSharedHostStatus.write(hosted.statusFile, AndroidSharedHostStatus.failed, 1, error.toString());
                runtimes.remove(actionId, hosted);
                stopIfIdle();
            }
        }
    }

    /** Keeps rendering headroom by reducing outer-world concurrency instead of slowing authoritative TPS. */
    private void refreshExecutionBudget(){
        int desired = 2;
        String reason = "";
        try{
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
                int thermal = power == null ? PowerManager.THERMAL_STATUS_NONE : power.getCurrentThermalStatus();
                if(thermal >= PowerManager.THERMAL_STATUS_MODERATE){
                    desired = 1;
                    reason = "thermal status " + thermal;
                }
            }
            ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
            if(manager != null){
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(memory);
                if(memory.lowMemory){
                    desired = 1;
                    reason = reason.isBlank() ? "low memory" : reason + ", low memory";
                }
            }
        }catch(Throwable error){
            Log.debug("Unable to sample Android shared-host resource pressure: @", error.toString());
        }
        try{
            InProcessSectorScheduler scheduler = InProcessSectorScheduler.shared();
            desired = Math.max(1, Math.min(desired, scheduler.parallelism()));
            scheduler.setActiveParallelismLimit(desired);
        }catch(Throwable ignored){}
        executionParallelism = desired;
        executionPressureReason = reason;
    }

    private static Thread daemonBackground(Runnable runnable, String name, int javaPriority){
        Thread thread = new Thread(() -> {
            try{ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }catch(Throwable ignored){}
            runnable.run();
        }, name);
        thread.setDaemon(true);
        thread.setPriority(javaPriority);
        return thread;
    }

    private void stopRuntime(String actionId, File fallbackStatus){
        Hosted hosted = runtimes.get(actionId);
        File status = hosted == null ? fallbackStatus : hosted.statusFile;
        AndroidSharedHostStatus previous = AndroidSharedHostStatus.read(status);
        if(hosted == null){
            // Preserve an already-published terminal verdict. Duplicate STOP is intentionally idempotent.
            if(previous != null && (AndroidSharedHostStatus.stopped.equals(previous.state) || AndroidSharedHostStatus.failed.equals(previous.state))) return;
            AndroidSharedHostStatus.write(status, AndroidSharedHostStatus.stopped, 0, "Action was no longer hosted");
            return;
        }

        AndroidSharedHostStatus.write(status, AndroidSharedHostStatus.stopping, Integer.MIN_VALUE, hosted.runtime.diagnostics());
        int exit = 0;
        try{
            hosted.runtime.terminateGracefully();
            exit = hosted.runtime.exitCode();
            if(exit == Integer.MIN_VALUE) exit = 0;
        }catch(Throwable error){
            exit = 1;
            AndroidSharedHostStatus.write(status, AndroidSharedHostStatus.failed, exit, error.toString());
            runtimes.remove(actionId, hosted);
            stopIfIdle();
            return;
        }
        runtimes.remove(actionId, hosted);
        AndroidSharedHostStatus.write(status, AndroidSharedHostStatus.stopped, exit, "");
        stopIfIdle();
    }

    private void stopIfIdle(){
        if(runtimes.isEmpty()) stopSelf();
    }

    @Override public void onDestroy(){
        for(var hosted : runtimes.values()) try{ hosted.runtime.terminateGracefully(); }catch(Throwable ignored){}
        runtimes.clear();
        commands.shutdownNow();
        monitor.shutdownNow();
        if(application != null) application.exit();
        super.onDestroy();
    }
}
