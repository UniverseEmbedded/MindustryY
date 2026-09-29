import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exactly-once regression coverage for campaign-client mutation request IDs. */
public class SharedCampaignControlReplayTests{
    @TempDir Path temp;

    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    void duplicateSettingsMutationWithSameRequestIdMustNotCommitTwice() throws Exception{
        Fi root = new Fi(temp.resolve("campaign").toFile());
        Fi mods = new Fi(temp.resolve("mods").toFile()); mods.mkdirs();
        SharedCampaignService service = new SharedCampaignService(Vars.game(), mods);
        try{
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.displayName = "Replay";
            options.primaryPlanetName = "serpulo";
            service.createLocal(root, options, "127.0.0.1", 0, 0);

            SharedCampaignClient.Credential credential = service.localMemberCredential(service.activeMemberId());
            int port = service.authority().coordinator().publicEntryPort();
            try(ControlProtocol.Connection connection = ControlProtocol.Connection.connect("127.0.0.1", port,
                ControlProtocol.Role.campaignClient, credential.memberId(), ControlProtocol.deriveKey(credential.secret()))){
                long requestId = 0x5eedL;
                byte[] payload = RuntimePayloads.encode(new RuntimePayloads.CampaignSettings(
                    1, true, false, InvitePolicy.ownerOnly, PersistenceProfile.highFrequencyWal));

                connection.send(ControlProtocol.Type.campaignSettingsRequest, requestId, payload);
                ControlProtocol.Frame first = receiveRequest(connection, requestId);
                assertEquals(ControlProtocol.Type.campaignSettingsResponse, first.type());
                SharedCampaignState firstState = SharedCampaignCodec.decode(first.payload());

                connection.send(ControlProtocol.Type.campaignSettingsRequest, requestId, payload);
                ControlProtocol.Frame replay = receiveRequest(connection, requestId);
                assertEquals(ControlProtocol.Type.campaignSettingsResponse, replay.type());
                SharedCampaignState replayState = SharedCampaignCodec.decode(replay.payload());

                assertEquals(firstState.revision, replayState.revision,
                    "same member/requestId/payload must replay the completed mutation instead of committing a second revision");
                assertEquals(0, replayState.controlRequestReceipts.size,
                    "strategic client snapshots must not expose coordinator replay bookkeeping");
                assertEquals(1, service.authority().state().controlRequestReceipts.size,
                    "successful mutation must durably retain one authority-side replay receipt");
            }
        }finally{
            service.close();
        }
    }

    @Test
    void duplicateSettingsMutationRemainsDeduplicatedAfterAuthorityRestart() throws Exception{
        Fi root = new Fi(temp.resolve("restart-campaign").toFile());
        Fi mods = new Fi(temp.resolve("restart-mods").toFile()); mods.mkdirs();
        SharedCampaignClient.Credential credential;
        long requestId = 0x5eed1234L;
        byte[] payload = RuntimePayloads.encode(new RuntimePayloads.CampaignSettings(
            1, true, false, InvitePolicy.ownerOnly, PersistenceProfile.highFrequencyWal));
        long committedRevision;

        SharedCampaignService first = new SharedCampaignService(Vars.game(), mods);
        try{
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.displayName = "Replay Restart";
            options.primaryPlanetName = "serpulo";
            first.createLocal(root, options, "127.0.0.1", 0, 0);
            credential = first.localMemberCredential(first.activeMemberId());
            try(ControlProtocol.Connection connection = ControlProtocol.Connection.connect("127.0.0.1",
                first.authority().coordinator().publicEntryPort(), ControlProtocol.Role.campaignClient,
                credential.memberId(), ControlProtocol.deriveKey(credential.secret()))){
                connection.send(ControlProtocol.Type.campaignSettingsRequest, requestId, payload);
                SharedCampaignState state = SharedCampaignCodec.decode(receiveRequest(connection, requestId).payload());
                committedRevision = state.revision;
                assertEquals(0, state.controlRequestReceipts.size);
                assertEquals(1, first.authority().state().controlRequestReceipts.size);
            }
        }finally{
            first.close();
        }

        SharedCampaignService reopened = new SharedCampaignService(Vars.game(), mods);
        try{
            reopened.openLocal(root, "owner", "127.0.0.1", 0, 0);
            try(ControlProtocol.Connection connection = ControlProtocol.Connection.connect("127.0.0.1",
                reopened.authority().coordinator().publicEntryPort(), ControlProtocol.Role.campaignClient,
                credential.memberId(), ControlProtocol.deriveKey(credential.secret()))){
                connection.send(ControlProtocol.Type.campaignSettingsRequest, requestId, payload);
                ControlProtocol.Frame replay = receiveRequest(connection, requestId);
                assertEquals(ControlProtocol.Type.campaignSettingsResponse, replay.type());
                SharedCampaignState replayState = SharedCampaignCodec.decode(replay.payload());
                assertEquals(committedRevision, replayState.revision,
                    "durable receipt must suppress the same mutation after coordinator restart");
                assertEquals(0, replayState.controlRequestReceipts.size);
                assertEquals(1, reopened.authority().state().controlRequestReceipts.size);
            }
        }finally{
            reopened.close();
        }
    }

    @Test
    void controlReceiptHistoryIsBoundedBelowHardSchemaLimit(){
        SharedCampaignState state = new SharedCampaignState();
        for(int i = 1; i <= 4096; i++){
            ControlRequestReceipt receipt = new ControlRequestReceipt();
            receipt.memberId = "owner";
            receipt.requestId = i;
            receipt.requestType = ControlProtocol.Type.campaignSettingsRequest.name();
            receipt.payloadHash = "0".repeat(64);
            receipt.responseType = ControlProtocol.Type.campaignSettingsResponse.name();
            receipt.completedRevision = i;
            receipt.completedAt = i;
            state.controlRequestReceipts.put(receipt.key(), receipt);
        }

        state.compactTerminalTransactionHistory();

        assertEquals(3072, state.controlRequestReceipts.size);
        assertNull(state.controlRequestReceipts.get(ControlRequestReceipt.durableKey("owner", 1L)));
        assertNotNull(state.controlRequestReceipts.get(ControlRequestReceipt.durableKey("owner", 4096L)));
    }

    @Test
    void reusingRequestIdForDifferentPayloadFailsClosedWithoutSecondCommit() throws Exception{
        Fi root = new Fi(temp.resolve("collision-campaign").toFile());
        Fi mods = new Fi(temp.resolve("collision-mods").toFile()); mods.mkdirs();
        SharedCampaignService service = new SharedCampaignService(Vars.game(), mods);
        try{
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.ownerId = "owner";
            options.ownerDisplayName = "Owner";
            options.displayName = "Replay Collision";
            options.primaryPlanetName = "serpulo";
            service.createLocal(root, options, "127.0.0.1", 0, 0);
            SharedCampaignClient.Credential credential = service.localMemberCredential(service.activeMemberId());
            long requestId = 0x7711L;
            byte[] firstPayload = RuntimePayloads.encode(new RuntimePayloads.CampaignSettings(
                1, true, false, InvitePolicy.ownerOnly, PersistenceProfile.highFrequencyWal));
            byte[] conflictingPayload = RuntimePayloads.encode(new RuntimePayloads.CampaignSettings(
                1, false, false, InvitePolicy.ownerOnly, PersistenceProfile.highFrequencyWal));

            try(ControlProtocol.Connection connection = ControlProtocol.Connection.connect("127.0.0.1",
                service.authority().coordinator().publicEntryPort(), ControlProtocol.Role.campaignClient,
                credential.memberId(), ControlProtocol.deriveKey(credential.secret()))){
                connection.send(ControlProtocol.Type.campaignSettingsRequest, requestId, firstPayload);
                SharedCampaignState firstState = SharedCampaignCodec.decode(receiveRequest(connection, requestId).payload());

                connection.send(ControlProtocol.Type.campaignSettingsRequest, requestId, conflictingPayload);
                ControlProtocol.Frame conflict = receiveRequest(connection, requestId);
                assertEquals(ControlProtocol.Type.error, conflict.type());
                assertTrue(RuntimePayloads.decodeString(conflict.payload()).contains("already used for a different request"));
                assertEquals(firstState.revision, service.authority().state().revision,
                    "request-ID collision must not create another durable revision");
                assertTrue(service.authority().state().freezeWhenEmpty,
                    "conflicting payload must not overwrite the originally committed settings");
            }
        }finally{
            service.close();
        }
    }


    @Test
    void duplicateLogisticsMutationWithSameRequestIdCommitsOnce() throws Exception{
        Fi root = new Fi(temp.resolve("logistics-replay").toFile());
        Fi mods = new Fi(temp.resolve("logistics-replay-mods").toFile()); mods.mkdirs();
        SharedCampaignService service = new SharedCampaignService(Vars.game(), mods);
        try{
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.ownerId = "owner"; options.ownerDisplayName = "Owner"; options.displayName = "Logistics Replay";
            options.primaryPlanetName = Planets.serpulo.name;
            service.createLocal(root, options, "127.0.0.1", 0, 0);
            String owner = service.activeMemberId();
            Sector source = Planets.serpulo.sectors.get(Planets.serpulo.startSector);
            Sector destination = Planets.serpulo.sectors.find(value -> value != source);
            assertNotNull(destination);
            String sourceKey = SharedCampaignProgress.sectorKey(source);
            String destinationKey = SharedCampaignProgress.sectorKey(destination);
            service.authority().coordinator().store().transact(owner, "test:seed-logistics-replay", state -> {
                state.sectors.put(sourceKey, base(source));
                state.sectors.put(destinationKey, base(destination));
            });

            SharedCampaignClient.Credential credential = service.localMemberCredential(owner);
            try(ControlProtocol.Connection connection = ControlProtocol.Connection.connect("127.0.0.1",
                service.authority().coordinator().publicEntryPort(), ControlProtocol.Role.campaignClient,
                credential.memberId(), ControlProtocol.deriveKey(credential.secret()))){
                long requestId = 0x10a1571cL;
                byte[] payload = RuntimePayloads.encode(new RuntimePayloads.SectorLogistics(sourceKey, destinationKey));
                connection.send(ControlProtocol.Type.sectorLogisticsRequest, requestId, payload);
                SharedCampaignState first = SharedCampaignCodec.decode(receiveRequest(connection, requestId).payload());
                connection.send(ControlProtocol.Type.sectorLogisticsRequest, requestId, payload);
                SharedCampaignState replay = SharedCampaignCodec.decode(receiveRequest(connection, requestId).payload());

                assertEquals(first.revision, replay.revision);
                assertEquals(destinationKey, replay.sectors.get(sourceKey).destinationSector);
                assertEquals(1, service.authority().state().controlRequestReceipts.values().toSeq()
                    .count(value -> ControlProtocol.Type.sectorLogisticsRequest.name().equals(value.requestType)));
            }
        }finally{
            service.close();
        }
    }


    @Test
    void duplicateInviteRotationWithSameRequestIdMustReplaySameCode() throws Exception{
        Fi root = new Fi(temp.resolve("invite-replay").toFile());
        Fi mods = new Fi(temp.resolve("invite-replay-mods").toFile()); mods.mkdirs();
        SharedCampaignService service = new SharedCampaignService(Vars.game(), mods);
        try{
            SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
            options.ownerId = "owner"; options.ownerDisplayName = "Owner"; options.displayName = "Invite Replay";
            options.primaryPlanetName = Planets.serpulo.name;
            service.createLocal(root, options, "127.0.0.1", 0, 0);
            SharedCampaignClient.Credential credential = service.localMemberCredential(service.activeMemberId());
            try(ControlProtocol.Connection connection = ControlProtocol.Connection.connect("127.0.0.1",
                service.authority().coordinator().publicEntryPort(), ControlProtocol.Role.campaignClient,
                credential.memberId(), ControlProtocol.deriveKey(credential.secret()))){
                long requestId = 0x1a117eL;
                connection.send(ControlProtocol.Type.inviteRotateRequest, requestId, new byte[0]);
                String first = RuntimePayloads.decodeString(receiveRequest(connection, requestId).payload());
                connection.send(ControlProtocol.Type.inviteRotateRequest, requestId, new byte[0]);
                String replay = RuntimePayloads.decodeString(receiveRequest(connection, requestId).payload());
                assertEquals(first, replay, "same request ID must not rotate the invite secret twice");
            }
        }finally{ service.close(); }
    }

    @Test
    void strategicCodecOmitsCoordinatorControlReceipts(){
        SharedCampaignState state = new SharedCampaignState();
        state.campaignId = "codec";
        state.displayName = "Codec";
        ControlRequestReceipt receipt = new ControlRequestReceipt();
        receipt.memberId = "owner"; receipt.requestId = 77L;
        receipt.requestType = ControlProtocol.Type.campaignSettingsRequest.name();
        receipt.payloadHash = "0".repeat(64);
        receipt.responseType = ControlProtocol.Type.campaignSettingsResponse.name();
        receipt.completedRevision = 1L; receipt.completedAt = 1L;
        state.controlRequestReceipts.put(receipt.key(), receipt);

        SharedCampaignState durable = SharedCampaignCodec.decode(SharedCampaignCodec.encode(state));
        SharedCampaignState strategic = SharedCampaignCodec.decode(SharedCampaignCodec.encodeStrategic(state));

        assertEquals(1, durable.controlRequestReceipts.size);
        assertTrue(strategic.controlRequestReceipts.isEmpty(),
            "client/action strategic snapshots must not carry coordinator replay receipts");
    }


    private static SectorState base(Sector sector){
        SectorState out = new SectorState();
        out.planetName = sector.planet.name;
        out.sectorName = SharedCampaignProgress.sectorId(sector);
        out.hasBase = true; out.captured = true;
        return out;
    }

    private static ControlProtocol.Frame receiveRequest(ControlProtocol.Connection connection, long requestId) throws Exception{
        while(true){
            ControlProtocol.Frame frame = connection.receive();
            if(frame.requestId() == requestId) return frame;
        }
    }
}
