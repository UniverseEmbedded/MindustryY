package mindustry.campaign.shared.ui;

import arc.struct.*;

/**
 * Product-level catalog of Shared Campaign operations reachable from the Desktop UI.
 *
 * <p>The catalog is intentionally independent from localized labels. Stable Scene selectors are the UI contract used
 * by journey tests, accessibility tooling and future clients. New user operations must be registered here so the
 * completeness gate can require capability/state/failure coverage instead of silently accepting a new button.</p>
 */
public enum SharedCampaignOperation{
    createCampaign("sharedCampaign.create", Capability.guest, false),
    openLocal("sharedCampaign.openLocal", Capability.guest, false),
    deleteLocal("sharedCampaign.openLocal.delete.", Capability.guest, true),
    recoverLocal("sharedCampaign.openLocal.recover.", Capability.guest, true),
    connect("sharedCampaign.connect", Capability.guest, true),
    importMigration("sharedCampaign.importMigration", Capability.operator, true),

    planetOperations("sharedCampaign.planetOperations", Capability.member, false),
    refresh("sharedCampaign.lobby.refresh", Capability.member, false),
    compatibility("sharedCampaign.lobby.compatibility", Capability.member, false),
    settings("sharedCampaign.lobby.settings", Capability.owner, true),
    invite("sharedCampaign.invite.show", Capability.member, true),
    rotateInvite("sharedCampaign.invite.rotate", Capability.owner, true),
    removeMember("sharedCampaign.member.remove.", Capability.owner, true),
    leaveMembership("sharedCampaign.member.leavePermanent", Capability.member, true),
    migrateHost("sharedCampaign.lobby.migrate", Capability.operator, true),
    leaveCampaign("sharedCampaign.lobby.leave", Capability.member, true),

    listView("sharedCampaign.planet.list", Capability.member, false),
    research("sharedCampaign.planet.research", Capability.member, true),
    planetInvite("sharedCampaign.planet.invite", Capability.member, true),
    planetSettings("sharedCampaign.planet.settings", Capability.owner, true),
    planetCompatibility("sharedCampaign.planet.compatibility", Capability.member, false),
    planetMigration("sharedCampaign.planet.migrate", Capability.operator, true),
    sectorDetails("sharedCampaign.sector.details.", Capability.member, false),

    launch("sharedCampaign.sector.launch", Capability.member, true, "available"),
    join("sharedCampaign.sector.join", Capability.member, true, "running"),
    spectate("sharedCampaign.sector.spectate", Capability.member, true, "running"),
    suspend("sharedCampaign.action.suspend", Capability.owner, true, "running"),
    wake("sharedCampaign.sector.wake", Capability.member, true, "suspended"),
    returnCurrentAction("sharedCampaign.sector.returnAction", Capability.member, true, "running"),
    logistics("sharedCampaign.sector.logistics", Capability.member, true),
    actionDetails("sharedCampaign.action.details.", Capability.member, false),

    researchNode("research.node.", Capability.member, true),
    settingsSave("sharedCampaign.settings.save", Capability.owner, true),
    migrationExport("sharedCampaign.migration.export.confirm", Capability.operator, true),
    migrationImport("sharedCampaign.migration.import.confirm", Capability.operator, true),
    logisticsClear("sharedCampaign.logistics.clear", Capability.member, true),
    logisticsTarget("sharedCampaign.logistics.target.", Capability.member, true);

    public enum Capability{ guest, member, operator, owner }

    private final String selector;
    private final Capability capability;
    private final boolean failureJourneyRequired;
    private final ObjectSet<String> allowedStatuses = new ObjectSet<>();

    SharedCampaignOperation(String selector, Capability capability, boolean failureJourneyRequired, String... allowedStatuses){
        this.selector = selector;
        this.capability = capability;
        this.failureJourneyRequired = failureJourneyRequired;
        this.allowedStatuses.addAll(allowedStatuses);
    }

    public String selector(){ return selector; }
    public Capability capability(){ return capability; }
    public boolean failureJourneyRequired(){ return failureJourneyRequired; }
    public ObjectSet<String> allowedStatuses(){ return new ObjectSet<>(allowedStatuses); }
    public boolean stateSensitive(){ return !allowedStatuses.isEmpty(); }
}
