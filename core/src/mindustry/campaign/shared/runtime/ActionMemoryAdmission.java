package mindustry.campaign.shared.runtime;

import java.util.*;

/**
 * Host-local admission guard for live Shared Campaign Actions.
 *
 * <p>The guard is deliberately opt-in until retained-memory telemetry is stable enough to derive an automatic budget.
 * When {@link #hostMemoryBudgetMiBProperty} is unset/zero, the existing maxActiveActions policy remains authoritative.
 * Once configured, every new/resumed production Action must fit inside the explicit host budget before a durable
 * STARTING transition is committed.</p>
 */
public final class ActionMemoryAdmission{
    public static final String hostMemoryBudgetMiBProperty = "mindustry.sharedCampaign.hostMemoryBudgetMiB";
    public static final String coordinatorReserveMiBProperty = "mindustry.sharedCampaign.coordinatorReserveMiB";
    public static final String jvmNativeReserveMiBProperty = "mindustry.sharedCampaign.jvmActionNativeReserveMiB";
    public static final String inProcessActionReserveMiBProperty = "mindustry.sharedCampaign.inProcessActionReserveMiB";
    public static final int defaultCoordinatorReserveMiB = 1024;
    public static final int defaultJvmNativeReserveMiB = 256;
    public static final int defaultInProcessActionReserveMiB = 512;

    private ActionMemoryAdmission(){}

    public record Decision(boolean enabled, boolean allowed, long budgetMiB, long requiredMiB, long perActionReserveMiB, String reason){}

    /** Evaluates capacity for one additional live Action; {@code liveActions} excludes the candidate. */
    public static Decision evaluate(SectorRuntime.Backend backend, int liveActions){
        Objects.requireNonNull(backend, "backend");
        if(liveActions < 0) throw new IllegalArgumentException("Live Action count cannot be negative");
        long budget = longProperty(hostMemoryBudgetMiBProperty, 0L, 0L, 1024L * 1024L);
        if(budget == 0L) return new Decision(false, true, 0L, 0L, 0L, "");

        long coordinator = longProperty(coordinatorReserveMiBProperty, defaultCoordinatorReserveMiB, 128L, 1024L * 1024L);
        long perAction = switch(backend){
            case jvmProcess -> Math.addExact(PackagedSectorLauncher.configuredActionMaxHeapMiB(),
                longProperty(jvmNativeReserveMiBProperty, defaultJvmNativeReserveMiB, 0L, 1024L * 1024L));
            case inProcess -> longProperty(inProcessActionReserveMiBProperty, defaultInProcessActionReserveMiB, 128L, 1024L * 1024L);
            case nativeProcess -> longProperty(inProcessActionReserveMiBProperty, defaultInProcessActionReserveMiB, 128L, 1024L * 1024L);
        };
        long required = Math.addExact(coordinator, Math.multiplyExact((long)liveActions + 1L, perAction));
        if(required <= budget) return new Decision(true, true, budget, required, perAction, "");
        String reason = "Host memory budget would be exceeded by another " + backend + " Action (budget=" + budget
            + " MiB, required=" + required + " MiB, coordinatorReserve=" + coordinator + " MiB, perActionReserve="
            + perAction + " MiB, liveActions=" + liveActions + ")";
        return new Decision(true, false, budget, required, perAction, reason);
    }

    private static long longProperty(String name, long fallback, long minimum, long maximum){
        String raw = System.getProperty(name, "").trim();
        if(raw.isEmpty()) return fallback;
        final long value;
        try{
            value = Long.parseLong(raw);
        }catch(NumberFormatException error){
            throw new IllegalArgumentException(name + " must be an integer MiB value: " + raw, error);
        }
        if(value < minimum || value > maximum) throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum + " MiB: " + value);
        return value;
    }
}
