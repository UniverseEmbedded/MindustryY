import arc.files.Fi;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.runtime.SharedCampaignClient;
import mindustry.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for real control-plane Invite enrollment races and server-issued identity exactly-once semantics. */
@Tag("shared-campaign-parallel")
public class SharedCampaignConcurrentEnrollmentTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentInviteEnrollmentsIssueUniqueCredentialsAndAllReconnect() throws Exception{
        Path directory = Files.createTempDirectory("shared-campaign-concurrent-enrollment-");
        SharedCampaignService service = new SharedCampaignService(Vars.game(), Vars.modDirectory);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<SharedCampaignClient> connected = new ArrayList<>();
        try{
            int port = freePort();
            service.createLocal(new Fi(directory.toString()), options(), "127.0.0.1", 0, port);
            String invite = service.localInviteCode();

            int clients = 6;
            CyclicBarrier start = new CyclicBarrier(clients);
            List<Future<SharedCampaignClient.Credential>> futures = new ArrayList<>();
            for(int i = 0; i < clients; i++){
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return SharedCampaignClient.enroll("127.0.0.1", port, invite, "concurrent-" + index);
                }));
            }

            List<SharedCampaignClient.Credential> credentials = new ArrayList<>();
            Set<String> memberIds = new HashSet<>();
            Set<String> secrets = new HashSet<>();
            for(Future<SharedCampaignClient.Credential> future : futures){
                SharedCampaignClient.Credential credential = future.get(15, TimeUnit.SECONDS);
                credentials.add(credential);
                assertTrue(memberIds.add(credential.memberId()), "every concurrent enrollment must receive a unique immutable memberId");
                assertTrue(secrets.add(credential.secret()), "every concurrent enrollment must receive a unique member credential secret");
            }

            // Prove the credentials are not merely durable rows: each must authenticate over a fresh real control connection.
            for(SharedCampaignClient.Credential credential : credentials){
                SharedCampaignClient client = new SharedCampaignClient("127.0.0.1", port, credential);
                connected.add(client);
                SharedCampaignState snapshot = client.snapshot();
                assertEquals(credential.memberId(), client.memberId());
                assertTrue(snapshot.members.containsKey(credential.memberId()), "credential reconnect must resolve its server-issued member");
            }

            SharedCampaignState finalState = service.state();
            assertNotNull(finalState);
            assertEquals(clients + 1, finalState.members.size, "owner + every concurrent enrollment must exist exactly once");
            assertTrue(finalState.members.containsKey("owner"));
            for(String memberId : memberIds) assertTrue(finalState.members.containsKey(memberId));
        }finally{
            for(SharedCampaignClient client : connected) try{ client.close(); }catch(IOException ignored){}
            pool.shutdownNow();
            service.close();
            deleteTree(directory);
        }
    }

    private static SharedCampaignCreationOptions options(){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = "Concurrent enrollment"; options.ownerId = "owner"; options.ownerDisplayName = "Owner"; options.primaryPlanetName = "serpulo";
        return options;
    }

    private static int freePort() throws IOException{
        try(ServerSocket socket = new ServerSocket(0)){
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
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
