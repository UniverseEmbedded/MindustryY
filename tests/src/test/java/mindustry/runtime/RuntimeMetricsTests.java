package mindustry.runtime;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeMetricsTests{
    @Test
    void processSnapshotIsSelfConsistent(){
        RuntimeMetrics.Snapshot snapshot = RuntimeMetrics.snapshot();
        assertTrue(snapshot.capturedAtMillis() > 0L);
        assertTrue(snapshot.availableProcessors() >= 1);
        assertTrue(snapshot.activeContexts() >= 0);
        assertTrue(snapshot.heapUsedBytes() >= 0L);
        assertTrue(snapshot.heapCommittedBytes() >= snapshot.heapUsedBytes());
        assertTrue(snapshot.liveThreads() >= 1);
        assertTrue(snapshot.peakThreads() >= snapshot.liveThreads());
        assertTrue(snapshot.rssBytes() == -1L || snapshot.rssBytes() > 0L);
        assertTrue(snapshot.runtimeWorkerThreads() >= 0);
        assertTrue(snapshot.sectorWorkerThreads() >= 0);
        assertTrue(snapshot.pathfinderThreads() >= 0);
        assertNotNull(snapshot.computePool());
        assertNotNull(snapshot.storagePool());
        assertNotNull(snapshot.pathPool());
        assertTrue(snapshot.pathPool().parallelism() >= 1);
        assertTrue(snapshot.computePool().queueCapacity() >= snapshot.computePool().parallelism());
        assertTrue(snapshot.computePool().contextQuota() >= 1);
        assertTrue(snapshot.storagePool().contextQuota() >= 1);
    }
}
