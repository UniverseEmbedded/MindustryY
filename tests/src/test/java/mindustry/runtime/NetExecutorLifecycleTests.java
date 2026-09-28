package mindustry.runtime;

import arc.func.*;
import mindustry.net.*;
import mindustry.net.Net.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("shared-campaign-parallel")
class NetExecutorLifecycleTests{
    @Test
    @Timeout(5)
    void gameContextDisposeTerminatesItsPingExecutor() throws Exception{
        GameContext context = new GameContext("net-executor-owner");
        Net[] net = new Net[1];
        RuntimeContexts.run(context, () -> net[0] = new Net(new NoopProvider()));
        context.net = net[0];
        ExecutorService executor = pingExecutor(net[0]);
        assertFalse(executor.isShutdown());

        context.dispose();

        assertTrue(executor.isShutdown(), "Net.dispose must stop the context-owned ping executor");
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS), "ping workers must not survive GameContext disposal");
    }

    private static ExecutorService pingExecutor(Net net) throws Exception{
        Field field = Net.class.getDeclaredField("pingExecutor");
        field.setAccessible(true);
        return (ExecutorService)field.get(net);
    }

    private static final class NoopProvider implements NetProvider{
        @Override public void connectClient(String ip, int port, Runnable success) throws IOException{ success.run(); }
        @Override public void sendClient(Object object, boolean reliable){}
        @Override public void disconnectClient(){}
        @Override public void discoverServers(Cons<Host> callback, Runnable done){ done.run(); }
        @Override public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed){}
        @Override public void hostServer(int port) throws IOException{}
        @Override public Iterable<? extends NetConnection> getConnections(){ return Collections.emptyList(); }
        @Override public void closeServer(){}
    }
}
