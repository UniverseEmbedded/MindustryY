package mindustry.campaign.shared.ui;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.func.*;
import arc.util.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.net.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.type.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.game.EventType.ResizeEvent;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.util.*;
import java.util.concurrent.*;

import static mindustry.Vars.*;

/** First-party product surface for creating, opening and joining a Shared Campaign. */
public class SharedCampaignDialog extends BaseDialog{
    /** Stable first-party Desktop shared-entry default. Action servers automatically skip ports already bound here. */
    private static final int defaultSharedPort = 6567;
    private final SharedCampaignService service;
    private final SharedLaunchLoadoutDialog launchPlanner = new SharedLaunchLoadoutDialog();
    /** Default: Action endpoints use loopback, which means "same public host as the coordinator" to remote members. */
    private boolean advertiseOverride;
    private String advertisedHost = "127.0.0.1";
    private TextField advertisedFieldRef;
    private SharedCampaignPlanetDialog planetDialog;
    private String renderedCampaignId = "";
    private long renderedRevision = Long.MIN_VALUE;
    private boolean rebuildQueued;
    /** Prevents overlapping product operations and lets the load-fragment watchdog fail closed. */
    private boolean busy;

    public SharedCampaignDialog(){
        super("@sharedcampaign.title");
        name = "sharedCampaign.dialog";
        cont.name = "sharedCampaign.content";
        buttons.name = "sharedCampaign.buttons";
        service = SharedCampaignService.install(game(), modDirectory);
        addCloseButton();
        shown(this::rebuild);
        onResize(this::rebuild);
        // BaseDialog.onResize can be skipped while a child dialog is on top. Keep the strategic surface
        // responsive across orientation/window changes even when a picker/details dialog is open.
        Events.on(ResizeEvent.class, event -> {
            if(isShown()) Core.app.post(this::rebuild);
        });
        cont.update(() -> {
            if(!isShown() || !service.remoteConnected() || rebuildQueued) return;
            try{
                SharedCampaignState current = service.state();
                if(Objects.equals(renderedCampaignId, current.campaignId) && renderedRevision == current.revision) return;
                rebuildQueued = true;
                Core.app.post(() -> {
                    rebuildQueued = false;
                    if(isShown()) rebuild();
                });
            }catch(Throwable ignored){}
        });
        Events.on(SharedCampaignEvents.CampaignSnapshotUpdated.class, event -> Core.app.post(() -> {
            if(busy || !strategicSurfaceVisible()) return;
            SharedCampaignState current = service.strategicState();
            if(current == null || !Objects.equals(event.campaignId(), current.campaignId)) return;
            if(isShown()) rebuild();
            else if(planetDialog != null && planetDialog.isShown()) planetDialog.updateCampaign(current);
        }));
    }

    public boolean strategicSurfaceVisible(){
        return isShown() || planetDialog != null && planetDialog.isShown();
    }

    /** Live logical width available to Shared Campaign UI. Never cache across orientation/scale changes. */
    private float sceneWidth(){
        if(Core.scene != null && Core.scene.root != null && Core.scene.root.getWidth() > 0f) return Core.scene.root.getWidth();
        if(Core.graphics != null) return Core.graphics.getWidth() / Scl.scl();
        return 900f;
    }

    private float sceneHeight(){
        if(Core.scene != null && Core.scene.root != null && Core.scene.root.getHeight() > 0f) return Core.scene.root.getHeight();
        if(Core.graphics != null) return Core.graphics.getHeight() / Scl.scl();
        return 700f;
    }

    private float contentWidth(){
        return Math.min(920f, Math.max(240f, sceneWidth() - 70f));
    }

    private float availableHeight(float reserved){
        return Math.max(180f, sceneHeight() - reserved);
    }

    private void rebuild(){
        cont.clear();
        cont.defaults().pad(6f);
        if(!service.localAuthorityOpen() && !service.remoteConnected()){
            renderedCampaignId = "";
            renderedRevision = Long.MIN_VALUE;
            buildLanding();
        }else{
            buildActive();
            if(planetDialog != null && planetDialog.isShown()){
                try{ planetDialog.updateCampaign(service.state()); }catch(Throwable ignored){}
            }
        }
    }

    private void buildLanding(){
        cont.top();
        float width = Math.min(600f, contentWidth());
        cont.defaults().pad(7f);
        cont.add("@sharedcampaign.welcome.description").color(Color.lightGray).wrap().growX().width(width)
            .name("sharedCampaign.entry.description").row();
        cont.button("@sharedcampaign.create", Icon.add, this::showCreate).width(width).height(62f).name("sharedCampaign.create").row();
        cont.button("@sharedcampaign.openlocal", Icon.folder, this::showOpen).width(width).height(62f).name("sharedCampaign.openLocal").row();
        cont.button("@sharedcampaign.restore", Icon.refresh, this::showRestoreBackup).width(width).height(62f).name("sharedCampaign.restore").row();
        cont.button("@sharedcampaign.connect", Icon.link, this::showConnect).width(width).height(62f).name("sharedCampaign.connect").row();
        cont.button("@sharedcampaign.importmigration", Icon.download, this::showMigrationImport).width(width).height(62f).name("sharedCampaign.importMigration").row();
        cont.add("@sharedcampaign.security.note").color(Pal.gray).wrap().growX().width(width).padTop(5f).row();
    }

    private void buildActive(){
        SharedCampaignState state;
        try{ state = service.state(); }
        catch(Throwable failure){ buildLanding(); return; }
        renderedCampaignId = state.campaignId == null ? "" : state.campaignId;
        renderedRevision = state.revision;
        boolean owner = Objects.equals(service.activeMemberId(), state.ownerId);
        boolean mayInvite = owner || state.invitePolicy == InvitePolicy.members && state.members.containsKey(service.activeMemberId());

        cont.top();
        cont.name = "sharedCampaign.lobby";
        cont.table(Styles.black6, head -> {
            head.left();
            Label title = new Label(state.displayName == null || state.displayName.isBlank() ? Core.bundle.get("sharedcampaign.title") : state.displayName, Styles.outlineLabel);
            title.setEllipsis(true);
            head.add(title).left().growX().maxWidth(contentWidth()).name("sharedCampaign.lobby.displayName");
            head.add("r" + state.revision).color(Pal.gray).padRight(8f).name("sharedCampaign.lobby.revision");
            head.button(Icon.info, this::showCompatibility).size(48f).tooltip("@sharedcampaign.compatibility").name("sharedCampaign.lobby.compatibility");
            if(owner) head.button(Icon.settings, () -> showSettings(state)).size(48f).tooltip("@settings").name("sharedCampaign.lobby.settings");
            head.button(Icon.refresh, this::refreshActive).size(48f).name("sharedCampaign.lobby.refresh");
            head.button(Icon.exit, () -> { service.closeCurrent(); rebuild(); }).size(48f).tooltip("@sharedcampaign.close").name("sharedCampaign.lobby.leave");
        }).growX().maxWidth(contentWidth()).row();

        cont.table(info -> {
            info.left();
            info.add(Core.bundle.format("sharedcampaign.summary", state.members.size, state.runningActions(), state.maxActiveActions, state.primaryPlanetName))
                .color(Pal.gray).growX().left().wrap();
            if(mayInvite) info.button("@sharedcampaign.invitecode", Icon.players, this::copyInvite).height(42f).minWidth(150f).name("sharedCampaign.invite.show");
            if(service.localAuthorityOpen()) info.button("@sharedcampaign.migratehost", Icon.export, this::showMigrationExport).height(42f).minWidth(160f).name("sharedCampaign.lobby.migrate");
        }).growX().maxWidth(contentWidth()).pad(6f).row();

        cont.pane(pane -> {
            pane.top().left();
            buildStrategicSummary(pane, state, owner);
            pane.add("@sharedcampaign.actions.heading").style(Styles.outlineLabel).left().growX().padTop(14f).row();
            buildActions(pane, state, owner);
            pane.button("@sharedcampaign.planetoperations", Icon.planet, () -> showStrategy(state)).height(54f).growX().padTop(12f)
                .name("sharedCampaign.planetOperations").row();
            if(erekirBoardVisible(state)){
                pane.add("@sharedcampaign.erekirmissions").style(Styles.outlineLabel).left().growX().padTop(18f).row();
                for(Sector sector : Planets.erekir.sectors) if(sector.preset != null) buildMissionCard(pane, state, sector.preset);
            }
            service.uiExtensions().build(SharedCampaignUiRegistry.Surface.campaignLobby, pane,
                new SharedCampaignUiRegistry.Context(service, state, null, null, "lobby"));
        }).growX().maxWidth(contentWidth()).height(Math.min(680f, availableHeight(165f))).row();
    }

    private void buildStrategicSummary(Table pane, SharedCampaignState state, boolean owner){
        int bases = 0, captured = 0, threatened = 0, waiting = 0;
        Seq<SectorState> threatenedSectors = new Seq<>(), logisticsWarnings = new Seq<>();
        for(SectorState sector : state.sectors.values()){
            if(sector.hasBase) bases++;
            if(sector.captured) captured++;
            if(sector.attacked){ threatened++; threatenedSectors.add(sector); }
            if(sector.waitingSettlement) waiting++;
            if(sector.logisticsWarning != null && !sector.logisticsWarning.isBlank()) logisticsWarnings.add(sector);
        }
        int preparing = 0;
        for(ActionState action : state.actions.values()) if(action.status == ActionStatus.preparing || action.status == ActionStatus.starting) preparing++;
        Seq<MemberState> members = state.members.values().toSeq();
        members.sort(Comparator.comparing(member -> member.displayName == null ? member.memberId : member.displayName, String.CASE_INSENSITIVE_ORDER));
        int online = 0;
        for(MemberState member : members) if(member.lastConnectedAt > member.lastDisconnectedAt) online++;
        boolean narrow = Core.graphics != null && Core.graphics.isPortrait() || sceneWidth() < 900f;
        int baseCount = bases, capturedCount = captured, threatCount = threatened, onlineCount = online, preparingCount = preparing, waitingCount = waiting;

        pane.table(Styles.black5, summary -> {
            summary.left();
            summary.add("@sharedcampaign.strategicoverview").style(Styles.outlineLabel).left().growX().row();
            summary.table(metrics -> {
                metrics.left();
                if(narrow){
                    metrics.add(Core.bundle.format("sharedcampaign.primaryplanet", state.primaryPlanetName)).left().row();
                    metrics.add(Core.bundle.format("sharedcampaign.basecount", baseCount)).left().row();
                    metrics.add(Core.bundle.format("sharedcampaign.capturecount", capturedCount)).left().row();
                    metrics.add(Core.bundle.format("sharedcampaign.onlinecount", onlineCount, state.members.size)).left().row();
                    metrics.add(Core.bundle.format("sharedcampaign.threatcount", threatCount)).color(threatCount > 0 ? Pal.remove : Pal.gray).left().row();
                }else{
                    metrics.add(Core.bundle.format("sharedcampaign.primaryplanet", state.primaryPlanetName)).padRight(16f);
                    metrics.add(Core.bundle.format("sharedcampaign.basecount", baseCount)).padRight(16f);
                    metrics.add(Core.bundle.format("sharedcampaign.capturecount", capturedCount)).padRight(16f);
                    metrics.add(Core.bundle.format("sharedcampaign.onlinecount", onlineCount, state.members.size)).padRight(16f);
                    metrics.add(Core.bundle.format("sharedcampaign.threatcount", threatCount)).color(threatCount > 0 ? Pal.remove : Pal.gray);
                }
            }).growX().left().row();
            summary.add(Core.bundle.format("sharedcampaign.progresssummary", state.researched.size, state.discovered.size, preparingCount, waitingCount))
                .color(Pal.gray).wrap().left().growX().maxWidth(Math.max(220f, contentWidth() - 55f)).row();
        }).growX().padBottom(6f).row();

        pane.table(Styles.black5, membersTable -> {
            membersTable.left();
            membersTable.add("@sharedcampaign.members").style(Styles.outlineLabel).left().growX();
            membersTable.button(Icon.menu, () -> showMembers(state)).size(40f).tooltip("@sharedcampaign.members").name("sharedCampaign.members");
            membersTable.row();
            for(MemberState member : members){
                boolean connected = member.lastConnectedAt > member.lastDisconnectedAt;
                ActionState current = SharedCampaignProgress.currentMemberAction(state, member.memberId, true);
                String display = member.displayName == null || member.displayName.isBlank() ? shortId(member.memberId) : Strings.stripColors(member.displayName);
                String location = current == null ? Core.bundle.get("sharedcampaign.lobby") : current.planetName + "/" + current.sectorName;
                boolean self = Objects.equals(member.memberId, service.activeMemberId());
                boolean mayRemove = owner && !Objects.equals(member.memberId, state.ownerId);
                membersTable.table(row -> {
                    row.left();
                    Label label = new Label((connected ? "[accent]●[] " : "[gray]○[] ") + display + "  [gray]" + location + "[]");
                    label.setEllipsis(true);
                    row.add(label).left().growX().maxWidth(contentWidth());
                    if(mayRemove) row.button(Icon.trash, () -> confirmRemoveMember(null, member)).size(38f).name("sharedCampaign.member.remove." + safeName(member.memberId));
                    else if(self && !Objects.equals(member.memberId, state.ownerId)) row.button(Icon.exit, () -> confirmLeaveMembership(null, member.memberId)).size(38f).name("sharedCampaign.member.leavePermanent");
                }).growX().row();
            }
        }).growX().padBottom(6f).row();

        if(!threatenedSectors.isEmpty()){
            threatenedSectors.sort(Comparator.comparing(sector -> sector.planetName + "/" + sector.sectorName));
            pane.table(Styles.black5, warning -> {
                warning.left(); warning.add("@sharedcampaign.threatenedsectors").style(Styles.outlineLabel).color(Pal.remove).left().growX().row();
                for(SectorState sector : threatenedSectors) warning.add(sector.planetName + "/" + sector.sectorName).left().growX().row();
            }).growX().padBottom(6f).row();
        }
        if(!logisticsWarnings.isEmpty()){
            logisticsWarnings.sort(Comparator.comparing(sector -> sector.planetName + "/" + sector.sectorName));
            pane.table(Styles.black5, warning -> {
                warning.left(); warning.add("@sharedcampaign.logisticswarnings").style(Styles.outlineLabel).color(Pal.accent).left().growX().row();
                for(SectorState sector : logisticsWarnings) warning.add(sector.planetName + "/" + sector.sectorName + " — " + sector.logisticsWarning).wrap().left().growX().row();
            }).growX().padBottom(6f).row();
        }
        Seq<ResearchState> activeResearch = state.research.values().toSeq().select(value -> !value.complete());
        if(!activeResearch.isEmpty()){
            activeResearch.sort(Comparator.comparing(value -> value.contentName));
            pane.table(Styles.black5, research -> {
                research.left(); research.add("@sharedcampaign.activeresearch").style(Styles.outlineLabel).left().growX().row();
                for(ResearchState value : activeResearch){
                    int required = 0, contributed = 0;
                    for(ObjectMap.Entry<String, Integer> entry : value.required){ required += entry.value; contributed += value.contributed.get(entry.key, 0); }
                    research.add(value.contentName + "  " + contributed + " / " + required).left().growX().row();
                }
            }).growX().padBottom(6f).row();
        }
        if(!state.recentEvents.isEmpty() || !state.backups.isEmpty()){
            pane.table(Styles.black5, recent -> {
                recent.left(); recent.add("@sharedcampaign.recentactivity").style(Styles.outlineLabel).left().growX().row();
                int first = Math.max(0, state.recentEvents.size - 6);
                for(int i = first; i < state.recentEvents.size; i++){
                    CampaignEvent event = state.recentEvents.get(i);
                    String subject = event.subjectId == null || event.subjectId.isBlank() ? "" : " — " + compactSubject(event.subjectId);
                    Label eventLine = new Label(compactTimestamp(event.timestamp) + "  " + event.type + subject);
                    eventLine.setWrap(true);
                    recent.add(eventLine).color(Pal.gray).wrap().left().growX().maxWidth(Math.max(220f, contentWidth() - 55f)).row();
                }
                if(!state.backups.isEmpty()){
                    BackupState latest = state.backups.max(Comparator.comparingLong(value -> value.createdAt));
                    if(latest != null) recent.add(Core.bundle.format("sharedcampaign.latestbackup", latest.displayName, compactTimestamp(latest.createdAt), latest.complete ? Core.bundle.get("complete") : Core.bundle.get("sharedcampaign.incomplete")))
                        .color(Pal.gray).wrap().left().growX().maxWidth(Math.max(220f, contentWidth() - 55f)).row();
                }
            }).growX().padBottom(6f).row();
        }
    }

    /** Whether Erekir actually participates in this campaign; installed planet policies alone must not surface its board. */
    static boolean erekirBoardVisible(SharedCampaignState campaign){
        return campaign != null && (Objects.equals(campaign.primaryPlanetName, "erekir")
            || campaign.sectors.values().toSeq().contains(sector -> Objects.equals(sector.planetName, "erekir"))
            || campaign.actions.values().toSeq().contains(action -> Objects.equals(action.planetName, "erekir"))
            || campaign.missions.values().toSeq().contains(mission -> Objects.equals(mission.planetName, "erekir")));
    }

    private void buildMissionCard(Table pane, SharedCampaignState state, SectorPreset preset){
        SharedCampaignProgress.Availability availability = SharedCampaignProgress.missionAvailability(state, preset);
        String sectorId = SharedCampaignProgress.sectorId(preset.sector);
        ActionState existing = state.actions.values().toSeq().find(action -> Objects.equals(action.planetName, preset.planet.name)
            && Objects.equals(action.sectorName, sectorId) && (action.status.isLive() || action.status == ActionStatus.suspended));
        SectorState sector = state.sectors.get(SharedCampaignProgress.sectorKey(preset.sector));
        var definition = service.missions().forSector(preset.planet.name, sectorId);
        MissionState mission = definition == null ? null : state.missions.get(definition.id());
        pane.table(Styles.grayPanel, card -> {
            card.left();
            card.image(preset.uiIcon).size(42f).padRight(8f);
            card.table(text -> {
                text.left();
                text.add(preset.localizedName).left().row();
                if(sector != null && sector.captured) text.add("@complete").color(Pal.heal).left();
                else if(mission != null && mission.status == MissionStatus.incompatible) text.add("@sharedcampaign.incompatible").color(Pal.remove).left();
                else if(!availability.available()) text.add(availability.missing().toString(", ")).color(Pal.remove).wrap().left().width(380f);
                else if(mission != null && mission.phase != null && !mission.phase.isBlank()) text.add(phaseLabel(mission.phase)).color(Pal.accent).left();
                else text.add("@sharedcampaign.available").color(Pal.accent).left();
            }).growX();
            if(mission != null) card.button(Icon.info, () -> showMissionDetails(preset, mission)).size(46f)
                .name("sharedCampaign.mission.details." + safeName(preset.name));
            if(existing != null){
                String label = existing.status == ActionStatus.suspended ? "@resume" : "@join";
                card.button(label, Icon.play, () -> {
                    if(existing.status == ActionStatus.suspended) resumeAction(existing);
                    else joinAction(existing.actionId, false);
                }).height(48f).width(120f).name("sharedCampaign.mission.join." + safeName(preset.name));
            }else{
                card.button("@sharedcampaign.startmission", Icon.play, () -> planSectorLaunch(preset.sector)).height(48f).width(160f)
                    .disabled(!availability.available() || state.runningActions() >= state.maxActiveActions || mission != null && mission.status == MissionStatus.incompatible)
                    .name("sharedCampaign.mission.start." + safeName(preset.name));
            }
        }).growX().pad(3f).row();
    }

    private void showMissionDetails(SectorPreset preset, MissionState mission){
        BaseDialog dialog = new BaseDialog(preset.localizedName);
        dialog.addCloseButton();
        dialog.cont.defaults().left().pad(4f);
        dialog.cont.add(Core.bundle.get("status") + ": " + Core.bundle.get("sharedcampaign.missionstatus." + mission.status.name(), mission.status.name())).row();
        if(mission.phase != null && !mission.phase.isBlank()) dialog.cont.add(Core.bundle.get("sharedcampaign.phase") + ": " + phaseLabel(mission.phase)).row();
        dialog.cont.add("@sharedcampaign.objectives").color(Pal.accent).padTop(8f).row();
        if(mission.objectives.isEmpty()) dialog.cont.add("@none").color(Pal.gray).row();
        Seq<ObjectiveState> objectives = mission.objectives.values().toSeq();
        objectives.sort(Comparator.comparingLong(objective -> objective.activatedAtTick));
        int index = 1;
        for(ObjectiveState objective : objectives){
            int number = index++;
            dialog.cont.table(line -> {
                line.left();
                line.image(objective.status == ObjectiveStatus.completed ? Icon.ok : Icon.cancel).size(22f).padRight(6f);
                line.add(Core.bundle.format("sharedcampaign.objective.number", number)).growX().left();
                if(objective.requiredProgress > 0) line.add(objective.progress + " / " + objective.requiredProgress).color(Pal.gray);
            }).growX().row();
        }
        dialog.show();
    }


    private String compactTimestamp(long timestamp){
        return new java.text.SimpleDateFormat("MM-dd HH:mm").format(new Date(timestamp));
    }

    private String compactSubject(String subject){
        if(subject == null || subject.length() <= 24) return subject == null ? "" : subject;
        // UUID/action/member identifiers dominate narrow lobby rows; keep them recognizable without widening the card.
        return subject.substring(0, 12) + "…";
    }

    private String phaseLabel(String phase){
        if(phase == null || phase.isBlank()) return Core.bundle.get("none");
        return Core.bundle.get("sharedcampaign.phase." + phase, phase);
    }

    /** Explicit network refresh remains a recovery path even though normal remote clients receive pushed revisions. */
    private void refreshActive(){
        if(!service.remoteConnected()){ rebuild(); return; }
        runAsync("@loading", () -> service.controlClient().snapshot(), ignored -> rebuild());
    }

    private void buildActions(Table list, SharedCampaignState state, boolean owner){
        list.top().left(); list.defaults().growX().pad(3f);
        Seq<ActionState> actions = state.actions.values().toSeq();
        actions.sort(Comparator.comparingLong(action -> action.createdAt));
        if(actions.isEmpty()){
            list.add("@sharedcampaign.actions.empty").color(Pal.gray).left().row();
            return;
        }
        boolean compactCards = Core.graphics != null && Core.graphics.isPortrait() || sceneWidth() < 760f;
        for(ActionState action : actions){
            list.table(Styles.black5, card -> {
                card.left();
                card.table(labels -> {
                    labels.left();
                    labels.add(action.planetName + "/" + action.sectorName).left();
                    labels.add("  " + actionKindLabel(action.kind)).color(Pal.gray).left();
                    labels.add("  " + actionStatusLabel(action.status)).color(actionStatusColor(action.status)).left().row();
                    labels.add(Core.bundle.format("sharedcampaign.actionmeta", action.connectedPlayers, action.summary.wave, phaseLabel(action.summary.phase))).color(Pal.gray).left();
                    if(action.connectedSpectators > 0) labels.add("  " + Core.bundle.format("sharedcampaign.spectatorcount", action.connectedSpectators)).color(Pal.gray).left();
                }).growX().left();
                if(compactCards) card.row();
                card.table(actionsTable -> {
                    actionsTable.right();
                    actionsTable.button(Icon.info, () -> showActionDetails(action)).size(46f).name("sharedCampaign.action.details." + safeName(action.actionId));
                    if(action.status.isLive()){
                        actionsTable.button("@sharedcampaign.join", Icon.play, () -> joinAction(action.actionId, false)).height(46f).minWidth(96f).name("sharedCampaign.action.join." + safeName(action.actionId));
                        if(action.spectatorsAllowed) actionsTable.button("@sharedcampaign.spectate", Icon.eye, () -> joinAction(action.actionId, true)).height(46f).name("sharedCampaign.action.spectate." + safeName(action.actionId));
                        if(owner && action.connectedPlayers == 0) actionsTable.button(Icon.pause, () -> suspendAction(action.actionId)).size(46f).tooltip("@sharedcampaign.suspend")
                            .name("sharedCampaign.action.suspend." + safeName(action.actionId));
                    }else if(action.status == ActionStatus.suspended){
                        actionsTable.button("@sharedcampaign.resume", Icon.play, () -> resumeAction(action)).height(46f).minWidth(110f).name("sharedCampaign.action.resume." + safeName(action.actionId));
                    }
                }).growX().align(compactCards ? Align.left : Align.right);
            }).growX().pad(4f).row();
        }
    }

    private String actionKindLabel(ActionKind kind){
        if(kind == null) return Core.bundle.get("none");
        return Core.bundle.get("sharedcampaign.actionkind." + kind.name(), kind.name());
    }

    private Color actionStatusColor(ActionStatus status){
        if(status == ActionStatus.failed || status == ActionStatus.incompatible) return Pal.remove;
        if(status == ActionStatus.completed) return Pal.heal;
        return status.isLive() ? Pal.accent : Pal.gray;
    }

    private void showStrategy(SharedCampaignState snapshot){
        showPlanetBoard(snapshot, null);
    }
    /** Opens the Shared strategic 3D planet page while a real Action is still running. */
    public void showPlanetFromAction(){
        SharedCampaignState snapshot = service.strategicState();
        if(snapshot == null){ ui.showErrorMessage("No active Shared Campaign"); return; }
        Planet initial = null;
        if(game().state != null && game().state.rules != null && game().state.rules.sector != null) initial = game().state.rules.sector.planet;
        showPlanetBoard(snapshot, initial);
    }

    /** Shared-aware research entry for HUD/input routing while attached to an Action. */
    public void showResearchFromAction(){
        Planet planet = game().state != null && game().state.rules != null && game().state.rules.sector != null ? game().state.rules.sector.planet : null;
        SharedCampaignUiRouter.showSharedStrategicResearch(planet);
    }

    /** Explicitly detaches the current real-time Action and returns to the durable Shared Campaign lobby. */
    public void leaveActionAndShowLobby(){
        try{
            if(service.owner().netClient != null) service.owner().netClient.disconnectQuietly();
        }catch(Throwable ignored){}
        try{ SharedCampaignNet.install(service.owner()).clearClientActionConnection(); }catch(Throwable ignored){}
        try{ if(service.owner().logic != null) service.owner().logic.reset(); }catch(Throwable ignored){}
        Core.app.post(() -> {
            if(planetDialog != null && planetDialog.isShown()) planetDialog.hide();
            if(!isShown()) show();
            else rebuild();
        });
    }


    private void showPlanetBoard(SharedCampaignState snapshot, Planet requestedInitial){
        Planet initial = requestedInitial;
        if(initial == null || initial.generator == null || initial.sectors.isEmpty()) initial = content.planet(snapshot.primaryPlanetName);
        if(initial == null || initial.generator == null || initial.sectors.isEmpty()) initial = Planets.serpulo;
        if(planetDialog == null) planetDialog = new SharedCampaignPlanetDialog(service);
        planetDialog.show(SharedCampaignStateCopy.copy(snapshot), initial, new SharedCampaignPlanetDialog.Operations(){
            @Override public String memberId(){ return service.activeMemberId(); }
            @Override public boolean owner(){ return Objects.equals(service.activeMemberId(), snapshot.ownerId); }
            @Override public boolean mayInvite(){ return owner() || snapshot.invitePolicy == InvitePolicy.members && snapshot.members.containsKey(service.activeMemberId()); }
            @Override public boolean authoritative(){ return service.localAuthorityOpen(); }
            @Override public void refresh(){ refreshActive(); }
            @Override public void list(Planet planet){ showStrategyList(planet); }
            @Override public void research(Planet planet){ SharedCampaignUiRouter.showSharedStrategicResearch(planet); }
            @Override public void settings(){ showSettings(service.state()); }
            @Override public void invite(){ copyInvite(); }
            @Override public void compatibility(){ showCompatibility(); }
            @Override public void migrate(){ showMigrationExport(); }
            @Override public void details(Sector sector){ showSectorDetails(sector); }
            @Override public void actionDetails(ActionState action){ showActionDetails(action); }
            @Override public void launch(Sector sector){ planSectorLaunch(sector); }
            @Override public void join(ActionState action){ joinAction(action.actionId, false); }
            @Override public void spectate(ActionState action){ joinAction(action.actionId, true); }
            @Override public void suspend(ActionState action){ suspendAction(action.actionId); }
            @Override public void logistics(Sector sector){ showLogistics(null, SharedCampaignProgress.sectorKey(sector)); }
        });
    }

    /** Dense list view retained as an alternate to the 3D strategic planet page. */
    private void showStrategyList(Planet requested){
        SharedCampaignState snapshot;
        try{ snapshot = service.state(); }catch(Throwable failure){ ui.showException(failure); return; }
        BaseDialog dialog = new BaseDialog("@sharedcampaign.strategy");
        dialog.addCloseButton();
        dialog.cont.pane(list -> {
            list.top().left(); list.defaults().growX().pad(3f);
            Seq<Planet> planets = new Seq<>();
            if(requested != null) planets.add(requested);
            else{
                for(String name : snapshot.planetPolicies.keys()){
                    Planet planet = content.planet(name);
                    if(planet != null) planets.addUnique(planet);
                }
                for(SectorState sector : snapshot.sectors.values()){
                    Planet planet = content.planet(sector.planetName);
                    if(planet != null) planets.addUnique(planet);
                }
            }
            planets.sort(Comparator.comparing(planet -> planet.localizedName, String.CASE_INSENSITIVE_ORDER));
            for(Planet planet : planets){
                list.table(header -> {
                    header.left();
                    header.add(planet.localizedName).color(Pal.accent).growX().left();
                    header.button("@research", Icon.tree, () -> SharedCampaignUiRouter.showSharedStrategicResearch(planet)).height(44f).width(150f)
                        .name("sharedCampaign.research." + safeName(planet.name));
                    header.button(Icon.planet, () -> { dialog.hide(); showPlanetBoard(snapshot, planet); }).size(44f)
                        .name("sharedCampaign.planet.open." + safeName(planet.name));
                }).growX().padTop(6f).row();
                Seq<Sector> visible = new Seq<>();
                for(Sector sector : planet.sectors) if(SharedCampaignProgress.sectorInspectable(snapshot, sector)) visible.add(sector);
                visible.sort(Comparator.comparing(SharedCampaignProgress::sectorId));
                for(Sector sector : visible) buildSectorRow(list, dialog, snapshot, sector);
            }
            if(planets.isEmpty()) list.add("@sharedcampaign.noplanets").color(Pal.gray).left().row();
        }).grow().minHeight(360f).row();
        dialog.show();
    }

    private ActionState actionForSector(SharedCampaignState state, Sector sector){
        if(state == null || sector == null) return null;
        ActionState best = null;
        int bestRank = -1;
        String sectorId = SharedCampaignProgress.sectorId(sector);
        for(ActionState candidate : state.actions.values()){
            if(!Objects.equals(candidate.planetName, sector.planet.name) || !Objects.equals(candidate.sectorName, sectorId)) continue;
            int rank = candidate.status == ActionStatus.preparing ? 6 :
                candidate.status.isLive() ? 5 :
                candidate.status == ActionStatus.suspended ? 4 :
                candidate.status == ActionStatus.incompatible ? 3 :
                candidate.status == ActionStatus.failed ? 2 : -1;
            if(rank < 0) continue;
            if(best == null || rank > bestRank || rank == bestRank && candidate.updatedAt > best.updatedAt){
                best = candidate;
                bestRank = rank;
            }
        }
        return best;
    }

    private String sectorDisplayName(Sector sector, SectorState saved){
        if(saved != null && saved.displayName != null && !saved.displayName.isBlank()) return saved.displayName;
        if(sector.preset != null && (sector.preset.requireUnlock || sector.preset.showHidden)) return sector.preset.localizedName;
        if(sector.planet.sectors.size == 1) return sector.planet.localizedName;
        return sector.name();
    }

    private UnlockableContent resolveSharedContent(String identity){
        if(identity == null || identity.isBlank()) return null;
        int split = identity.indexOf(':');
        if(split <= 0 || split + 1 >= identity.length()) return null;
        try{
            ContentType type = ContentType.valueOf(identity.substring(0, split));
            var found = content.getByName(type, identity.substring(split + 1));
            return found instanceof UnlockableContent unlock ? unlock : null;
        }catch(Throwable ignored){
            return null;
        }
    }

    private TextureRegion sectorListIcon(SharedCampaignState state, Sector sector, SectorState saved, SharedCampaignProgress.Availability availability){
        if(saved != null && saved.attacked) return Fonts.getLargeIcon("warning");
        if(SharedCampaignProgress.sectorInspectable(state, sector) && (availability == null || !availability.available())) return Fonts.getLargeIcon("lock");
        if(saved != null){
            UnlockableContent contentIcon = resolveSharedContent(saved.contentIcon);
            if(contentIcon != null && contentIcon.uiIcon != null && contentIcon.uiIcon.found()) return contentIcon.uiIcon;
            if(saved.icon != null && !saved.icon.isBlank()) return Fonts.getLargeIcon(saved.icon);
        }
        if(sector.preset != null && sector.preset.uiIcon != null && sector.preset.uiIcon.found()) return sector.preset.uiIcon;
        return Fonts.getLargeIcon("terrain");
    }

    private boolean sectorVulnerable(SharedCampaignState state, Sector sector, SectorState saved){
        if(saved == null || !saved.hasBase || !sector.planet.campaignRules.sectorInvasion) return false;
        return sector.near().contains(neighbor -> {
            SectorState enemy = state.sectors.get(SharedCampaignProgress.sectorKey(neighbor));
            return enemy != null && enemy.hasEnemyBase && (neighbor.preset == null || !neighbor.preset.requireUnlock);
        });
    }

    private String sectorThreatText(Sector sector, SectorState saved){
        float threat = saved == null ? 0f : saved.threat;
        if(threat <= 0.0001f && sector.preset != null) threat = sector.preset.difficulty / 10f;
        String[] levels = {"low", "medium", "high", "extreme", "eradication"};
        String key = sector.preset != null && arc.math.Mathf.equal(sector.preset.difficulty, SectorDifficulty.unreasonable) ?
            "unreasonable" : levels[Math.min(Math.max((int)(threat / 0.25f), 0), levels.length - 1)];
        return Core.bundle.get("threat." + key);
    }

    private void addSharedStrategicDetails(Table body, SharedCampaignState state, Sector sector, SectorState saved, SharedCampaignProgress.Availability availability){
        if(saved == null || !saved.hasBase){
            if(availability != null && availability.available()) body.add(Core.bundle.get("sectors.threat") + " " + sectorThreatText(sector, saved)).color(Pal.gray).left().row();
        }
        if(saved == null) return;
        if(sectorVulnerable(state, sector, saved)) body.add("@sectors.vulnerable").color(Pal.remove).left().row();
        else if(!saved.hasBase && saved.hasEnemyBase) body.add("@sectors.enemybase").color(Pal.remove).left().row();
        if(saved.resources != null && saved.resources.any()){
            body.table(resources -> {
                resources.left(); resources.add("@sectors.resources").padRight(6f);
                for(String identity : saved.resources){
                    UnlockableContent value = resolveSharedContent(identity);
                    if(value != null && value.uiIcon != null && value.uiIcon.found()) resources.image(value.uiIcon).size(24f).padRight(3f);
                }
            }).left().growX().padTop(3f).row();
        }
    }

    private void buildSectorRow(Table list, BaseDialog parent, SharedCampaignState state, Sector sector){
        String key = SharedCampaignProgress.sectorKey(sector);
        SectorState durable = state.sectors.get(key);
        ActionState action = actionForSector(state, sector);
        SharedCampaignProgress.Availability availability = SharedCampaignProgress.sectorAvailability(state, sector);
        list.table(Styles.grayPanel, row -> {
            row.left();
            TextureRegion icon = sectorListIcon(state, sector, durable, availability);
            if(icon != null) row.image(icon).size(38f).padRight(6f);
            row.button(Icon.info, () -> showSectorDetails(sector)).size(46f)
                .name("sharedCampaign.sector.details." + safeName(key));
            row.table(info -> {
                info.left();
                info.add(sectorDisplayName(sector, durable)).growX().left().row();
                info.add(sectorStatus(durable, action, availability)).color(Color.lightGray).left();
                if(action != null && action.status == ActionStatus.suspending){
                    info.row(); info.add("@sharedcampaign.sectorstate.suspending").color(Pal.accent).left();
                }
                if(!availability.available() && availability.missing() != null && !availability.missing().isEmpty()){
                    info.row(); info.add(availability.missing().first()).color(Pal.gray).wrap().left();
                }
                addSharedStrategicDetails(info, state, sector, durable, availability);
            }).growX();
            if(durable != null && durable.hasBase) row.button(Icon.export, () -> showLogistics(parent, key)).size(46f)
                .name("sharedCampaign.sector.logistics");
            if(action != null && action.status == ActionStatus.suspended){
                row.button("@sharedcampaign.wake", Icon.play, () -> { parent.hide(); resumeAction(action); }).height(46f).width(120f)
                    .name("sharedCampaign.sector.wake");
            }else if(action != null && action.status == ActionStatus.running){
                row.button("@sharedcampaign.join", Icon.play, () -> { parent.hide(); joinAction(action.actionId, false); }).height(46f).width(105f)
                    .name("sharedCampaign.sector.join");
                if(action.spectatorsAllowed) row.button(Icon.eye, () -> { parent.hide(); joinAction(action.actionId, true); }).size(46f)
                    .name("sharedCampaign.sector.spectate");
            }else if(action != null && (action.status == ActionStatus.preparing || action.status == ActionStatus.starting || action.status == ActionStatus.suspending)){
                String label = action.status == ActionStatus.suspending ? "@sharedcampaign.sectorstate.suspending" : "@sharedcampaign.sectorstate.preparing";
                row.button(label, Icon.refresh, () -> {}).height(46f).width(140f).disabled(true)
                    .name("sharedCampaign.sector.transitioning");
            }else if(action != null && action.status == ActionStatus.incompatible){
                row.button(Icon.info, this::showCompatibility).size(46f).name("sharedCampaign.sector.compatibility");
            }else if(availability.available()){
                row.button("@sharedcampaign.launch", Icon.play, () -> launchSector(parent, sector)).height(46f).width(120f)
                    .disabled(state.runningActions() >= state.maxActiveActions)
                    .name("sharedCampaign.sector.launch");
            }
        }).growX().row();
    }

    private String sectorStatus(SectorState sector, ActionState action, SharedCampaignProgress.Availability availability){
        if(action != null) return action.status.name();
        if(sector != null && sector.attacked) return Core.bundle.get("sharedcampaign.status.attacked");
        if(sector != null && sector.hasBase) return Core.bundle.get("sharedcampaign.status.base");
        if(sector != null && sector.captured) return Core.bundle.get("sharedcampaign.status.captured");
        return Core.bundle.get(availability.available() ? "sharedcampaign.status.available" : "sharedcampaign.status.locked");
    }

    private void launchSector(BaseDialog parent, Sector sector){
        planSectorLaunch(parent, sector);
    }

    private void planSectorLaunch(Sector sector){
        planSectorLaunch(null, sector);
    }

    private void planSectorLaunch(BaseDialog parent, Sector sector){
        SharedCampaignState snapshot;
        try{ snapshot = service.state(); }catch(Throwable failure){ ui.showException(failure); return; }
        String missionId = "";
        var mission = service.missions().forSector(sector.planet.name, SharedCampaignProgress.sectorId(sector));
        if(mission != null) missionId = mission.id();
        String finalMissionId = missionId;
        SectorState saved = snapshot.sectors.get(SharedCampaignProgress.sectorKey(sector));
        if(saved != null && saved.saveRelativePath != null && !saved.saveRelativePath.isBlank()){
            startSector(parent, sector, finalMissionId, RuntimePayloads.LaunchPlan.empty(), false);
            return;
        }
        Seq<SectorState> sources = SharedCampaignProgress.launchSources(snapshot, sector);
        if(sources.isEmpty()) startSector(parent, sector, finalMissionId, RuntimePayloads.LaunchPlan.empty(), true);
        else launchPlanner.show(SharedCampaignStateCopy.copy(snapshot), sector, plan -> startSector(parent, sector, finalMissionId, plan, true));
    }

    private void startSector(BaseDialog parent, Sector sector, String missionId, RuntimePayloads.LaunchPlan launch, boolean landingPresentation){
        // Close the planet page up front so @loading is not trapped behind a full-screen paused dialog.
        hideSharedUiForJoin();
        runAsync("@loading", () -> startAndAwaitRunning(sector.planet.name, SharedCampaignProgress.sectorId(sector), missionId, launch), result -> {
            if(result.error() != null && !result.error().isBlank()){ ui.showErrorMessage(result.error()); return; }
            if(parent != null) parent.hide();
            joinAction(result.actionId(), false, landingPresentation);
        });
    }

    private void showLogistics(BaseDialog strategy, String sourceKey){
        SharedCampaignState state;
        try{ state = service.state(); }catch(Throwable failure){ ui.showException(failure); return; }
        SectorState source = state.sectors.get(sourceKey);
        if(source == null || !source.hasBase) return;
        BaseDialog dialog = new BaseDialog("@sharedcampaign.logistics");
        dialog.addCloseButton();
        dialog.cont.add("@sharedcampaign.logisticsdescription").color(Color.lightGray).wrap().growX().maxWidth(Math.min(560f, contentWidth())).pad(8f).row();
        dialog.cont.button("@sharedcampaign.logisticsclear", Icon.cancel, () -> updateLogistics(strategy, dialog, sourceKey, "")).growX().height(50f)
            .name("sharedCampaign.logistics.clear").row();
        Seq<SectorState> targets = state.sectors.values().toSeq().select(candidate -> candidate.hasBase && source.planetName.equals(candidate.planetName)
            && !sourceKey.equals(SharedCampaignProgress.sectorKey(candidate.planetName, candidate.sectorName)));
        targets.sort(Comparator.comparing(candidate -> candidate.sectorName));
        for(SectorState target : targets){
            String key = SharedCampaignProgress.sectorKey(target.planetName, target.sectorName);
            Sector actual = SharedCampaignProgress.findSector(target.planetName, target.sectorName);
            String label = actual != null && actual.preset != null ? actual.preset.localizedName : target.sectorName;
            dialog.cont.button(label, Styles.flatt, () -> updateLogistics(strategy, dialog, sourceKey, key)).growX().height(50f)
                .name("sharedCampaign.logistics.target." + safeName(target.sectorName)).row();
        }
        if(targets.isEmpty()) dialog.cont.add("@sharedcampaign.logisticsnone").color(Pal.gray).pad(10f).row();
        dialog.show();
    }

    private void updateLogistics(BaseDialog strategy, BaseDialog selector, String source, String destination){
        runAsync("@loading", () -> service.controlClient().updateSectorLogistics(source, destination), updated -> {
            selector.hide(); if(strategy != null) strategy.hide(); showStrategy(updated);
        });
    }

    private void showCompatibility(){
        SharedCampaignState state;
        try{ state = service.state(); }catch(Throwable failure){ ui.showException(failure); return; }
        BaseDialog dialog = new BaseDialog("Shared Campaign compatibility");
        dialog.addCloseButton();
        dialog.cont.pane(list -> {
            list.top().left(); list.defaults().growX().left().pad(4f);
            list.add("Runtime fingerprint").color(Pal.accent).row();
            list.add(state.contentFingerprint == null ? "" : state.contentFingerprint).color(Color.lightGray).wrap().row();
            list.add("Extensions").color(Pal.accent).padTop(8f).row();
            if(state.extensionSchemas.isEmpty()) list.add("None").color(Pal.gray).row();
            Seq<String> ids = state.extensionSchemas.keys().toSeq();
            ids.sort();
            for(String id : ids){
                String compat = state.extensionCompatibility.get(id, "");
                boolean required = state.extensionRequired.get(id, false);
                list.add(id + "  schema=" + state.extensionSchemas.get(id, 0) + "  required=" + required + "  compat=" + compat).wrap().row();
            }
        }).grow().minHeight(260f).row();
        dialog.show();
    }

    private void showSectorDetails(Sector sector){
        SharedCampaignState state;
        try{ state = service.state(); }catch(Throwable failure){ ui.showException(failure); return; }
        String key = SharedCampaignProgress.sectorKey(sector);
        SectorState saved = state.sectors.get(key);
        ActionState action = actionForSector(state, sector);
        SharedCampaignProgress.Availability availability = SharedCampaignProgress.sectorAvailability(state, sector);
        BaseDialog dialog = new BaseDialog(sectorDisplayName(sector, saved));
        dialog.name = "sharedCampaign.sectorDetails";
        dialog.addCloseButton();
        dialog.cont.defaults().left().pad(4f);
        dialog.cont.add(sector.planet.localizedName + " / " + SharedCampaignProgress.sectorId(sector)).color(Pal.accent).row();
        dialog.cont.add(sectorStatus(saved, action, availability)).color(Color.lightGray).row();
        addSharedStrategicDetails(dialog.cont, state, sector, saved, availability);
        if(saved == null){
            dialog.cont.add("@sharedcampaign.sector.nosummary").color(Pal.gray).row();
        }else{
            dialog.cont.add("base=" + saved.hasBase + " captured=" + saved.captured + " attacked=" + saved.attacked).row();
            dialog.cont.add("wave=" + saved.summary.wave + " cores=" + saved.summary.coreCount + " units=" + saved.summary.unitCount + " enemies=" + saved.summary.enemyCount).row();
            if(saved.logisticsWarning != null && !saved.logisticsWarning.isBlank()) dialog.cont.add(saved.logisticsWarning).color(Pal.remove).wrap().row();
        }
        if(action != null && action.status == ActionStatus.suspended){
            dialog.buttons.button("@sharedcampaign.wake", Icon.play, () -> { dialog.hide(); resumeAction(action); }).size(190f, 58f)
                .name("sharedCampaign.sector.wake");
        }else if(action != null && action.status.isLive()){
            dialog.buttons.button("@sharedcampaign.join", Icon.play, () -> { dialog.hide(); joinAction(action.actionId, false); }).size(150f, 58f)
                .name("sharedCampaign.sector.join");
            if(action.spectatorsAllowed) dialog.buttons.button(Icon.eye, () -> { dialog.hide(); joinAction(action.actionId, true); }).size(58f)
                .name("sharedCampaign.sector.spectate");
        }else if(action != null && action.status == ActionStatus.incompatible){
            dialog.buttons.button("@sharedcampaign.compatibility", Icon.info, () -> { dialog.hide(); showCompatibility(); }).size(200f, 58f)
                .name("sharedCampaign.sector.compatibility");
        }else if(availability.available()){
            dialog.buttons.button(saved != null && saved.saveRelativePath != null && !saved.saveRelativePath.isBlank() ? "@sharedcampaign.wake" : "@sharedcampaign.launch", Icon.play,
                () -> { dialog.hide(); planSectorLaunch(sector); }).size(190f, 58f)
                .disabled(state.runningActions() >= state.maxActiveActions).name("sharedCampaign.sector.launch");
        }
        ActionState current = SharedCampaignProgress.currentMemberAction(state, service.activeMemberId(), true);
        if(current != null && current.status.isLive() && (action == null || !current.actionId.equals(action.actionId))){
            dialog.buttons.button("@sharedcampaign.returnaction", Icon.left, () -> { dialog.hide(); joinAction(current.actionId, false); }).size(210f, 58f)
                .name("sharedCampaign.sector.returnAction");
        }
        if(saved != null && saved.hasBase){
            dialog.buttons.button("@sharedcampaign.logistics", Icon.export, () -> showLogistics(dialog, key)).size(210f, 58f)
                .name("sharedCampaign.sector.logistics");
        }
        service.uiExtensions().build(SharedCampaignUiRegistry.Surface.sectorDetails, dialog.cont,
            new SharedCampaignUiRegistry.Context(service, state, saved, action, "sector"));
        dialog.show();
    }

    private void showActionDetails(ActionState action){
        SharedCampaignState state;
        try{ state = service.state(); }catch(Throwable failure){ ui.showException(failure); return; }
        BaseDialog dialog = new BaseDialog("@sharedcampaign.actiondetails");
        dialog.name = "sharedCampaign.actionDetails";
        dialog.addCloseButton();
        dialog.cont.defaults().left().pad(4f);
        dialog.cont.add(action.planetName + " / " + action.sectorName).color(Pal.accent).row();
        dialog.cont.add(Core.bundle.get("status") + ": " + actionStatusLabel(action.status)).row();
        if(action.objectiveSummary != null && !action.objectiveSummary.isBlank()) dialog.cont.add(Core.bundle.get("sharedcampaign.objective") + ": " + action.objectiveSummary).wrap().row();
        if(action.resourceNeeds != null && !action.resourceNeeds.isEmpty()) dialog.cont.add(Core.bundle.get("sharedcampaign.resourceneeds") + ": " + itemMap(action.resourceNeeds)).wrap().row();
        dialog.cont.add(Core.bundle.get("sharedcampaign.wave") + ": " + action.summary.wave).row();
        dialog.cont.add(Core.bundle.get("sharedcampaign.players") + ": " + action.connectedPlayers).row();
        dialog.cont.add(Core.bundle.get("sharedcampaign.spectators") + ": " + action.connectedSpectators).row();
        if(!action.participants.isEmpty()){
            dialog.cont.add("@sharedcampaign.participants").color(Pal.accent).padTop(6f).row();
            dialog.cont.add(participantNames(state, action)).wrap().row();
        }
        if(action.failureReason != null && !action.failureReason.isBlank()) dialog.cont.add(action.failureReason).color(Pal.remove).wrap().row();
        service.uiExtensions().build(SharedCampaignUiRegistry.Surface.actionDetails, dialog.cont,
            new SharedCampaignUiRegistry.Context(service, state, null, action, "action"));
        dialog.show();
    }

    private String participantNames(SharedCampaignState state, ActionState action){
        Seq<String> names = new Seq<>();
        for(String id : action.participants){
            MemberState member = state.members.get(id);
            String display = member == null || member.displayName == null ? "" : Strings.stripColors(member.displayName).trim();
            if(display.isBlank() || display.equals(id)) display = Core.bundle.get("sharedcampaign.member");
            names.add(display);
        }
        names.sort();
        return names.isEmpty() ? Core.bundle.get("none") : names.toString("\n");
    }

    private String itemMap(ObjectMap<String, Integer> values){
        Seq<String> lines = new Seq<>();
        for(ObjectMap.Entry<String, Integer> entry : values) if(entry.value != null && entry.value > 0) lines.add(entry.key + " × " + entry.value);
        lines.sort();
        return lines.isEmpty() ? Core.bundle.get("none") : lines.toString(", ");
    }

    private void showMigrationExport(){
        if(!service.localAuthorityOpen()) return;
        BaseDialog dialog = new BaseDialog("@sharedcampaign.migratehost");
        TextField targetHost = new TextField("new-host");
        TextField hours = new TextField("24");
        TextField path = new TextField(dataDirectory.child("shared-campaign-migration.mycm").absolutePath());
        dialog.cont.defaults().left().pad(5f);
        float formWidth = Math.max(220f, Math.min(560f, contentWidth() - 35f));
        dialog.cont.add("Target host ID").row(); dialog.cont.add(targetHost).width(formWidth).row();
        dialog.cont.add("Valid for hours").row(); dialog.cont.add(hours).width(180f).row();
        dialog.cont.add("Migration bundle").row(); dialog.cont.add(path).width(formWidth).row();
        dialog.buttons.button("@cancel", Icon.cancel, dialog::hide).size(160f, 54f);
        dialog.buttons.button("@export", Icon.export, () -> {
            try{
                long validFor = Long.parseLong(hours.getText().trim()) * 60L * 60L * 1000L;
                Fi target = new Fi(path.getText().trim());
                dialog.hide();
                runAsync("@loading", () -> service.exportMigration(target, targetHost.getText().trim(), validFor), bundle -> {
                    if(planetDialog != null && planetDialog.isShown()) planetDialog.hide();
                    Core.app.setClipboardText(bundle.transferCode());
                    ui.showInfo("Transfer code: " + bundle.transferCode() + "\n" + bundle.file().absolutePath());
                    rebuild();
                });
            }catch(Throwable failure){ ui.showException(failure); }
        }).size(160f, 54f).name("sharedCampaign.migration.export.confirm");
        dialog.show();
    }

    private void showMembers(SharedCampaignState state){
        BaseDialog dialog = new BaseDialog("@sharedcampaign.members");
        dialog.addCloseButton();
        boolean owner = service.activeMemberId().equals(state.ownerId);
        String current = service.activeMemberId();
        dialog.cont.pane(list -> {
            list.top().left(); list.defaults().growX().pad(4f);
            Seq<MemberState> members = state.members.values().toSeq();
            members.sort(Comparator.comparing(member -> member.displayName == null ? "" : member.displayName, String.CASE_INSENSITIVE_ORDER));
            for(MemberState member : members){
                list.table(Styles.grayPanel, row -> {
                    row.left();
                    row.table(info -> {
                        info.left();
                        String display = member.displayName == null || member.displayName.isBlank() ? member.memberId : member.displayName;
                        info.add(display).growX().left();
                        if(member.memberId.equals(state.ownerId)) info.add("  " + Core.bundle.get("sharedcampaign.ownerbadge")).color(Pal.accent);
                        if(member.memberId.equals(current)) info.add("  " + Core.bundle.get("sharedcampaign.you")).color(Pal.heal);
                        info.row();
                        info.add(shortId(member.memberId)).color(Color.lightGray).left();
                    }).growX();
                    if(owner && !member.memberId.equals(state.ownerId)){
                        row.button(Icon.trash, () -> confirmRemoveMember(dialog, member)).size(46f)
                            .name("sharedCampaign.member.remove." + safeName(member.memberId));
                    }
                }).growX().row();
            }
        }).grow().minHeight(260f).maxHeight(460f).row();
        if(owner){
            dialog.buttons.button("@sharedcampaign.rotateinvite", Icon.refresh, () -> rotateInvite(dialog)).size(220f, 54f)
                .name("sharedCampaign.invite.rotate");
        }else{
            dialog.buttons.button("@sharedcampaign.member.leavepermanent", Icon.exit, () -> confirmLeaveMembership(dialog, current)).size(220f, 54f)
                .name("sharedCampaign.member.leavePermanent");
        }
        dialog.show();
    }

    private void showSettings(SharedCampaignState state){
        BaseDialog dialog = new BaseDialog("@sharedcampaign.settings");
        TextField maxActions = new TextField(Integer.toString(state.maxActiveActions));
        CheckBox freeze = new CheckBox("@sharedcampaign.freezeempty"); freeze.setChecked(state.freezeWhenEmpty);
        CheckBox multi = new CheckBox("@sharedcampaign.multifront"); multi.setChecked(state.multiFrontEnabled);
        CheckBox memberInvites = new CheckBox("@sharedcampaign.invitemembers"); memberInvites.setChecked(state.invitePolicy == InvitePolicy.members);
        PersistenceProfile[] persistence = {state.persistenceProfile == null ? PersistenceProfile.lowFrequencyWal : state.persistenceProfile};
        dialog.cont.defaults().left().pad(6f);
        dialog.cont.add("@sharedcampaign.maxactions").row(); dialog.cont.add(maxActions).width(160f).row();
        dialog.cont.add(freeze).row(); dialog.cont.add(multi).row(); dialog.cont.add(memberInvites).row();
        dialog.cont.add("@sharedcampaign.persistence").padTop(10f).row();
        addPersistenceProfilePicker(dialog.cont, persistence);
        service.uiExtensions().build(SharedCampaignUiRegistry.Surface.campaignSettings, dialog.cont,
            new SharedCampaignUiRegistry.Context(service, state, null, null, "settings"));
        dialog.buttons.button("@cancel", Icon.cancel, dialog::hide).size(160f, 54f);
        dialog.buttons.button("@save", Icon.ok, () -> {
            int requested;
            try{ requested = Integer.parseInt(maxActions.getText().trim()); }
            catch(NumberFormatException invalid){ ui.showErrorMessage("@sharedcampaign.invalidmaxactions"); return; }
            boolean enabled = multi.isChecked();
            int max = enabled ? requested : 1;
            runAsync("@saving", () -> service.controlClient().updateSettings(max, freeze.isChecked(), enabled,
                memberInvites.isChecked() ? InvitePolicy.members : InvitePolicy.ownerOnly, persistence[0]), updated -> {
                dialog.hide(); rebuild();
            });
        }).size(170f, 54f).name("sharedCampaign.settings.save");
        dialog.show();
    }

    private void copyInvite(){
        if(service.localAuthorityOpen()){
            showInviteCode(service.localInviteCode());
            return;
        }
        runAsync("@loading", () -> service.controlClient().requestInviteCode(), this::showInviteCode);
    }

    private void showInviteCode(String code){
        BaseDialog dialog = new BaseDialog("@sharedcampaign.invitecode");
        dialog.name = "sharedCampaign.invite.dialog";
        float width = Math.min(620f, contentWidth());
        dialog.cont.defaults().pad(8f).width(width);
        dialog.cont.add("@sharedcampaign.invitecode.description").color(Pal.gray).wrap().growX().left().row();
        dialog.cont.add(code).color(Pal.accent).fillX().wrap().left().name("sharedCampaign.invite.value").row();
        dialog.buttons.button("@copy", Icon.copy, () -> Core.app.setClipboardText(code)).size(160f, 58f).name("sharedCampaign.invite.copy");
        boolean owner = false;
        try{ owner = Objects.equals(service.activeMemberId(), service.state().ownerId); }catch(Throwable ignored){}
        if(owner){
            dialog.buttons.button("@sharedcampaign.rotateinvite", Icon.refresh, () -> {
                runAsync("@loading", () -> service.controlClient().rotateInviteCode(), rotated -> {
                    dialog.hide();
                    showInviteCode(rotated);
                });
            }).size(190f, 58f).name("sharedCampaign.invite.rotate");
        }
        dialog.buttons.button("@ok", Icon.ok, dialog::hide).size(150f, 58f).name("sharedCampaign.invite.ok");
        dialog.show();
    }

    private void rotateInvite(BaseDialog parent){
        runAsync("@loading", () -> service.controlClient().rotateInviteCode(), code -> {
            Core.app.setClipboardText(code);
            ui.showInfo(Core.bundle.format("sharedcampaign.invite.rotated", code));
        });
    }

    private void confirmRemoveMember(BaseDialog parent, MemberState member){
        String display = member.displayName == null || member.displayName.isBlank() ? shortId(member.memberId) : member.displayName;
        ui.showConfirm("@confirm", Core.bundle.format("sharedcampaign.member.remove.confirm", display), () ->
            runAsync("@loading", () -> service.controlClient().removeMember(member.memberId), updated -> { if(parent != null) parent.hide(); rebuild(); }));
    }

    private void confirmLeaveMembership(BaseDialog parent, String memberId){
        ui.showConfirm("@confirm", "@sharedcampaign.member.leavepermanent.confirm", () ->
            runAsync("@loading", () -> service.controlClient().removeMember(memberId), updated -> {
                if(parent != null) parent.hide();
                service.closeCurrent();
                rebuild();
            }));
    }

    private String actionStatusLabel(ActionStatus status){
        String key = "sharedcampaign.actionstatus." + status.name();
        return Core.bundle.get(key, status.name());
    }

    private void suspendAction(String actionId){
        runAsync("@loading", () -> {
            SharedCampaignClient client = service.controlClient();
            client.suspendAction(actionId);
            awaitActionSuspended(client, actionId, 30_000L);
            return Boolean.TRUE;
        }, ignored -> rebuild());
    }


    private void awaitActionSuspended(SharedCampaignClient client, String actionId, long timeoutMillis) throws Exception{
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while(System.nanoTime() < deadline){
            SharedCampaignState state = client.snapshot();
            ActionState action = state.actions.get(actionId);
            if(action != null){
                if(action.status == ActionStatus.suspended) return;
                if(action.status == ActionStatus.failed || action.status == ActionStatus.incompatible || action.status == ActionStatus.completed){
                    String reason = action.failureReason == null || action.failureReason.isBlank() ? action.status.name() : action.failureReason;
                    throw new IllegalStateException("Action could not suspend cleanly: " + reason);
                }
            }
            try{
                Thread.sleep(50L);
            }catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Action to suspend", interrupted);
            }
        }
        throw new IllegalStateException("Timed out waiting for Action to suspend");
    }

    private void resumeAction(ActionState action){
        hideSharedUiForJoin();
        runAsync("@loading", () -> startAndAwaitRunning(action.planetName, action.sectorName, action.missionId), result -> {
            if(result.error() != null && !result.error().isBlank()){ ui.showErrorMessage(result.error()); rebuild(); return; }
            joinAction(result.actionId(), false);
        });
    }

    /**
     * startAction is intentionally asynchronous: a successful control response means the durable Action exists,
     * not that its game listener is ready for admission yet. Product UI must not race that transition.
     */
    private RuntimePayloads.StartResult startAndAwaitRunning(String planetName, String sectorName, String missionId) throws Exception{
        return startAndAwaitRunning(planetName, sectorName, missionId, RuntimePayloads.LaunchPlan.empty());
    }

    private RuntimePayloads.StartResult startAndAwaitRunning(String planetName, String sectorName, String missionId, RuntimePayloads.LaunchPlan launch) throws Exception{
        SharedCampaignClient client = service.controlClient();
        RuntimePayloads.StartResult result = client.startAction(planetName, sectorName, missionId, launch);
        if(result.error() != null && !result.error().isBlank()) return result;
        awaitActionRunning(client, result.actionId(), 30_000L);
        return result;
    }

    private void awaitActionRunning(SharedCampaignClient client, String actionId, long timeoutMillis) throws Exception{
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while(System.nanoTime() < deadline){
            SharedCampaignState state = client.snapshot();
            ActionState action = state.actions.get(actionId);
            if(action != null){
                if(action.status == ActionStatus.running) return;
                if(action.status == ActionStatus.failed || action.status == ActionStatus.incompatible || action.status == ActionStatus.completed || action.status == ActionStatus.suspended){
                    String reason = action.failureReason == null || action.failureReason.isBlank() ? action.status.name() : action.failureReason;
                    throw new IllegalStateException("Action could not become ready: " + reason);
                }
            }
            try{
                Thread.sleep(50L);
            }catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Action to start", interrupted);
            }
        }
        throw new IllegalStateException("Timed out waiting for Action to start");
    }

    private void joinAction(String actionId, boolean spectator){
        joinAction(actionId, spectator, false);
    }

    private void joinAction(String actionId, boolean spectator, boolean landingPresentation){
        String uuid = platform.getUUID();
        if(uuid == null || uuid.isBlank()){ ui.showErrorMessage("@invalidid"); return; }
        SharedCampaignNet net = SharedCampaignNet.install(game());
        String currentAction = net.activeClientActionId();
        String currentSession = net.activeBrokerSessionId();
        if(currentAction != null && !currentAction.isBlank() && !currentAction.equals(actionId)
            && currentSession != null && !currentSession.isBlank()){
            runAsync("@connecting", () -> service.hotSwitchRemoteAction(currentAction, actionId, spectator), result -> {
                if(result.sameTcp() && (result.error() == null || result.error().isBlank())){
                    hideSharedUiForJoin();
                    return;
                }
                if(result.joinToken() != null && !result.joinToken().isBlank() && result.host() != null && !result.host().isBlank() && result.port() > 0){
                    connectJoinResult(result.actionId(), result.host(), result.port(), result.joinToken(), spectator, landingPresentation);
                }else{
                    ordinaryJoinAction(actionId, spectator, uuid, result.error(), landingPresentation);
                }
            });
            return;
        }
        ordinaryJoinAction(actionId, spectator, uuid, "", landingPresentation);
    }

    /**
     * The planet page is a full-screen paused dialog. Leaving it open after a successful join/switch covers the
     * Action world (and makes the player think the sector never opened), so every enter-sector path must close it.
     */
    private void hideSharedUiForJoin(){
        if(planetDialog != null && planetDialog.isShown()) planetDialog.hide();
        hide();
    }

    private void ordinaryJoinAction(String actionId, boolean spectator, String uuid, String hotSwitchReason, boolean landingPresentation){
        runAsync("@connecting", () -> service.controlClient().joinAction(actionId, spectator, uuid), result -> {
            if(result.error() != null && !result.error().isBlank()){
                String reason = hotSwitchReason == null || hotSwitchReason.isBlank() ? result.error() : hotSwitchReason + "\n" + result.error();
                ui.showErrorMessage(reason);
                return;
            }
            connectJoinResult(result.actionId(), result.host(), result.port(), result.joinToken(), spectator, landingPresentation);
        });
    }

    private void connectJoinResult(String actionId, String host, int port, String joinToken, boolean spectator, boolean landingPresentation){
        try{
            SharedCampaignClient control = service.controlClient();
            // JoinDialog.connect rejects a blank player name even when campaign enrollment already used a display name.
            ensureNetworkPlayerName(control);
            SharedCampaignState campaignState = service.strategicState();
            ActionState planned = campaignState == null ? null : campaignState.actions.get(actionId);
            // Arc Log uses '@' only as a placeholder; build the entry@actual label as a single argument.
            Log.info("Prepare shared action join: action=@ sharedEntry=@ ports=@",
                actionId, true, "entry@actual=" + mindustry.y.util.YPortLog.entryAtActual(port, planned == null ? -1 : planned.port));
            SharedCampaignNet.install(game()).prepareJoin(actionId, joinToken, spectator, landingPresentation, control.memberId(), true);
            hideSharedUiForJoin();
            ui.join.connect(host, port);
        }catch(Throwable failure){ ui.showException(failure); }
    }

    /** Last coordinator endpoint the player attempted to reach; shown in actionable connect-failure guidance. */
    private String pendingCoordinatorHost = "";
    private int pendingCoordinatorPort = -1;

    private <T> void runAsync(String loadingText, AsyncSupplier<T> operation, Cons<T> success){
        if(busy) return;
        busy = true;
        ui.loadfrag.show(loadingText);
        // mdt-y regression fix: a deadlocked worker must not leave ui.loadfrag pinned forever.
        // 45s covers the hot-switch barrier/reset/prepare/rebind budgets with margin.
        java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();
        Time.run(60f * 45f, () -> {
            if(!settled.compareAndSet(false, true) || !busy) return;
            busy = false;
            ui.loadfrag.hide();
            Log.err("Shared Campaign operation timed out; clearing loading UI");
            showOperationError(new TimeoutException("Shared Campaign operation timed out"));
        });
        Threads.daemon("shared-campaign-ui", () -> {
            try{
                T result = operation.get();
                if(!settled.compareAndSet(false, true)) return;
                Core.app.post(() -> {
                    busy = false;
                    ui.loadfrag.hide();
                    success.get(result);
                });
            }catch(Throwable failure){
                if(!settled.compareAndSet(false, true)) return;
                Core.app.post(() -> {
                    busy = false;
                    ui.loadfrag.hide();
                    showOperationError(failure);
                });
            }
        });
    }

    /**
     * Surfaces an operation failure, replacing a raw refused-connect stack line with actionable coordinator
     * guidance: a refused coordinator connect is the common player mistake (nothing listening at the typed
     * address), and the raw {@code ConnectException} gives no hint what to do next.
     */
    private void showOperationError(Throwable error){
        if(coordinatorUnreachable(error)){
            String message = Strings.getFinalMessage(error);
            if(message == null || message.isBlank()) message = error == null ? "Shared Campaign operation failed" : error.getClass().getSimpleName();
            String host = pendingCoordinatorHost;
            int port = pendingCoordinatorPort;
            SharedCampaignClient control = null;
            try{ control = service.controlClient(); }catch(Throwable ignored){}
            if(control != null){
                if(host == null || host.isBlank()) host = control.host();
                if(port <= 0) port = control.port();
            }
            ui.showErrorMessage(Core.bundle.format("sharedcampaign.error.coordinatorunreachable", host == null ? "" : host, port, message));
            return;
        }
        ui.showException(error);
    }

    /** True when the failure means the coordinator endpoint actively refused/never completed the connection. */
    private static boolean coordinatorUnreachable(Throwable error){
        Throwable cursor = error;
        for(int depth = 0; cursor != null && depth < 8; depth++){
            if(cursor instanceof java.net.ConnectException) return true;
            String text = cursor.getMessage();
            if(text != null && (text.contains("Connection refused") || text.contains("ECONNREFUSED"))) return true;
            Throwable cause = cursor.getCause();
            if(cause == cursor) break;
            cursor = cause;
        }
        return false;
    }

    private static String shortId(String id){
        if(id == null) return "";
        return id.length() <= 12 ? id : id.substring(0, 8) + "…";
    }

    private static String safeName(String value){
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    @FunctionalInterface
    private interface AsyncSupplier<T>{ T get() throws Exception; }

    /**
     * Shared Campaign member display names are campaign-state only. The network ConnectPacket and the vanilla
     * "pick a player name" prompt both read {@code player.name}/{@code settings("name")}, so a non-blank campaign
     * display name must be written back or the game keeps using the old multiplayer name (or blocks with @noname).
     */
    private void applyLocalPlayerName(String displayName){
        String clean = displayName == null ? "" : displayName.trim();
        if(clean.isEmpty()) clean = Core.bundle.get("sharedcampaign.member.default");
        player.name(clean);
        Core.settings.put("name", clean);
    }

    /**
     * {@code ui.join.connect} hard-fails with {@code @noname} when {@code player.name} is blank. Enrollment can
     * succeed with only a campaign display name (or after a restart that never flushed settings), so restore the
     * network name from the active member profile immediately before entering an Action.
     */
    private void ensureNetworkPlayerName(SharedCampaignClient control){
        if(player.name != null && !player.name.trim().isEmpty()) return;
        String fallback = Core.settings.getString("name", "");
        try{
            SharedCampaignState state = service.state();
            SharedCampaignState.MemberState member = state == null ? null : state.members.get(control.memberId());
            if(member != null && member.displayName != null && !member.displayName.isBlank()){
                fallback = member.displayName;
            }
        }catch(Throwable ignored){
        }
        applyLocalPlayerName(fallback);
    }

    private void showCreate(){
        BaseDialog dialog = new BaseDialog("@sharedcampaign.create");
        dialog.name = "sharedCampaign.create.dialog";
        TextField name = new TextField(Core.bundle.get("sharedcampaign.defaultname"));
        name.name = "sharedCampaign.create.name";
        String existingName = Core.settings.getString("name", "");
        TextField owner = new TextField(existingName == null || existingName.isBlank() ? Core.bundle.get("sharedcampaign.owner.default") : existingName);
        owner.name = "sharedCampaign.create.owner";
        TextField port = new TextField(Integer.toString(defaultSharedPort));
        port.name = "sharedCampaign.create.port";
        TextField maxActions = new TextField("2");
        maxActions.name = "sharedCampaign.create.maxActions";

        CampaignOrigin[] origin = {CampaignOrigin.newCampaign};
        Planet[] primary = {Planets.serpulo};
        InvitePolicy[] invite = {InvitePolicy.members};
        boolean[] multiFront = {true};
        boolean[] freezeWhenEmpty = {true};
        boolean[] importAllPlanets = {false};
        PersistenceProfile[] persistence = {PersistenceProfile.lowFrequencyWal};
        Fi[] source = {null};
        ObjectMap<String, String> policyOverrides = new ObjectMap<>();

        Table form = new Table();
        Runnable[] rebuildForm = new Runnable[1];
        rebuildForm[0] = () -> {
            form.clear();
            form.defaults().left().pad(5f);
            float width = Math.max(220f, Math.min(560f, contentWidth() - 35f));

            form.add("@sharedcampaign.name").left().row();
            form.add(name).width(width).row();
            form.add("@sharedcampaign.ownername").left().row();
            form.add(owner).width(width).row();
            form.add("@sharedcampaign.creation.origin").left().padTop(6f).row();
            form.button(originLabel(origin[0]), Icon.downOpen, () -> showCreationOriginPicker(origin, rebuildForm[0]))
                .width(width).height(48f).name("sharedCampaign.create.origin").row();

            if(origin[0] == CampaignOrigin.backupRestore){
                form.add("@sharedcampaign.creation.backup").left().padTop(6f).row();
                form.table(sourceRow -> {
                    Label value = new Label(source[0] == null ? Core.bundle.get("none") : source[0].name());
                    value.setEllipsis(true);
                    sourceRow.add(value).growX().left().maxWidth(width - 120f);
                    sourceRow.button("@select", Icon.folder, () -> FileChooser.open("mycb").submit(file -> {
                        source[0] = file;
                        rebuildForm[0].run();
                    })).height(46f).name("sharedCampaign.create.backup.select");
                }).width(width).row();
                form.add("@sharedcampaign.creation.restoredescription").color(Pal.gray).wrap().growX().width(width).row();
            }else{
                Label planetValue = new Label(primary[0] == null ? "—" : primary[0].localizedName);
                planetValue.setEllipsis(true);
                SharedCampaignPlanetRegistry.PlanetPolicy selectedPolicy = selectedCreationPolicy(primary[0], policyOverrides);
                Label policyValue = new Label(selectedPolicy.id() + " · " + modeLabel(selectedPolicy.mode()));
                policyValue.setEllipsis(true);

                form.add("@sharedcampaign.creation.primaryplanet").left().padTop(6f).row();
                form.button(row -> { row.left(); row.image(Icon.planet).size(34f).padRight(8f); row.add(planetValue).growX().left(); row.image(Icon.downOpen).size(20f); }, Styles.flatt,
                    () -> showCreationPlanetPicker(primary, policyOverrides, planetValue, policyValue, null, maxActions))
                    .width(width).height(52f).name("sharedCampaign.create.primaryPlanet").row();

                form.table(Styles.black5, details -> {
                    details.left();
                    SharedCampaignPlanetRegistry.PlanetPolicy policy = selectedCreationPolicy(primary[0], policyOverrides);
                    details.add(Core.bundle.format("sharedcampaign.creation.policy", policy.id(), modeLabel(policy.mode()))).wrap().growX().left().row();
                    details.add(Core.bundle.format("sharedcampaign.creation.policyrecommendation", Math.max(1, policy.recommendedActiveActions()))).color(Pal.gray).left().growX();
                    if(service.planetPolicies().policies(primary[0].name).size > 1){
                        details.button("@sharedcampaign.creation.choosepolicy", Icon.settings, () -> showCreationPolicyPicker(primary[0], policyOverrides, policyValue, null, maxActions))
                            .height(42f).name("sharedCampaign.create.policy");
                    }
                }).width(width).padTop(4f).row();

                SharedCampaignPlanetRegistry.PlanetPolicy policy = selectedCreationPolicy(primary[0], policyOverrides);
                if(!policy.supportsMultipleActions()) multiFront[0] = false;
                form.table(row -> {
                    CheckBox box = new CheckBox("@sharedcampaign.creation.multifront");
                    box.setChecked(multiFront[0]);
                    box.setDisabled(!policy.supportsMultipleActions());
                    box.changed(() -> { multiFront[0] = policy.supportsMultipleActions() && box.isChecked(); rebuildForm[0].run(); });
                    box.name = "sharedCampaign.create.multiFront";
                    row.add(box).growX().left();
                }).width(width).padTop(5f).row();
                if(multiFront[0]){
                    form.add("@sharedcampaign.maxactions").left().row();
                    form.add(maxActions).width(Math.min(220f, width)).row();
                }

                if(origin[0] == CampaignOrigin.singlePlayerImport){
                    form.table(row -> {
                        CheckBox box = new CheckBox("@sharedcampaign.creation.importallplanets");
                        box.setChecked(importAllPlanets[0]);
                        box.changed(() -> importAllPlanets[0] = box.isChecked());
                        box.name = "sharedCampaign.create.importAllPlanets";
                        row.add(box).growX().left();
                    }).width(width).padTop(5f).row();
                    form.add("@sharedcampaign.creation.importsource").left().padTop(5f).row();
                    form.table(sourceRow -> {
                        Label value = new Label(source[0] == null ? Core.bundle.get("sharedcampaign.creation.importcurrent") : source[0].name());
                        value.setEllipsis(true);
                        sourceRow.add(value).growX().left().maxWidth(width - 120f);
                        sourceRow.button("@select", Icon.folder, () -> FileChooser.open("zip").submit(file -> { source[0] = file; rebuildForm[0].run(); }))
                            .height(46f).name("sharedCampaign.create.import.select");
                        if(source[0] != null) sourceRow.button(Icon.cancel, () -> { source[0] = null; rebuildForm[0].run(); }).size(46f)
                            .name("sharedCampaign.create.import.clear");
                    }).width(width).row();
                    form.add(source[0] == null ? "@sharedcampaign.creation.importdescription" : "@sharedcampaign.creation.importarchivedescription")
                        .color(Pal.gray).wrap().growX().width(width).padTop(4f).row();
                }
            }

            form.table(row -> {
                CheckBox box = new CheckBox("@sharedcampaign.freezeempty");
                box.setChecked(freezeWhenEmpty[0]);
                box.changed(() -> freezeWhenEmpty[0] = box.isChecked());
                box.name = "sharedCampaign.create.freezeWhenEmpty";
                row.add(box).growX().left();
            }).width(width).padTop(5f).row();

            form.add("@sharedcampaign.creation.invitepolicy").left().padTop(5f).row();
            form.button(inviteLabel(invite[0]), Icon.downOpen, () -> showCreationInvitePicker(invite, rebuildForm[0]))
                .width(width).height(48f).name("sharedCampaign.create.invitePolicy").row();
            form.add("@sharedcampaign.persistence").left().padTop(5f).row();
            addPersistenceProfilePicker(form, persistence, width);
            form.add("@sharedcampaign.controlport").left().padTop(5f).row();
            form.add(port).width(Math.min(220f, width)).row();
            form.add(advertisedAddressRow(width)).width(width).padTop(5f).row();
        };
        rebuildForm[0].run();

        dialog.cont.pane(form).growX().maxWidth(Math.min(620f, contentWidth())).height(Math.min(650f, availableHeight(170f)));
        dialog.buttons.button("@cancel", Icon.cancel, dialog::hide).size(170f, 54f).name("sharedCampaign.create.cancel");
        dialog.buttons.button("@sharedcampaign.create", Icon.add, () -> {
            try{
                if(origin[0] == CampaignOrigin.backupRestore && source[0] == null){
                    ui.showInfo("@sharedcampaign.creation.backuprequired");
                    return;
                }
                SharedCampaignPlanetRegistry.PlanetPolicy policy = selectedCreationPolicy(primary[0], policyOverrides);
                int selectedMax = multiFront[0] ? Integer.parseInt(maxActions.getText().trim()) : 1;
                if(selectedMax < 1 || selectedMax > 32) throw new IllegalArgumentException("Maximum active actions must be between 1 and 32");
                int selectedPort = parseSharedPort(port.getText());
                String selectedHost = confirmAdvertisedHost();
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.origin = origin[0];
                options.displayName = name.getText().trim();
                options.ownerDisplayName = owner.getText().trim();
                applyLocalPlayerName(options.ownerDisplayName);
                options.ownerId = UUID.randomUUID().toString();
                options.controlPort = selectedPort;
                options.primaryPlanetName = primary[0].name;
                options.planetPolicyIds.putAll(policyOverrides);
                options.multiFrontEnabled = origin[0] != CampaignOrigin.backupRestore && policy.supportsMultipleActions() && multiFront[0];
                options.maxActiveActions = options.multiFrontEnabled ? selectedMax : 1;
                options.freezeWhenEmpty = freezeWhenEmpty[0];
                options.persistenceProfile = persistence[0];
                options.invitePolicy = invite[0];
                options.importAllPlanets = origin[0] == CampaignOrigin.singlePlayerImport && importAllPlanets[0];
                options.source = source[0];
                options.sourceLabel = switch(origin[0]){
                    case newCampaign -> "";
                    case singlePlayerImport -> source[0] == null ? "Local single-player profile" + (options.importAllPlanets ? " · all supported planets" : " · " + options.primaryPlanetName) : "Mindustry data export · " + source[0].name();
                    case backupRestore -> source[0] == null ? "" : source[0].name();
                };
                options.progressReporter = (planetName, done, total, progress) -> Core.app.post(() -> {
                    if(!busy) return;
                    if(planetName != null && !planetName.isBlank() && !planetName.startsWith("#fingerprint")){
                        ui.loadfrag.setText(Core.bundle.format("sharedcampaign.creation.importingsector", planetName, done, Math.max(1, total)));
                    }else{
                        String mod = planetName != null && planetName.startsWith("#fingerprint:") ? planetName.substring("#fingerprint:".length()) : "";
                        ui.loadfrag.setText(mod.isEmpty() ? Core.bundle.get("sharedcampaign.creation.fingerprinting") : Core.bundle.format("sharedcampaign.creation.fingerprintingmod", mod));
                    }
                    ui.loadfrag.setProgress(Mathf.clamp(progress));
                    ui.loadfrag.showProgressBar();
                });
                Fi directory = dataDirectory.child("shared-campaigns").child(UUID.randomUUID().toString());
                dialog.hide();
                runAsync("@loading", () -> service.createLocal(directory, options, selectedHost, 0, selectedPort), ignored -> rebuild());
            }catch(Throwable failure){ ui.showException(failure); }
        }).size(190f, 54f).name("sharedCampaign.create.confirm");
        dialog.show();
    }

    private void showCreationOriginPicker(CampaignOrigin[] selected, Runnable rebuild){
        BaseDialog chooser = new BaseDialog("@sharedcampaign.creation.origin");
        chooser.addCloseButton();
        for(CampaignOrigin value : CampaignOrigin.values()){
            chooser.cont.button(originLabel(value), Styles.flatt, () -> { selected[0] = value; chooser.hide(); rebuild.run(); })
                .width(Math.min(420f, contentWidth())).height(52f).name("sharedCampaign.create.origin." + value.name()).row();
        }
        chooser.show();
    }

    private void showCreationInvitePicker(InvitePolicy[] selected, Runnable rebuild){
        BaseDialog chooser = new BaseDialog("@sharedcampaign.creation.invitepolicy");
        chooser.addCloseButton();
        for(InvitePolicy value : InvitePolicy.values()){
            chooser.cont.button(inviteLabel(value), Styles.flatt, () -> { selected[0] = value; chooser.hide(); rebuild.run(); })
                .width(Math.min(420f, contentWidth())).height(52f).name("sharedCampaign.create.invitePolicy." + value.name()).row();
        }
        chooser.show();
    }

    private String originLabel(CampaignOrigin value){ return Core.bundle.get("sharedcampaign.creation.origin." + value.name(), value.name()); }
    private String inviteLabel(InvitePolicy value){ return Core.bundle.get("sharedcampaign.creation.invite." + value.name(), value.name()); }
    private String modeLabel(SharedCampaignPlanetRegistry.MultiplayerMode value){ return Core.bundle.get("sharedcampaign.creation.mode." + value.name(), value.name()); }

    private void addPersistenceProfilePicker(Table parent, PersistenceProfile[] selected){
        addPersistenceProfilePicker(parent, selected, Math.min(620f, contentWidth()));
    }

    private void addPersistenceProfilePicker(Table parent, PersistenceProfile[] selected, float width){
        ButtonGroup<TextButton> group = new ButtonGroup<>();
        group.setMinCheckCount(1);
        group.setMaxCheckCount(1);
        Table choices = new Table();
        choices.defaults().growX().height(52f).pad(2f);
        for(PersistenceProfile profile : PersistenceProfile.values()){
            String key = "sharedcampaign.persistence." + switch(profile){
                case highFrequencyWal -> "high";
                case lowFrequencyWal -> "low";
                case traditional -> "traditional";
            };
            TextButton button = new TextButton(Core.bundle.get(key), Styles.togglet);
            button.name = "sharedCampaign.persistence." + profile.name();
            group.add(button);
            button.setChecked(profile == selected[0]);
            button.changed(() -> { if(button.isChecked()) selected[0] = profile; });
            choices.add(button);
        }
        parent.add(choices).width(width).row();
        Label description = new Label(() -> Core.bundle.get("sharedcampaign.persistence." + switch(selected[0]){
            case highFrequencyWal -> "high.description";
            case lowFrequencyWal -> "low.description";
            case traditional -> "traditional.description";
        }));
        description.setWrap(true);
        parent.add(description).width(width).growX().padBottom(8f).row();
    }

    private SharedCampaignPlanetRegistry.PlanetPolicy selectedCreationPolicy(Planet planet, ObjectMap<String, String> overrides){
        String requested = overrides.get(planet.name, "");
        return service.planetPolicies().resolve(planet.name, requested);
    }

    private void showCreationPlanetPicker(Planet[] primary, ObjectMap<String, String> overrides, Label planetValue, Label policyValue,
                                          CheckBox multi, TextField maxActions){
        BaseDialog chooser = new BaseDialog("@sharedcampaign.creation.primaryplanet");
        chooser.addCloseButton();
        chooser.cont.pane(list -> {
            list.top().left(); list.defaults().growX().pad(3f);
            for(Planet planet : content.planets()){
                Seq<SharedCampaignPlanetRegistry.PlanetPolicy> policies = service.planetPolicies().policies(planet.name);
                if(policies.isEmpty()) continue;
                SharedCampaignPlanetRegistry.PlanetPolicy policy;
                try{ policy = service.planetPolicies().resolve(planet.name, ""); }catch(Throwable ignored){ continue; }
                if(policy.mode() == SharedCampaignPlanetRegistry.MultiplayerMode.unsupported) continue;
                list.button(planet.localizedName, Styles.flatt, () -> {
                    primary[0] = planet;
                    planetValue.setText(planet.localizedName);
                    overrides.remove(planet.name);
                    SharedCampaignPlanetRegistry.PlanetPolicy selected = selectedCreationPolicy(planet, overrides);
                    policyValue.setText(selected.id() + " · " + selected.mode().name());
                    multi.setDisabled(!selected.supportsMultipleActions());
                    multi.setChecked(selected.supportsMultipleActions());
                    maxActions.setText(Integer.toString(Math.max(1, selected.supportsMultipleActions() ? selected.recommendedActiveActions() : 1)));
                    chooser.hide();
                }).height(50f).name("sharedCampaign.create.planet." + safeName(planet.name)).row();
            }
        }).growX().maxWidth(Math.min(620f, contentWidth())).maxHeight(Math.min(520f, availableHeight(170f)));
        chooser.show();
    }

    private void showCreationPolicyPicker(Planet planet, ObjectMap<String, String> overrides, Label policyValue, CheckBox multi, TextField maxActions){
        BaseDialog chooser = new BaseDialog("@sharedcampaign.creation.choosepolicy");
        chooser.addCloseButton();
        chooser.cont.defaults().growX().pad(3f);
        for(SharedCampaignPlanetRegistry.PlanetPolicy policy : service.planetPolicies().policies(planet.name)){
            if(policy.mode() == SharedCampaignPlanetRegistry.MultiplayerMode.unsupported) continue;
            chooser.cont.button(policy.id() + " · " + policy.mode().name(), Styles.flatt, () -> {
                overrides.put(planet.name, policy.id());
                policyValue.setText(policy.id() + " · " + policy.mode().name());
                multi.setDisabled(!policy.supportsMultipleActions());
                multi.setChecked(policy.supportsMultipleActions());
                maxActions.setText(Integer.toString(Math.max(1, policy.supportsMultipleActions() ? policy.recommendedActiveActions() : 1)));
                chooser.hide();
            }).height(52f).name("sharedCampaign.create.policy." + safeName(policy.id())).row();
        }
        chooser.show();
    }

    private void showOpen(){
        BaseDialog dialog = new BaseDialog("@sharedcampaign.openlocal");
        dialog.name = "sharedCampaign.openLocal.dialog";
        dialog.addCloseButton();
        Fi root = dataDirectory.child("shared-campaigns");
        root.mkdirs();
        Seq<LocalCampaign> entries = new Seq<>();
        for(Fi directory : root.list()){
            if(!directory.isDirectory()) continue;
            Fi snapshot = directory.child("campaign.mycp"), backup = directory.child("campaign.mycp.bak");
            if(!snapshot.exists() && !backup.exists()) continue;
            SharedCampaignState state = null;
            boolean recovered = false;
            Throwable failure = null;
            if(snapshot.exists()){
                try(java.io.InputStream input = new java.io.BufferedInputStream(snapshot.read())){ state = SharedCampaignCodec.decode(input); }
                catch(Throwable error){ failure = error; }
            }
            if(state == null && backup.exists()){
                try(java.io.InputStream input = new java.io.BufferedInputStream(backup.read())){ state = SharedCampaignCodec.decode(input); recovered = true; }
                catch(Throwable error){ if(failure == null) failure = error; }
            }
            entries.add(new LocalCampaign(directory, state, recovered, failure));
        }
        entries.sort((a, b) -> Long.compare(b.state == null ? 0L : b.state.updatedAt, a.state == null ? 0L : a.state.updatedAt));
        dialog.cont.pane(list -> {
            list.top().left(); list.defaults().growX().pad(4f);
            if(entries.isEmpty()) list.add("@sharedcampaign.nolocal").color(Pal.gray).pad(18f).row();
            for(LocalCampaign entry : entries){
                list.table(Styles.grayPanel, card -> {
                    card.left();
                    card.table(labels -> {
                        labels.left();
                        String title = entry.state == null || entry.state.displayName == null || entry.state.displayName.isBlank() ? entry.directory.name() : entry.state.displayName;
                        labels.add(title).growX().left().row();
                        if(entry.state == null) labels.add("@sharedcampaign.corruptlocal").color(Pal.remove).left().row();
                        else{
                            labels.add(Core.bundle.format("sharedcampaign.localmeta", entry.state.revision, entry.state.actions.size, entry.state.authorityGeneration))
                                .color(entry.recovered ? Pal.accent : Pal.gray).left().row();
                        }
                        long openedAt = lastOpenedMillis(entry);
                        if(openedAt > 0L){
                            labels.add(Core.bundle.format("sharedcampaign.lastopened", new Date(openedAt)))
                                .color(Pal.gray).left();
                        }
                    }).growX();
                    if(entry.state != null){
                        card.button(entry.recovered ? "@sharedcampaign.recover" : "@open", entry.recovered ? Icon.refresh : Icon.folder,
                            () -> showOpenOptions(dialog, entry)).height(48f)
                            .name((entry.recovered ? "sharedCampaign.openLocal.recover." : "sharedCampaign.openLocal.entry.") + entry.directory.name());
                    }
                    card.button(Icon.trash, () -> confirmDeleteLocal(dialog, entry)).size(48f).tooltip("@delete")
                        .name("sharedCampaign.openLocal.delete." + entry.directory.name());
                }).growX().row();
            }
        }).growX().minHeight(220f).maxHeight(520f).row();
        dialog.buttons.button("@sharedcampaign.open", Icon.folder, () -> showOpenPath(dialog)).size(200f, 54f)
            .name("sharedCampaign.openLocal.path");
        dialog.show();
    }

    private void showOpenOptions(BaseDialog parent, LocalCampaign entry){
        if(entry.state == null) return;
        BaseDialog options = new BaseDialog(entry.recovered ? "@sharedcampaign.recover" : "@sharedcampaign.openlocal");
        TextField host = new TextField(entry.state.authorityHostId == null || entry.state.authorityHostId.isBlank() ? "local-host" : entry.state.authorityHostId);
        TextField port = new TextField(Integer.toString(defaultSharedPort));
        options.cont.defaults().left().pad(5f);
        float formWidth = Math.max(220f, Math.min(520f, contentWidth() - 35f));
        options.cont.add(entry.state.displayName == null ? entry.directory.name() : entry.state.displayName).width(formWidth).wrap().growX().row();
        options.cont.add("@sharedcampaign.hostid").row(); options.cont.add(host).width(formWidth).row();
        options.cont.add("@sharedcampaign.port").row(); options.cont.add(port).width(180f).row();
        options.cont.add(advertisedAddressRow(formWidth)).width(formWidth).row();
        options.buttons.button("@cancel", Icon.cancel, options::hide).size(170f, 54f);
        options.buttons.button(entry.recovered ? "@sharedcampaign.recover" : "@open", entry.recovered ? Icon.refresh : Icon.folder, () -> {
            try{
                service.openLocal(entry.directory, host.getText().trim(), confirmAdvertisedHost(), 0, parseSharedPort(port.getText()));
                options.hide(); parent.hide(); rebuild();
            }catch(Throwable failure){ ui.showException(failure); }
        }).size(190f, 54f).name("sharedCampaign.openLocal.confirm");
        options.show();
    }

    private void showOpenPath(BaseDialog parent){
        BaseDialog dialog = new BaseDialog("@sharedcampaign.open");
        TextField path = new TextField(dataDirectory.child("shared-campaigns").absolutePath());
        TextField host = new TextField("local-host");
        TextField port = new TextField(Integer.toString(defaultSharedPort));
        dialog.cont.defaults().left().pad(5f);
        float formWidth = Math.max(220f, Math.min(560f, contentWidth() - 35f));
        dialog.cont.add("@sharedcampaign.directory").row(); dialog.cont.add(path).width(formWidth).row();
        dialog.cont.add("@sharedcampaign.hostid").row(); dialog.cont.add(host).width(formWidth).row();
        dialog.cont.add("@sharedcampaign.port").row(); dialog.cont.add(port).width(180f).row();
        dialog.cont.add(advertisedAddressRow(formWidth)).width(formWidth).row();
        dialog.buttons.button("@cancel", Icon.cancel, dialog::hide).size(170f, 54f);
        dialog.buttons.button("@open", Icon.folder, () -> {
            try{
                service.openLocal(new Fi(path.getText().trim()), host.getText().trim(), confirmAdvertisedHost(), 0, parseSharedPort(port.getText()));
                dialog.hide(); parent.hide(); rebuild();
            }catch(Throwable failure){ ui.showException(failure); }
        }).size(190f, 54f).name("sharedCampaign.openLocal.path.confirm");
        dialog.show();
    }

    private void confirmDeleteLocal(BaseDialog parent, LocalCampaign entry){
        BaseDialog confirm = new BaseDialog("@sharedcampaign.delete.title");
        confirm.name = "sharedCampaign.openLocal.deleteConfirm";
        confirm.cont.add("@sharedcampaign.delete.confirm").growX().maxWidth(Math.min(520f, contentWidth())).wrap().pad(18f);
        confirm.buttons.button("@cancel", Icon.cancel, confirm::hide).size(160f, 54f)
            .name("sharedCampaign.openLocal.delete.cancel");
        confirm.buttons.button("@delete", Icon.trash, () -> {
            try{
                service.deleteLocalCampaign(entry.directory);
                confirm.hide(); parent.hide(); showOpen();
            }catch(Throwable failure){ ui.showException(failure); }
        }).size(180f, 54f).name("sharedCampaign.openLocal.delete.confirm");
        confirm.show();
    }

    private void showRestoreBackup(){
        BaseDialog dialog = new BaseDialog("@sharedcampaign.restore");
        TextField archive = new TextField("");
        TextField port = new TextField(Integer.toString(defaultSharedPort));
        dialog.cont.defaults().left().pad(5f);
        float formWidth = Math.max(220f, Math.min(560f, contentWidth() - 35f));
        dialog.cont.add("@sharedcampaign.backupfile").row(); dialog.cont.add(archive).width(formWidth).row();
        dialog.cont.add("@sharedcampaign.port").row(); dialog.cont.add(port).width(180f).row();
        dialog.cont.add(advertisedAddressRow(formWidth)).width(formWidth).row();
        dialog.buttons.button("@cancel", Icon.cancel, dialog::hide).size(170f, 54f);
        dialog.buttons.button("@sharedcampaign.restore", Icon.refresh, () -> {
            try{
                Fi source = new Fi(archive.getText().trim());
                if(!source.exists() || source.isDirectory()) throw new IllegalArgumentException("Shared Campaign backup archive does not exist");
                SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
                options.origin = CampaignOrigin.backupRestore;
                options.source = source;
                options.ownerId = UUID.randomUUID().toString(); // replaced by durable authority identity after restore
                options.primaryPlanetName = "serpulo";
                Fi destination = dataDirectory.child("shared-campaigns").child(UUID.randomUUID().toString());
                service.createLocal(destination, options, confirmAdvertisedHost(), 0, parseSharedPort(port.getText()));
                dialog.hide(); rebuild();
            }catch(Throwable failure){ ui.showException(failure); }
        }).size(190f, 54f).name("sharedCampaign.restore.confirm");
        dialog.show();
    }

    private void showMigrationImport(){
        BaseDialog dialog = new BaseDialog("@sharedcampaign.importmigration");
        TextField bundle = new TextField("");
        TextField targetHost = new TextField(UUID.randomUUID().toString());
        TextField code = new TextField("");
        TextField port = new TextField(Integer.toString(defaultSharedPort));
        dialog.cont.defaults().left().pad(5f);
        float formWidth = Math.max(220f, Math.min(560f, contentWidth() - 35f));
        dialog.cont.add("@sharedcampaign.migrationfile").row(); dialog.cont.add(bundle).width(formWidth).row();
        dialog.cont.add("@sharedcampaign.targethost").row(); dialog.cont.add(targetHost).width(formWidth).row();
        dialog.cont.add("@sharedcampaign.transfercode").row(); dialog.cont.add(code).width(formWidth).row();
        dialog.cont.add("@sharedcampaign.port").row(); dialog.cont.add(port).width(180f).row();
        dialog.cont.add(advertisedAddressRow(formWidth)).width(formWidth).row();
        dialog.buttons.button("@cancel", Icon.cancel, dialog::hide).size(170f, 54f);
        dialog.buttons.button("@import", Icon.download, () -> {
            try{
                Fi source = new Fi(bundle.getText().trim());
                if(!source.exists() || source.isDirectory()) throw new IllegalArgumentException("Host migration bundle does not exist");
                if(targetHost.getText().isBlank() || code.getText().isBlank()) return;
                Fi destination = dataDirectory.child("shared-campaigns").child(UUID.randomUUID().toString());
                service.importMigration(source, destination, targetHost.getText().trim(), code.getText().trim(), confirmAdvertisedHost(), 0, parseSharedPort(port.getText()));
                dialog.hide(); rebuild();
            }catch(Throwable failure){ ui.showException(failure); }
        }).size(170f, 54f).name("sharedCampaign.migration.import.confirm");
        dialog.show();
    }

    private void showConnect(){
        BaseDialog dialog = new BaseDialog("@sharedcampaign.connect");
        TextField host = new TextField("127.0.0.1"), port = new TextField(Integer.toString(defaultSharedPort)), invite = new TextField("");
        String existingName = Core.settings.getString("name", "");
        TextField display = new TextField(existingName == null || existingName.isBlank() ? Core.bundle.get("sharedcampaign.member.default") : existingName);
        dialog.cont.defaults().left().pad(5f);
        float formWidth = Math.max(220f, Math.min(500f, contentWidth() - 35f));
        dialog.cont.add("@sharedcampaign.host").row(); dialog.cont.add(host).width(formWidth).row();
        dialog.cont.add("@sharedcampaign.port").row(); dialog.cont.add(port).width(180f).row();
        dialog.cont.add("@sharedcampaign.invitecode").row(); dialog.cont.add(invite).width(formWidth).row();
        dialog.cont.add("@sharedcampaign.displayname").row(); dialog.cont.add(display).width(formWidth).row();
        dialog.buttons.button("@cancel", Icon.cancel, dialog::hide).size(170f, 54f);
        dialog.buttons.button("@sharedcampaign.connect", Icon.link, () -> {
            try{
                String displayName = display.getText().trim();
                applyLocalPlayerName(displayName);
                if(displayName.isEmpty()) display.setText(player.name());
                pendingCoordinatorHost = host.getText().trim();
                pendingCoordinatorPort = Integer.parseInt(port.getText().trim());
                service.connect(pendingCoordinatorHost, pendingCoordinatorPort, invite.getText().trim(), player.name());
                dialog.hide(); rebuild();
            }
            catch(Throwable failure){ showOperationError(failure); }
        }).size(190f, 54f).name("sharedCampaign.connect.confirm");
        dialog.show();
    }

    private static String normalizeAdvertisedHost(String value){
        String host = value == null ? "" : value.trim();
        return host.isEmpty() ? "127.0.0.1" : host;
    }

    private String confirmAdvertisedHost(){
        return advertiseOverride && advertisedFieldRef != null ? normalizeAdvertisedHost(advertisedFieldRef.getText()) : "127.0.0.1";
    }

    /** Restores the donor product behavior: the public/shared port is always configurable, while an Action host
     * override is opt-in because loopback means "advertise the coordinator's public address" to remote members. */
    private Table advertisedAddressRow(float width){
        Table row = new Table();
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            row.clearChildren();
            row.defaults().pad(2f).left();
            CheckBox toggle = new CheckBox("@sharedcampaign.advertisedhost.toggle");
            toggle.name = "sharedCampaign.advertisedHost.toggle";
            toggle.setChecked(advertiseOverride);
            toggle.changed(() -> {
                advertiseOverride = toggle.isChecked();
                rebuild[0].run();
            });
            row.add(toggle).left().row();
            if(advertiseOverride){
                TextField field = new TextField(advertisedHost);
                field.name = "sharedCampaign.advertisedHost";
                field.changed(() -> advertisedHost = field.getText());
                advertisedFieldRef = field;
                row.add(field).width(width).row();
                row.add("@sharedcampaign.advertisedhost.hint").color(Pal.gray).wrap().growX().width(width).row();
            }else{
                advertisedFieldRef = null;
            }
        };
        rebuild[0].run();
        return row;
    }

    private static int parseSharedPort(String value){
        int port = Integer.parseInt(value == null ? "" : value.trim());
        if(port < 1 || port > 65535) throw new IllegalArgumentException("Shared Campaign port must be between 1 and 65535");
        return port;
    }

    /** Prefers the open marker written on successful open; falls back to durable updatedAt / snapshot mtime. */
    private static long lastOpenedMillis(LocalCampaign entry){
        Fi marker = entry.directory.child("last-opened");
        if(marker.exists()){
            try{
                long value = Long.parseLong(marker.readString().trim());
                if(value > 0L) return value;
            }catch(Throwable ignored){
            }
        }
        if(entry.state != null && entry.state.updatedAt > 0L) return entry.state.updatedAt;
        Fi snapshot = entry.directory.child("campaign.mycp");
        return snapshot.exists() ? snapshot.lastModified() : 0L;
    }

    private record LocalCampaign(Fi directory, SharedCampaignState state, boolean recovered, Throwable failure){}

}
