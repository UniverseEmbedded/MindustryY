package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.campaign.shared.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** One isolated, full Mindustry server process hosting a real-time campaign action. */
public class ActionProcess implements SectorRuntime{
    private final SharedCampaignState.ActionState action;
    private final Fi directory;
    private final String coordinatorHost;
    private final int coordinatorPort;
    private final String controlSecret;
    private final String joinSecret;
    private final Fi modsSource;
    private final SharedCampaignState.PersistenceProfile persistenceProfile;
    private Process process;

    public ActionProcess(SharedCampaignState.ActionState action, Fi directory, String coordinatorHost, int coordinatorPort, String controlSecret, String joinSecret, Fi modsSource, SharedCampaignState.PersistenceProfile persistenceProfile){
        this.action = action;
        this.directory = directory;
        this.coordinatorHost = coordinatorHost;
        this.coordinatorPort = coordinatorPort;
        this.controlSecret = controlSecret;
        this.joinSecret = joinSecret;
        this.modsSource = modsSource;
        this.persistenceProfile = persistenceProfile == null ? SharedCampaignState.PersistenceProfile.lowFrequencyWal : persistenceProfile;
    }

    @Override
    public synchronized void start(Fi sourceSave) throws IOException{
        if(process != null && process.isAlive()) throw new IllegalStateException("Action already running: " + action.actionId);
        directory.mkdirs();
        Fi saveDir = directory.child("config/saves"); saveDir.mkdirs();
        SectorRuntimeFactory.synchronizeMods(directory.child("config/mods"), modsSource);
        Fi target = saveDir.child("action." + Vars.saveExtension);
        if(sourceSave != null && sourceSave.exists()){
            Path sourcePath = sourceSave.file().toPath().toAbsolutePath().normalize();
            Path targetPath = target.file().toPath().toAbsolutePath().normalize();
            if(!sourcePath.equals(targetPath)) Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        directory.child("config/action-summary.bin").writeBytes(RuntimePayloads.encodeSummary(action.summary), false);
        directory.child("config/action-launch.bin").writeBytes(RuntimePayloads.encode(new RuntimePayloads.LaunchPlan(action.launchOriginSector, action.launchLoadout, action.launchResources)), false);

        Fi descriptorFile = directory.child("config/action-runtime.bin");
        // A non-empty launch loadout is authoritative proof that this Action must bootstrap a fresh world. A retained
        // source save may still be present for vanilla lost-sector reconstruction and must not be loaded as a resume.
        boolean bootstrapFreshWorld = action.launchLoadout != null && !action.launchLoadout.isBlank();
        int autosaveSeconds = SharedCampaignPersistence.policy(persistenceProfile).actionAutosaveSeconds();
        String baseCommand = "config port " + action.port + ",config autoPause true,config autosave true,config autosaveSpacing " + autosaveSeconds;
        String serverCommand = bootstrapFreshWorld ? baseCommand : baseCommand + ",load action";
        ActionRuntimeDescriptor descriptor = new ActionRuntimeDescriptor(
            action.actionId, coordinatorHost, coordinatorPort, action.hostGeneration, action.runtimeIncarnation,
            "action", directory.child("config/action-summary.bin").absolutePath(), directory.child("config/action-launch.bin").absolutePath(),
            action.planetName, action.sectorName, action.attemptId, action.port, bootstrapFreshWorld,
            directory.child("config/research-reservations.bin").absolutePath(),
            directory.child("config/transport-reservations.bin").absolutePath(), autosaveSeconds, serverCommand
        );
        descriptor.write(descriptorFile);
        Seq<String> command = PackagedSectorLauncher.command(descriptorFile);

        ProcessBuilder builder = new ProcessBuilder(command.toArray(String.class));
        builder.environment().put(ActionRuntimeConfig.actionControlSecretEnvironment, controlSecret);
        builder.environment().put(ActionRuntimeConfig.actionJoinSecretEnvironment, joinSecret);
        builder.directory(directory.file());
        builder.redirectErrorStream(true);
        File logFile = directory.child("action.log").file();
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        process = builder.start();
    }


    @Override
    public Backend backend(){ return Backend.jvmProcess; }

    @Override
    public synchronized boolean isAlive(){ return process != null && process.isAlive(); }
    @Override
    public synchronized int exitCode(){ return process == null || process.isAlive() ? Integer.MIN_VALUE : process.exitValue(); }

    @Override
    public synchronized void crashForTesting(){
        if(process != null && process.isAlive()) process.destroyForcibly();
    }

    @Override
    public synchronized void terminateGracefully(){
        if(process == null || !process.isAlive()) return;
        process.destroy();
        try{
            if(!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }


    @Override public void close(){ terminateGracefully(); }
}
