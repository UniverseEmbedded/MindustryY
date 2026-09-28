import arc.files.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.net.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Product gate proving the enhanced MYCS lane and pure-vanilla direct-admission lane can coexist on one authority. */
@Tag("shared-campaign-parallel-soak")
public class SharedCampaignMixedCompatibilityTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void enhancedHotSwitchAndVanillaAdmissionsRemainIsolatedWhileBothActionsAreLive() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-mixed-lanes-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.parallel, 2)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            Socket enhanced = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Mixed compatibility lanes";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = Planets.serpulo.name;
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult first = owner.startAction(Planets.serpulo.name, "groundZero", "");
                RuntimePayloads.StartResult second = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(first.error(), "Serpulo start failed");
                assertBlank(second.error(), "Erekir start failed");
                awaitStatus(owner, first.actionId(), ActionStatus.running, Duration.ofSeconds(30));
                awaitStatus(owner, second.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                SharedCampaignCoordinator coordinator = service.authority().coordinator();
                ActionSessionBroker broker = coordinator.entryRouter().broker();
                assertEquals(2, broker.liveRouteCount(), "both live Actions must be routable through MYCS");

                String enhancedUuid = platformUuid(71);
                RuntimePayloads.JoinResult enhancedJoin = owner.joinAction(first.actionId(), false, enhancedUuid);
                assertBlank(enhancedJoin.error(), "enhanced join grant failed");
                assertTrue(enhancedJoin.port() > 0);
                String sessionId = "mixed-session-01";
                enhanced = new Socket();
                enhanced.connect(new InetSocketAddress(enhancedJoin.host(), enhancedJoin.port()), 3_000);
                enhanced.setTcpNoDelay(true);
                enhanced.setSoTimeout(500);
                enhanced.getOutputStream().write(ActionSessionHandshake.encode(new ActionSessionHandshake.Request(
                    ActionSessionHandshake.version, ActionSessionHandshake.Kind.action, ActionSessionHandshake.flagInlineArc,
                    sessionId, first.actionId(), "owner", enhancedJoin.joinToken(), 0)));
                enhanced.getOutputStream().flush();

                waitUntil(() -> broker.liveSessionCount() == 1 && broker.liveRelayCount() == 1, Duration.ofSeconds(5));
                ActionSessionBroker.Session session = broker.session(sessionId);
                assertNotNull(session);
                assertEquals("owner", session.memberId);
                assertEquals(first.actionId(), session.actionId);

                CoordinatorCredentials.MemberCredential vanillaMember = coordinator.clientControl().enrollTrustedLocal("Vanilla peer");
                String vanillaUuid = platformUuid(72);
                RuntimePayloads.VanillaTransferResult vanilla = coordinator.actionCommands().prepareVanillaBootstrap(
                    vanillaMember.memberId(), second.actionId(), vanillaUuid, "203.0.113.92", false);
                assertBlank(vanilla.error(), "vanilla bootstrap failed");
                assertEquals(second.actionId(), vanilla.actionId());

                GameContext secondContext = actionContext(coordinator, second.actionId());
                SharedCampaignNet secondNet = SharedCampaignNet.find(secondContext);
                assertNotNull(secondNet);
                ConnectPacket vanillaPacket = new ConnectPacket();
                vanillaPacket.uuid = ConnectPacket.serverUuid(vanillaUuid);
                TestConnection vanillaConnection = new TestConnection("203.0.113.92:43000");
                RuntimeContexts.run(secondContext, () -> {
                    assertTrue(secondNet.validateActionAdmission(vanillaConnection, vanillaPacket));
                    assertEquals(vanillaMember.memberId(), secondNet.authenticatedMemberId(vanillaConnection));
                    assertFalse(secondNet.validateActionAdmission(new TestConnection("203.0.113.92:43001"), vanillaPacket),
                        "pure-vanilla grant must remain one-shot while an MYCS relay is live");
                });

                assertEquals(1, broker.liveSessionCount(), "vanilla admission must not consume an MYCS session slot");
                assertEquals(1, broker.liveRelayCount(), "vanilla admission must not create or replace a broker relay");
                session = broker.session(sessionId);
                assertNotNull(session);
                assertEquals(first.actionId(), session.actionId);

                RuntimePayloads.HotSwitchResult switching = owner.hotSwitchAction(sessionId, first.actionId(), second.actionId(), false, enhancedUuid);
                assertBlank(switching.error(), "enhanced hot-switch preparation failed");
                assertTrue(switching.sameTcp(), "enhanced client should retain the existing broker TCP");
                byte[] marker = ActionSessionHandshake.hotSwitchBarrier(sessionId, first.actionId(), second.actionId());
                assertTrue(drainThroughAndEcho(enhanced, marker, Duration.ofSeconds(8), ActionSessionBroker.hotSwitchBarrierMaxDrainBytes),
                    "enhanced client did not observe/echo the same-TCP barrier");
                assertTrue(owner.resumeHotSwitch(sessionId), "coordinator failed to resume the preserved enhanced relay");
                waitUntil(() -> {
                    ActionSessionBroker.Session live = broker.session(sessionId);
                    return live != null && live.state == ActionSessionBroker.SessionState.bound && second.actionId().equals(live.actionId);
                }, Duration.ofSeconds(5));

                assertEquals(1, broker.liveSessionCount());
                assertEquals(1, broker.liveRelayCount());
                assertEquals(1, broker.session(sessionId).switchCount);

                // The direct lane must still work in the opposite direction after an enhanced same-TCP switch.
                RuntimePayloads.VanillaTransferResult returnGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                    vanillaMember.memberId(), first.actionId(), platformUuid(73), "203.0.113.93", false);
                assertBlank(returnGrant.error(), "second vanilla grant failed after enhanced switch");
                GameContext firstContext = actionContext(coordinator, first.actionId());
                SharedCampaignNet firstNet = SharedCampaignNet.find(firstContext);
                ConnectPacket returnPacket = new ConnectPacket();
                returnPacket.uuid = ConnectPacket.serverUuid(platformUuid(73));
                RuntimeContexts.run(firstContext, () -> assertTrue(firstNet.validateActionAdmission(
                    new TestConnection("203.0.113.93:43002"), returnPacket)));

                assertEquals(1, broker.liveSessionCount(), "second vanilla admission disturbed enhanced session accounting");
                assertEquals(1, broker.liveRelayCount(), "second vanilla admission disturbed enhanced relay accounting");

                // Cross the two lanes at their most failure-prone point: hold the enhanced relay paused mid-switch,
                // revoke a different vanilla member whose one-shot grant is already installed on the destination,
                // and require both operations to converge without sharing or corrupting transport/admission state.
                CoordinatorCredentials.MemberCredential revokedMember = coordinator.clientControl().enrollTrustedLocal("Revoked vanilla peer");
                String revokedUuid = platformUuid(74);
                RuntimePayloads.VanillaTransferResult revokedGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                    revokedMember.memberId(), first.actionId(), revokedUuid, "203.0.113.94", false);
                assertBlank(revokedGrant.error(), "revocation-race vanilla grant failed");

                RuntimePayloads.HotSwitchResult returnSwitch = owner.hotSwitchAction(sessionId, second.actionId(), first.actionId(), false, enhancedUuid);
                assertBlank(returnSwitch.error(), "return hot-switch preparation failed");
                assertTrue(returnSwitch.sameTcp());
                ActionSessionBroker.Session paused = broker.session(sessionId);
                assertNotNull(paused);
                assertEquals(ActionSessionBroker.SessionState.switching, paused.state);
                assertEquals(first.actionId(), paused.actionId);

                owner.removeMember(revokedMember.memberId());
                assertFalse(service.state().members.containsKey(revokedMember.memberId()));
                ConnectPacket revokedPacket = new ConnectPacket();
                revokedPacket.uuid = ConnectPacket.serverUuid(revokedUuid);
                RuntimeContexts.run(firstContext, () -> assertFalse(firstNet.validateActionAdmission(
                    new TestConnection("203.0.113.94:43003"), revokedPacket),
                    "member removal must invalidate a prepared vanilla grant even while another member's MYCS relay is paused"));

                byte[] returnMarker = ActionSessionHandshake.hotSwitchBarrier(sessionId, second.actionId(), first.actionId());
                assertTrue(drainThroughAndEcho(enhanced, returnMarker, Duration.ofSeconds(8), ActionSessionBroker.hotSwitchBarrierMaxDrainBytes),
                    "enhanced return switch did not observe/echo the stream barrier after vanilla revocation");
                assertTrue(owner.resumeHotSwitch(sessionId), "enhanced relay could not resume after unrelated vanilla member revocation");
                waitUntil(() -> {
                    ActionSessionBroker.Session live = broker.session(sessionId);
                    return live != null && live.state == ActionSessionBroker.SessionState.bound && first.actionId().equals(live.actionId);
                }, Duration.ofSeconds(5));
                assertEquals(2, broker.session(sessionId).switchCount);
                assertEquals(1, broker.liveSessionCount());
                assertEquals(1, broker.liveRelayCount());
                assertTrue(service.state().members.containsKey("owner"), "unrelated owner membership was disturbed by vanilla revocation");
            }finally{
                if(enhanced != null) try{ enhanced.close(); }catch(IOException ignored){}
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "mixed compatibility test leaked an embedded runtime");
        }finally{
            deleteTree(directory);
        }
    }


    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void targetCrashDuringEnhancedSwitchClosesRelayAndDoesNotReviveVanillaGrant() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-mixed-crash-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.parallel, 2)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            Socket enhanced = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Mixed compatibility crash";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = Planets.serpulo.name;
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult first = owner.startAction(Planets.serpulo.name, "groundZero", "");
                RuntimePayloads.StartResult second = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(first.error(), "Serpulo start failed");
                assertBlank(second.error(), "Erekir start failed");
                awaitStatus(owner, first.actionId(), ActionStatus.running, Duration.ofSeconds(30));
                awaitStatus(owner, second.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                // Seed an authoritative save so an injected target crash has a deterministic suspended recovery point.
                owner.suspendAction(second.actionId());
                awaitStatus(owner, second.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
                RuntimePayloads.StartResult resumed = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(resumed.error(), "Erekir seeded resume failed");
                assertEquals(second.actionId(), resumed.actionId());
                awaitStatus(owner, second.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                SharedCampaignCoordinator coordinator = service.authority().coordinator();
                ActionSessionBroker broker = coordinator.entryRouter().broker();
                String enhancedUuid = platformUuid(81);
                RuntimePayloads.JoinResult enhancedJoin = owner.joinAction(first.actionId(), false, enhancedUuid);
                assertBlank(enhancedJoin.error(), "enhanced crash-window join failed");
                String sessionId = "mixed-crash-01";
                enhanced = new Socket();
                enhanced.connect(new InetSocketAddress(enhancedJoin.host(), enhancedJoin.port()), 3_000);
                enhanced.setTcpNoDelay(true);
                enhanced.setSoTimeout(500);
                enhanced.getOutputStream().write(ActionSessionHandshake.encode(new ActionSessionHandshake.Request(
                    ActionSessionHandshake.version, ActionSessionHandshake.Kind.action, ActionSessionHandshake.flagInlineArc,
                    sessionId, first.actionId(), "owner", enhancedJoin.joinToken(), 0)));
                enhanced.getOutputStream().flush();
                waitUntil(() -> broker.liveSessionCount() == 1 && broker.liveRelayCount() == 1, Duration.ofSeconds(5));

                CoordinatorCredentials.MemberCredential vanillaMember = coordinator.clientControl().enrollTrustedLocal("Crash-window vanilla peer");
                String staleUuid = platformUuid(82);
                RuntimePayloads.VanillaTransferResult staleGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                    vanillaMember.memberId(), second.actionId(), staleUuid, "203.0.113.102", false);
                assertBlank(staleGrant.error(), "pre-crash vanilla grant failed");

                RuntimePayloads.HotSwitchResult switching = owner.hotSwitchAction(sessionId, first.actionId(), second.actionId(), false, enhancedUuid);
                assertBlank(switching.error(), "crash-window hot-switch preparation failed");
                assertTrue(switching.sameTcp());
                ActionSessionBroker.Session paused = broker.session(sessionId);
                assertNotNull(paused);
                assertEquals(ActionSessionBroker.SessionState.switching, paused.state);
                assertEquals(second.actionId(), paused.actionId);

                SectorRuntime targetRuntime = coordinator.actionRuntimes().runtime(second.actionId());
                assertNotNull(targetRuntime);
                targetRuntime.crashForTesting();
                coordinator.actionRuntimes().monitorOnce();
                awaitStatus(owner, second.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
                waitUntil(() -> broker.liveSessionCount() == 0 && broker.liveRelayCount() == 0, Duration.ofSeconds(5));
                assertNull(broker.session(sessionId), "target route loss must fail-close the switching MYCS session");
                assertFalse(owner.resumeHotSwitch(sessionId), "a switching session closed by target crash must not be resumable");

                enhanced.close();
                enhanced = null;

                RuntimePayloads.StartResult recovered = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(recovered.error(), "Erekir recovery resume failed");
                assertEquals(second.actionId(), recovered.actionId());
                SharedCampaignState liveAgain = awaitStatus(owner, second.actionId(), ActionStatus.running, Duration.ofSeconds(30));
                assertEquals(3L, liveAgain.actions.get(second.actionId()).runtimeIncarnation);

                GameContext newTargetContext = actionContext(coordinator, second.actionId());
                SharedCampaignNet newTargetNet = SharedCampaignNet.find(newTargetContext);
                ConnectPacket stalePacket = new ConnectPacket();
                stalePacket.uuid = ConnectPacket.serverUuid(staleUuid);
                RuntimeContexts.run(newTargetContext, () -> assertFalse(newTargetNet.validateActionAdmission(
                    new TestConnection("203.0.113.102:44000"), stalePacket),
                    "a direct grant prepared in the crashed runtime must not resurrect in its replacement context"));

                RuntimePayloads.VanillaTransferResult freshGrant = coordinator.actionCommands().prepareVanillaBootstrap(
                    vanillaMember.memberId(), second.actionId(), platformUuid(83), "203.0.113.103", false);
                assertBlank(freshGrant.error(), "fresh vanilla grant failed after target recovery");
                ConnectPacket freshPacket = new ConnectPacket();
                freshPacket.uuid = ConnectPacket.serverUuid(platformUuid(83));
                RuntimeContexts.run(newTargetContext, () -> assertTrue(newTargetNet.validateActionAdmission(
                    new TestConnection("203.0.113.103:44001"), freshPacket),
                    "recovered Action must accept newly prepared vanilla grants"));
            }finally{
                if(enhanced != null) try{ enhanced.close(); }catch(IOException ignored){}
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "mixed crash test leaked an embedded runtime");
        }finally{
            deleteTree(directory);
        }
    }


    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void sourceCrashDuringSwitchPreservesDestinationSessionAndAllowsReturnAfterRecovery() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-mixed-source-crash-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.parallel, 2)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            Socket enhanced = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Mixed source crash";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = Planets.serpulo.name;
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult first = owner.startAction(Planets.serpulo.name, "groundZero", "");
                RuntimePayloads.StartResult second = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(first.error(), "Serpulo start failed");
                assertBlank(second.error(), "Erekir start failed");
                awaitStatus(owner, first.actionId(), ActionStatus.running, Duration.ofSeconds(30));
                awaitStatus(owner, second.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                // Seed A so its injected crash has a deterministic SUSPENDED recovery point.
                owner.suspendAction(first.actionId());
                awaitStatus(owner, first.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
                RuntimePayloads.StartResult firstResumed = owner.startAction(Planets.serpulo.name, "groundZero", "");
                assertBlank(firstResumed.error(), "Serpulo seeded resume failed");
                assertEquals(first.actionId(), firstResumed.actionId());
                awaitStatus(owner, first.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                SharedCampaignCoordinator coordinator = service.authority().coordinator();
                ActionSessionBroker broker = coordinator.entryRouter().broker();
                String enhancedUuid = platformUuid(101);
                RuntimePayloads.JoinResult enhancedJoin = owner.joinAction(first.actionId(), false, enhancedUuid);
                assertBlank(enhancedJoin.error(), "source-crash enhanced join failed");
                String sessionId = "mixed-source-crash-01";
                enhanced = new Socket();
                enhanced.connect(new InetSocketAddress(enhancedJoin.host(), enhancedJoin.port()), 3_000);
                enhanced.setTcpNoDelay(true);
                enhanced.setSoTimeout(500);
                enhanced.getOutputStream().write(ActionSessionHandshake.encode(new ActionSessionHandshake.Request(
                    ActionSessionHandshake.version, ActionSessionHandshake.Kind.action, ActionSessionHandshake.flagInlineArc,
                    sessionId, first.actionId(), "owner", enhancedJoin.joinToken(), 0)));
                enhanced.getOutputStream().flush();
                waitUntil(() -> broker.liveSessionCount() == 1 && broker.liveRelayCount() == 1, Duration.ofSeconds(5));

                RuntimePayloads.HotSwitchResult switching = owner.hotSwitchAction(sessionId, first.actionId(), second.actionId(), false, enhancedUuid);
                assertBlank(switching.error(), "source-crash hot-switch preparation failed");
                assertTrue(switching.sameTcp());
                ActionSessionBroker.Session paused = broker.session(sessionId);
                assertNotNull(paused);
                assertEquals(ActionSessionBroker.SessionState.switching, paused.state);
                assertEquals(second.actionId(), paused.actionId,
                    "once SWITCHING is committed the broker session must already be owned by the destination route");

                SectorRuntime sourceRuntime = coordinator.actionRuntimes().runtime(first.actionId());
                assertNotNull(sourceRuntime);
                sourceRuntime.crashForTesting();
                coordinator.actionRuntimes().monitorOnce();
                awaitStatus(owner, first.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));

                ActionSessionBroker.Session afterSourceLoss = broker.session(sessionId);
                assertNotNull(afterSourceLoss, "source route loss incorrectly killed a session already switching to B");
                assertEquals(ActionSessionBroker.SessionState.switching, afterSourceLoss.state);
                assertEquals(second.actionId(), afterSourceLoss.actionId);
                assertEquals(1, broker.liveSessionCount());
                assertEquals(1, broker.liveRelayCount());

                byte[] marker = ActionSessionHandshake.hotSwitchBarrier(sessionId, first.actionId(), second.actionId());
                assertTrue(drainThroughAndEcho(enhanced, marker, Duration.ofSeconds(8), ActionSessionBroker.hotSwitchBarrierMaxDrainBytes),
                    "destination barrier could not complete after source Action crash");
                assertTrue(owner.resumeHotSwitch(sessionId), "destination relay could not resume after source Action crash");
                waitUntil(() -> {
                    ActionSessionBroker.Session live = broker.session(sessionId);
                    return live != null && live.state == ActionSessionBroker.SessionState.bound && second.actionId().equals(live.actionId);
                }, Duration.ofSeconds(5));
                assertEquals(1, broker.session(sessionId).switchCount);

                // Recreate A as the same logical Action and prove the surviving session can later return to it.
                RuntimePayloads.StartResult recoveredSource = owner.startAction(Planets.serpulo.name, "groundZero", "");
                assertBlank(recoveredSource.error(), "Serpulo recovery resume failed");
                assertEquals(first.actionId(), recoveredSource.actionId());
                awaitStatus(owner, first.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                RuntimePayloads.HotSwitchResult returnSwitch = owner.hotSwitchAction(sessionId, second.actionId(), first.actionId(), false, enhancedUuid);
                assertBlank(returnSwitch.error(), "return switch after source recovery failed");
                assertTrue(returnSwitch.sameTcp());
                byte[] returnMarker = ActionSessionHandshake.hotSwitchBarrier(sessionId, second.actionId(), first.actionId());
                assertTrue(drainThroughAndEcho(enhanced, returnMarker, Duration.ofSeconds(8), ActionSessionBroker.hotSwitchBarrierMaxDrainBytes),
                    "return barrier failed after source recovery");
                assertTrue(owner.resumeHotSwitch(sessionId), "return relay could not resume after source recovery");
                waitUntil(() -> {
                    ActionSessionBroker.Session live = broker.session(sessionId);
                    return live != null && live.state == ActionSessionBroker.SessionState.bound && first.actionId().equals(live.actionId);
                }, Duration.ofSeconds(5));
                assertEquals(2, broker.session(sessionId).switchCount);
                assertEquals(1, broker.liveSessionCount());
                assertEquals(1, broker.liveRelayCount());
            }finally{
                if(enhanced != null) try{ enhanced.close(); }catch(IOException ignored){}
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "source-crash mixed test leaked an embedded runtime");
        }finally{
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void authorityRestartDuringSwitchDropsEphemeralSessionsAndRecoversDurableActions() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-mixed-authority-restart-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.parallel, 2)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            Socket enhanced = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Mixed authority restart";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = Planets.serpulo.name;
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult first = owner.startAction(Planets.serpulo.name, "groundZero", "");
                RuntimePayloads.StartResult second = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(first.error(), "Serpulo start failed");
                assertBlank(second.error(), "Erekir start failed");
                awaitStatus(owner, first.actionId(), ActionStatus.running, Duration.ofSeconds(30));
                awaitStatus(owner, second.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                // Give both Actions an authoritative save before simulating an authority/process lifetime boundary.
                owner.suspendAction(first.actionId());
                owner.suspendAction(second.actionId());
                awaitStatus(owner, first.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
                awaitStatus(owner, second.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
                RuntimePayloads.StartResult firstResumed = owner.startAction(Planets.serpulo.name, "groundZero", "");
                RuntimePayloads.StartResult secondResumed = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(firstResumed.error(), "Serpulo seeded resume failed");
                assertBlank(secondResumed.error(), "Erekir seeded resume failed");
                assertEquals(first.actionId(), firstResumed.actionId());
                assertEquals(second.actionId(), secondResumed.actionId());
                awaitStatus(owner, first.actionId(), ActionStatus.running, Duration.ofSeconds(30));
                awaitStatus(owner, second.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                SharedCampaignCoordinator beforeRestart = service.authority().coordinator();
                ActionSessionBroker oldBroker = beforeRestart.entryRouter().broker();
                String enhancedUuid = platformUuid(91);
                RuntimePayloads.JoinResult enhancedJoin = owner.joinAction(first.actionId(), false, enhancedUuid);
                assertBlank(enhancedJoin.error(), "enhanced restart-window join failed");
                String sessionId = "mixed-authority-restart-01";
                enhanced = new Socket();
                enhanced.connect(new InetSocketAddress(enhancedJoin.host(), enhancedJoin.port()), 3_000);
                enhanced.setTcpNoDelay(true);
                enhanced.setSoTimeout(500);
                enhanced.getOutputStream().write(ActionSessionHandshake.encode(new ActionSessionHandshake.Request(
                    ActionSessionHandshake.version, ActionSessionHandshake.Kind.action, ActionSessionHandshake.flagInlineArc,
                    sessionId, first.actionId(), "owner", enhancedJoin.joinToken(), 0)));
                enhanced.getOutputStream().flush();
                waitUntil(() -> oldBroker.liveSessionCount() == 1 && oldBroker.liveRelayCount() == 1, Duration.ofSeconds(5));

                CoordinatorCredentials.MemberCredential vanillaMember = beforeRestart.clientControl().enrollTrustedLocal("Restart-window vanilla peer");
                String staleUuid = platformUuid(92);
                RuntimePayloads.VanillaTransferResult staleGrant = beforeRestart.actionCommands().prepareVanillaBootstrap(
                    vanillaMember.memberId(), second.actionId(), staleUuid, "203.0.113.112", false);
                assertBlank(staleGrant.error(), "pre-restart vanilla grant failed");

                RuntimePayloads.HotSwitchResult switching = owner.hotSwitchAction(sessionId, first.actionId(), second.actionId(), false, enhancedUuid);
                assertBlank(switching.error(), "restart-window hot-switch preparation failed");
                assertTrue(switching.sameTcp());
                ActionSessionBroker.Session paused = oldBroker.session(sessionId);
                assertNotNull(paused);
                assertEquals(ActionSessionBroker.SessionState.switching, paused.state);
                assertEquals(second.actionId(), paused.actionId);

                // Cross the whole authority lifetime while both ephemeral mechanisms are armed.
                service.close();
                owner = null;
                waitUntil(() -> oldBroker.liveSessionCount() == 0 && oldBroker.liveRelayCount() == 0, Duration.ofSeconds(5));
                assertNull(oldBroker.session(sessionId), "authority close retained an MYCS session");
                waitUntil(() -> scheduler.size() == 0, Duration.ofSeconds(5));
                try{ enhanced.close(); }catch(IOException ignored){}
                enhanced = null;

                service.openLocal(new Fi(directory.toString()), "owner", "127.0.0.1", 0, 0);
                owner = service.controlClient();
                SharedCampaignCoordinator recoveredCoordinator = service.authority().coordinator();
                ActionSessionBroker recoveredBroker = recoveredCoordinator.entryRouter().broker();
                assertEquals(0, recoveredBroker.liveSessionCount(), "MYCS session crossed authority restart");
                assertEquals(0, recoveredBroker.liveRelayCount(), "MYCS relay crossed authority restart");
                assertNull(recoveredBroker.session(sessionId));
                assertFalse(owner.resumeHotSwitch(sessionId), "old hot-switch session unexpectedly survived authority restart");

                // Model a restart whose downtime exceeded the old runtime lease without sleeping for a wall-clock lease.
                recoveredCoordinator.store().transact("test", "shared-campaign:test-expire-restart-leases", state -> {
                    long expired = System.currentTimeMillis() - 1L;
                    for(ActionState action : state.actions.values()) if(action.status.isLive()) action.leaseExpiresAt = expired;
                });
                recoveredCoordinator.actionRuntimes().monitorOnce();
                awaitStatus(owner, first.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));
                awaitStatus(owner, second.actionId(), ActionStatus.suspended, Duration.ofSeconds(30));

                RuntimePayloads.StartResult recoveredTarget = owner.startAction(Planets.erekir.name, "onset", "vanilla-erekir:onset");
                assertBlank(recoveredTarget.error(), "Erekir resume after authority restart failed");
                assertEquals(second.actionId(), recoveredTarget.actionId());
                awaitStatus(owner, second.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                GameContext recoveredTargetContext = actionContext(recoveredCoordinator, second.actionId());
                SharedCampaignNet recoveredTargetNet = SharedCampaignNet.find(recoveredTargetContext);
                ConnectPacket stalePacket = new ConnectPacket();
                stalePacket.uuid = ConnectPacket.serverUuid(staleUuid);
                RuntimeContexts.run(recoveredTargetContext, () -> assertFalse(recoveredTargetNet.validateActionAdmission(
                    new TestConnection("203.0.113.112:45000"), stalePacket),
                    "a direct grant prepared before authority restart must not reappear in the replacement runtime"));

                RuntimePayloads.VanillaTransferResult freshGrant = recoveredCoordinator.actionCommands().prepareVanillaBootstrap(
                    vanillaMember.memberId(), second.actionId(), platformUuid(93), "203.0.113.113", false);
                assertBlank(freshGrant.error(), "fresh vanilla grant failed after authority restart");
                ConnectPacket freshPacket = new ConnectPacket();
                freshPacket.uuid = ConnectPacket.serverUuid(platformUuid(93));
                RuntimeContexts.run(recoveredTargetContext, () -> assertTrue(recoveredTargetNet.validateActionAdmission(
                    new TestConnection("203.0.113.113:45001"), freshPacket),
                    "recovered authority must accept newly prepared vanilla grants"));
            }finally{
                if(enhanced != null) try{ enhanced.close(); }catch(IOException ignored){}
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "authority restart mixed test leaked an embedded runtime");
        }finally{
            deleteTree(directory);
        }
    }

    private static GameContext actionContext(SharedCampaignCoordinator coordinator, String actionId){
        SectorRuntime runtime = coordinator.actionRuntimes().runtime(actionId);
        assertTrue(runtime instanceof InProcessSectorRuntime, "expected embedded runtime for " + actionId);
        return ((InProcessSectorRuntime)runtime).context();
    }

    private static boolean drainThroughAndEcho(Socket socket, byte[] marker, Duration timeout, int maxBytes) throws IOException{
        long deadline = System.nanoTime() + timeout.toNanos();
        int[] fallback = new int[marker.length];
        for(int i = 1, prefix = 0; i < marker.length; i++){
            while(prefix > 0 && marker[i] != marker[prefix]) prefix = fallback[prefix - 1];
            if(marker[i] == marker[prefix]) prefix++;
            fallback[i] = prefix;
        }
        int matched = 0, consumed = 0;
        byte[] buffer = new byte[Math.min(4096, Math.max(256, marker.length * 2))];
        while(consumed < maxBytes && System.nanoTime() < deadline){
            int read;
            try{ read = socket.getInputStream().read(buffer, 0, Math.min(buffer.length, maxBytes - consumed)); }
            catch(SocketTimeoutException timeoutPoll){ continue; }
            if(read < 0) return false;
            consumed += read;
            for(int i = 0; i < read; i++){
                byte value = buffer[i];
                while(matched > 0 && value != marker[matched]) matched = fallback[matched - 1];
                if(value == marker[matched]) matched++;
                if(matched == marker.length){
                    socket.getOutputStream().write(marker);
                    socket.getOutputStream().flush();
                    return true;
                }
            }
        }
        return false;
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

    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline){
            if(condition.getAsBoolean()) return;
            Thread.sleep(10L);
        }
        fail("condition not met within " + timeout);
    }

    private static String platformUuid(int marker){
        byte[] seed = new byte[8];
        seed[7] = (byte)marker;
        return new String(Base64Coder.encode(seed));
    }

    private static void assertBlank(String value, String message){
        assertTrue(value == null || value.isBlank(), () -> message + ": " + value);
    }

    private static final class TestConnection extends NetConnection{
        TestConnection(String address){ super(address); }
        @Override public void send(Object object, boolean reliable){}
        @Override public void close(){}
        @Override public void kick(String reason, long duration){}
    }

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root)) return;
        try(var paths = Files.walk(root)){
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try{ Files.deleteIfExists(path); }catch(IOException error){ throw new UncheckedIOException(error); }
            });
        }catch(UncheckedIOException error){ throw error.getCause(); }
    }
}
