import arc.files.*;
import arc.net.*;
import arc.struct.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.net.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.net.ArcNetProvider.*;
import mindustry.net.Packets.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real ArcNet + MYCS product gate proving one external TCP can move A -> B -> A across JVM_PROCESS Actions. */
@Tag("shared-campaign-jvm-multi-action")
public class SharedCampaignJvmSameTcpPlayersTests{
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
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void newlyEnrolledMemberCanEnhancedJoinAlreadyRunningAction() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-late-enrollment-enhanced-join-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null, memberClient = null;
        RealSharedEntryClient player = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Late enrollment enhanced join gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 1;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(ground.error(), "Ground Zero start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential member = coordinator.clientControl().enrollTrustedLocal("Late enhanced member");
            memberClient = new SharedCampaignClient("127.0.0.1", coordinator.publicEntryPort(),
                new SharedCampaignClient.Credential(member.memberId(), member.secret()));

            String networkUuid = platformUuid(119);
            RuntimePayloads.JoinResult join = memberClient.joinAction(ground.actionId(), false, networkUuid);
            assertBlank(join.error(), "late-enrolled enhanced join failed");
            player = RealSharedEntryClient.connect("Late enhanced member", networkUuid, member.memberId(),
                ground.actionId(), join.joinToken(), join.host(), join.port());

            final SharedCampaignClient snapshotClient = owner;
            waitUntil(() -> {
                try{
                    ActionState action = requireAction(snapshotClient.snapshot(), ground.actionId());
                    return action.connectedPlayers == 1 && action.participants.contains(member.memberId());
                }catch(Throwable ignored){ return false; }
            }, Duration.ofSeconds(30));
            long before = requireAction(owner.snapshot(), ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), before + 20L, Duration.ofSeconds(20));
        }finally{
            if(player != null) player.close();
            if(memberClient != null) memberClient.close();
            if(owner != null) owner.close();
            service.close();
        }
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void realArcNetPlayerHotSwitchesOnSameTcpGroundOnsetGround() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-same-tcp-player-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RealSharedEntryClient player = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM same-TCP real-player gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            RuntimePayloads.StartResult onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(ground.error(), "Ground Zero start failed");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            String networkUuid = platformUuid(121);
            RuntimePayloads.JoinResult join = owner.joinAction(ground.actionId(), false, networkUuid);
            assertBlank(join.error(), "initial enhanced join failed");
            player = RealSharedEntryClient.connect("Same TCP traveler", networkUuid, owner.memberId(),
                ground.actionId(), join.joinToken(), join.host(), join.port());
            SharedCampaignState onGround = awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));
            long groundTick = requireAction(onGround, ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), groundTick + 30L, Duration.ofSeconds(20));

            ActionSessionBroker broker = service.authority().coordinator().entryRouter().broker();
            ActionSessionBroker.Session session = awaitSingleSession(broker, owner.memberId(), ground.actionId(), Duration.ofSeconds(10));
            String sessionId = session.sessionId;
            assertEquals(1, broker.liveSessionCount());
            assertEquals(1, broker.liveRelayCount());
            SocketChannel preserved = player.detach();
            CompletableFuture<Boolean> barrier = synchronizeBarrierAsync(preserved,
                ActionSessionHandshake.hotSwitchBarrier(sessionId, ground.actionId(), onset.actionId()));
            RuntimePayloads.HotSwitchResult toOnset = owner.hotSwitchAction(sessionId, ground.actionId(), onset.actionId(), false, networkUuid);
            assertBlank(toOnset.error(), "Ground -> Onset hot-switch prepare failed");
            assertTrue(toOnset.sameTcp(), "Ground -> Onset unexpectedly fell back to ordinary join");
            assertTrue(barrier.get(10, TimeUnit.SECONDS), "Ground -> Onset stream barrier failed");
            assertTrue(owner.resumeHotSwitch(sessionId), "Ground -> Onset broker resume failed");
            player.prepareRebind(onset.actionId(), toOnset.joinToken(), owner.memberId(), sessionId);
            player.rebind(preserved);

            SharedCampaignState onOnset = awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 1, Duration.ofSeconds(30));
            long onsetTick = requireAction(onOnset, onset.actionId()).actionTick;
            awaitSingleTick(owner, onset.actionId(), onsetTick + 30L, Duration.ofSeconds(20));
            ActionSessionBroker.Session afterFirst = broker.session(sessionId);
            assertNotNull(afterFirst);
            assertEquals(onset.actionId(), afterFirst.actionId);
            assertEquals(1, afterFirst.switchCount);
            assertEquals(1, broker.liveSessionCount());
            assertEquals(1, broker.liveRelayCount());

            SocketChannel preservedAgain = player.detach();
            assertSame(preserved, preservedAgain, "hot-switch replaced the external client TCP channel");
            CompletableFuture<Boolean> returnBarrier = synchronizeBarrierAsync(preservedAgain,
                ActionSessionHandshake.hotSwitchBarrier(sessionId, onset.actionId(), ground.actionId()));
            RuntimePayloads.HotSwitchResult toGround = owner.hotSwitchAction(sessionId, onset.actionId(), ground.actionId(), false, networkUuid);
            assertBlank(toGround.error(), "Onset -> Ground hot-switch prepare failed");
            assertTrue(toGround.sameTcp(), "Onset -> Ground unexpectedly fell back to ordinary join");
            assertTrue(returnBarrier.get(10, TimeUnit.SECONDS), "Onset -> Ground stream barrier failed");
            assertTrue(owner.resumeHotSwitch(sessionId), "Onset -> Ground broker resume failed");
            player.prepareRebind(ground.actionId(), toGround.joinToken(), owner.memberId(), sessionId);
            player.rebind(preservedAgain);

            SharedCampaignState returned = awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));
            long returnedTick = requireAction(returned, ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), returnedTick + 30L, Duration.ofSeconds(20));
            ActionSessionBroker.Session afterReturn = broker.session(sessionId);
            assertNotNull(afterReturn);
            assertEquals(ground.actionId(), afterReturn.actionId);
            assertEquals(2, afterReturn.switchCount);
            assertEquals(1, broker.liveSessionCount());
            assertEquals(1, broker.liveRelayCount());

            player.close();
            player = null;
            awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));
            waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, Duration.ofSeconds(10));

            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(player != null) try{ player.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }



    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void targetCrashAfterBarrierFailsClosedAndFreshSameTcpSessionCanRecover() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-same-tcp-target-crash-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RealSharedEntryClient player = null;
        SocketChannel preserved = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM same-TCP target crash gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            RuntimePayloads.StartResult onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(ground.error(), "Ground Zero start failed");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            // Give the destination a real authoritative recovery point before injecting the switch-window crash.
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            RuntimePayloads.StartResult seededOnset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(seededOnset.error(), "Onset seeded resume failed");
            assertEquals(onset.actionId(), seededOnset.actionId());
            SharedCampaignState seeded = awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(seeded, onset.actionId()).runtimeIncarnation);

            String networkUuid = platformUuid(131);
            RuntimePayloads.JoinResult join = owner.joinAction(ground.actionId(), false, networkUuid);
            assertBlank(join.error(), "initial Ground Zero join failed");
            player = RealSharedEntryClient.connect("Crash-window traveler", networkUuid, owner.memberId(),
                ground.actionId(), join.joinToken(), join.host(), join.port());
            SharedCampaignState onGround = awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));
            long groundTick = requireAction(onGround, ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), groundTick + 30L, Duration.ofSeconds(20));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            ActionSessionBroker broker = coordinator.entryRouter().broker();
            ActionSessionBroker.Session initial = awaitSingleSession(broker, owner.memberId(), ground.actionId(), Duration.ofSeconds(10));
            String doomedSessionId = initial.sessionId;
            preserved = player.detach();
            CompletableFuture<Boolean> barrier = synchronizeBarrierAsync(preserved,
                ActionSessionHandshake.hotSwitchBarrier(doomedSessionId, ground.actionId(), onset.actionId()));
            RuntimePayloads.HotSwitchResult switching = owner.hotSwitchAction(
                doomedSessionId, ground.actionId(), onset.actionId(), false, networkUuid);
            assertBlank(switching.error(), "crash-window hot-switch prepare failed");
            assertTrue(switching.sameTcp(), "crash-window switch unexpectedly fell back to ordinary join");
            assertTrue(barrier.get(10, TimeUnit.SECONDS), "crash-window MYHB barrier failed");

            SectorRuntime targetRuntime = coordinator.actionRuntimes().runtime(onset.actionId());
            assertNotNull(targetRuntime, "target JVM runtime disappeared before crash injection");
            targetRuntime.crashForTesting();
            SharedCampaignState recovered = awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(recovered, onset.actionId()).runtimeIncarnation,
                "crashed destination unexpectedly restarted before explicit resume");
            waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, Duration.ofSeconds(15));
            assertNull(broker.session(doomedSessionId), "target route loss did not fail-close the switching session");
            assertFalse(owner.resumeHotSwitch(doomedSessionId), "closed crash-window session remained resumable");
            assertTrue(awaitEof(preserved, Duration.ofSeconds(10)), "preserved client TCP did not close after target route loss");
            preserved.close();
            preserved = null;
            player.close();
            player = null;
            awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));

            RuntimePayloads.StartResult liveAgain = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(liveAgain.error(), "Onset recovery resume failed");
            assertEquals(onset.actionId(), liveAgain.actionId());
            SharedCampaignState liveState = awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(3L, requireAction(liveState, onset.actionId()).runtimeIncarnation);

            RuntimePayloads.JoinResult freshJoin = owner.joinAction(ground.actionId(), false, networkUuid);
            assertBlank(freshJoin.error(), "fresh Ground Zero join failed after target crash");
            player = RealSharedEntryClient.connect("Crash-window traveler", networkUuid, owner.memberId(),
                ground.actionId(), freshJoin.joinToken(), freshJoin.host(), freshJoin.port());
            awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));
            ActionSessionBroker.Session fresh = awaitSingleSession(broker, owner.memberId(), ground.actionId(), Duration.ofSeconds(10));
            assertNotEquals(doomedSessionId, fresh.sessionId, "fresh shared-entry connection reused the failed session id");

            preserved = player.detach();
            CompletableFuture<Boolean> recoveryBarrier = synchronizeBarrierAsync(preserved,
                ActionSessionHandshake.hotSwitchBarrier(fresh.sessionId, ground.actionId(), onset.actionId()));
            RuntimePayloads.HotSwitchResult recoverySwitch = owner.hotSwitchAction(
                fresh.sessionId, ground.actionId(), onset.actionId(), false, networkUuid);
            assertBlank(recoverySwitch.error(), "post-recovery hot-switch prepare failed");
            assertTrue(recoverySwitch.sameTcp(), "post-recovery switch lost same-TCP capability");
            assertTrue(recoveryBarrier.get(10, TimeUnit.SECONDS), "post-recovery MYHB barrier failed");
            assertTrue(owner.resumeHotSwitch(fresh.sessionId), "post-recovery relay could not resume");
            player.prepareRebind(onset.actionId(), recoverySwitch.joinToken(), owner.memberId(), fresh.sessionId);
            player.rebind(preserved);
            preserved = null; // the ArcNet client owns the rebound channel again.

            SharedCampaignState onRecoveredTarget = awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 1, Duration.ofSeconds(30));
            long recoveredTick = requireAction(onRecoveredTarget, onset.actionId()).actionTick;
            awaitSingleTick(owner, onset.actionId(), recoveredTick + 30L, Duration.ofSeconds(20));
            ActionSessionBroker.Session rebound = broker.session(fresh.sessionId);
            assertNotNull(rebound);
            assertEquals(onset.actionId(), rebound.actionId);
            assertEquals(1, rebound.switchCount);
            assertEquals(1, broker.liveSessionCount());
            assertEquals(1, broker.liveRelayCount());

            player.close();
            player = null;
            awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));
            waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, Duration.ofSeconds(10));
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(preserved != null) try{ preserved.close(); }catch(IOException ignored){}
            if(player != null) try{ player.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }


    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void sourceCrashAfterBarrierStillResumesDestinationAndAllowsSameTcpReturn() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-same-tcp-source-crash-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RealSharedEntryClient player = null;
        SocketChannel detached = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM same-TCP source crash gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            RuntimePayloads.StartResult onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(ground.error(), "Ground Zero start failed");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            // Seed the source so its injected crash has a deterministic authoritative recovery point.
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            RuntimePayloads.StartResult seededGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(seededGround.error(), "Ground Zero seeded resume failed");
            assertEquals(ground.actionId(), seededGround.actionId());
            SharedCampaignState seeded = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(seeded, ground.actionId()).runtimeIncarnation);

            String networkUuid = platformUuid(141);
            RuntimePayloads.JoinResult join = owner.joinAction(ground.actionId(), false, networkUuid);
            assertBlank(join.error(), "initial Ground Zero join failed");
            player = RealSharedEntryClient.connect("Source-crash traveler", networkUuid, owner.memberId(),
                ground.actionId(), join.joinToken(), join.host(), join.port());
            SharedCampaignState onGround = awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));
            long groundTick = requireAction(onGround, ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), groundTick + 30L, Duration.ofSeconds(20));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            ActionSessionBroker broker = coordinator.entryRouter().broker();
            ActionSessionBroker.Session session = awaitSingleSession(broker, owner.memberId(), ground.actionId(), Duration.ofSeconds(10));
            String sessionId = session.sessionId;
            detached = player.detach();
            SocketChannel physical = detached;
            CompletableFuture<Boolean> barrier = synchronizeBarrierAsync(detached,
                ActionSessionHandshake.hotSwitchBarrier(sessionId, ground.actionId(), onset.actionId()));
            RuntimePayloads.HotSwitchResult switching = owner.hotSwitchAction(
                sessionId, ground.actionId(), onset.actionId(), false, networkUuid);
            assertBlank(switching.error(), "source-crash hot-switch prepare failed");
            assertTrue(switching.sameTcp(), "source-crash switch unexpectedly fell back to ordinary join");
            assertTrue(barrier.get(10, TimeUnit.SECONDS), "source-crash MYHB barrier failed");

            SectorRuntime sourceRuntime = coordinator.actionRuntimes().runtime(ground.actionId());
            assertNotNull(sourceRuntime, "source JVM runtime disappeared before crash injection");
            sourceRuntime.crashForTesting();
            SharedCampaignState sourceSuspended = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(sourceSuspended, ground.actionId()).runtimeIncarnation);
            ActionSessionBroker.Session switchingSession = broker.session(sessionId);
            assertNotNull(switchingSession, "source route loss incorrectly closed a session already owned by the destination");
            assertEquals(ActionSessionBroker.SessionState.switching, switchingSession.state);
            assertEquals(onset.actionId(), switchingSession.actionId);
            assertEquals(1, broker.liveSessionCount());
            assertEquals(1, broker.liveRelayCount());

            assertTrue(owner.resumeHotSwitch(sessionId), "destination relay could not resume after source crash");
            player.prepareRebind(onset.actionId(), switching.joinToken(), owner.memberId(), sessionId);
            player.rebind(detached);
            detached = null; // ArcNet owns the preserved channel again.
            SharedCampaignState onOnset = awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 1, Duration.ofSeconds(30));
            long onsetTick = requireAction(onOnset, onset.actionId()).actionTick;
            awaitSingleTick(owner, onset.actionId(), onsetTick + 30L, Duration.ofSeconds(20));
            ActionSessionBroker.Session boundTarget = broker.session(sessionId);
            assertNotNull(boundTarget);
            assertEquals(ActionSessionBroker.SessionState.bound, boundTarget.state);
            assertEquals(onset.actionId(), boundTarget.actionId);
            assertEquals(1, boundTarget.switchCount);

            RuntimePayloads.StartResult recoveredGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(recoveredGround.error(), "Ground Zero recovery resume failed");
            assertEquals(ground.actionId(), recoveredGround.actionId());
            SharedCampaignState groundLiveAgain = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(3L, requireAction(groundLiveAgain, ground.actionId()).runtimeIncarnation);

            detached = player.detach();
            assertSame(physical, detached, "source crash/recovery replaced the preserved external TCP channel");
            CompletableFuture<Boolean> returnBarrier = synchronizeBarrierAsync(detached,
                ActionSessionHandshake.hotSwitchBarrier(sessionId, onset.actionId(), ground.actionId()));
            RuntimePayloads.HotSwitchResult returnSwitch = owner.hotSwitchAction(
                sessionId, onset.actionId(), ground.actionId(), false, networkUuid);
            assertBlank(returnSwitch.error(), "return hot-switch after source recovery failed");
            assertTrue(returnSwitch.sameTcp(), "return switch lost same-TCP capability after source recovery");
            assertTrue(returnBarrier.get(10, TimeUnit.SECONDS), "return MYHB barrier failed after source recovery");
            assertTrue(owner.resumeHotSwitch(sessionId), "return relay could not resume after source recovery");
            player.prepareRebind(ground.actionId(), returnSwitch.joinToken(), owner.memberId(), sessionId);
            player.rebind(detached);
            detached = null;

            SharedCampaignState returned = awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));
            long returnedTick = requireAction(returned, ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), returnedTick + 30L, Duration.ofSeconds(20));
            ActionSessionBroker.Session rebound = broker.session(sessionId);
            assertNotNull(rebound);
            assertEquals(ground.actionId(), rebound.actionId);
            assertEquals(2, rebound.switchCount);
            assertEquals(1, broker.liveSessionCount());
            assertEquals(1, broker.liveRelayCount());

            player.close();
            player = null;
            awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));
            waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, Duration.ofSeconds(10));
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(detached != null) try{ detached.close(); }catch(IOException ignored){}
            if(player != null) try{ player.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 420, unit = TimeUnit.SECONDS)
    void repeatedSameTcpChurnSurvivesIdleSourceCrashWithPeerPlayerOnline() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-same-tcp-churn-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RealSharedEntryClient traveler = null;
        SharedCampaignJvmConcurrentPlayersTests.RealArcClient anchor = null;
        SocketChannel detached = null, physical = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM same-TCP churn gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(ground.error(), "Ground Zero start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            // Give Ground Zero a deterministic durable recovery point before the churn starts.
            owner.suspendAction(ground.actionId());
            SharedCampaignState seeded = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            assertFalse(requireAction(seeded, ground.actionId()).lastSaveHash.isBlank(), "Ground Zero seed save missing");
            RuntimePayloads.StartResult seededGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(seededGround.error(), "Ground Zero seeded resume failed");
            assertEquals(ground.actionId(), seededGround.actionId());
            SharedCampaignState groundIncarnationTwo = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(groundIncarnationTwo, ground.actionId()).runtimeIncarnation);

            RuntimePayloads.StartResult onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential anchorMember = coordinator.clientControl().enrollTrustedLocal("Onset anchor");
            String anchorUuid = platformUuid(151);
            RuntimePayloads.VanillaTransferResult anchorGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                anchorMember.memberId(), onset.actionId(), anchorUuid, "127.0.0.1", false);
            assertBlank(anchorGrant.error(), "Onset anchor admission failed");
            anchor = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Onset anchor", anchorUuid, anchorGrant.host(), anchorGrant.port());

            String travelerUuid = platformUuid(152);
            RuntimePayloads.JoinResult initialJoin = owner.joinAction(ground.actionId(), false, travelerUuid);
            assertBlank(initialJoin.error(), "initial enhanced traveler join failed");
            traveler = RealSharedEntryClient.connect("Churn traveler", travelerUuid, owner.memberId(),
                ground.actionId(), initialJoin.joinToken(), initialJoin.host(), initialJoin.port());
            SharedCampaignState initialPresence = awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 1, Duration.ofSeconds(30));
            long groundTick = requireAction(initialPresence, ground.actionId()).actionTick;
            long onsetTick = requireAction(initialPresence, onset.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), groundTick + 20L, Duration.ofSeconds(20));
            awaitSingleTick(owner, onset.actionId(), onsetTick + 20L, Duration.ofSeconds(20));

            ActionSessionBroker broker = coordinator.entryRouter().broker();
            ActionSessionBroker.Session session = awaitSingleSession(broker, owner.memberId(), ground.actionId(), Duration.ofSeconds(10));
            String sessionId = session.sessionId;
            String currentAction = ground.actionId();
            int expectedSwitches = 0;

            for(int step = 1; step <= 4; step++){
                String targetAction = currentAction.equals(ground.actionId()) ? onset.actionId() : ground.actionId();
                detached = traveler.detach();
                if(physical == null) physical = detached;
                else assertSame(physical, detached, "same-TCP churn replaced the physical client channel at step " + step);

                CompletableFuture<Boolean> barrier = synchronizeBarrierAsync(detached,
                    ActionSessionHandshake.hotSwitchBarrier(sessionId, currentAction, targetAction));
                RuntimePayloads.HotSwitchResult switching = owner.hotSwitchAction(
                    sessionId, currentAction, targetAction, false, travelerUuid);
                assertBlank(switching.error(), "same-TCP churn prepare failed at step " + step);
                assertTrue(switching.sameTcp(), "same-TCP churn fell back to ordinary join at step " + step);
                assertTrue(barrier.get(10, TimeUnit.SECONDS), "same-TCP churn barrier failed at step " + step);
                assertTrue(owner.resumeHotSwitch(sessionId), "same-TCP churn resume failed at step " + step);
                traveler.prepareRebind(targetAction, switching.joinToken(), owner.memberId(), sessionId);
                traveler.rebind(detached);
                detached = null;
                currentAction = targetAction;
                expectedSwitches++;

                SharedCampaignState arrived = currentAction.equals(ground.actionId())
                    ? awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 1, Duration.ofSeconds(30))
                    : awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 2, Duration.ofSeconds(30));
                long targetTick = requireAction(arrived, currentAction).actionTick;
                awaitSingleTick(owner, currentAction, targetTick + 15L, Duration.ofSeconds(20));

                ActionSessionBroker.Session afterSwitch = broker.session(sessionId);
                assertNotNull(afterSwitch, "same-TCP churn lost broker session at step " + step);
                assertEquals(ActionSessionBroker.SessionState.bound, afterSwitch.state);
                assertEquals(currentAction, afterSwitch.actionId);
                assertEquals(expectedSwitches, afterSwitch.switchCount);
                assertEquals(1, broker.liveSessionCount(), "same-TCP churn leaked sessions at step " + step);
                assertEquals(1, broker.liveRelayCount(), "same-TCP churn leaked relays at step " + step);

                if(step == 3){
                    assertEquals(onset.actionId(), currentAction, "crash injection must happen while traveler is on Onset");
                    SectorRuntime doomedGround = coordinator.actionRuntimes().runtime(ground.actionId());
                    assertNotNull(doomedGround, "Ground Zero runtime missing before churn crash");
                    doomedGround.crashForTesting();
                    SharedCampaignState crashed = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
                    assertEquals(2L, requireAction(crashed, ground.actionId()).runtimeIncarnation,
                        "Ground Zero unexpectedly restarted before explicit churn recovery");
                    assertEquals(2, requireAction(crashed, onset.actionId()).connectedPlayers,
                        "Onset players were disturbed by idle sibling crash during churn");
                    long peerTick = requireAction(crashed, onset.actionId()).actionTick;
                    SharedCampaignState peerAdvanced = awaitSingleTick(owner, onset.actionId(), peerTick + 20L, Duration.ofSeconds(20));
                    assertEquals(2, requireAction(peerAdvanced, onset.actionId()).connectedPlayers,
                        "Onset players disappeared while peer world advanced after sibling crash");
                    ActionSessionBroker.Session afterCrash = broker.session(sessionId);
                    assertNotNull(afterCrash, "traveler session disappeared when idle source child crashed");
                    assertEquals(ActionSessionBroker.SessionState.bound, afterCrash.state);
                    assertEquals(onset.actionId(), afterCrash.actionId);
                    assertEquals(expectedSwitches, afterCrash.switchCount);

                    RuntimePayloads.StartResult recoveredGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
                    assertBlank(recoveredGround.error(), "Ground Zero churn recovery resume failed");
                    assertEquals(ground.actionId(), recoveredGround.actionId());
                    SharedCampaignState recovered = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
                    assertEquals(3L, requireAction(recovered, ground.actionId()).runtimeIncarnation);
                    assertEquals(2, requireAction(recovered, onset.actionId()).connectedPlayers,
                        "Onset players were disturbed by Ground Zero recovery during churn");
                }
            }

            assertEquals(ground.actionId(), currentAction, "four same-TCP churn switches should end back on Ground Zero");
            traveler.close();
            traveler = null;
            waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, Duration.ofSeconds(15));

            SharedCampaignState anchorOnly = awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 1, Duration.ofSeconds(30));
            long anchorTick = requireAction(anchorOnly, onset.actionId()).actionTick;
            awaitSingleTick(owner, onset.actionId(), anchorTick + 20L, Duration.ofSeconds(20));
            anchor.close();
            anchor = null;
            awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));

            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(detached != null) try{ detached.close(); }catch(IOException ignored){}
            if(physical != null) try{ physical.close(); }catch(IOException ignored){}
            if(traveler != null) try{ traveler.close(); }catch(Throwable ignored){}
            if(anchor != null) try{ anchor.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void repeatedAlternatingChildCrashesPreserveSameTcpSessionAndIncarnations() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-same-tcp-alternating-crash-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RealSharedEntryClient traveler = null;
        SocketChannel detached = null, physical = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "JVM same-TCP alternating crash churn";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 2;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(ground.error(), "Ground Zero start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            RuntimePayloads.StartResult seededGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(seededGround.error(), "Ground Zero seeded resume failed");
            assertEquals(ground.actionId(), seededGround.actionId());
            assertEquals(2L, requireAction(awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45)),
                ground.actionId()).runtimeIncarnation);

            RuntimePayloads.StartResult onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            RuntimePayloads.StartResult seededOnset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(seededOnset.error(), "Onset seeded resume failed");
            assertEquals(onset.actionId(), seededOnset.actionId());
            assertEquals(2L, requireAction(awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45)),
                onset.actionId()).runtimeIncarnation);

            String networkUuid = platformUuid(161);
            RuntimePayloads.JoinResult initialJoin = owner.joinAction(ground.actionId(), false, networkUuid);
            assertBlank(initialJoin.error(), "initial alternating-crash traveler join failed");
            traveler = RealSharedEntryClient.connect("Alternating crash traveler", networkUuid, owner.memberId(),
                ground.actionId(), initialJoin.joinToken(), initialJoin.host(), initialJoin.port());
            awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            ActionSessionBroker broker = coordinator.entryRouter().broker();
            ActionSessionBroker.Session session = awaitSingleSession(broker, owner.memberId(), ground.actionId(), Duration.ofSeconds(10));
            String sessionId = session.sessionId;
            String currentAction = ground.actionId();

            for(int step = 1; step <= 4; step++){
                String sourceAction = currentAction;
                String targetAction = sourceAction.equals(ground.actionId()) ? onset.actionId() : ground.actionId();
                detached = traveler.detach();
                if(physical == null) physical = detached;
                else assertSame(physical, detached, "alternating crash churn replaced physical TCP at step " + step);

                CompletableFuture<Boolean> barrier = synchronizeBarrierAsync(detached,
                    ActionSessionHandshake.hotSwitchBarrier(sessionId, sourceAction, targetAction));
                RuntimePayloads.HotSwitchResult switching = owner.hotSwitchAction(
                    sessionId, sourceAction, targetAction, false, networkUuid);
                assertBlank(switching.error(), "alternating crash switch prepare failed at step " + step);
                assertTrue(switching.sameTcp(), "alternating crash switch fell back to ordinary join at step " + step);
                assertTrue(barrier.get(10, TimeUnit.SECONDS), "alternating crash MYHB barrier failed at step " + step);
                assertTrue(owner.resumeHotSwitch(sessionId), "alternating crash broker resume failed at step " + step);
                traveler.prepareRebind(targetAction, switching.joinToken(), owner.memberId(), sessionId);
                traveler.rebind(detached);
                detached = null;
                currentAction = targetAction;

                SharedCampaignState arrived = currentAction.equals(ground.actionId())
                    ? awaitCounts(owner, ground.actionId(), 1, onset.actionId(), 0, Duration.ofSeconds(30))
                    : awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 1, Duration.ofSeconds(30));
                long liveTick = requireAction(arrived, currentAction).actionTick;
                awaitSingleTick(owner, currentAction, liveTick + 15L, Duration.ofSeconds(20));

                ActionSessionBroker.Session bound = broker.session(sessionId);
                assertNotNull(bound, "alternating crash churn lost broker session at step " + step);
                assertEquals(ActionSessionBroker.SessionState.bound, bound.state);
                assertEquals(currentAction, bound.actionId);
                assertEquals(step, bound.switchCount);
                assertEquals(1, broker.liveSessionCount(), "alternating crash churn leaked sessions before crash at step " + step);
                assertEquals(1, broker.liveRelayCount(), "alternating crash churn leaked relays before crash at step " + step);

                // The just-left source is empty. Crash it, let production monitoring converge it to SUSPENDED,
                // then explicitly recover it before the next reverse switch.
                ActionState sourceBeforeCrash = requireAction(arrived, sourceAction);
                long sourceIncarnation = sourceBeforeCrash.runtimeIncarnation;
                SectorRuntime doomed = coordinator.actionRuntimes().runtime(sourceAction);
                assertNotNull(doomed, "alternating crash source runtime missing at step " + step);
                doomed.crashForTesting();
                SharedCampaignState suspended = awaitStatus(owner, sourceAction, ActionStatus.suspended, Duration.ofSeconds(45));
                assertEquals(sourceIncarnation, requireAction(suspended, sourceAction).runtimeIncarnation,
                    "crashed source restarted before explicit recovery at step " + step);
                assertEquals(1, requireAction(suspended, currentAction).connectedPlayers,
                    "traveler disappeared when empty source child crashed at step " + step);
                long afterCrashTick = requireAction(suspended, currentAction).actionTick;
                SharedCampaignState peerAdvanced = awaitSingleTick(owner, currentAction, afterCrashTick + 15L, Duration.ofSeconds(20));
                assertEquals(1, requireAction(peerAdvanced, currentAction).connectedPlayers,
                    "traveler did not remain online while current world advanced at step " + step);

                ActionSessionBroker.Session afterCrash = broker.session(sessionId);
                assertNotNull(afterCrash, "alternating crash churn lost session after source crash at step " + step);
                assertEquals(ActionSessionBroker.SessionState.bound, afterCrash.state);
                assertEquals(currentAction, afterCrash.actionId);
                assertEquals(step, afterCrash.switchCount);
                assertEquals(1, broker.liveSessionCount(), "alternating crash churn leaked sessions after crash at step " + step);
                assertEquals(1, broker.liveRelayCount(), "alternating crash churn leaked relays after crash at step " + step);

                RuntimePayloads.StartResult recovered = sourceAction.equals(ground.actionId())
                    ? owner.startAction(Planets.serpulo.name, "groundZero", "")
                    : owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(recovered.error(), "alternating crash source recovery failed at step " + step);
                assertEquals(sourceAction, recovered.actionId());
                SharedCampaignState recoveredState = awaitStatus(owner, sourceAction, ActionStatus.running, Duration.ofSeconds(45));
                assertEquals(sourceIncarnation + 1L, requireAction(recoveredState, sourceAction).runtimeIncarnation,
                    "source incarnation did not advance exactly once at step " + step);
                assertEquals(1, requireAction(recoveredState, currentAction).connectedPlayers,
                    "recovering empty source disturbed traveler at step " + step);
            }

            SharedCampaignState finalState = owner.snapshot();
            assertEquals(ground.actionId(), currentAction, "four alternating crash switches should finish on Ground Zero");
            assertEquals(4L, requireAction(finalState, ground.actionId()).runtimeIncarnation,
                "Ground Zero did not accumulate exactly two crash recoveries");
            assertEquals(4L, requireAction(finalState, onset.actionId()).runtimeIncarnation,
                "Onset did not accumulate exactly two crash recoveries");
            ActionSessionBroker.Session finalSession = broker.session(sessionId);
            assertNotNull(finalSession);
            assertEquals(ground.actionId(), finalSession.actionId);
            assertEquals(4, finalSession.switchCount);
            assertEquals(1, broker.liveSessionCount());
            assertEquals(1, broker.liveRelayCount());

            traveler.close();
            traveler = null;
            awaitCounts(owner, ground.actionId(), 0, onset.actionId(), 0, Duration.ofSeconds(30));
            waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, Duration.ofSeconds(15));

            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(detached != null) try{ detached.close(); }catch(IOException ignored){}
            if(physical != null) try{ physical.close(); }catch(IOException ignored){}
            if(traveler != null) try{ traveler.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void mixedVanillaAndSameTcpPlayersChurnAcrossThreeJvmActions() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-mixed-three-action-churn-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RealSharedEntryClient enhanced = null;
        SharedCampaignJvmConcurrentPlayersTests.RealArcClient vanilla = null, anchor = null;
        SocketChannel detached = null, physical = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Mixed vanilla + same-TCP three-Action churn";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 3;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            // Frozen Forest needs the vanilla strategic prerequisite. Seed only that durable strategic state;
            // all three live worlds below are still production JVM_PROCESS children.
            service.authority().coordinator().store().transact("owner", "test:seed-mixed-churn-frozen-progress", state -> {
                SectorState strategic = new SectorState();
                strategic.planetName = Planets.serpulo.name;
                strategic.sectorName = "groundZero";
                strategic.hasBase = true;
                strategic.captured = true;
                strategic.summary = new SectorSummary();
                strategic.summary.planetName = Planets.serpulo.name;
                strategic.summary.coreType = "core-foundation";
                for(var item : Vars.content.items()) strategic.items.put(item.name, 100_000);
                state.sectors.put(SharedCampaignSectors.sectorKey(Planets.serpulo.name, "groundZero"), strategic);
                state.researched.add("junction");
                state.researched.add("router");
            });

            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(ground.error(), "Ground Zero start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            RuntimePayloads.StartResult onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            RuntimePayloads.StartResult frozen = owner.startAction(Planets.serpulo.name, "frozenForest", "");
            assertBlank(frozen.error(), "Frozen Forest start failed");
            awaitStatus(owner, frozen.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential vanillaMember = coordinator.clientControl().enrollTrustedLocal("Vanilla churn traveler");
            CoordinatorCredentials.MemberCredential anchorMember = coordinator.clientControl().enrollTrustedLocal("Onset churn anchor");
            String vanillaUuid = platformUuid(171), anchorUuid = platformUuid(172), enhancedUuid = platformUuid(173);

            RuntimePayloads.VanillaTransferResult vanillaGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                vanillaMember.memberId(), frozen.actionId(), vanillaUuid, "127.0.0.1", false);
            RuntimePayloads.VanillaTransferResult anchorGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                anchorMember.memberId(), onset.actionId(), anchorUuid, "127.0.0.1", false);
            assertBlank(vanillaGrant.error(), "Frozen Forest vanilla admission failed");
            assertBlank(anchorGrant.error(), "Onset anchor admission failed");
            vanilla = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Vanilla churn traveler", vanillaUuid, vanillaGrant.host(), vanillaGrant.port());
            anchor = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Onset churn anchor", anchorUuid, anchorGrant.host(), anchorGrant.port());

            RuntimePayloads.JoinResult enhancedJoin = owner.joinAction(ground.actionId(), false, enhancedUuid);
            assertBlank(enhancedJoin.error(), "enhanced Ground Zero join failed");
            enhanced = RealSharedEntryClient.connect("Enhanced churn traveler", enhancedUuid, owner.memberId(),
                ground.actionId(), enhancedJoin.joinToken(), enhancedJoin.host(), enhancedJoin.port());

            SharedCampaignState initial = awaitCounts3(owner,
                ground.actionId(), 1, onset.actionId(), 1, frozen.actionId(), 1, Duration.ofSeconds(30));
            long groundTick = requireAction(initial, ground.actionId()).actionTick;
            long onsetTick = requireAction(initial, onset.actionId()).actionTick;
            long frozenTick = requireAction(initial, frozen.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), groundTick + 15L, Duration.ofSeconds(20));
            awaitSingleTick(owner, onset.actionId(), onsetTick + 15L, Duration.ofSeconds(20));
            awaitSingleTick(owner, frozen.actionId(), frozenTick + 15L, Duration.ofSeconds(20));

            ActionSessionBroker broker = coordinator.entryRouter().broker();
            ActionSessionBroker.Session session = awaitSingleSession(broker, owner.memberId(), ground.actionId(), Duration.ofSeconds(10));
            String sessionId = session.sessionId;
            int switchCount = 0;

            for(int round = 1; round <= 2; round++){
                // Enhanced lane: Ground Zero -> Onset on one preserved physical TCP.
                detached = enhanced.detach();
                if(physical == null) physical = detached;
                else assertSame(physical, detached, "mixed churn replaced enhanced TCP before round " + round);
                CompletableFuture<Boolean> toOnsetBarrier = synchronizeBarrierAsync(detached,
                    ActionSessionHandshake.hotSwitchBarrier(sessionId, ground.actionId(), onset.actionId()));
                RuntimePayloads.HotSwitchResult toOnset = owner.hotSwitchAction(
                    sessionId, ground.actionId(), onset.actionId(), false, enhancedUuid);
                assertBlank(toOnset.error(), "mixed churn enhanced Ground -> Onset failed in round " + round);
                assertTrue(toOnset.sameTcp());
                assertTrue(toOnsetBarrier.get(10, TimeUnit.SECONDS));
                assertTrue(owner.resumeHotSwitch(sessionId));
                enhanced.prepareRebind(onset.actionId(), toOnset.joinToken(), owner.memberId(), sessionId);
                enhanced.rebind(detached);
                detached = null;
                switchCount++;
                SharedCampaignState enhancedOnOnset = awaitCounts3(owner,
                    ground.actionId(), 0, onset.actionId(), 2, frozen.actionId(), 1, Duration.ofSeconds(30));
                long enhancedOnsetTick = requireAction(enhancedOnOnset, onset.actionId()).actionTick;
                awaitSingleTick(owner, onset.actionId(), enhancedOnsetTick + 15L, Duration.ofSeconds(20));
                ActionSessionBroker.Session onOnset = broker.session(sessionId);
                assertNotNull(onOnset);
                assertEquals(onset.actionId(), onOnset.actionId);
                assertEquals(switchCount, onOnset.switchCount);
                assertEquals(1, broker.liveSessionCount());
                assertEquals(1, broker.liveRelayCount());

                // Pure-vanilla lane: Frozen Forest -> Ground Zero via the real /sector command and connect RPC.
                vanilla.sendCommand("/sector groundZero");
                var toGround = vanilla.awaitRedirect(Duration.ofSeconds(30));
                assertEquals(ground.port(), toGround.port(), "vanilla Frozen -> Ground redirect used wrong port in round " + round);
                vanilla.close();
                vanilla = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                    "Vanilla churn traveler", vanillaUuid, toGround.host(), toGround.port());
                SharedCampaignState vanillaOnGround = awaitCounts3(owner,
                    ground.actionId(), 1, onset.actionId(), 2, frozen.actionId(), 0, Duration.ofSeconds(30));
                assertTrue(requireAction(vanillaOnGround, ground.actionId()).participants.contains(vanillaMember.memberId()));
                long vanillaGroundTick = requireAction(vanillaOnGround, ground.actionId()).actionTick;
                awaitSingleTick(owner, ground.actionId(), vanillaGroundTick + 15L, Duration.ofSeconds(20));
                assertEquals(1, broker.liveSessionCount(), "vanilla transfer polluted enhanced session count in round " + round);
                assertEquals(1, broker.liveRelayCount(), "vanilla transfer polluted enhanced relay count in round " + round);

                // Enhanced lane returns Onset -> Ground Zero over the exact same TCP while vanilla player is already there.
                detached = enhanced.detach();
                assertSame(physical, detached, "mixed churn enhanced TCP changed before return in round " + round);
                CompletableFuture<Boolean> toGroundBarrier = synchronizeBarrierAsync(detached,
                    ActionSessionHandshake.hotSwitchBarrier(sessionId, onset.actionId(), ground.actionId()));
                RuntimePayloads.HotSwitchResult enhancedReturn = owner.hotSwitchAction(
                    sessionId, onset.actionId(), ground.actionId(), false, enhancedUuid);
                assertBlank(enhancedReturn.error(), "mixed churn enhanced Onset -> Ground failed in round " + round);
                assertTrue(enhancedReturn.sameTcp());
                assertTrue(toGroundBarrier.get(10, TimeUnit.SECONDS));
                assertTrue(owner.resumeHotSwitch(sessionId));
                enhanced.prepareRebind(ground.actionId(), enhancedReturn.joinToken(), owner.memberId(), sessionId);
                enhanced.rebind(detached);
                detached = null;
                switchCount++;
                SharedCampaignState bothOnGround = awaitCounts3(owner,
                    ground.actionId(), 2, onset.actionId(), 1, frozen.actionId(), 0, Duration.ofSeconds(30));
                long bothGroundTick = requireAction(bothOnGround, ground.actionId()).actionTick;
                awaitSingleTick(owner, ground.actionId(), bothGroundTick + 15L, Duration.ofSeconds(20));
                ActionSessionBroker.Session backOnGround = broker.session(sessionId);
                assertNotNull(backOnGround);
                assertEquals(ground.actionId(), backOnGround.actionId);
                assertEquals(switchCount, backOnGround.switchCount);
                assertEquals(1, broker.liveSessionCount());
                assertEquals(1, broker.liveRelayCount());

                // Pure-vanilla lane returns Ground Zero -> Frozen Forest, preserving its durable member identity.
                vanilla.sendCommand("/sector frozenForest");
                var toFrozen = vanilla.awaitRedirect(Duration.ofSeconds(30));
                assertEquals(frozen.port(), toFrozen.port(), "vanilla Ground -> Frozen redirect used wrong port in round " + round);
                vanilla.close();
                vanilla = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                    "Vanilla churn traveler", vanillaUuid, toFrozen.host(), toFrozen.port());
                SharedCampaignState roundComplete = awaitCounts3(owner,
                    ground.actionId(), 1, onset.actionId(), 1, frozen.actionId(), 1, Duration.ofSeconds(30));
                assertTrue(requireAction(roundComplete, frozen.actionId()).participants.contains(vanillaMember.memberId()));
                long frozenRoundTick = requireAction(roundComplete, frozen.actionId()).actionTick;
                awaitSingleTick(owner, frozen.actionId(), frozenRoundTick + 15L, Duration.ofSeconds(20));
                assertEquals(3, requireAction(roundComplete, ground.actionId()).connectedPlayers
                    + requireAction(roundComplete, onset.actionId()).connectedPlayers
                    + requireAction(roundComplete, frozen.actionId()).connectedPlayers,
                    "mixed churn duplicated or lost a player in round " + round);
                assertEquals(1, broker.liveSessionCount());
                assertEquals(1, broker.liveRelayCount());
            }

            ActionSessionBroker.Session finalSession = broker.session(sessionId);
            assertNotNull(finalSession);
            assertEquals(ground.actionId(), finalSession.actionId);
            assertEquals(4, finalSession.switchCount);

            enhanced.close(); enhanced = null;
            SharedCampaignState withoutEnhanced = awaitCounts3(owner,
                ground.actionId(), 0, onset.actionId(), 1, frozen.actionId(), 1, Duration.ofSeconds(30));
            long anchorTick = requireAction(withoutEnhanced, onset.actionId()).actionTick;
            long vanillaTick = requireAction(withoutEnhanced, frozen.actionId()).actionTick;
            awaitSingleTick(owner, onset.actionId(), anchorTick + 15L, Duration.ofSeconds(20));
            awaitSingleTick(owner, frozen.actionId(), vanillaTick + 15L, Duration.ofSeconds(20));
            waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, Duration.ofSeconds(15));

            vanilla.close(); vanilla = null;
            anchor.close(); anchor = null;
            awaitCounts3(owner, ground.actionId(), 0, onset.actionId(), 0, frozen.actionId(), 0, Duration.ofSeconds(30));
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(frozen.actionId());
            awaitStatus(owner, frozen.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(detached != null) try{ detached.close(); }catch(IOException ignored){}
            if(physical != null) try{ physical.close(); }catch(IOException ignored){}
            if(enhanced != null) try{ enhanced.close(); }catch(Throwable ignored){}
            if(vanilla != null) try{ vanilla.close(); }catch(Throwable ignored){}
            if(anchor != null) try{ anchor.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void mixedLanesRecoverVanillaPlayerCrashWithoutBreakingSameTcpSession() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-jvm-mixed-lane-player-crash-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignClient owner = null;
        RealSharedEntryClient enhanced = null;
        SharedCampaignJvmConcurrentPlayersTests.RealArcClient vanilla = null, anchor = null;
        SocketChannel detached = null, physical = null;
        try{
            service.runtimeFactory(SectorRuntimeFactory.jvmProcess());
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Mixed-lane occupied child crash gate";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = Planets.serpulo.name;
            options.maxActiveActions = 3;
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
            owner = service.controlClient();

            service.authority().coordinator().store().transact("owner", "test:seed-mixed-fault-frozen-progress", state -> {
                SectorState strategic = new SectorState();
                strategic.planetName = Planets.serpulo.name;
                strategic.sectorName = "groundZero";
                strategic.hasBase = true;
                strategic.captured = true;
                strategic.summary = new SectorSummary();
                strategic.summary.planetName = Planets.serpulo.name;
                strategic.summary.coreType = "core-foundation";
                for(var item : Vars.content.items()) strategic.items.put(item.name, 100_000);
                state.sectors.put(SharedCampaignSectors.sectorKey(Planets.serpulo.name, "groundZero"), strategic);
                state.researched.add("junction");
                state.researched.add("router");
            });

            RuntimePayloads.StartResult frozen = owner.startAction(Planets.serpulo.name, "frozenForest", "");
            assertBlank(frozen.error(), "Frozen Forest start failed");
            awaitStatus(owner, frozen.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            RuntimePayloads.StartResult ground = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(ground.error(), "Ground Zero start failed");
            awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            // Seed a durable save so the occupied crash below has a deterministic recovery point.
            owner.suspendAction(ground.actionId());
            SharedCampaignState groundSeeded = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            assertFalse(requireAction(groundSeeded, ground.actionId()).lastSaveHash.isBlank());
            RuntimePayloads.StartResult groundResumed = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(groundResumed.error(), "Ground Zero seeded resume failed");
            assertEquals(ground.actionId(), groundResumed.actionId());
            assertEquals(2L, requireAction(awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45)),
                ground.actionId()).runtimeIncarnation);

            RuntimePayloads.StartResult onset = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
            assertBlank(onset.error(), "Onset start failed");
            awaitStatus(owner, onset.actionId(), ActionStatus.running, Duration.ofSeconds(45));

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential vanillaMember = coordinator.clientControl().enrollTrustedLocal("Vanilla fault traveler");
            CoordinatorCredentials.MemberCredential anchorMember = coordinator.clientControl().enrollTrustedLocal("Onset fault anchor");
            String vanillaUuid = platformUuid(181), anchorUuid = platformUuid(182), enhancedUuid = platformUuid(183);
            RuntimePayloads.VanillaTransferResult vanillaGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                vanillaMember.memberId(), frozen.actionId(), vanillaUuid, "127.0.0.1", false);
            RuntimePayloads.VanillaTransferResult anchorGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                anchorMember.memberId(), onset.actionId(), anchorUuid, "127.0.0.1", false);
            assertBlank(vanillaGrant.error(), "Frozen vanilla admission failed");
            assertBlank(anchorGrant.error(), "Onset anchor admission failed");
            vanilla = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Vanilla fault traveler", vanillaUuid, vanillaGrant.host(), vanillaGrant.port());
            anchor = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Onset fault anchor", anchorUuid, anchorGrant.host(), anchorGrant.port());

            RuntimePayloads.JoinResult enhancedJoin = owner.joinAction(ground.actionId(), false, enhancedUuid);
            assertBlank(enhancedJoin.error(), "enhanced Ground join failed");
            enhanced = RealSharedEntryClient.connect("Enhanced fault traveler", enhancedUuid, owner.memberId(),
                ground.actionId(), enhancedJoin.joinToken(), enhancedJoin.host(), enhancedJoin.port());
            awaitCounts3(owner, ground.actionId(), 1, onset.actionId(), 1, frozen.actionId(), 1, Duration.ofSeconds(30));

            ActionSessionBroker broker = coordinator.entryRouter().broker();
            ActionSessionBroker.Session session = awaitSingleSession(broker, owner.memberId(), ground.actionId(), Duration.ofSeconds(10));
            String sessionId = session.sessionId;

            // Enhanced player moves to Onset first, preserving its physical TCP.
            detached = enhanced.detach();
            physical = detached;
            CompletableFuture<Boolean> toOnsetBarrier = synchronizeBarrierAsync(detached,
                ActionSessionHandshake.hotSwitchBarrier(sessionId, ground.actionId(), onset.actionId()));
            RuntimePayloads.HotSwitchResult toOnset = owner.hotSwitchAction(
                sessionId, ground.actionId(), onset.actionId(), false, enhancedUuid);
            assertBlank(toOnset.error(), "enhanced Ground -> Onset failed");
            assertTrue(toOnset.sameTcp());
            assertTrue(toOnsetBarrier.get(10, TimeUnit.SECONDS));
            assertTrue(owner.resumeHotSwitch(sessionId));
            enhanced.prepareRebind(onset.actionId(), toOnset.joinToken(), owner.memberId(), sessionId);
            enhanced.rebind(detached);
            detached = null;
            awaitCounts3(owner, ground.actionId(), 0, onset.actionId(), 2, frozen.actionId(), 1, Duration.ofSeconds(30));

            // Vanilla player moves into Ground Zero through the real child-side /sector path.
            vanilla.sendCommand("/sector groundZero");
            var vanillaToGround = vanilla.awaitRedirect(Duration.ofSeconds(30));
            vanilla.close();
            vanilla = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Vanilla fault traveler", vanillaUuid, vanillaToGround.host(), vanillaToGround.port());
            SharedCampaignState occupiedGround = awaitCounts3(owner,
                ground.actionId(), 1, onset.actionId(), 2, frozen.actionId(), 0, Duration.ofSeconds(30));
            assertTrue(requireAction(occupiedGround, ground.actionId()).participants.contains(vanillaMember.memberId()));
            long onsetBeforeCrash = requireAction(occupiedGround, onset.actionId()).actionTick;

            // Crash the Action that currently hosts the pure-vanilla player. The enhanced same-TCP session is bound
            // to Onset and must not be perturbed, nor may the Onset anchor disappear.
            SectorRuntime doomedGround = coordinator.actionRuntimes().runtime(ground.actionId());
            assertNotNull(doomedGround);
            doomedGround.crashForTesting();
            SharedCampaignState crashed = awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            assertEquals(2L, requireAction(crashed, ground.actionId()).runtimeIncarnation);
            assertEquals(2, requireAction(crashed, onset.actionId()).connectedPlayers,
                "enhanced/anchor players disappeared when vanilla sibling child crashed");
            assertEquals(0, requireAction(crashed, frozen.actionId()).connectedPlayers);
            SharedCampaignState onsetAdvanced = awaitSingleTick(owner, onset.actionId(), onsetBeforeCrash + 30L, Duration.ofSeconds(20));
            assertEquals(2, requireAction(onsetAdvanced, onset.actionId()).connectedPlayers);
            ActionSessionBroker.Session afterCrash = broker.session(sessionId);
            assertNotNull(afterCrash, "enhanced session disappeared when vanilla sibling child crashed");
            assertEquals(ActionSessionBroker.SessionState.bound, afterCrash.state);
            assertEquals(onset.actionId(), afterCrash.actionId);
            assertEquals(1, afterCrash.switchCount);
            assertEquals(1, broker.liveSessionCount());
            assertEquals(1, broker.liveRelayCount());

            vanilla.close(); vanilla = null;
            RuntimePayloads.StartResult recoveredGround = owner.startAction(Planets.serpulo.name, "groundZero", "");
            assertBlank(recoveredGround.error(), "Ground Zero recovery failed");
            assertEquals(ground.actionId(), recoveredGround.actionId());
            SharedCampaignState groundRecovered = awaitStatus(owner, ground.actionId(), ActionStatus.running, Duration.ofSeconds(45));
            assertEquals(3L, requireAction(groundRecovered, ground.actionId()).runtimeIncarnation);
            assertEquals(2, requireAction(groundRecovered, onset.actionId()).connectedPlayers,
                "recovering Ground disturbed Onset players");

            RuntimePayloads.VanillaTransferResult recoveredVanillaGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                vanillaMember.memberId(), ground.actionId(), vanillaUuid, "127.0.0.1", false);
            assertBlank(recoveredVanillaGrant.error(), "recovered Ground vanilla admission failed");
            vanilla = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Vanilla fault traveler recovered", vanillaUuid, recoveredVanillaGrant.host(), recoveredVanillaGrant.port());
            awaitCounts3(owner, ground.actionId(), 1, onset.actionId(), 2, frozen.actionId(), 0, Duration.ofSeconds(30));

            // The enhanced traveler must still be able to hand the original physical TCP into the recovered Action.
            detached = enhanced.detach();
            assertSame(physical, detached, "mixed-lane crash replaced enhanced physical TCP");
            CompletableFuture<Boolean> returnBarrier = synchronizeBarrierAsync(detached,
                ActionSessionHandshake.hotSwitchBarrier(sessionId, onset.actionId(), ground.actionId()));
            RuntimePayloads.HotSwitchResult enhancedToRecoveredGround = owner.hotSwitchAction(
                sessionId, onset.actionId(), ground.actionId(), false, enhancedUuid);
            assertBlank(enhancedToRecoveredGround.error(), "enhanced switch into recovered Ground failed");
            assertTrue(enhancedToRecoveredGround.sameTcp());
            assertTrue(returnBarrier.get(10, TimeUnit.SECONDS));
            assertTrue(owner.resumeHotSwitch(sessionId));
            enhanced.prepareRebind(ground.actionId(), enhancedToRecoveredGround.joinToken(), owner.memberId(), sessionId);
            enhanced.rebind(detached);
            detached = null;
            SharedCampaignState bothGround = awaitCounts3(owner,
                ground.actionId(), 2, onset.actionId(), 1, frozen.actionId(), 0, Duration.ofSeconds(30));
            long recoveredGroundTick = requireAction(bothGround, ground.actionId()).actionTick;
            awaitSingleTick(owner, ground.actionId(), recoveredGroundTick + 20L, Duration.ofSeconds(20));
            ActionSessionBroker.Session returned = broker.session(sessionId);
            assertNotNull(returned);
            assertEquals(ground.actionId(), returned.actionId);
            assertEquals(2, returned.switchCount);
            assertEquals(1, broker.liveSessionCount());
            assertEquals(1, broker.liveRelayCount());

            vanilla.sendCommand("/sector frozenForest");
            var vanillaToFrozen = vanilla.awaitRedirect(Duration.ofSeconds(30));
            vanilla.close();
            vanilla = SharedCampaignJvmConcurrentPlayersTests.RealArcClient.connectVanilla(
                "Vanilla fault traveler", vanillaUuid, vanillaToFrozen.host(), vanillaToFrozen.port());
            SharedCampaignState recoveredLayout = awaitCounts3(owner,
                ground.actionId(), 1, onset.actionId(), 1, frozen.actionId(), 1, Duration.ofSeconds(30));
            assertTrue(requireAction(recoveredLayout, frozen.actionId()).participants.contains(vanillaMember.memberId()));
            long frozenTick = requireAction(recoveredLayout, frozen.actionId()).actionTick;
            awaitSingleTick(owner, frozen.actionId(), frozenTick + 20L, Duration.ofSeconds(20));

            enhanced.close(); enhanced = null;
            vanilla.close(); vanilla = null;
            anchor.close(); anchor = null;
            awaitCounts3(owner, ground.actionId(), 0, onset.actionId(), 0, frozen.actionId(), 0, Duration.ofSeconds(30));
            waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, Duration.ofSeconds(15));
            owner.suspendAction(ground.actionId());
            awaitStatus(owner, ground.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(onset.actionId());
            awaitStatus(owner, onset.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
            owner.suspendAction(frozen.actionId());
            awaitStatus(owner, frozen.actionId(), ActionStatus.suspended, Duration.ofSeconds(45));
        }finally{
            if(detached != null) try{ detached.close(); }catch(IOException ignored){}
            if(physical != null) try{ physical.close(); }catch(IOException ignored){}
            if(enhanced != null) try{ enhanced.close(); }catch(Throwable ignored){}
            if(vanilla != null) try{ vanilla.close(); }catch(Throwable ignored){}
            if(anchor != null) try{ anchor.close(); }catch(Throwable ignored){}
            if(owner != null) try{ owner.close(); }catch(IOException ignored){}
            service.close();
            deleteTree(directory);
        }
    }

    private static boolean awaitEof(SocketChannel channel, Duration timeout) throws Exception{
        channel.configureBlocking(false);
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            buffer.clear();
            int read = channel.read(buffer);
            if(read < 0) return true;
            if(read == 0) Thread.sleep(10L);
        }
        return false;
    }

    private static CompletableFuture<Boolean> synchronizeBarrierAsync(SocketChannel channel, byte[] marker){
        return CompletableFuture.supplyAsync(() -> {
            try{ return synchronizeBarrier(channel, marker, 15_000L, ActionSessionBroker.hotSwitchBarrierMaxDrainBytes); }
            catch(IOException e){ throw new CompletionException(e); }
        });
    }

    private static boolean synchronizeBarrier(SocketChannel channel, byte[] marker, long timeoutMillis, int maxBytes) throws IOException{
        channel.configureBlocking(false);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int[] fallback = new int[marker.length];
        for(int i = 1, prefix = 0; i < marker.length; i++){
            while(prefix > 0 && marker[i] != marker[prefix]) prefix = fallback[prefix - 1];
            if(marker[i] == marker[prefix]) prefix++;
            fallback[i] = prefix;
        }
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        int matched = 0, consumed = 0;
        boolean found = false;
        while(!found && consumed < maxBytes && System.currentTimeMillis() < deadline){
            buffer.clear();
            buffer.limit(Math.min(buffer.capacity(), maxBytes - consumed));
            int read = channel.read(buffer);
            if(read < 0) return false;
            if(read == 0){ sleep(1L); continue; }
            consumed += read;
            buffer.flip();
            while(buffer.hasRemaining()){
                byte value = buffer.get();
                while(matched > 0 && value != marker[matched]) matched = fallback[matched - 1];
                if(value == marker[matched]) matched++;
                if(matched == marker.length){ found = true; break; }
            }
        }
        if(!found) return false;
        ByteBuffer echo = ByteBuffer.wrap(marker);
        while(echo.hasRemaining() && System.currentTimeMillis() < deadline){
            int wrote = channel.write(echo);
            if(wrote < 0) return false;
            if(wrote == 0) sleep(1L);
        }
        return !echo.hasRemaining();
    }

    private static final class RealSharedEntryClient implements AutoCloseable{
        private final GameContext context;
        private final SharedCampaignNet network;
        private final Client client;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final String name, networkUuid;

        private RealSharedEntryClient(String name, String networkUuid){
            this.name = name;
            this.networkUuid = networkUuid;
            context = new GameContext("shared-entry-player-" + name.replace(' ', '-'));
            RuntimeContexts.run(context, () -> context.net = new Net(null));
            network = SharedCampaignNet.install(context);
            client = new Client(16384, ArcNetProvider.clientReadBufferSize, new PacketSerializer(context));
            client.addListener(new NetListener(){
                @Override public void connected(Connection connection){
                    try{
                        ConnectPacket packet = new ConnectPacket();
                        packet.version = Version.build;
                        packet.versionType = Version.type;
                        packet.mods = new Seq<>();
                        packet.name = RealSharedEntryClient.this.name;
                        packet.locale = "en";
                        packet.uuid = RealSharedEntryClient.this.networkUuid;
                        packet.usid = "shared-entry-test-" + RealSharedEntryClient.this.name.replace(' ', '-');
                        packet.mobile = false;
                        packet.color = 0xffffffff;
                        network.decorateConnectPacket(packet);
                        connection.sendTCP(packet);
                        connection.sendTCP(new ConnectConfirmCallPacket());
                    }catch(Throwable error){ failure.compareAndSet(null, error); }
                }

                @Override public void received(Connection connection, Object object){
                    // Real ArcNet framing and world stream are consumed; rendering is intentionally omitted.
                }
            });
        }

        static RealSharedEntryClient connect(String name, String networkUuid, String memberId, String actionId,
                                             String token, String host, int port) throws Exception{
            RealSharedEntryClient result = new RealSharedEntryClient(name, networkUuid);
            try{
                result.network.prepareJoin(actionId, token, false, false, memberId, true);
                byte[] preface = result.network.clientConnectionPreamble();
                assertNotNull(preface, "shared-entry MYCS preface was not prepared");
                result.client.start();
                result.client.connect(8_000, InetAddress.getByName(host), port, -1, preface);
                result.assertHealthy();
                return result;
            }catch(Throwable failure){ result.close(); throw failure; }
        }

        SocketChannel detach() throws Exception{
            assertHealthy();
            SocketChannel channel = client.detachTcpChannel();
            assertNotNull(channel, "ArcNet did not detach the live shared-entry TCP channel");
            assertTrue(channel.isOpen(), "detached shared-entry channel is closed");
            return channel;
        }

        void prepareRebind(String actionId, String token, String memberId, String sessionId){
            network.prepareRebindJoin(actionId, token, false, false, memberId, sessionId);
        }

        void rebind(SocketChannel channel) throws Exception{
            client.start();
            client.connectDetached(8_000, channel);
            assertHealthy();
        }

        private void assertHealthy(){
            Throwable error = failure.get();
            if(error != null) throw new AssertionError("real shared-entry ArcNet client failed", error);
            assertTrue(client.isConnected(), "real shared-entry ArcNet client is not connected");
        }

        @Override public void close(){
            try{ client.stop(); }catch(Throwable ignored){}
            try{ client.dispose(); }catch(Throwable ignored){}
            context.dispose();
        }
    }

    private static ActionSessionBroker.Session awaitSingleSession(ActionSessionBroker broker, String memberId, String actionId, Duration timeout) throws Exception{
        final ActionSessionBroker.Session[] found = {null};
        waitUntil(() -> {
            var sessions = broker.sessionsSnapshot().stream().filter(s -> memberId.equals(s.memberId) && actionId.equals(s.actionId)).toList();
            if(sessions.size() == 1){ found[0] = sessions.get(0); return true; }
            return false;
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitCounts(SharedCampaignClient client, String actionA, int playersA,
                                                     String actionB, int playersB, Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB);
                if(a != null && b != null && a.connectedPlayers == playersA && b.connectedPlayers == playersB){ found[0] = state; return true; }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitCounts3(SharedCampaignClient client,
                                                      String actionA, int playersA,
                                                      String actionB, int playersB,
                                                      String actionC, int playersC,
                                                      Duration timeout) throws Exception{
        final SharedCampaignState[] found = {null};
        waitUntil(() -> {
            try{
                SharedCampaignState state = client.snapshot();
                ActionState a = state.actions.get(actionA), b = state.actions.get(actionB), c = state.actions.get(actionC);
                if(a != null && b != null && c != null
                    && a.connectedPlayers == playersA && b.connectedPlayers == playersB && c.connectedPlayers == playersC){
                    found[0] = state;
                    return true;
                }
                return false;
            }catch(Exception ignored){ return false; }
        }, timeout);
        return found[0];
    }

    private static SharedCampaignState awaitSingleTick(SharedCampaignClient client, String actionId, long tick, Duration timeout) throws Exception{
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

    private static ActionState requireAction(SharedCampaignState state, String actionId){
        ActionState action = state.actions.get(actionId);
        assertNotNull(action, "Action missing from authority snapshot: " + actionId);
        return action;
    }

    private static String platformUuid(int marker){
        byte[] seed = new byte[8]; seed[7] = (byte)marker;
        return new String(Base64Coder.encode(seed));
    }

    private static void assertBlank(String value, String message){ assertTrue(value == null || value.isBlank(), () -> message + ": " + value); }
    private static void sleep(long millis) throws InterruptedIOException{
        try{ Thread.sleep(millis); }catch(InterruptedException e){ Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){ if(condition.getAsBoolean()) return; Thread.sleep(50L); }
        fail("condition not met within " + timeout);
    }
    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths = Files.walk(root)){ for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
