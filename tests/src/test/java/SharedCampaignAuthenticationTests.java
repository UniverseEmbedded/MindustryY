import arc.files.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/** Security regressions for invite enrollment versus durable member identity authentication. */
@Tag("shared-campaign-parallel")
public class SharedCampaignAuthenticationTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    @Timeout(20)
    void validInviteCannotAuthenticateAClaimedOwnerIdentity() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-auth-owner-spoof-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            int port = freePort();
            service.createLocal(new Fi(directory.toString()), options("Auth campaign"), "127.0.0.1", 0, port);
            String invite = service.localInviteCode();

            assertThrows(Exception.class, () -> {
                try(ControlProtocol.Connection ignored = ControlProtocol.Connection.connect(
                    "127.0.0.1", port, ControlProtocol.Role.campaignClient, "owner", ControlProtocol.deriveKey(invite))){
                }
            }, "the campaign-wide invite key must never authenticate a claimed long-lived member identity");

            SharedCampaignClient.Credential enrolled = SharedCampaignClient.enroll("127.0.0.1", port, invite, "owner");
            assertNotEquals("owner", enrolled.memberId(), "invite enrollment must use a server-issued immutable identity, not the claimed owner ID");

            try(SharedCampaignClient invited = new SharedCampaignClient("127.0.0.1", port, enrolled)){
                SharedCampaignState state = invited.snapshot();
                assertTrue(state.members.containsKey(enrolled.memberId()));
                assertThrows(IOException.class, () -> invited.updateSettings(1, false, false, SharedCampaignState.InvitePolicy.ownerOnly),
                    "an invited member must not gain owner-only settings authority by supplying the owner's display/enrollment hint");
            }

            SharedCampaignClient.Credential ownerCredential = service.localMemberCredential("owner");
            assertNotEquals(invite, ownerCredential.secret(), "owner credential must be independent from the campaign invitation secret");
            try(SharedCampaignClient owner = new SharedCampaignClient("127.0.0.1", port, ownerCredential)){
                SharedCampaignState updated = owner.updateSettings(1, false, false, SharedCampaignState.InvitePolicy.ownerOnly);
                assertEquals(SharedCampaignState.InvitePolicy.ownerOnly, updated.invitePolicy);
            }
        }finally{
            service.close();
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(20)
    void memberCredentialIsBoundToItsServerIssuedIdentity() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-auth-member-binding-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            int port = freePort();
            service.createLocal(new Fi(directory.toString()), options("Binding campaign"), "127.0.0.1", 0, port);
            SharedCampaignClient.Credential member = SharedCampaignClient.enroll("127.0.0.1", port, service.localInviteCode(), "member-a");

            try(SharedCampaignClient connected = new SharedCampaignClient("127.0.0.1", port, member)){
                assertEquals(member.memberId(), connected.memberId());
                assertTrue(connected.snapshot().members.containsKey(member.memberId()));
            }

            assertThrows(Exception.class, () -> {
                try(ControlProtocol.Connection ignored = ControlProtocol.Connection.connect(
                    "127.0.0.1", port, ControlProtocol.Role.campaignClient, "owner", ControlProtocol.deriveKey(member.secret()))){
                }
            }, "a valid member secret must not authenticate another identity");
        }finally{
            service.close();
            deleteTree(directory);
        }
    }


    @Test
    @Timeout(30)
    void removingMemberRevokesCredentialAndCannotBeUndoneByReconnect() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-auth-member-revoke-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            int port = freePort();
            service.createLocal(new Fi(directory.toString()), options("Revoke campaign"), "127.0.0.1", 0, port);
            SharedCampaignClient.Credential member = SharedCampaignClient.enroll("127.0.0.1", port, service.localInviteCode(), "member-a");
            try(SharedCampaignClient owner = new SharedCampaignClient("127.0.0.1", port, service.localMemberCredential("owner"))){
                assertTrue(owner.snapshot().members.containsKey(member.memberId()));
                SharedCampaignState removed = owner.removeMember(member.memberId());
                assertFalse(removed.members.containsKey(member.memberId()));
            }

            assertThrows(Exception.class, () -> {
                try(SharedCampaignClient ignored = new SharedCampaignClient("127.0.0.1", port, member)){
                    ignored.snapshot();
                }
            }, "a stale member secret must not recreate durable membership after removal");
            assertFalse(service.state().members.containsKey(member.memberId()));
        }finally{
            service.close(); deleteTree(directory);
        }
    }

    @Test
    @Timeout(30)
    void remoteServiceReceivesUnsolicitedCommittedSnapshots() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-auth-snapshot-push-");
        Path remoteDirectory = Files.createTempDirectory("shared-campaign-auth-snapshot-push-remote-");
        SharedCampaignService authority = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignService remote = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            int port = freePort();
            authority.createLocal(new Fi(directory.toString()), options("Push campaign"), "127.0.0.1", 0, port);
            SharedCampaignClient.Credential member = SharedCampaignClient.enroll("127.0.0.1", port, authority.localInviteCode(), "remote");
            SharedCampaignState initial = remote.connect("127.0.0.1", port, member);
            long initialRevision = initial.revision;

            authority.authority().coordinator().store().transact("owner", "shared-campaign:test-push", state -> state.displayName = "Pushed campaign");

            long deadline = System.nanoTime() + 5_000_000_000L;
            SharedCampaignState pushed = remote.state();
            while(System.nanoTime() < deadline && (pushed.revision <= initialRevision || !"Pushed campaign".equals(pushed.displayName))){
                Thread.sleep(20L);
                pushed = remote.state();
            }
            assertTrue(pushed.revision > initialRevision, "remote cached state should advance without issuing a snapshot request");
            assertEquals("Pushed campaign", pushed.displayName, "unsolicited push should refresh the product service cache");
        }finally{
            remote.close();
            authority.close();
            deleteTree(remoteDirectory);
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(70)
    void remoteSnapshotSubscriptionSurvivesIdleControlTimeoutWindow() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-auth-idle-push-");
        Path remoteDirectory = Files.createTempDirectory("shared-campaign-auth-idle-push-remote-");
        SharedCampaignService authority = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        SharedCampaignService remote = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            int port = freePort();
            authority.createLocal(new Fi(directory.toString()), options("Idle push campaign"), "127.0.0.1", 0, port);
            SharedCampaignClient.Credential member = SharedCampaignClient.enroll("127.0.0.1", port, authority.localInviteCode(), "idle-remote");
            SharedCampaignState initial = remote.connect("127.0.0.1", port, member);
            long initialRevision = initial.revision;

            // ControlProtocol connections default to a 45-second read timeout. A campaign client is a persistent push
            // subscriber, so it must stay registered even when the lobby performs no explicit control requests during
            // that entire window (for example while the player is inside an Action).
            Thread.sleep(47_000L);
            authority.authority().coordinator().store().transact("owner", "shared-campaign:test-idle-push",
                state -> state.displayName = "Still subscribed");

            long deadline = System.nanoTime() + 5_000_000_000L;
            SharedCampaignState pushed = remote.state();
            while(System.nanoTime() < deadline && (pushed.revision <= initialRevision || !"Still subscribed".equals(pushed.displayName))){
                Thread.sleep(20L);
                pushed = remote.state();
            }
            assertTrue(pushed.revision > initialRevision, "idle remote cache should still receive an unsolicited revision");
            assertEquals("Still subscribed", pushed.displayName,
                "campaign push subscription must survive the generic control read-timeout window");
        }finally{
            remote.close();
            authority.close();
            deleteTree(remoteDirectory);
            deleteTree(directory);
        }
    }

    @Test
    @Timeout(30)
    void rotatingInviteInvalidatesOldEnrollmentSecretWithoutDisconnectingExistingMembers() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-auth-invite-rotate-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        try{
            int port = freePort();
            service.createLocal(new Fi(directory.toString()), options("Rotate invite"), "127.0.0.1", 0, port);
            String oldInvite = service.localInviteCode();
            SharedCampaignClient.Credential existing = SharedCampaignClient.enroll("127.0.0.1", port, oldInvite, "existing");

            String newInvite;
            try(SharedCampaignClient owner = new SharedCampaignClient("127.0.0.1", port, service.localMemberCredential("owner"))){
                newInvite = owner.rotateInviteCode();
            }
            assertNotEquals(oldInvite, newInvite);
            assertThrows(Exception.class, () -> SharedCampaignClient.enroll("127.0.0.1", port, oldInvite, "stale-invite"),
                "rotating the invitation must revoke the previous enrollment secret");

            try(SharedCampaignClient oldMember = new SharedCampaignClient("127.0.0.1", port, existing)){
                assertTrue(oldMember.snapshot().members.containsKey(existing.memberId()),
                    "rotating invite validity must not revoke established member credentials");
            }
            SharedCampaignClient.Credential fresh = SharedCampaignClient.enroll("127.0.0.1", port, newInvite, "fresh");
            try(SharedCampaignClient connected = new SharedCampaignClient("127.0.0.1", port, fresh)){
                assertTrue(connected.snapshot().members.containsKey(fresh.memberId()));
            }
        }finally{
            service.close(); deleteTree(directory);
        }
    }

    private static SharedCampaignCreationOptions options(String name){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = name; options.ownerId = "owner"; options.ownerDisplayName = "Owner"; options.primaryPlanetName = "serpulo";
        return options;
    }

    private static int freePort() throws IOException{
        try(ServerSocket socket = new ServerSocket(0)){ socket.setReuseAddress(true); return socket.getLocalPort(); }
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
