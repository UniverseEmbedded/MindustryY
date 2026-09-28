import arc.util.*;
import arc.util.serialization.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.net.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Security and one-shot admission gates for the pure-vanilla /sector compatibility lane. */
@Tag("shared-campaign-parallel")
public class SharedCampaignVanillaCompatibilityTests{
    private GameContext context;
    private SharedCampaignNet network;
    private SharedCampaignState campaign;

    @BeforeEach
    void setUp(){
        context = new GameContext("vanilla-compat-test");
        ActionRuntimeDescriptor descriptor = new ActionRuntimeDescriptor(
            "action-a", "127.0.0.1", 6570, 1L, 1L,
            "action", "config/summary.bin", "config/launch.bin", "serpulo", "groundZero", "",
            6567, false, "config/research.bin", "config/transport.bin", 300, "config port 6567");
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.install(context, new ActionRuntimeConfig(descriptor, "control-secret", "join-secret"));
        network = SharedCampaignNet.install(context);
        campaign = new SharedCampaignState();
        campaign.ownerId = "owner";
        shared.attach(SharedCampaignNet.CampaignStateSource.class, () -> campaign);
    }

    @AfterEach
    void tearDown(){
        if(context != null) context.dispose();
    }

    @Test
    void vanillaGrantIsOneShotAndBindsAuthenticatedMember(){
        String platformUuid = platformUuid(1);
        ConnectPacket packet = packet(platformUuid);
        TestConnection first = new TestConnection("/203.0.113.7:43111");
        TestConnection replay = new TestConnection("203.0.113.7:43112");

        RuntimeContexts.run(context, () -> {
            network.prepareVanillaAdmission("grant-1", platformUuid, "owner", "203.0.113.7", false, Time.millis() + 15_000L);
            assertTrue(network.validateActionAdmission(first, packet));
            assertEquals("owner", network.authenticatedMemberId(first));
            assertFalse(network.consumeSpectator(first));

            assertFalse(network.validateActionAdmission(replay, packet), "a one-shot vanilla grant must not be replayable");
            assertTrue(replay.kicked);
        });
    }

    @Test
    void wrongAddressOrUuidDoesNotConsumeLegitimateGrant(){
        String platformUuid = platformUuid(2);
        ConnectPacket packet = packet(platformUuid);
        ConnectPacket wrongUuidPacket = packet(platformUuid(3));
        TestConnection wrongAddress = new TestConnection("198.51.100.40:50000");
        TestConnection wrongUuid = new TestConnection("203.0.113.9:50001");
        TestConnection legitimate = new TestConnection("/203.0.113.9:50002");

        RuntimeContexts.run(context, () -> {
            network.prepareVanillaAdmission("grant-2", platformUuid, "owner", "/203.0.113.9:44000", false, Time.millis() + 15_000L);
            assertFalse(network.validateActionAdmission(wrongAddress, packet));
            assertTrue(wrongAddress.kicked);
            assertFalse(network.validateActionAdmission(wrongUuid, wrongUuidPacket));
            assertTrue(wrongUuid.kicked);
            assertTrue(network.validateActionAdmission(legitimate, packet),
                "failed guesses must not burn the legitimate one-shot grant");
            assertEquals("owner", network.authenticatedMemberId(legitimate));
        });
    }

    @Test
    void revokedMemberInvalidatesPreparedGrantAndCannotReuseItAfterReenrollment(){
        SharedCampaignState.MemberState member = new SharedCampaignState.MemberState();
        member.memberId = "member-a";
        member.displayName = "Member A";
        campaign.members.put(member.memberId, member);
        String platformUuid = platformUuid(4);
        ConnectPacket packet = packet(platformUuid);
        TestConnection revoked = new TestConnection("203.0.113.12:51000");
        TestConnection replay = new TestConnection("203.0.113.12:51001");

        RuntimeContexts.run(context, () -> {
            network.prepareVanillaAdmission("grant-3", platformUuid, "member-a", "203.0.113.12", false, Time.millis() + 15_000L);
            campaign.members.remove("member-a");
            assertFalse(network.validateActionAdmission(revoked, packet));
            assertTrue(revoked.kicked);

            campaign.members.put(member.memberId, member);
            assertFalse(network.validateActionAdmission(replay, packet),
                "a grant rejected for revoked membership must be destroyed, not resurrected by later reenrollment");
            assertTrue(replay.kicked);
        });
    }

    @Test
    void revokeIsGrantSpecificAndPreventsResidualAdmission(){
        String platformUuid = platformUuid(7);
        TestConnection connection = new TestConnection("203.0.113.20:53000");
        RuntimeContexts.run(context, () -> {
            network.prepareVanillaAdmission("grant-7", platformUuid, "owner", "203.0.113.20", false, Time.millis() + 15_000L);
            assertFalse(network.revokeVanillaAdmission("stale-grant", platformUuid),
                "a stale cleanup must not revoke a newer grant sharing the same UUID");
            assertTrue(network.revokeVanillaAdmission("grant-7", platformUuid));
            assertFalse(network.validateActionAdmission(connection, packet(platformUuid)));
            assertTrue(connection.kicked);
        });
    }

    @Test
    void revokePayloadRoundTripsWithoutLosingGrantIdentity(){
        RuntimePayloads.VanillaAdmissionRevoke value = new RuntimePayloads.VanillaAdmissionRevoke("action-b", "grant-8", platformUuid(8));
        assertEquals(value, RuntimePayloads.vanillaAdmissionRevoke(RuntimePayloads.encode(value)));
    }

    @Test
    void spectatorRoleSurvivesVanillaAdmission(){
        String platformUuid = platformUuid(5);
        TestConnection connection = new TestConnection("[2001:db8::10]:52000");
        RuntimeContexts.run(context, () -> {
            network.prepareVanillaAdmission("grant-4", platformUuid, "owner", "[2001:db8::10]:44000", true, Time.millis() + 15_000L);
            assertTrue(network.validateActionAdmission(connection, packet(platformUuid)));
            assertEquals("owner", network.authenticatedMemberId(connection));
            assertTrue(network.consumeSpectator(connection));
            assertFalse(network.consumeSpectator(connection), "spectator approval itself is one-shot");
        });
    }

    @Test
    void uuidSeedRoundTripsAcrossVanillaReconnect(){
        String platformUuid = platformUuid(6);
        String serverUuid = ConnectPacket.serverUuid(platformUuid);
        assertNotNull(serverUuid);
        assertEquals(platformUuid, ConnectPacket.platformUuid(serverUuid));
    }

    private static ConnectPacket packet(String platformUuid){
        ConnectPacket packet = new ConnectPacket();
        packet.uuid = ConnectPacket.serverUuid(platformUuid);
        return packet;
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
}
