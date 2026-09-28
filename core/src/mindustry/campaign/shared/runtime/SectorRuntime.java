package mindustry.campaign.shared.runtime;

import arc.files.*;

import java.io.*;
import java.nio.channels.SocketChannel;

/**
 * Runtime boundary for one live Shared Campaign Sector action.
 *
 * The Coordinator owns this abstraction instead of assuming that every action is a child JVM. Implementations must
 * preserve the same Shared Campaign authority and protocol semantics regardless of where the real World is hosted.
 */
public interface SectorRuntime extends Closeable{
    enum Backend{
        /** A real Sector hosted by the current JVM after runtime-state contextualization. */
        inProcess,
        /** A real Sector hosted by an isolated JVM process. */
        jvmProcess,
        /** A real Sector hosted by an AOT/native worker when the active content set is certified compatible. */
        nativeProcess
    }

    Backend backend();

    void start(Fi sourceSave) throws IOException;

    boolean isAlive();

    /** Process-like exit status; Integer.MIN_VALUE means not exited / not applicable yet. */
    int exitCode();

    void terminateGracefully();

    /** Abrupt termination used only by deterministic product/fault tests; implementations must not emit a clean stop. */
    default void crashForTesting(){ throw new UnsupportedOperationException("Runtime does not support test crash injection"); }

    /** Stops control-plane heartbeats while leaving the runtime/game server alive; test-only fault injection. */
    default void suppressHeartbeatsForTesting(){ throw new UnsupportedOperationException("Runtime does not support heartbeat fault injection"); }

    /**
     * Same-process Channel injection for single-entry routing. Returns true when the world took ownership of the
     * channel. Child-JVM runtimes leave this false so the broker falls back to loopback TCP relay.
     */
    default boolean injectClient(SocketChannel channel, byte[] preRead){ return false; }

    @Override
    default void close(){
        terminateGracefully();
    }
}
