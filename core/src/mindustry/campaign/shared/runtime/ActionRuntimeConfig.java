package mindustry.campaign.shared.runtime;

import java.util.*;

/** Runtime-local Shared Campaign action identity and credentials. */
public record ActionRuntimeConfig(ActionRuntimeDescriptor descriptor, String controlSecret, String joinSecret){
    public static final String actionIdProperty = "sharedCampaign.action.id";
    public static final String actionControlSecretProperty = "sharedCampaign.action.controlSecret";
    public static final String actionJoinSecretProperty = "sharedCampaign.action.joinSecret";
    public static final String actionControlSecretEnvironment = "SHARED_CAMPAIGN_ACTION_CONTROL_SECRET";
    public static final String actionJoinSecretEnvironment = "SHARED_CAMPAIGN_ACTION_JOIN_SECRET";
    public static final String coordinatorHostProperty = "sharedCampaign.coordinator.host";
    public static final String coordinatorPortProperty = "sharedCampaign.coordinator.port";
    public static final String authorityGenerationProperty = "sharedCampaign.authority.generation";
    public static final String runtimeIncarnationProperty = "sharedCampaign.action.runtimeIncarnation";
    public static final String actionSaveProperty = "sharedCampaign.action.save";
    public static final String actionSummaryProperty = "sharedCampaign.action.summary";
    public static final String actionLaunchProperty = "sharedCampaign.action.launch";
    public static final String actionPlanetProperty = "sharedCampaign.action.planet";
    public static final String actionSectorProperty = "sharedCampaign.action.sector";
    public static final String actionAttemptProperty = "sharedCampaign.action.attempt";
    public static final String actionGamePortProperty = "sharedCampaign.action.gamePort";
    public static final String actionNewProperty = "sharedCampaign.action.new";
    public static final String actionReservationsProperty = "sharedCampaign.action.reservations";
    public static final String actionTransportReservationsProperty = "sharedCampaign.action.transportReservations";
    public static final String actionServerCommandProperty = "sharedCampaign.action.serverCommand";

    public ActionRuntimeConfig{
        controlSecret = Objects.requireNonNullElse(controlSecret, "");
        joinSecret = Objects.requireNonNullElse(joinSecret, "");
    }
    public boolean enabled(){ return descriptor != null && !descriptor.actionId().isBlank(); }
    public String actionId(){ return enabled() ? descriptor.actionId() : ""; }
    public static ActionRuntimeConfig disabled(){ return new ActionRuntimeConfig(null, "", ""); }

    /** Process bootstrap for the isolated JVM backend. In-process runtimes receive an explicit instance instead. */
    public static ActionRuntimeConfig fromProcessBootstrap(){
        String actionId = System.getProperty(actionIdProperty, "");
        if(actionId.isBlank()) return disabled();
        int defaultPort = 6567;
        ActionRuntimeDescriptor descriptor = new ActionRuntimeDescriptor(
            actionId,
            System.getProperty(coordinatorHostProperty, "127.0.0.1"),
            Integer.getInteger(coordinatorPortProperty, 6570),
            Long.getLong(authorityGenerationProperty, 0L),
            Long.getLong(runtimeIncarnationProperty, 1L),
            System.getProperty(actionSaveProperty, "action"),
            System.getProperty(actionSummaryProperty, "config/action-summary.bin"),
            System.getProperty(actionLaunchProperty, "config/action-launch.bin"),
            System.getProperty(actionPlanetProperty, ""),
            System.getProperty(actionSectorProperty, ""),
            System.getProperty(actionAttemptProperty, ""),
            Integer.getInteger(actionGamePortProperty, defaultPort),
            Boolean.getBoolean(actionNewProperty),
            System.getProperty(actionReservationsProperty, "config/research-reservations.bin"),
            System.getProperty(actionTransportReservationsProperty, "config/transport-reservations.bin"),
            5 * 60,
            System.getProperty(actionServerCommandProperty, "config port " + defaultPort)
        );
        return new ActionRuntimeConfig(descriptor, secret(actionControlSecretProperty, actionControlSecretEnvironment),
            secret(actionJoinSecretProperty, actionJoinSecretEnvironment));
    }

    static String secret(String property, String environment){
        String value = System.getProperty(property, "");
        if(value == null || value.isBlank()) value = System.getenv(environment);
        return value == null ? "" : value;
    }
}
