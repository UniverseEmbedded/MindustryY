package mindustry.runtime;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Process-level observability snapshot for multi-context runtime health and serial/parallel acceptance gates. */
public final class RuntimeMetrics{
    private RuntimeMetrics(){}

    public record Snapshot(long capturedAtMillis, int availableProcessors, int activeContexts,
                           long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
                           long nonHeapUsedBytes, long directBufferBytes, long rssBytes,
                           int liveThreads, int peakThreads, int runtimeWorkerThreads, int sectorWorkerThreads, int pathfinderThreads,
                           long gcCollections, long gcMillis,
                           double processCpuLoad, long processCpuNanos,
                           int openFileDescriptors, int openSockets,
                           RuntimeWorkerPool.Metrics computePool, RuntimeWorkerPool.Metrics storagePool,
                           RuntimePathExecutor.Metrics pathPool){}

    public static Snapshot snapshot(){
        ManagementSnapshot management = managementSnapshot();
        int[] fds = procFileDescriptors();
        int[] named = namedThreadCounts();
        long rss = procRssBytes();
        RuntimeWorkerPool.Metrics computePool = RuntimeWorkerPool.sharedCompute().metrics();
        RuntimeWorkerPool.Metrics storagePool = RuntimeStorageExecutor.metrics();
        RuntimePathExecutor.Metrics pathPool = RuntimePathExecutor.shared().metrics();
        int activeContexts = Math.max(pathPool.registeredLanes(), Math.max(computePool.trackedContexts(), storagePool.trackedContexts()));
        return new Snapshot(System.currentTimeMillis(), Runtime.getRuntime().availableProcessors(), activeContexts,
            management.heapUsedBytes, management.heapCommittedBytes, management.heapMaxBytes,
            management.nonHeapUsedBytes, management.directBufferBytes, rss,
            management.liveThreads, management.peakThreads, named[0], named[1], named[2],
            management.gcCollections, management.gcMillis, management.processCpuLoad, management.processCpuNanos,
            fds[0], fds[1], computePool, storagePool, pathPool);
    }

    /**
     * java.lang.management and com.sun.management are desktop/server JDK APIs and are not part of Android's
     * API-21 runtime. Keep them behind reflection so loading this shared-core class never links those types on
     * Android. Unsupported metrics keep the existing sentinel convention of {@code -1}.
     */
    private static ManagementSnapshot managementSnapshot(){
        Runtime runtime = Runtime.getRuntime();
        ManagementSnapshot out = new ManagementSnapshot();
        out.heapCommittedBytes = runtime.totalMemory();
        out.heapUsedBytes = Math.max(0L, out.heapCommittedBytes - runtime.freeMemory());
        out.heapMaxBytes = runtime.maxMemory();

        try{
            Set<Thread> threads = Thread.getAllStackTraces().keySet();
            out.liveThreads = threads.size();
        }catch(SecurityException ignored){
            out.liveThreads = -1;
        }

        try{
            Class<?> managementFactory = Class.forName("java.lang.management.ManagementFactory");
            readMemoryMetrics(managementFactory, out);
            readThreadMetrics(managementFactory, out);
            readGcMetrics(managementFactory, out);
            readBufferPoolMetrics(managementFactory, out);
            readOperatingSystemMetrics(managementFactory, out);
        }catch(ReflectiveOperationException | LinkageError | SecurityException ignored){
            // Android and trimmed/custom runtimes are allowed to have no JMX management surface.
        }
        return out;
    }

    private static void readMemoryMetrics(Class<?> managementFactory, ManagementSnapshot out) throws ReflectiveOperationException{
        Class<?> memoryBean = Class.forName("java.lang.management.MemoryMXBean");
        Class<?> memoryUsage = Class.forName("java.lang.management.MemoryUsage");
        Object bean = managementFactory.getMethod("getMemoryMXBean").invoke(null);
        Object heap = memoryBean.getMethod("getHeapMemoryUsage").invoke(bean);
        Object nonHeap = memoryBean.getMethod("getNonHeapMemoryUsage").invoke(bean);
        out.heapUsedBytes = longValue(memoryUsage, heap, "getUsed", out.heapUsedBytes);
        out.heapCommittedBytes = longValue(memoryUsage, heap, "getCommitted", out.heapCommittedBytes);
        out.heapMaxBytes = longValue(memoryUsage, heap, "getMax", out.heapMaxBytes);
        out.nonHeapUsedBytes = longValue(memoryUsage, nonHeap, "getUsed", -1L);
    }

    private static void readThreadMetrics(Class<?> managementFactory, ManagementSnapshot out) throws ReflectiveOperationException{
        Class<?> threadBean = Class.forName("java.lang.management.ThreadMXBean");
        Object bean = managementFactory.getMethod("getThreadMXBean").invoke(null);
        out.liveThreads = intValue(threadBean, bean, "getThreadCount", out.liveThreads);
        out.peakThreads = intValue(threadBean, bean, "getPeakThreadCount", -1);
    }

    private static void readGcMetrics(Class<?> managementFactory, ManagementSnapshot out) throws ReflectiveOperationException{
        Class<?> gcBean = Class.forName("java.lang.management.GarbageCollectorMXBean");
        Object beans = managementFactory.getMethod("getGarbageCollectorMXBeans").invoke(null);
        if(!(beans instanceof Iterable<?> iterable)) return;
        long collections = 0L, millis = 0L;
        for(Object bean : iterable){
            long count = longValue(gcBean, bean, "getCollectionCount", -1L);
            long time = longValue(gcBean, bean, "getCollectionTime", -1L);
            if(count >= 0L) collections += count;
            if(time >= 0L) millis += time;
        }
        out.gcCollections = collections;
        out.gcMillis = millis;
    }

    private static void readBufferPoolMetrics(Class<?> managementFactory, ManagementSnapshot out) throws ReflectiveOperationException{
        Class<?> bufferBean = Class.forName("java.lang.management.BufferPoolMXBean");
        Object beans = managementFactory.getMethod("getPlatformMXBeans", Class.class).invoke(null, bufferBean);
        if(!(beans instanceof Iterable<?> iterable)) return;
        long direct = 0L;
        for(Object bean : iterable){
            Object name = bufferBean.getMethod("getName").invoke(bean);
            if(!"direct".equals(name) && !"mapped".equals(name)) continue;
            direct += Math.max(0L, longValue(bufferBean, bean, "getMemoryUsed", 0L));
        }
        out.directBufferBytes = direct;
    }

    private static void readOperatingSystemMetrics(Class<?> managementFactory, ManagementSnapshot out) throws ReflectiveOperationException{
        Object bean = managementFactory.getMethod("getOperatingSystemMXBean").invoke(null);
        Class<?> sunBean;
        try{
            sunBean = Class.forName("com.sun.management.OperatingSystemMXBean");
        }catch(ClassNotFoundException ignored){
            return;
        }
        if(!sunBean.isInstance(bean)) return;
        Object load = sunBean.getMethod("getProcessCpuLoad").invoke(bean);
        Object nanos = sunBean.getMethod("getProcessCpuTime").invoke(bean);
        if(load instanceof Number number) out.processCpuLoad = number.doubleValue();
        if(nanos instanceof Number number) out.processCpuNanos = number.longValue();
    }

    private static long longValue(Class<?> owner, Object target, String method, long fallback) throws ReflectiveOperationException{
        Object value = owner.getMethod(method).invoke(target);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static int intValue(Class<?> owner, Object target, String method, int fallback) throws ReflectiveOperationException{
        Object value = owner.getMethod(method).invoke(target);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static int[] namedThreadCounts(){
        int runtimeWorkers = 0, sectorWorkers = 0, pathfinders = 0;
        try{
            for(Thread thread : Thread.getAllStackTraces().keySet()){
                if(thread == null) continue;
                String name = thread.getName();
                if(name.startsWith("Mindustry-Runtime-Compute-") || name.startsWith("Mindustry-Runtime-Worker-")) runtimeWorkers++;
                if(name.startsWith("SharedCampaign-InProcess-Worker-") || name.equals("SharedCampaign-InProcess-Sectors")) sectorWorkers++;
                if(name.equals("Pathfinder") || name.equals("Control Pathfinder") || name.startsWith("Mindustry-Runtime-Path-")) pathfinders++;
            }
        }catch(SecurityException ignored){}
        return new int[]{runtimeWorkers, sectorWorkers, pathfinders};
    }

    /** Linux VmRSS is a useful process-level baseline; unsupported hosts report -1. */
    private static long procRssBytes(){
        Path status = Path.of("/proc/self/status");
        if(!Files.isRegularFile(status)) return -1L;
        try{
            for(String line : Files.readAllLines(status)){
                if(!line.startsWith("VmRSS:")) continue;
                String[] parts = line.substring(6).trim().split("\\s+");
                if(parts.length > 0) return Long.parseLong(parts[0]) * 1024L;
            }
        }catch(Exception ignored){}
        return -1L;
    }

    /** Linux provides exact process FD/socket counts; unsupported hosts report -1 rather than inventing data. */
    private static int[] procFileDescriptors(){
        Path root = Path.of("/proc/self/fd");
        if(!Files.isDirectory(root)) return new int[]{-1, -1};
        int count = 0, sockets = 0;
        try(var stream = Files.list(root)){
            for(Path path : (Iterable<Path>)stream::iterator){
                count++;
                try{
                    String target = Files.readSymbolicLink(path).toString();
                    if(target.startsWith("socket:[")) sockets++;
                }catch(Exception ignored){}
            }
            return new int[]{count, sockets};
        }catch(Exception ignored){
            return new int[]{-1, -1};
        }
    }

    private static final class ManagementSnapshot{
        long heapUsedBytes = -1L;
        long heapCommittedBytes = -1L;
        long heapMaxBytes = -1L;
        long nonHeapUsedBytes = -1L;
        long directBufferBytes = -1L;
        int liveThreads = -1;
        int peakThreads = -1;
        long gcCollections = -1L;
        long gcMillis = -1L;
        double processCpuLoad = -1d;
        long processCpuNanos = -1L;
    }
}
