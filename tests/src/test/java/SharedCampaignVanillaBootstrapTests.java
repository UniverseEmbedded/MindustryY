import arc.files.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.campaign.shared.net.*;
import mindustry.game.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** First-entry gate for operator-assisted unmodified vanilla clients. */
@Tag("shared-campaign-backend-differential")
public class SharedCampaignVanillaBootstrapTests{
    @BeforeAll static void bootstrap(){
        ApplicationTests.launchApplication(false);
        if(Vars.schematics == null){ Vars.schematics = new Schematics(); Vars.schematics.load(); }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void trustedOperatorEnrollmentCanStageFirstVanillaAdmission() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-vanilla-bootstrap-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Vanilla bootstrap";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = "serpulo";
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
                assertTrue(started.error() == null || started.error().isBlank(), started.error());
                awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                SharedCampaignCoordinator coordinator = service.authority().coordinator();
                CoordinatorCredentials.MemberCredential member = coordinator.clientControl().enrollTrustedLocal("Vanilla newcomer");
                assertTrue(service.state().members.containsKey(member.memberId()));
                assertNotNull(coordinator.credentials().memberKey(member.memberId()));

                String platformUuid = platformUuid(31);
                RuntimePayloads.VanillaTransferResult admission = coordinator.actionCommands().prepareVanillaBootstrap(
                    member.memberId(), started.actionId(), platformUuid, "203.0.113.77:45678", false);
                assertTrue(admission.error() == null || admission.error().isBlank(), admission.error());
                assertEquals(started.actionId(), admission.actionId());
                assertTrue(admission.port() > 0);
                assertTrue(admission.expiresAt() > System.currentTimeMillis());

                SectorRuntime runtime = coordinator.actionRuntimes().runtime(started.actionId());
                assertTrue(runtime instanceof InProcessSectorRuntime);
                GameContext actionContext = ((InProcessSectorRuntime)runtime).context();
                SharedCampaignNet actionNetwork = SharedCampaignNet.find(actionContext);
                assertNotNull(actionNetwork);
                ConnectPacket packet = new ConnectPacket();
                packet.uuid = ConnectPacket.serverUuid(platformUuid);
                TestConnection connection = new TestConnection("203.0.113.77:50000");
                RuntimeContexts.run(actionContext, () -> {
                    assertTrue(actionNetwork.validateActionAdmission(connection, packet),
                        "the destination Action must observe the newly enrolled member before the first vanilla reconnect");
                    assertEquals(member.memberId(), actionNetwork.authenticatedMemberId(connection));
                });

                MemberState durable = service.state().members.get(member.memberId());
                assertNotNull(durable);
                assertEquals("Vanilla newcomer", durable.displayName);
                assertEquals(started.actionId(), durable.lastActionId);
            }finally{
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "vanilla bootstrap test leaked an embedded runtime");
        }finally{
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void removingMemberInvalidatesPreparedVanillaAdmissionAtLiveAction() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-vanilla-bootstrap-revoke-");
        try(InProcessSectorScheduler scheduler = InProcessSectorScheduler.create(InProcessSectorScheduler.Mode.serial, 1)){
            SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
            SharedCampaignClient owner = null;
            try{
                service.runtimeFactory(SectorRuntimeFactory.inProcess(scheduler));
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.displayName = "Vanilla bootstrap revoke";
                options.ownerId = "owner";
                options.ownerDisplayName = "Owner";
                options.primaryPlanetName = "serpulo";
                options.maxActiveActions = 2;
                service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);
                owner = service.controlClient();

                RuntimePayloads.StartResult started = owner.startAction("serpulo", "groundZero", "");
                assertTrue(started.error() == null || started.error().isBlank(), started.error());
                awaitStatus(owner, started.actionId(), ActionStatus.running, Duration.ofSeconds(30));

                SharedCampaignCoordinator coordinator = service.authority().coordinator();
                CoordinatorCredentials.MemberCredential member = coordinator.clientControl().enrollTrustedLocal("No show");
                String platformUuid = platformUuid(33);
                RuntimePayloads.VanillaTransferResult admission = coordinator.actionCommands().prepareVanillaBootstrap(
                    member.memberId(), started.actionId(), platformUuid, "203.0.113.79", false);
                assertTrue(admission.error() == null || admission.error().isBlank(), admission.error());

                owner.removeMember(member.memberId());
                assertFalse(service.state().members.containsKey(member.memberId()));
                assertNull(coordinator.credentials().memberKey(member.memberId()));

                SectorRuntime runtime = coordinator.actionRuntimes().runtime(started.actionId());
                assertTrue(runtime instanceof InProcessSectorRuntime);
                GameContext actionContext = ((InProcessSectorRuntime)runtime).context();
                SharedCampaignNet actionNetwork = SharedCampaignNet.find(actionContext);
                ConnectPacket packet = new ConnectPacket();
                packet.uuid = ConnectPacket.serverUuid(platformUuid);
                TestConnection connection = new TestConnection("203.0.113.79:50001");
                RuntimeContexts.run(actionContext, () -> assertFalse(actionNetwork.validateActionAdmission(connection, packet),
                    "a durable member removal must reach the live Action before a prepared vanilla grant can be consumed"));
            }finally{
                if(owner != null) try{ owner.close(); }catch(IOException ignored){}
                service.close();
            }
            assertEquals(0, scheduler.size(), "vanilla member-removal test leaked an embedded runtime");
        }finally{
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void failedBootstrapCanRollbackNewMembershipAndCredential() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-vanilla-bootstrap-rollback-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.displayName = "Vanilla bootstrap rollback";
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.primaryPlanetName = "serpulo";
            service.createLocal(new Fi(directory.toString()), options, "127.0.0.1", 0, 0);

            SharedCampaignCoordinator coordinator = service.authority().coordinator();
            CoordinatorCredentials.MemberCredential member = coordinator.clientControl().enrollTrustedLocal("Will rollback");
            RuntimePayloads.VanillaTransferResult admission = coordinator.actionCommands().prepareVanillaBootstrap(
                member.memberId(), "not-a-valid-target", platformUuid(32), "203.0.113.78", false);
            assertFalse(admission.error() == null || admission.error().isBlank());

            coordinator.clientControl().rollbackTrustedEnrollment(member.memberId());
            assertFalse(service.state().members.containsKey(member.memberId()));
            assertNull(coordinator.credentials().memberKey(member.memberId()), "rollback must revoke the unused control credential too");
        }finally{
            service.close();
            deleteTree(directory);
        }
    }

    private static SharedCampaignState awaitStatus(SharedCampaignClient client, String actionId, ActionStatus expected, Duration timeout) throws Exception{
        long deadline = System.nanoTime() + timeout.toNanos();
        SharedCampaignState last = null;
        while(System.nanoTime() < deadline){
            last = client.snapshot();
            ActionState action = last.actions.get(actionId);
            if(action != null && action.status == ActionStatus.failed) fail("action failed: " + action.failureReason);
            if(action != null && action.status == expected) return last;
            Thread.sleep(50L);
        }
        ActionState action = last == null ? null : last.actions.get(actionId);
        fail("action did not reach " + expected + "; status=" + (action == null ? "missing" : action.status));
        return last;
    }

    private static String platformUuid(int marker){
        byte[] seed = new byte[8];
        seed[7] = (byte)marker;
        return new String(Base64Coder.encode(seed));
    }

    private static final class TestConnection extends NetConnection{
        boolean kicked;
        TestConnection(String address){ super(address); }
        @Override public void send(Object object, boolean reliable){}
        @Override public void close(){}
        @Override public void kick(String reason, long duration){ kicked = true; }
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
