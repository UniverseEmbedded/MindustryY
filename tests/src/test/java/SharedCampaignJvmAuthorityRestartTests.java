import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/** Product gate for an abrupt same-host authority JVM restart while two JVM_PROCESS Actions stay live. */
@Tag("shared-campaign-jvm-authority-restart")
public class SharedCampaignJvmAuthorityRestartTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(true);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
        System.setProperty(PackagedSectorLauncher.developmentFallbackProperty, "true");
        System.setProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty, "256");
    }

    @AfterAll static void resetLauncherProperties(){
        System.clearProperty(PackagedSectorLauncher.developmentFallbackProperty);
        System.clearProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty);
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void abruptAuthorityDeathReattachesTwoLiveChildrenAndPreservesIndependentRecovery() throws Exception{
        Path root = Files.createTempDirectory("shared-campaign-authority-restart-");
        Path campaign = root.resolve("campaign");
        Path ready = root.resolve("authority-ready.properties");
        Path helperLog = root.resolve("authority.log");
        int controlPort = freePort();
        int publicEntryPort = freePort();
        while(publicEntryPort == controlPort) publicEntryPort = freePort();

        Process authorityProcess = null;
        SharedCampaignService reopened = null;
        SharedCampaignClient owner = null;
        try{
            authorityProcess = launchAuthorityHarness(root, campaign, ready, helperLog, controlPort, publicEntryPort);
            Properties seeded = awaitProperties(ready, authorityProcess, Duration.ofSeconds(120));
            String serpuloId = required(seeded, "serpulo.actionId");
            String erekirId = required(seeded, "erekir.actionId");
            int serpuloPort = Integer.parseInt(required(seeded, "serpulo.port"));
            int erekirPort = Integer.parseInt(required(seeded, "erekir.port"));
            long serpuloIncarnation = Long.parseLong(required(seeded, "serpulo.incarnation"));
            long erekirIncarnation = Long.parseLong(required(seeded, "erekir.incarnation"));
            long authorityGeneration = Long.parseLong(required(seeded, "authority.generation"));
            assertEquals(2L, serpuloIncarnation, "harness must seed a durable save before the crash");
            assertEquals(2L, erekirIncarnation, "harness must seed a durable save before the crash");
            assertListening(serpuloPort, Duration.ofSeconds(10));
            assertListening(erekirPort, Duration.ofSeconds(10));

            // This is deliberately not SharedCampaignService.close(): SIGKILL the authority JVM and leave its child
            // sector JVMs untouched so their production reconnect loops experience a real control-plane disappearance.
            authorityProcess.destroyForcibly();
            assertTrue(authorityProcess.waitFor(15, TimeUnit.SECONDS), "authority JVM did not die after destroyForcibly");
            authorityProcess = null;
            assertListening(serpuloPort, Duration.ofSeconds(10));
            assertListening(erekirPort, Duration.ofSeconds(10));
            awaitPortReleased(controlPort, Duration.ofSeconds(10));
            awaitPortReleased(publicEntryPort, Duration.ofSeconds(10));

            reopened = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            reopened.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignState opened = reopened.openLocal(new Fi(campaign.toString()), "owner", "127.0.0.1", controlPort, publicEntryPort);
            assertEquals(authorityGeneration, opened.authorityGeneration, "same-host restart unexpectedly changed the authority generation");
            owner = reopened.controlClient();

            SharedCampaignService active = reopened;
            waitUntil(() -> heartbeatSeen(active, serpuloId, serpuloIncarnation) > 0L
                && heartbeatSeen(active, erekirId, erekirIncarnation) > 0L, Duration.ofSeconds(30));
            long erekirHeartbeatBeforePeerSuspend = heartbeatSeen(active, erekirId, erekirIncarnation);
            assertListening(serpuloPort, Duration.ofSeconds(10));
            assertListening(erekirPort, Duration.ofSeconds(10));

            SharedCampaignState adopted = owner.snapshot();
            assertEquals(ActionStatus.running, requireAction(adopted, serpuloId).status);
            assertEquals(ActionStatus.running, requireAction(adopted, erekirId).status);
            assertEquals(serpuloIncarnation, requireAction(adopted, serpuloId).runtimeIncarnation);
            assertEquals(erekirIncarnation, requireAction(adopted, erekirId).runtimeIncarnation);

            // Adopted children predate this coordinator instance, so they are control-plane-attached rather than
            // represented by a newly spawned SectorRuntime object. Suspension must still work through the live channel.
            assertNull(reopened.authority().coordinator().actionRuntimes().runtime(serpuloId));
            assertNull(reopened.authority().coordinator().actionRuntimes().runtime(erekirId));

            owner.suspendAction(serpuloId);
            SharedCampaignState oneSuspended = awaitStatus(owner, serpuloId, ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(serpuloPort, Duration.ofSeconds(15));
            assertEquals(ActionStatus.running, requireAction(oneSuspended, erekirId).status,
                "suspending an adopted Serpulo child stopped its adopted Erekir peer");
            waitUntil(() -> heartbeatSeen(active, erekirId, erekirIncarnation) > erekirHeartbeatBeforePeerSuspend,
                Duration.ofSeconds(15));
            assertListening(erekirPort, Duration.ofSeconds(10));
            assertFalse(requireAction(oneSuspended, serpuloId).lastSaveHash.isBlank(), "adopted Serpulo suspend did not commit a save");

            owner.suspendAction(erekirId);
            SharedCampaignState bothSuspended = awaitStatus(owner, erekirId, ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(erekirPort, Duration.ofSeconds(15));
            assertFalse(requireAction(bothSuspended, erekirId).lastSaveHash.isBlank(), "adopted Erekir suspend did not commit a save");

            RuntimePayloads.StartResult resumedSerpulo = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(resumedSerpulo.error(), "Serpulo post-restart resume failed");
            assertEquals(serpuloId, resumedSerpulo.actionId());
            SharedCampaignState serpuloRunning = awaitStatus(owner, serpuloId, ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(serpuloIncarnation + 1L, requireAction(serpuloRunning, serpuloId).runtimeIncarnation);
            assertEquals(erekirIncarnation, requireAction(serpuloRunning, erekirId).runtimeIncarnation,
                "resuming Serpulo changed suspended Erekir incarnation");

            RuntimePayloads.StartResult resumedErekir = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(resumedErekir.error(), "Erekir post-restart resume failed");
            assertEquals(erekirId, resumedErekir.actionId());
            SharedCampaignState bothResumed = awaitStatus(owner, erekirId, ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(serpuloIncarnation + 1L, requireAction(bothResumed, serpuloId).runtimeIncarnation);
            assertEquals(erekirIncarnation + 1L, requireAction(bothResumed, erekirId).runtimeIncarnation);
            assertListening(resumedSerpulo.port(), Duration.ofSeconds(10));
            assertListening(resumedErekir.port(), Duration.ofSeconds(10));

            owner.suspendAction(serpuloId);
            awaitStatus(owner, serpuloId, ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(resumedSerpulo.port(), Duration.ofSeconds(15));
            owner.suspendAction(erekirId);
            awaitStatus(owner, erekirId, ActionStatus.suspended, Duration.ofSeconds(45));
            awaitPortReleased(resumedErekir.port(), Duration.ofSeconds(15));
        }finally{
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            if(reopened != null) reopened.close();
            if(authorityProcess != null && authorityProcess.isAlive()) authorityProcess.destroyForcibly();
            killProcessesReferencing(root);
            deleteTree(root);
        }
    }

    @Test
    @Timeout(value = 360, unit = TimeUnit.SECONDS)
    void realPlayersKeepWorldsAdvancingAcrossAbruptAuthorityRestart() throws Exception{
        Path root = Files.createTempDirectory("shared-campaign-authority-player-restart-");
        Path campaign = root.resolve("campaign");
        Path ready = root.resolve("authority-ready.properties");
        Path playersReady = root.resolve("players-ready.properties");
        Path helperLog = root.resolve("authority.log");
        int controlPort = freePort();
        int publicEntryPort = freePort();
        while(publicEntryPort == controlPort) publicEntryPort = freePort();

        Process authorityProcess = null;
        SharedCampaignService reopened = null;
        SharedCampaignClient owner = null;
        SharedCampaignJvmConcurrentPlayersTests.RealArcClient serpuloClient = null, erekirClient = null;
        try{
            authorityProcess = launchAuthorityHarness(root, campaign, ready, playersReady, helperLog, controlPort, publicEntryPort);
            Properties seeded = awaitProperties(ready, authorityProcess, Duration.ofSeconds(120));
            String serpuloId = required(seeded, "serpulo.actionId");
            String erekirId = required(seeded, "erekir.actionId");
            int serpuloPort = Integer.parseInt(required(seeded, "serpulo.port"));
            int erekirPort = Integer.parseInt(required(seeded, "erekir.port"));
            long serpuloIncarnation = Long.parseLong(required(seeded, "serpulo.incarnation"));
            long erekirIncarnation = Long.parseLong(required(seeded, "erekir.incarnation"));
            long authorityGeneration = Long.parseLong(required(seeded, "authority.generation"));
            String serpuloMember = required(seeded, "serpulo.memberId");
            String erekirMember = required(seeded, "erekir.memberId");
            String serpuloUuid = required(seeded, "serpulo.uuid");
            String erekirUuid = required(seeded, "erekir.uuid");

            serpuloClient = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Authority restart Serpulo", serpuloUuid, "127.0.0.1", serpuloPort);
            erekirClient = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Authority restart Erekir", erekirUuid, "127.0.0.1", erekirPort);

            Properties live = awaitProperties(playersReady, authorityProcess, Duration.ofSeconds(45));
            long serpuloTickBeforeCrash = Long.parseLong(required(live, "serpulo.tick"));
            long erekirTickBeforeCrash = Long.parseLong(required(live, "erekir.tick"));
            assertTrue(serpuloTickBeforeCrash > 0L);
            assertTrue(erekirTickBeforeCrash > 0L);

            authorityProcess.destroyForcibly();
            assertTrue(authorityProcess.waitFor(15, TimeUnit.SECONDS), "authority JVM did not die after destroyForcibly");
            authorityProcess = null;
            awaitPortReleased(controlPort, Duration.ofSeconds(10));
            awaitPortReleased(publicEntryPort, Duration.ofSeconds(10));
            assertListening(serpuloPort, Duration.ofSeconds(10));
            assertListening(erekirPort, Duration.ofSeconds(10));

            // Give the orphaned live worlds time to advance with their existing players while no authority exists.
            Thread.sleep(2_000L);
            assertTrue(serpuloClient.connected(), "Serpulo player disconnected when only the authority died");
            assertTrue(erekirClient.connected(), "Erekir player disconnected when only the authority died");

            reopened = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            reopened.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignState opened = reopened.openLocal(new Fi(campaign.toString()), "owner", "127.0.0.1", controlPort, publicEntryPort);
            assertEquals(authorityGeneration, opened.authorityGeneration, "same-host restart unexpectedly changed authority generation");
            owner = reopened.controlClient();

            SharedCampaignService active = reopened;
            waitUntil(() -> heartbeatSeen(active, serpuloId, serpuloIncarnation) > 0L
                && heartbeatSeen(active, erekirId, erekirIncarnation) > 0L, Duration.ofSeconds(30));
            SharedCampaignState adopted = awaitLivePlayers(owner, serpuloId, serpuloMember, erekirId, erekirMember, Duration.ofSeconds(30));
            ActionState serpuloAdopted = requireAction(adopted, serpuloId);
            ActionState erekirAdopted = requireAction(adopted, erekirId);
            assertEquals(serpuloIncarnation, serpuloAdopted.runtimeIncarnation);
            assertEquals(erekirIncarnation, erekirAdopted.runtimeIncarnation);
            assertTrue(serpuloAdopted.actionTick > serpuloTickBeforeCrash,
                "Serpulo world did not advance while authority was absent");
            assertTrue(erekirAdopted.actionTick > erekirTickBeforeCrash,
                "Erekir world did not advance while authority was absent");

            long serpuloAfterAdopt = serpuloAdopted.actionTick;
            long erekirAfterAdopt = erekirAdopted.actionTick;
            SharedCampaignState advancing = awaitTicks(owner, serpuloId, serpuloAfterAdopt + 20L,
                erekirId, erekirAfterAdopt + 20L, Duration.ofSeconds(20));
            assertEquals(1, requireAction(advancing, serpuloId).connectedPlayers);
            assertEquals(1, requireAction(advancing, erekirId).connectedPlayers);

            // Management still owns the adopted child after restart, but the normal product invariant remains intact:
            // an Action with a live player cannot be suspended out from under them.
            SharedCampaignClient adoptedOwner = owner;
            IOException occupiedSuspend = assertThrows(IOException.class, () -> adoptedOwner.suspendAction(serpuloId));
            assertTrue(occupiedSuspend.getMessage().contains("players are present"), occupiedSuspend.getMessage());

            // Once that player leaves, the adopted child can be suspended without disturbing its live peer.
            long erekirBeforePeerSuspend = requireAction(advancing, erekirId).actionTick;
            serpuloClient.close();
            serpuloClient = null;
            awaitPlayerCounts(owner, serpuloId, 0, erekirId, 1, Duration.ofSeconds(30));
            owner.suspendAction(serpuloId);
            awaitStatus(owner, serpuloId, ActionStatus.suspended, Duration.ofSeconds(45));
            SharedCampaignState peerAdvanced = awaitActionTick(owner, erekirId, erekirBeforePeerSuspend + 20L, Duration.ofSeconds(20));
            assertEquals(1, requireAction(peerAdvanced, erekirId).connectedPlayers,
                "suspending adopted Serpulo child dropped Erekir's live player");
            assertTrue(erekirClient.connected(), "Erekir client disconnected during peer suspend after authority restart");

            erekirClient.close();
            erekirClient = null;
            awaitPlayerCounts(owner, serpuloId, 0, erekirId, 0, Duration.ofSeconds(30));
            owner.suspendAction(erekirId);
            awaitStatus(owner, erekirId, ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(serpuloClient != null) try{ serpuloClient.close(); }catch(Throwable ignored){}
            if(erekirClient != null) try{ erekirClient.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            if(reopened != null) reopened.close();
            if(authorityProcess != null && authorityProcess.isAlive()) authorityProcess.destroyForcibly();
            killProcessesReferencing(root);
            deleteTree(root);
        }
    }

    /** Separate authority process so the test can crash the host without running any close handlers. */
    public static final class AuthorityHarness{
        public static void main(String[] args) throws Exception{
            if(args.length != 5 && args.length != 6) throw new IllegalArgumentException("usage: <root> <campaign> <ready> <controlPort> <publicEntryPort> [playersReady]");
            Path root = Path.of(args[0]);
            Path campaign = Path.of(args[1]);
            Path ready = Path.of(args[2]);
            int controlPort = Integer.parseInt(args[3]);
            int publicEntryPort = Integer.parseInt(args[4]);
            Path playersReady = args.length == 6 ? Path.of(args[5]) : null;

            ApplicationTests.launchApplication(true);
            if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
            System.setProperty(PackagedSectorLauncher.developmentFallbackProperty, "true");
            System.setProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty, "256");

            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Abrupt authority restart gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(campaign.toString()), options, "127.0.0.1", controlPort, publicEntryPort);
            SharedCampaignClient owner = service.controlClient();

            RuntimePayloads.StartResult serpulo = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertHarnessBlank(serpulo.error(), "Serpulo seed start failed");
            awaitHarnessStatus(owner, serpulo.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            owner.suspendAction(serpulo.actionId());
            awaitHarnessStatus(owner, serpulo.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            RuntimePayloads.StartResult serpuloResumed = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertHarnessBlank(serpuloResumed.error(), "Serpulo seed resume failed");
            awaitHarnessStatus(owner, serpulo.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            RuntimePayloads.StartResult erekir = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertHarnessBlank(erekir.error(), "Erekir seed start failed");
            awaitHarnessStatus(owner, erekir.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            owner.suspendAction(erekir.actionId());
            awaitHarnessStatus(owner, erekir.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            RuntimePayloads.StartResult erekirResumed = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertHarnessBlank(erekirResumed.error(), "Erekir seed resume failed");
            SharedCampaignState bothRunning = awaitHarnessStatus(owner, erekir.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            bothRunning = awaitHarnessStatus(owner, serpulo.actionId(), ActionStatus.running, Duration.ofSeconds(15));

            Properties properties = new Properties();
            properties.setProperty("serpulo.actionId", serpulo.actionId());
            properties.setProperty("serpulo.port", Integer.toString(requireHarnessAction(bothRunning, serpulo.actionId()).port));
            properties.setProperty("serpulo.incarnation", Long.toString(requireHarnessAction(bothRunning, serpulo.actionId()).runtimeIncarnation));
            properties.setProperty("erekir.actionId", erekir.actionId());
            properties.setProperty("erekir.port", Integer.toString(requireHarnessAction(bothRunning, erekir.actionId()).port));
            properties.setProperty("erekir.incarnation", Long.toString(requireHarnessAction(bothRunning, erekir.actionId()).runtimeIncarnation));
            properties.setProperty("authority.generation", Long.toString(bothRunning.authorityGeneration));

            CoordinatorCredentials.MemberCredential serpuloMember = null, erekirMember = null;
            String serpuloUuid = null, erekirUuid = null;
            if(playersReady != null){
                SharedCampaignCoordinator coordinator = service.authority().coordinator();
                serpuloMember = coordinator.clientControl().enrollTrustedLocal("Authority restart Serpulo");
                erekirMember = coordinator.clientControl().enrollTrustedLocal("Authority restart Erekir");
                serpuloUuid = platformUuid(181);
                erekirUuid = platformUuid(182);
                RuntimePayloads.VanillaTransferResult serpuloGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                    serpuloMember.memberId(), serpulo.actionId(), serpuloUuid, "127.0.0.1", false);
                RuntimePayloads.VanillaTransferResult erekirGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                    erekirMember.memberId(), erekir.actionId(), erekirUuid, "127.0.0.1", false);
                assertHarnessBlank(serpuloGrant.error(), "Serpulo player admission failed");
                assertHarnessBlank(erekirGrant.error(), "Erekir player admission failed");
                properties.setProperty("serpulo.memberId", serpuloMember.memberId());
                properties.setProperty("serpulo.uuid", serpuloUuid);
                properties.setProperty("erekir.memberId", erekirMember.memberId());
                properties.setProperty("erekir.uuid", erekirUuid);
            }
            Path temporary = ready.resolveSibling(ready.getFileName() + ".tmp");
            try(OutputStream out = Files.newOutputStream(temporary)){ properties.store(out, "authority ready"); }
            Files.move(temporary, ready, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            if(playersReady != null){
                String finalSerpuloMember = serpuloMember.memberId(), finalErekirMember = erekirMember.memberId();
                SharedCampaignState players = awaitHarnessPlayers(owner, serpulo.actionId(), finalSerpuloMember,
                    erekir.actionId(), finalErekirMember, Duration.ofSeconds(45));
                // Require some actual simulation progress before publishing the pre-crash watermark.
                long serpuloTick = requireHarnessAction(players, serpulo.actionId()).actionTick;
                long erekirTick = requireHarnessAction(players, erekir.actionId()).actionTick;
                players = awaitHarnessTicks(owner, serpulo.actionId(), serpuloTick + 20L,
                    erekir.actionId(), erekirTick + 20L, Duration.ofSeconds(20));
                Properties playerProperties = new Properties();
                playerProperties.setProperty("serpulo.tick", Long.toString(requireHarnessAction(players, serpulo.actionId()).actionTick));
                playerProperties.setProperty("erekir.tick", Long.toString(requireHarnessAction(players, erekir.actionId()).actionTick));
                Path playerTemporary = playersReady.resolveSibling(playersReady.getFileName() + ".tmp");
                try(OutputStream out = Files.newOutputStream(playerTemporary)){ playerProperties.store(out, "players ready"); }
                Files.move(playerTemporary, playersReady, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }

            // Parent deliberately SIGKILLs this process. Never close the service here: that would gracefully terminate
            // the child Action JVMs and would not test authority crash/reconnect semantics.
            while(true) Thread.sleep(60_000L);
        }
    }

    private static Process launchAuthorityHarness(Path root, Path campaign, Path ready, Path log, int controlPort, int publicEntryPort) throws IOException{
        return launchAuthorityHarness(root, campaign, ready, null, log, controlPort, publicEntryPort);
    }

    private static Process launchAuthorityHarness(Path root, Path campaign, Path ready, Path playersReady, Path log, int controlPort, int publicEntryPort) throws IOException{
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        ArrayList<String> command = new ArrayList<>(List.of(java, "-Xms128m", "-Xmx768m", "-cp", absoluteClasspath(),
            AuthorityHarness.class.getName(), root.toString(), campaign.toString(), ready.toString(), Integer.toString(controlPort), Integer.toString(publicEntryPort)));
        if(playersReady != null) command.add(playersReady.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Path.of(System.getProperty("user.dir", ".")).toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        return builder.start();
    }

    private static String absoluteClasspath(){
        String base = System.getProperty("user.dir", ".");
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for(String entry : System.getProperty("java.class.path", "").split(java.util.regex.Pattern.quote(File.pathSeparator), -1)){
            if(entry.isBlank()) continue;
            Path path = Path.of(entry);
            entries.add(path.isAbsolute() ? path.normalize().toString() : Path.of(base).resolve(path).normalize().toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    private static Properties awaitProperties(Path path, Process process, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(Files.isRegularFile(path)){
                Properties result = new Properties();
                try(InputStream in = Files.newInputStream(path)){ result.load(in); }
                return result;
            }
            if(!process.isAlive()) fail("authority harness exited before becoming ready; exit=" + process.exitValue());
            Thread.sleep(100L);
        }
        fail("authority harness did not become ready within " + timeout);
        return null;
    }

    private static long heartbeatSeen(SharedCampaignService service, String actionId, long incarnation){
        return service.authority().coordinator().actionRuntimes().controlPlane().lastHeartbeatSeen(actionId, incarnation);
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) fail("Action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(50L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("Action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
        return last;
    }

    private static SharedCampaignState awaitHarnessStatus(SharedCampaignClient client, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) throw new IllegalStateException("Action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(50L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        throw new IllegalStateException("Action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
    }

    private static SharedCampaignState awaitLivePlayers(SharedCampaignClient client, String actionA, String memberA,
                                                          String actionB, String memberB, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
                if(a != null && b != null && a.connectedPlayers == 1 && b.connectedPlayers == 1
                    && a.participants.contains(memberA) && b.participants.contains(memberB)){
                    found[0] = state;
                    return true;
                }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitTicks(SharedCampaignClient client, String actionA, long tickA,
                                                   String actionB, long tickB, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
                if(a != null && b != null && a.actionTick >= tickA && b.actionTick >= tickB){ found[0] = state; return true; }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitPlayerCounts(SharedCampaignClient client, String actionA, int playersA,
                                                           String actionB, int playersB, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
                if(a != null && b != null && a.connectedPlayers == playersA && b.connectedPlayers == playersB){
                    found[0] = state;
                    return true;
                }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitActionTick(SharedCampaignClient client, String actionId, long tick, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState action = state.actions.get(actionId);
                if(action != null && action.actionTick >= tick){ found[0] = state; return true; }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitHarnessPlayers(SharedCampaignClient client, String actionA, String memberA,
                                                             String actionB, String memberB, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            SharedCampaignState state = client.snapshot();
            ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
            if(a != null && b != null && a.connectedPlayers == 1 && b.connectedPlayers == 1
                && a.participants.contains(memberA) && b.participants.contains(memberB)) return state;
            Thread.sleep(50L);
        }
        throw new IllegalStateException("real players did not appear in both Actions within " + timeout);
    }

    private static SharedCampaignState awaitHarnessTicks(SharedCampaignClient client, String actionA, long tickA,
                                                           String actionB, long tickB, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            SharedCampaignState state = client.snapshot();
            ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
            if(a != null && b != null && a.actionTick >= tickA && b.actionTick >= tickB) return state;
            Thread.sleep(50L);
        }
        throw new IllegalStateException("real-player worlds did not advance within " + timeout);
    }

    private static String platformUuid(int marker){
        byte[] seed = new byte[8]; seed[7] = (byte)marker;
        return new String(arc.util.serialization.Base64Coder.encode(seed));
    }

    private static ActionState requireAction(SharedCampaignState state, String actionId){
        ActionState action = state.actions.get(actionId);
        assertNotNull(action, "Action missing from authority snapshot: " + actionId);
        return action;
    }

    private static ActionState requireHarnessAction(SharedCampaignState state, String actionId){
        ActionState action = state.actions.get(actionId);
        if(action == null) throw new IllegalStateException("Action missing from authority snapshot: " + actionId);
        return action;
    }

    private static String required(Properties properties, String key){
        String value = properties.getProperty(key, "").trim();
        assertFalse(value.isBlank(), "missing helper property " + key);
        return value;
    }

    private static void assertBlank(String value, String message){ assertTrue(value == null || value.isBlank(), () -> message + ": " + value); }
    private static void assertHarnessBlank(String value, String message){ if(value != null && !value.isBlank()) throw new IllegalStateException(message + ": " + value); }

    private static int freePort() throws IOException{
        try(ServerSocket socket = new ServerSocket(0)){ socket.setReuseAddress(true); return socket.getLocalPort(); }
    }

    private static void assertListening(int port, Duration timeout) throws Exception{
        waitUntil(() -> canConnect(port), timeout);
    }

    private static void awaitPortReleased(int port, Duration timeout) throws Exception{
        waitUntil(() -> !canConnect(port), timeout);
    }

    private static boolean canConnect(int port){
        try(Socket socket = new Socket()){
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200);
            return true;
        }catch(IOException ignored){ return false; }
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(condition.getAsBoolean()) return;
            Thread.sleep(50L);
        }
        fail("condition not met within " + timeout);
    }

    private static void killProcessesReferencing(Path root){
        String marker = root.toAbsolutePath().normalize().toString();
        ProcessHandle.allProcesses().forEach(handle -> {
            if(handle.pid() == ProcessHandle.current().pid()) return;
            String arguments = String.join(" ", handle.info().arguments().orElse(new String[0]));
            if(arguments.contains(marker)){
                try{ handle.destroyForcibly(); }catch(Throwable ignored){}
            }
        });
    }

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths = Files.walk(root)){
            for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
