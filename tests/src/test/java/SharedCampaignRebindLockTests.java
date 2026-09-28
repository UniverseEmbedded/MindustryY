import mindustry.campaign.shared.net.SharedCampaignNet;
import mindustry.net.Packets.ConnectPacket;
import arc.struct.Seq;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hot-switch prepare-rebind must never block forever on SharedCampaignNet's admission lock.
 * A stuck owner-lane holder used to leave {@code ui.loadfrag} up after planet-page sector switches.
 */
public class SharedCampaignRebindLockTests{
    @BeforeAll
    static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @AfterEach
    void cleanup(){
        System.clearProperty("mindustry.uiTest");
    }

    @Test
    void tryPrepareRebindJoinSucceedsWhenLockIsFree() throws Exception{
        SharedCampaignNet net = new SharedCampaignNet();
        assertTrue(net.tryPrepareRebindJoin("action-b", "token-b", false, false, "member-1", "broker-1", 250L));
        assertEquals("action-b", net.preparedActionId());
        net.clearPending();
        assertEquals("", net.preparedActionId());
    }

    @Test
    void tryPrepareRebindJoinReturnsFalseInsteadOfHangingWhenLockIsHeld() throws Exception{
        System.setProperty("mindustry.uiTest", "true");
        SharedCampaignNet net = new SharedCampaignNet();
        // decorateConnectPacket sleeps while holding the same lock when a UI-test delay is armed.
        net.configureAdmissionFaultForTesting("delay", 1_500L);
        net.prepareJoin("hold-action", "hold-token", false);

        Thread holder = new Thread(() -> {
            ConnectPacket packet = new ConnectPacket();
            packet.mods = new Seq<>();
            net.decorateConnectPacket(packet);
        }, "admission-lock-holder");
        holder.setDaemon(true);
        holder.start();

        // Wait until the holder is sleeping inside decorateConnectPacket (lock held).
        long deadline = System.currentTimeMillis() + 2_000L;
        while(holder.getState() != Thread.State.TIMED_WAITING && holder.getState() != Thread.State.WAITING
            && System.currentTimeMillis() < deadline && holder.isAlive()){
            Thread.sleep(5L);
        }
        assertEquals(Thread.State.TIMED_WAITING, holder.getState(),
            "holder must be sleeping under the admission lock before the contender runs");

        long started = System.nanoTime();
        boolean prepared = net.tryPrepareRebindJoin("action-c", "token-c", false, false, "member-1", "broker-1", 150L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertFalse(prepared, "tryPrepareRebindJoin must fail closed when the admission lock is contended");
        assertTrue(elapsedMs >= 100L, "timeout must actually wait for the configured budget, elapsed=" + elapsedMs);
        assertTrue(elapsedMs < 1_200L, "timeout must not wait for the full holder sleep, elapsed=" + elapsedMs);
        assertNotEquals("action-c", net.preparedActionId(), "timed-out prepare must not publish the destination action");

        holder.join(5_000L);
        assertFalse(holder.isAlive(), "holder must finish its UI-test delay");
        assertTrue(net.tryPrepareRebindJoin("action-c", "token-c", false, false, "member-1", "broker-1", 250L),
            "after the holder releases, prepare must succeed");
        assertEquals("action-c", net.preparedActionId());
        net.clearPending();
    }

    @Test
    void prepareRebindJoinClearsSharedEntryFlagForRebindPath(){
        SharedCampaignNet net = new SharedCampaignNet();
        net.prepareJoin("action-shared", "token", false, false, "member-1", true);
        net.prepareRebindJoin("action-b", "token-b", false, false, "member-1", "broker-1");
        assertEquals("action-b", net.preparedActionId());
        // Rebind path must not request a second MYCS preface (sharedEntry=false).
        assertNull(net.clientConnectionPreamble(), "rebind prepare must not re-arm the shared-entry preface");
        net.clearPending();
    }
}
