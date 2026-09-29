import org.junit.jupiter.api.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** Guards player-visible Shared Campaign integration points that were missed by selector-only/Desktop acceptance tests. */
public class SharedCampaignProductSurfaceRegressionTests{
    @Test
    void actionHudRoutesPlanetResearchAndDatabaseThroughSharedAwareSurfaces() throws Exception{
        String desktop = source("core/src/mindustry/input/DesktopInput.java");
        assertTrue(desktop.contains("SharedCampaignUiRouter.showPlanet()"));
        assertTrue(desktop.contains("SharedCampaignUiRouter.showResearch()"));
        assertTrue(desktop.contains("SharedCampaignUiRouter.showDatabase()"));
        String paused = source("core/src/mindustry/ui/dialogs/PausedDialog.java");
        assertTrue(paused.contains("SharedCampaignUiRouter.showPlanet()"));
        assertTrue(paused.contains("SharedCampaignUiRouter::showResearch"));
    }

    @Test
    void freshActionsCannotOverwriteAuthoredCampaignWaveRulesFromEmptySummary() throws Exception{
        String agent = source("core/src/mindustry/campaign/shared/runtime/SharedActionAgent.java");
        int guard = agent.indexOf("if(!newAction){", agent.indexOf("private void applySummary()"));
        int waves = agent.indexOf("rules.waves = summary.waves", guard);
        assertTrue(guard >= 0 && waves > guard, "Fresh Action summary rule writes must remain behind !newAction guard");
    }

    @Test
    void sharedUnlocksDoNotFallBackToDesktopSinglePlayerProfile() throws Exception{
        String unlock = source("core/src/mindustry/ctype/UnlockableContent.java");
        assertTrue(count(unlock, "shared.sharedModeActive()) return belongsToActiveCampaignPlanet() && (alwaysUnlocked || shared.sharedUnlocked(name))") >= 2);
    }

    @Test
    void strategicPlanetPageFiltersUnrelatedPlanetsAndCompletesCameraTransition() throws Exception{
        String planet = source("core/src/mindustry/campaign/shared/ui/SharedCampaignPlanetDialog.java");
        assertTrue(planet.contains("Objects.equals(model.primaryPlanetName, planet.name)"));
        assertTrue(planet.contains("campaign.sectors.values().toSeq().contains"));
        assertTrue(planet.contains("state.otherCamAlpha = Mathf.lerpDelta"));
        assertTrue(planet.contains("state.otherCamPos = null"));
    }


    @Test
    void strategicPlanetUsesVanillaResearchAndListViewConsumesSharedPresentationState() throws Exception{
        String dialog = source("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java");
        assertTrue(dialog.contains("@Override public void research(Planet planet){ SharedCampaignUiRouter.showSharedStrategicResearch(planet); }"));
        assertFalse(dialog.contains("private void showSharedResearch()"), "Do not reintroduce a second Shared research presentation");
        assertTrue(dialog.contains("SharedCampaignProgress.sectorInspectable(snapshot, sector)"));
        assertTrue(dialog.contains("sectorDisplayName(sector, saved)"));
        assertTrue(dialog.contains("sectorListIcon(state, sector, durable, availability)"));
        assertTrue(dialog.contains("ActionStatus.suspending"));
        assertTrue(dialog.contains("ActionStatus.incompatible"));
        assertTrue(dialog.contains("ActionStatus.failed"));
        assertTrue(dialog.contains("saved.resources"));
        assertTrue(dialog.contains("saved.hasEnemyBase"));
        assertTrue(dialog.contains("saved.threat"));
    }


    @Test
    void sharedStrategicResearchIsExplicitAndDoesNotEquateCoordinatorConnectionWithLocalRuntime() throws Exception{
        String research = source("core/src/mindustry/ui/dialogs/ResearchDialog.java");
        assertTrue(research.contains("private boolean sharedStrategicPresentation"));
        assertTrue(research.contains("public void showSharedCampaign()"));
        assertTrue(research.contains("sharedStrategicPresentation || sharedCampaign.sharedModeActive()"));
        assertTrue(research.contains("sharedStrategicPresentation = false"));
        assertFalse(research.contains("sharedCampaign.connectedToCoordinator()"),
            "ResearchDialog must not turn an unrelated Local Campaign into Shared mode merely because the control-plane client remains connected");
        String router = source("core/src/mindustry/campaign/shared/ui/SharedCampaignUiRouter.java");
        assertTrue(router.contains("showSharedStrategicResearch"));
        assertTrue(router.contains("ui.research.showSharedCampaign(planet)"));
    }


    @Test
    void sharedSnapshotUiRebuildsStayOnTheApplicationThread() throws Exception{
        String dialog = source("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java");
        int event = dialog.indexOf("Events.on(SharedCampaignEvents.CampaignSnapshotUpdated.class");
        int apply = dialog.indexOf("planetDialog.updateCampaign(current)", event);
        int post = dialog.indexOf("Core.app.post(() ->", event);
        assertTrue(event >= 0 && post > event && apply > post,
            "CampaignSnapshotUpdated may arrive from coordinator/research workers; Shared UI mutation must be posted to the application thread");

        String research = source("core/src/mindustry/ui/dialogs/ResearchDialog.java");
        int researchEvent = research.indexOf("Events.on(SharedCampaignEvents.CampaignSnapshotUpdated.class");
        int researchPost = research.indexOf("Core.app.post(() ->", researchEvent);
        int researchVisible = research.indexOf("if(!isShown() || !sharedResearchMode()) return", researchPost);
        assertTrue(researchEvent >= 0 && researchPost > researchEvent && researchVisible > researchPost,
            "ResearchDialog must decide Scene2D visibility/mode only after CampaignSnapshotUpdated has been posted to the application lane");
    }


    @Test
    void strategicPlanetIconPrecedenceKeepsWarningAndLockAboveCustomPresentation() throws Exception{
        String planet = source("core/src/mindustry/campaign/shared/ui/SharedCampaignPlanetDialog.java");
        int method = planet.indexOf("private @Nullable TextureRegion primarySectorIcon");
        int attacked = planet.indexOf("saved.attacked", method);
        int lock = planet.indexOf("!availability.available() && inspectable(sector)", method);
        int custom = planet.indexOf("TextureRegion custom = sharedCustomIcon(saved)", method);
        assertTrue(method >= 0 && attacked > method && lock > attacked && custom > lock,
            "Shared strategic icon precedence must match vanilla semantics: attacked > locked > custom/preset");
    }

    @Test
    void ordinaryInfoPanelsDoNotExposeProtocolIdentityIds() throws Exception{
        String dialog = source("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java");
        int actionStart = dialog.indexOf("private void showActionDetails");
        int actionEnd = dialog.indexOf("private String actionStatusLabel", actionStart);
        String action = dialog.substring(actionStart, actionEnd);
        assertFalse(action.contains("action.actionId"));
        assertFalse(action.contains("action.participants.toSeq().sort().toString"));
        int missionStart = dialog.indexOf("private void showMissionDetails");
        int missionEnd = dialog.indexOf("private void showActionDetails", missionStart);
        String mission = dialog.substring(missionStart, missionEnd);
        assertFalse(mission.contains("attemptId"));
        assertFalse(mission.contains("eventId"));
        assertFalse(mission.contains("objective.objectiveId"));
    }

    @Test
    void strategicPlanetSanitizesHoverBeforeAnyDisclosureAndSeparatesInfoFromOperations() throws Exception{
        String planet = source("core/src/mindustry/campaign/shared/ui/SharedCampaignPlanetDialog.java");
        assertTrue(planet.contains("hovered = pickInspectableSector()"));
        assertTrue(planet.contains("return inspectable(candidate) ? candidate : null"),
            "uninspectable sectors must never enter hover presentation state");
        assertTrue(planet.contains("pointerOverPlanetViewport()"),
            "planet picking must not pass through sibling UI chrome");
        assertTrue(planet.contains("private final Table operationsPanel"));
        assertTrue(planet.contains("infoLayer.top().right()"));
        assertTrue(planet.contains("operationLayer.bottom()"));
        assertFalse(planet.contains("sectorCard.bottom()"), "read-only sector information must not remain coupled to the bottom operation card");
    }

    @Test
    void sharedResearchCarriesPlanetIdentityAndUsesOneResourceEligibilityContract() throws Exception{
        String research = source("core/src/mindustry/ui/dialogs/ResearchDialog.java");
        String service = source("core/src/mindustry/campaign/shared/SharedCampaignService.java");
        String coordinator = source("core/src/mindustry/campaign/shared/runtime/CampaignActionCommands.java");
        String client = source("core/src/mindustry/campaign/shared/runtime/SharedCampaignClient.java");
        assertTrue(research.contains("SharedCampaignProgress.researchItems(campaign, lastNode, planet == null ? \"\" : planet.name)"));
        assertTrue(research.contains("sharedCampaign.requestResearch(node.content.name, requestPlanetName)"));
        assertTrue(service.contains("requestResearch(String contentName, String planetName)"));
        assertTrue(client.contains("new RuntimePayloads.ResearchRequest(contentName, planetName)"));
        assertTrue(coordinator.contains("SharedCampaignProgress.researchEligibleSectors(state, node, researchPlanet)"));
        assertFalse(coordinator.contains("n.content == target && n.planet == Planets.erekir"),
            "Coordinator must not guess one hard-coded TechTree after the client selected a planet");
    }

    @Test
    void liveActionPlanetOverlayContinuesReceivingStrategicSnapshots() throws Exception{
        String dialog = source("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java");
        int event = dialog.indexOf("Events.on(SharedCampaignEvents.CampaignSnapshotUpdated.class");
        int visible = dialog.indexOf("planetDialog != null && planetDialog.isShown()", event);
        int apply = dialog.indexOf("planetDialog.updateCampaign(current)", event);
        assertTrue(event >= 0 && visible > event && apply > visible,
            "showPlanetFromAction opens Planet without showing the lobby, so snapshot convergence must include the Planet surface itself");
        assertFalse(dialog.substring(event, apply).contains("if(!isShown() || busy) return"),
            "live-Action Planet overlays must not freeze at their opening snapshot");
    }

    @Test
    void currentActionPresentationDoesNotTreatLastActionHistoryAsLivePresence() throws Exception{
        String progress = source("core/src/mindustry/campaign/shared/SharedCampaignProgress.java");
        String planet = source("core/src/mindustry/campaign/shared/ui/SharedCampaignPlanetDialog.java");
        String dialog = source("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java");
        String coordinator = source("core/src/mindustry/campaign/shared/runtime/CampaignActionCommands.java");
        assertTrue(progress.contains("currentMemberAction"));
        assertTrue(progress.contains("action.participants.contains(memberId)"));
        assertTrue(planet.contains("SharedCampaignProgress.currentMemberAction(campaign, memberId(), true)"));
        assertTrue(dialog.contains("SharedCampaignProgress.currentMemberAction(state, service.activeMemberId(), true)"));
        assertTrue(coordinator.contains("SharedCampaignProgress.currentMemberPlayerAction(state, memberId)"));
    }

    @Test
    void sharedActionAudioAndLobbyLifecycleAreRuntimeOwned() throws Exception{
        String dialog = source("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java");
        String planet = source("core/src/mindustry/campaign/shared/ui/SharedCampaignPlanetDialog.java");
        // Donor guards Vars.runtimeAudioEnabled()/Arc Sound.worldPlaybackAllowed; this line never adopted the
        // donor Arc audio gate, so the product contract is the planet-dialog suppression wiring below.
        assertTrue(planet.contains("pushGameplayAudioSuppression()"));
        assertTrue(planet.contains("hidden(this::releaseActionAudioSuppression)"));
        assertTrue(dialog.contains("public void leaveActionAndShowLobby()"));
        assertTrue(dialog.contains("clearClientActionConnection()"));
        assertTrue(dialog.contains("logic.reset()"));
    }

    @Test
    void staleSnapshotsCannotRollSharedUiAuthorityBackward() throws Exception{
        String service = source("core/src/mindustry/campaign/shared/SharedCampaignService.java");
        assertTrue(service.contains("owned.revision < previousRevision) return"));
    }

    @Test
    void sharedCampaignLobbyKeepsPrototypeResponsiveInformationArchitecture() throws Exception{
        String dialog = source("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java");
        assertTrue(dialog.contains("Events.on(ResizeEvent.class"),
            "Lobby must rebuild even when a child dialog owns the top Scene2D slot during resize/orientation changes");
        assertTrue(dialog.contains("private float contentWidth()"));
        assertTrue(dialog.contains("buildStrategicSummary(pane, state, owner)"));
        assertTrue(dialog.contains("@sharedcampaign.strategicoverview"));
        assertTrue(dialog.contains("SharedCampaignProgress.currentMemberAction(state, member.memberId, true)"),
            "Member presence belongs inline in the lobby instead of being hidden behind a management-only button");
        assertTrue(dialog.contains("sharedcampaign.actionmeta"));
        assertTrue(dialog.contains("boolean compactCards"),
            "Action cards must move controls to a second row on narrow/portrait layouts instead of clipping the right edge");
        assertTrue(dialog.contains("compactTimestamp(event.timestamp)"));
        assertTrue(dialog.contains("compactSubject(event.subjectId)"),
            "Recent activity must not let UUID-like subjects force the lobby wider than the viewport");
        assertTrue(dialog.contains("maxWidth(contentWidth())"));
        assertTrue(dialog.contains("dialog.cont.pane(form).growX().maxWidth(Math.min(620f, contentWidth()))"),
            "Create flow must clamp to live scene width rather than the old fixed 420/560/620px form");
    }

    @Test
    void localCampaignUxOffersBackupRecoveryAndDeletion() throws Exception{
        String dialog = source("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java");
        assertTrue(dialog.contains("campaign.mycp.bak"));
        assertTrue(dialog.contains("sharedCampaign.openLocal.recover."));
        assertTrue(dialog.contains("sharedCampaign.openLocal.delete."));
        String service = source("core/src/mindustry/campaign/shared/SharedCampaignService.java");
        assertTrue(service.contains("deleteLocalCampaign(Fi directory)"));
    }

    private static int count(String text, String token){
        int count = 0, at = 0;
        while((at = text.indexOf(token, at)) >= 0){ count++; at += token.length(); }
        return count;
    }

    @Test
    void realtimeYConsumersNeverTreatLastActionIdAsCurrentPresence() throws Exception{
        // Donor also guards core/src/mindustry/y/{rts,render,dashboard,replay} consumers; those subsystems are
        // not part of this line, so only the shared-campaign router contract is asserted here.
        String router = source("core/src/mindustry/campaign/shared/ui/SharedCampaignUiRouter.java");
        assertTrue(router.contains("strategicContextActive()"));
    }

    private static String source(String relative) throws Exception{ return Files.readString(locate(relative)); }


    private static Path locateArc(String relative){
        Path cursor = Path.of("").toAbsolutePath();
        for(int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()){
            Path candidate = cursor.resolve("Arc-Y-18fd1e1").resolve(relative);
            if(Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate Arc-Y source " + relative);
    }
    private static Path locate(String relative){
        Path cursor = Path.of("").toAbsolutePath();
        for(int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()){
            Path candidate = cursor.resolve(relative);
            if(Files.exists(candidate)) return candidate;
            candidate = cursor.resolve("Mindustry-Y-62dbbe3").resolve(relative);
            if(Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate project source " + relative);
    }
}
