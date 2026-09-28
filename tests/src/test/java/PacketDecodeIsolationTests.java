import arc.util.io.*;
import mindustry.net.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression gate for generated remote packet decode scratch under overlapping GameContext/network handling. */
@Tag("shared-campaign-parallel")
public class PacketDecodeIsolationTests{
    private static final class GeneratedLikePacket extends Packet{
        private byte[] data;
        private int decoded;

        @Override
        public void read(Reads read, int length){
            data = read.b(length);
        }

        @Override
        public void handled(){
            //Mirrors CallGenerator-generated handled(): BAIS.setBytes(DATA), then READ.* calls.
            BAIS.setBytes(data);
            decoded = READ.i();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void packetDecodeStreamsAreInstanceIsolatedAcrossWorkers() throws Exception{
        GeneratedLikePacket left = packet(0x13579bdf), right = packet(0x2468ace0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);

        Thread a = new Thread(() -> decodeLoop(left, 0x13579bdf, start, failure), "packet-decode-left");
        Thread b = new Thread(() -> decodeLoop(right, 0x2468ace0, start, failure), "packet-decode-right");
        a.start(); b.start(); start.countDown(); a.join(); b.join();

        if(failure.get() != null) throw new AssertionError("packet decode state crossed worker ownership", failure.get());
    }

    private static GeneratedLikePacket packet(int value) throws IOException{
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(bytes)){
            out.writeInt(value);
        }
        GeneratedLikePacket packet = new GeneratedLikePacket();
        packet.data = bytes.toByteArray();
        return packet;
    }

    private static void decodeLoop(GeneratedLikePacket packet, int expected, CountDownLatch start, AtomicReference<Throwable> failure){
        try{
            start.await();
            for(int i = 0; i < 50_000 && failure.get() == null; i++){
                packet.handled();
                if(packet.decoded != expected) throw new AssertionError("expected=" + expected + " actual=" + packet.decoded);
            }
        }catch(Throwable error){
            failure.compareAndSet(null, error);
        }
    }
}
