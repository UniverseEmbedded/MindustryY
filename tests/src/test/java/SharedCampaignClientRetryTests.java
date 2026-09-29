import mindustry.campaign.shared.runtime.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Transport retry must be reserved for transport failures, never coordinator-declared business errors. */
public class SharedCampaignClientRetryTests{
    @Test
    @Timeout(10)
    void remoteBusinessErrorIsReturnedWithoutReconnectOrRetry() throws Exception{
        String memberId = "member-retry";
        String secret = "retry-secret";
        byte[] key = ControlProtocol.deriveKey(secret);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();

        try(ServerSocket server = new ServerSocket(0)){
            server.setSoTimeout(3000);
            Thread peer = new Thread(() -> {
                try{
                    while(requests.get() < 3){
                        Socket socket;
                        try{ socket = server.accept(); }
                        catch(SocketTimeoutException done){ return; }
                        ControlProtocol.Accepted accepted = ControlProtocol.Connection.accept(socket, new byte[0],
                            (role, identity) -> role == ControlProtocol.Role.campaignClient && memberId.equals(identity) ? key : null,
                            server.getLocalPort());
                        try(ControlProtocol.Connection connection = accepted.connection()){
                            ControlProtocol.Frame request = connection.receive();
                            requests.incrementAndGet();
                            connection.send(ControlProtocol.Type.error, request.requestId(), RuntimePayloads.encodeString("rejected-by-authority"));
                        }
                    }
                }catch(Throwable error){ serverFailure.set(error); }
            }, "fake-shared-campaign-coordinator");
            peer.setDaemon(true);
            peer.start();

            try(SharedCampaignClient client = new SharedCampaignClient("127.0.0.1", server.getLocalPort(),
                new SharedCampaignClient.Credential(memberId, secret))){
                IOException failure = assertThrows(IOException.class, () -> client.updateSectorLogistics("a", "b"));
                assertTrue(failure.getMessage().contains("rejected-by-authority"));
            }

            peer.join(3500L);
            if(serverFailure.get() != null) throw new AssertionError("fake coordinator failed", serverFailure.get());
            assertEquals(1, requests.get(), "a coordinator error is a final business result and must not be retried on a new TCP connection");
        }
    }
}
