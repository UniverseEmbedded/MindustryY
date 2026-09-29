package mindustry.android.shared;

import android.content.*;
import arc.files.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.runtime.*;

import java.io.*;
import java.nio.channels.*;

/** Process-proxy implementation; gameplay sockets stay on the existing broker/loopback protocol. */
final class AndroidSharedHostSectorRuntime implements SectorRuntime{
    private static final long staleMillis = 15_000L;
    private final Context context;
    private final SharedCampaignState.ActionState action;
    private final Fi directory, modsSource;
    private final String coordinatorHost, controlSecret, joinSecret;
    private final int coordinatorPort;
    private final File requestFile, statusFile;
    private volatile boolean launched;
    private volatile boolean hostClaimed;
    private volatile boolean stopRequested;
    private volatile long launchedAt;
    private volatile long stopRequestedAt;
    private volatile int localExit = Integer.MIN_VALUE;

    AndroidSharedHostSectorRuntime(Context context, SharedCampaignState.ActionState action, Fi directory, String coordinatorHost, int coordinatorPort,
                                   String controlSecret, String joinSecret, Fi modsSource){
        this.context = context.getApplicationContext();
        this.action = action;
        this.directory = directory;
        this.coordinatorHost = coordinatorHost;
        this.coordinatorPort = coordinatorPort;
        this.controlSecret = controlSecret == null ? "" : controlSecret;
        this.joinSecret = joinSecret == null ? "" : joinSecret;
        this.modsSource = modsSource;
        Fi control = directory.child("config"); control.mkdirs();
        this.requestFile = control.child("android-host-request.bin").file();
        this.statusFile = control.child("android-host-status.txt").file();
    }

    @Override public Backend backend(){ return Backend.inProcess; }

    @Override public synchronized void start(Fi sourceSave) throws IOException{
        if(launched) throw new IllegalStateException("Android Action runtime is single-use: " + action.actionId);
        if(statusFile.exists()) statusFile.delete();
        new AndroidSharedHostRuntimeRequest(action, directory.absolutePath(), coordinatorHost, coordinatorPort, controlSecret, joinSecret,
            modsSource == null ? "" : modsSource.absolutePath(), sourceSave == null ? "" : sourceSave.absolutePath()).write(requestFile);
        AndroidSharedHostClient.acquireHost();
        hostClaimed = true;
        Intent intent = new Intent(context, AndroidSharedCampaignHostService.class)
            .setAction(AndroidSharedCampaignHostService.actionStart)
            .putExtra(AndroidSharedCampaignHostService.extraRequestPath, requestFile.getAbsolutePath());
        try{
            context.startService(intent);
            launched = true;
            launchedAt = System.currentTimeMillis();
        }catch(Throwable error){
            releaseHostClaim();
            if(error instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Unable to start Android Shared Campaign host service", error);
        }

        long deadline = System.currentTimeMillis() + 5_000L;
        while(System.currentTimeMillis() < deadline){
            AndroidSharedHostStatus status = AndroidSharedHostStatus.read(statusFile);
            if(status != null){
                if(AndroidSharedHostStatus.failed.equals(status.state)){
                    localExit = status.exitCode;
                    launched = false;
                    releaseHostClaim();
                    throw new IOException("Android shared-host rejected Action " + action.actionId + ": " + status.message);
                }
                if(AndroidSharedHostStatus.accepted.equals(status.state) || AndroidSharedHostStatus.running.equals(status.state)) return;
            }
            try{ Thread.sleep(20L); }catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        }
        // The service may still be cold-starting; liveness polling and the coordinator heartbeat lease provide the durable verdict.
    }

    @Override public synchronized boolean isAlive(){
        if(!launched || localExit != Integer.MIN_VALUE) return false;
        long now = System.currentTimeMillis();
        AndroidSharedHostStatus status = AndroidSharedHostStatus.read(statusFile);
        if(status == null){
            // startService() succeeding does not prove that Android actually initialized the remote process. Never
            // keep a binding claim forever when the worker dies before publishing its first durable status marker.
            long age = launchedAt <= 0L ? Long.MAX_VALUE : now - launchedAt;
            return age <= 120_000L || failProxy();
        }
        if(AndroidSharedHostStatus.failed.equals(status.state) || AndroidSharedHostStatus.stopped.equals(status.state)){
            localExit = status.exitCode == Integer.MIN_VALUE ? (AndroidSharedHostStatus.failed.equals(status.state) ? 1 : 0) : status.exitCode;
            launched = false;
            releaseHostClaim();
            return false;
        }
        long age = Math.max(0L, now - status.updatedAt);
        // Cold process/content/mod bootstrap can legitimately take much longer than one liveness interval.
        if(AndroidSharedHostStatus.accepted.equals(status.state)) return age <= 120_000L || failProxy();
        if(AndroidSharedHostStatus.stopping.equals(status.state)) return age <= 30_000L || failProxy();
        if(stopRequested && stopRequestedAt > 0L && now - stopRequestedAt > 30_000L) return failProxy();
        if(AndroidSharedHostStatus.running.equals(status.state)) return age <= staleMillis || failProxy();
        // Unknown status values are protocol corruption, not proof of life.
        return failProxy();
    }

    private boolean failProxy(){
        localExit = 1;
        launched = false;
        releaseHostClaim();
        return false;
    }

    @Override public int exitCode(){ return isAlive() ? Integer.MIN_VALUE : (localExit == Integer.MIN_VALUE ? 1 : localExit); }

    @Override public synchronized void terminateGracefully(){
        if(!launched || stopRequested) return;
        try{
            Intent intent = new Intent(context, AndroidSharedCampaignHostService.class)
                .setAction(AndroidSharedCampaignHostService.actionStop)
                .putExtra(AndroidSharedCampaignHostService.extraActionId, action.actionId)
                .putExtra(AndroidSharedCampaignHostService.extraStatusPath, statusFile.getAbsolutePath());
            context.startService(intent);
            stopRequested = true;
            stopRequestedAt = System.currentTimeMillis();
            // The STOP intent makes the Service a started service until its command queue handles this Action. Releasing
            // the UI binding here therefore does not abort cleanup; it only allows the host process to disappear once the
            // final Action has actually been removed and the Service calls stopSelf().
            releaseHostClaim();
        }catch(Throwable error){
            localExit = 1;
            launched = false;
            releaseHostClaim();
        }
    }

    private synchronized void releaseHostClaim(){
        if(!hostClaimed) return;
        hostClaimed = false;
        AndroidSharedHostClient.releaseHost();
    }

    @Override public boolean injectClient(SocketChannel channel, byte[] preRead){ return false; }
}
