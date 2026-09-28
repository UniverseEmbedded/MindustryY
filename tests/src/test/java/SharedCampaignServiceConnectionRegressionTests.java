import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Late mdt-y lifecycle regressions: candidate control connections must not freeze or destroy the active Campaign. */
@Tag("shared-campaign-parallel")
public class SharedCampaignServiceConnectionRegressionTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    @Timeout(25)
    void failedReplacementConnectionKeepsCurrentRemoteCampaign() throws Exception{
        Path root = Files.createTempDirectory("shared-service-replacement-");
        SharedCampaignService authority = new SharedCampaignService(Vars.game(), new Fi(root.resolve("mods-a").toString()));
        SharedCampaignService remote = new SharedCampaignService(Vars.game(), new Fi(root.resolve("mods-b").toString()));
        try{
            int port = freePort();
            authority.createLocal(new Fi(root.resolve("campaign").toString()), options("Original"), "127.0.0.1", 0, port);
            SharedCampaignClient.Credential credential = authority.localMemberCredential("owner");
            SharedCampaignState opened = remote.connect("127.0.0.1", port, credential);

            int deadPort = freePort();
            assertThrows(IOException.class, () -> remote.connect("127.0.0.1", deadPort, credential));

            SharedCampaignState stillOpen = remote.state();
            assertEquals(opened.campaignId, stillOpen.campaignId,
                "a failed candidate connection must not close the already active Shared Campaign");
            assertTrue(remote.remoteConnected());
        }finally{
            remote.close(); authority.close(); deleteTree(root);
        }
    }

    @Test
    @Timeout(25)
    void stalledReplacementHandshakeDoesNotBlockStateApi() throws Exception{
        Path root = Files.createTempDirectory("shared-service-stalled-replacement-");
        SharedCampaignService authority = new SharedCampaignService(Vars.game(), new Fi(root.resolve("mods-a").toString()));
        SharedCampaignService remote = new SharedCampaignService(Vars.game(), new Fi(root.resolve("mods-b").toString()));
        ExecutorService workers = Executors.newFixedThreadPool(3);
        AtomicReference<Socket> accepted = new AtomicReference<>();
        try(ServerSocket stall = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())){
            int port = freePort();
            authority.createLocal(new Fi(root.resolve("campaign").toString()), options("Responsive"), "127.0.0.1", 0, port);
            SharedCampaignClient.Credential credential = authority.localMemberCredential("owner");
            SharedCampaignState opened = remote.connect("127.0.0.1", port, credential);

            CountDownLatch connected = new CountDownLatch(1);
            Future<?> accepter = workers.submit(() -> {
                try{
                    Socket socket = stall.accept();
                    accepted.set(socket);
                    connected.countDown();
                    while(!socket.isClosed()) Thread.sleep(20L);
                }catch(IOException ignored){}
                catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); }
            });
            Future<SharedCampaignState> replacement = workers.submit(() -> remote.connect("127.0.0.1", stall.getLocalPort(), credential));
            assertTrue(connected.await(3, TimeUnit.SECONDS), "candidate connection never reached the stalled endpoint");

            long started = System.nanoTime();
            SharedCampaignState whileStalled = workers.submit(remote::state).get(500, TimeUnit.MILLISECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(opened.campaignId, whileStalled.campaignId);
            assertTrue(elapsedMillis < 450L, "state() was blocked behind replacement network I/O for " + elapsedMillis + "ms");

            Socket socket = accepted.get();
            if(socket != null) socket.close();
            assertThrows(ExecutionException.class, () -> replacement.get(3, TimeUnit.SECONDS));
            accepter.cancel(true);
            assertEquals(opened.campaignId, remote.state().campaignId);
        }finally{
            Socket socket = accepted.get();
            if(socket != null) try{ socket.close(); }catch(IOException ignored){}
            workers.shutdownNow();
            remote.close(); authority.close(); deleteTree(root);
        }
    }

    private static SharedCampaignCreationOptions options(String name){
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.displayName = name;
        options.ownerId = "owner";
        options.ownerDisplayName = "Owner";
        options.primaryPlanetName = "serpulo";
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
