package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.runtime.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** A real Shared Campaign Sector hosted as its own GameContext inside the current JVM. */
public final class InProcessSectorRuntime implements SectorRuntime, InProcessSectorScheduler.RuntimeLane{
    private final SharedCampaignState.ActionState action;
    private final Fi directory;
    private final String coordinatorHost;
    private final int coordinatorPort;
    private final String controlSecret;
    private final String joinSecret;
    private final Fi modsSource;
    private final InProcessSectorScheduler scheduler;
    private final AtomicBoolean alive = new AtomicBoolean();
    private volatile int exitCode = Integer.MIN_VALUE;
    private volatile Throwable failure;
    private volatile GameContext context;
    private volatile boolean manualTicksForTesting;

    public InProcessSectorRuntime(SharedCampaignState.ActionState action, Fi directory, String coordinatorHost, int coordinatorPort,
                                  String controlSecret, String joinSecret, Fi modsSource){
        this(action, directory, coordinatorHost, coordinatorPort, controlSecret, joinSecret, modsSource, InProcessSectorScheduler.shared());
    }

    InProcessSectorRuntime(SharedCampaignState.ActionState action, Fi directory, String coordinatorHost, int coordinatorPort,
                           String controlSecret, String joinSecret, Fi modsSource, InProcessSectorScheduler scheduler){
        this.action = Objects.requireNonNull(action, "action");
        this.directory = Objects.requireNonNull(directory, "runtime directory");
        this.coordinatorHost = Objects.requireNonNull(coordinatorHost, "coordinatorHost");
        this.coordinatorPort = coordinatorPort;
        this.controlSecret = controlSecret == null ? "" : controlSecret;
        this.joinSecret = joinSecret == null ? "" : joinSecret;
        this.modsSource = modsSource;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override public synchronized void start(Fi sourceSave) throws IOException{
        if(alive.get()) throw new IllegalStateException("Action already running: " + action.actionId);
        if(context != null) throw new IllegalStateException("Runtime cannot be restarted after termination: " + action.actionId);
        if(action.port <= 0 || action.port > 65535) throw new IllegalArgumentException("Invalid action port: " + action.port);
        if(OS.isAndroid && !Boolean.getBoolean("mindustry.uiTest") && !RuntimeExecutionBudget.androidHostDaemon()){
            throw new IOException(SectorRuntimeFactory.hostLocalAuthoritativeBlockedMessage());
        }

        RuntimeStorage storage = new RuntimeStorage(directory);
        storage.prepare();
        SectorRuntimeFactory.synchronizeMods(storage.mods(), modsSource);
        Fi target = storage.actionSave("action");
        if(sourceSave != null && sourceSave.exists()) copyIfDifferent(sourceSave, target);

        Fi summary = storage.config().child("action-summary.bin");
        Fi launch = storage.config().child("action-launch.bin");
        summary.writeBytes(RuntimePayloads.encodeSummary(action.summary), false);
        launch.writeBytes(RuntimePayloads.encode(new RuntimePayloads.LaunchPlan(action.launchOriginSector, action.launchLoadout, action.launchResources)), false);

        boolean bootstrapFreshWorld = action.launchLoadout != null && !action.launchLoadout.isBlank();
        ActionRuntimeDescriptor descriptor = new ActionRuntimeDescriptor(
            action.actionId, coordinatorHost, coordinatorPort, action.hostGeneration, action.runtimeIncarnation,
            "action", summary.absolutePath(), launch.absolutePath(), action.planetName, action.sectorName, action.attemptId,
            action.port, bootstrapFreshWorld,
            storage.config().child("research-reservations.bin").absolutePath(),
            storage.config().child("transport-reservations.bin").absolutePath(),
            5 * 60,
            "embedded:" + action.actionId
        );
        descriptor.write(storage.config().child("action-runtime.bin"));

        ActionRuntimeConfig config = new ActionRuntimeConfig(descriptor, controlSecret, joinSecret);
        try{
            context = SharedActionBootstrap.createEmbedded("shared-action-" + action.actionId, config, storage, this::terminateGracefully);
            alive.set(true);

            if(sourceSave != null && sourceSave.exists() && !bootstrapFreshWorld){
                RuntimeContexts.run(context, () -> {
                    try{
                        if(!SaveIO.isSaveValid(target)) throw new IllegalStateException("Suspended action save is invalid: " + target);
                        SaveIO.load(target);
                        context.state.set(GameState.State.playing);
                        SharedActionAgent agent = SharedActionBootstrap.findAgent(context);
                        if(agent == null) throw new IllegalStateException("Resumed Action has no SharedActionAgent after world load");
                        agent.onWorldReady();
                        context.netServer.openServer(action.port);
                    }catch(Throwable error){
                        throw new RuntimeException(error);
                    }
                });
            }
            scheduler.register(this);
        }catch(Throwable error){
            alive.set(false);
            exitCode = 1;
            failure = error;
            if(context != null) context.dispose();
            context = null;
            if(error instanceof IOException io) throw io;
            throw new IOException("Failed to start in-process Sector " + action.actionId, error);
        }
    }

    private static void copyIfDifferent(Fi source, Fi target) throws IOException{
        Path sourcePath = source.file().toPath().toAbsolutePath().normalize();
        Path targetPath = target.file().toPath().toAbsolutePath().normalize();
        if(!sourcePath.equals(targetPath)){
            target.parent().mkdirs();
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override public synchronized boolean tickFromScheduler(){
        if(manualTicksForTesting) return false;
        return tickInternal();
    }

    private boolean tickInternal(){
        GameContext value = context;
        if(!alive.get() || value == null || value.closed()) return false;
        InProcessSectorScheduler.tick(value);
        return true;
    }

    public synchronized void setManualTicksForTesting(boolean enabled){
        if(!Boolean.getBoolean("mindustry.uiTest")) throw new SecurityException("Manual Shared Campaign ticks are test-only");
        if(manualTicksForTesting == enabled) return;
        manualTicksForTesting = enabled;
        if(enabled) scheduler.unregister(this);
        else if(alive.get() && context != null && !context.closed()) scheduler.register(this);
    }

    public boolean manualTicksForTesting(){
        if(!Boolean.getBoolean("mindustry.uiTest")) throw new SecurityException("Manual Shared Campaign ticks are test-only");
        return manualTicksForTesting;
    }

    public synchronized double tickManuallyForTesting(int count){
        if(!Boolean.getBoolean("mindustry.uiTest")) throw new SecurityException("Manual Shared Campaign ticks are test-only");
        if(!manualTicksForTesting) throw new IllegalStateException("Action is not in manual-tick mode: " + action.actionId);
        if(count < 1 || count > 100_000) throw new IllegalArgumentException("count must be in [1,100000]");
        for(int i = 0; i < count; i++) if(!tickInternal()) break;
        return context == null || context.state == null ? -1d : context.state.tick;
    }

    @Override public void failFromScheduler(Throwable error){
        failure = error;
        exitCode = 1;
        alive.set(false);
        Log.err("In-process Shared Campaign Sector failed: " + action.actionId, error);
        terminateGracefully();
    }

    public GameContext context(){ return context; }
    public Throwable failure(){ return failure; }
    public String diagnostics(){
        GameContext value = context;
        SharedActionAgent agent = SharedActionBootstrap.findAgent(value);
        String actionDiagnostics = agent == null ? "agent=null" : agent.bootstrapDiagnostics();
        return "runtime=" + action.actionId + ", alive=" + alive.get() + ", exitCode=" + exitCode + ", failure=" + failure + ", " + actionDiagnostics;
    }

    @Override public boolean parallelTickReady(){
        GameContext value = context;
        if(value == null) return false;
        SharedCampaignRuntimeState attached = SharedCampaignRuntimeState.find(value);
        return attached != null && attached.parallelTickReady();
    }

    @Override public Backend backend(){ return Backend.inProcess; }
    @Override public boolean isAlive(){ return alive.get() && context != null && !context.closed(); }
    @Override public int exitCode(){ return isAlive() ? Integer.MIN_VALUE : exitCode; }

    @Override public boolean injectClient(SocketChannel channel, byte[] preRead){
        GameContext value = context;
        if(channel == null || value == null || value.net == null || value.closed()) return false;
        boolean[] injected = {false};
        RuntimeContexts.run(value, () -> {
            try{
                value.net.injectExternalConnection(channel, preRead == null ? null : ByteBuffer.wrap(preRead));
                injected[0] = true;
            }catch(Throwable error){
                Log.warn("Failed to inject shared campaign client into @: @", action.actionId, error.toString());
                try{ channel.close(); }catch(IOException ignored){}
            }
        });
        return injected[0];
    }

    @Override public synchronized void crashForTesting(){
        scheduler.unregister(this);
        failure = new IllegalStateException("Injected Shared Campaign runtime crash for test");
        exitCode = 137;
        alive.set(false);
        GameContext value = context;
        if(value != null && !value.closed()) value.dispose();
    }

    @Override public synchronized void suppressHeartbeatsForTesting(){
        GameContext value = context;
        SharedActionAgent agent = SharedActionBootstrap.findAgent(value);
        if(value == null || value.closed() || agent == null) throw new IllegalStateException("Action agent is not available: " + action.actionId);
        RuntimeContexts.run(value, agent::suppressHeartbeatsForTesting);
    }

    @Override public synchronized void terminateGracefully(){
        scheduler.unregister(this);
        GameContext value = context;
        if(value != null && !value.closed()) value.dispose();
        if(exitCode == Integer.MIN_VALUE) exitCode = 0;
        alive.set(false);
    }
}
