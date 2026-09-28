import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Clean extension persistence and compatibility lifecycle gate. */
@Tag("shared-campaign-extension-lifecycle")
public class SharedCampaignExtensionLifecycleTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void requiredExtensionPersistsMutatesFailsClosedAndMigrates() throws Exception{
        Path root = Files.createTempDirectory("shared-campaign-extension-");
        Path campaign = root.resolve("campaign");
        try{
            TestExtension v1 = new TestExtension(1);
            try(SharedCampaignService service = service(root.resolve("mods-v1"))){
                service.register(v1);
                int entryPort = freePort();
                SharedCampaignState created = service.createLocal(new Fi(campaign.toString()), options(), "127.0.0.1", 0, entryPort);
                assertEquals(1, created.extensionSchemas.get("test:extension"));
                assertTrue(created.extensionRequired.get("test:extension"));
                assertEquals("test:compat", created.extensionCompatibility.get("test:extension"));
                assertEquals(1, v1.opened);
                assertEquals(created.campaignId, v1.openedCampaignId);
                assertEquals(created.campaignId, v1.openedApiCampaignId);
                assertTrue(v1.openedAuthoritative);

                service.transactExtension("owner", "test:extension", "test:write", old -> "one".getBytes(StandardCharsets.UTF_8));
                byte[] copy = service.extensionState("test:extension");
                assertEquals("one", new String(copy, StandardCharsets.UTF_8));
                copy[0] = 'X';
                assertEquals("one", new String(service.extensionState("test:extension"), StandardCharsets.UTF_8), "extensionState must be defensive");
                assertTrue(v1.commitTypes.contains("test:write"));

                SharedCampaignClient.Credential enrolled = SharedCampaignClient.enroll("127.0.0.1", entryPort, service.localInviteCode(), "extension-member");
                assertNotNull(enrolled.memberId());
                assertTrue(v1.commitTypes.contains("shared-campaign:member-enrolled"),
                    "broker-thread durable commits must reach extension observers");
                assertTrue(v1.callbacksOwned, "extension callbacks must execute with the authority GameContext bound");

                service.authority().coordinator().store().transact("owner", "test:action-preparing", state -> {
                    SharedCampaignState.ActionState action = new SharedCampaignState.ActionState();
                    action.actionId = "extension-action";
                    action.planetName = "serpulo";
                    action.sectorName = "groundZero";
                    action.status = SharedCampaignState.ActionStatus.preparing;
                    state.actions.put(action.actionId, action);
                });
                service.authority().coordinator().store().transact("owner", "test:action-running", state -> {
                    SharedCampaignState.ActionState action = state.actions.get("extension-action");
                    action.status = SharedCampaignState.ActionStatus.running;
                    action.hostGeneration = state.authorityGeneration;
                });
                assertEquals(List.of("preparing", "running"), v1.actionPhases);

                service.closeCurrent();
                assertEquals(1, v1.closed);
                assertEquals(created.campaignId, v1.closedCampaignId);
                assertEquals(created.campaignId, v1.closedApiCampaignId);
            }

            try(SharedCampaignService missing = service(root.resolve("mods-missing"))){
                assertThrows(IllegalStateException.class,
                    () -> missing.openLocal(new Fi(campaign.toString()), "owner", "127.0.0.1", 0, freePort()),
                    "a persisted required extension must fail closed when absent");
                assertFalse(missing.localAuthorityOpen());
            }

            TestExtension v2 = new TestExtension(2);
            try(SharedCampaignService upgraded = service(root.resolve("mods-v2"))){
                upgraded.register(v2);
                SharedCampaignState reopened = upgraded.openLocal(new Fi(campaign.toString()), "owner", "127.0.0.1", 0, freePort());
                assertEquals(2, reopened.extensionSchemas.get("test:extension"));
                assertEquals("one-v2", new String(upgraded.extensionState("test:extension"), StandardCharsets.UTF_8));
                assertTrue(v2.migrated, "schema upgrade must invoke extension migration");
                assertEquals(1, v2.opened);
            }
        }finally{
            deleteTree(root);
        }
    }

    private static SharedCampaignService service(Path mods){ return new SharedCampaignService(Vars.game(), new Fi(mods.toString())); }

    private static SharedCampaignCreationOptions options(){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = "Extension lifecycle";
        options.ownerId = "owner";
        options.ownerDisplayName = "owner";
        options.primaryPlanetName = "serpulo";
        return options;
    }

    private static int freePort() throws IOException{
        try(ServerSocket socket = new ServerSocket(0)){ socket.setReuseAddress(true); return socket.getLocalPort(); }
    }

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths = Files.walk(root)){
            for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class TestExtension implements SharedCampaignExtension{
        private final int schema;
        private boolean migrated;
        private int opened;
        private int closed;
        private String openedCampaignId = "";
        private String openedApiCampaignId = "";
        private boolean openedAuthoritative;
        private String closedCampaignId = "";
        private String closedApiCampaignId = "";
        private final List<String> commitTypes = new ArrayList<>();
        private final List<String> actionPhases = new ArrayList<>();
        private final GameContext expectedOwner = RuntimeContexts.requireCurrent();
        private boolean callbacksOwned = true;
        TestExtension(int schema){ this.schema = schema; }
        private void checkOwner(){ callbacksOwned &= RuntimeContexts.bound() == expectedOwner; }
        @Override public String id(){ return "test:extension"; }
        @Override public int schemaVersion(){ return schema; }
        @Override public String compatibilityId(){ return "test:compat"; }
        @Override public void onCampaignOpened(SharedCampaignContext context){
            checkOwner();
            opened++;
            openedCampaignId = context.campaignId();
            openedAuthoritative = context.authoritative();
            openedApiCampaignId = context.api().state().campaignId;
        }
        @Override public void onCampaignClosed(SharedCampaignContext context){
            checkOwner();
            closed++;
            closedCampaignId = context.campaignId();
            closedApiCampaignId = context.api().state().campaignId;
        }
        @Override public void onCampaignCommitted(CampaignCommitEvent event){ checkOwner(); commitTypes.add(event.mutationType()); }
        @Override public void onActionLifecycle(ActionLifecycleEvent event){ checkOwner(); actionPhases.add(event.phase().name()); }
        @Override public void migrate(ExtensionMigration migration){
            checkOwner();
            migrated = true;
            String input = new String(migration.input(), StandardCharsets.UTF_8);
            migration.output((input + "-v" + migration.toVersion()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
