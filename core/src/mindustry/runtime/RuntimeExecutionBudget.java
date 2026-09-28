package mindustry.runtime;

/** Conservative process-wide execution policy for Android multi-context host daemons. */
public final class RuntimeExecutionBudget{
    private RuntimeExecutionBudget(){}

    public static final String androidHostDaemonProperty = "mindustry.runtime.androidHostDaemon";

    public static boolean androidHostDaemon(){
        return Boolean.getBoolean(androidHostDaemonProperty);
    }

    /** Android host daemons avoid nested sector x physics/avoidance parallelism to protect thermal/FPS headroom. */
    public static boolean inlineAsyncProcesses(){
        return androidHostDaemon() && !Boolean.getBoolean("mindustry.runtime.androidHostDaemon.allowNestedAsync");
    }

    public static int defaultSectorParallelism(int hostProcessors){
        if(androidHostDaemon()) return Math.max(1, Integer.getInteger("mindustry.runtime.androidHostDaemon.maxSectorParallelism", 2));
        return Math.max(1, Math.min(8, hostProcessors - 1));
    }

    public static int defaultComputeParallelism(int hostProcessors){
        if(androidHostDaemon()) return 1;
        return Math.max(2, Math.min(8, hostProcessors - 1));
    }

    public static int defaultPathParallelism(int hostProcessors){
        if(androidHostDaemon()) return 1;
        return Math.max(1, Math.min(4, Math.max(1, hostProcessors / 2)));
    }

    /** Applies a conservative Java/Linux scheduling hint without introducing an Android dependency into core. */
    public static Thread configureWorkerThread(Thread thread){
        if(thread != null && androidHostDaemon()){
            try{ thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2)); }catch(Throwable ignored){}
        }
        return thread;
    }
}
