package mindustry.campaign.shared.ui;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.scene.ui.layout.Scl;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.game.*;
import mindustry.graphics.*;
import mindustry.graphics.g3d.*;
import mindustry.graphics.g3d.PlanetRenderer.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.util.*;

import static arc.Core.*;
import static mindustry.Vars.*;
import static mindustry.graphics.g3d.PlanetRenderer.*;

/**
 * Shared Campaign strategic planet page.
 *
 * This deliberately renders from SharedCampaignState instead of Sector.info()/the single-player campaign Universe.
 * A Shared Campaign may have several authoritative actions and suspended sectors at once, so the normal PlanetDialog
 * is an excellent renderer/input reference but not a valid state authority for this screen.
 */
public class SharedCampaignPlanetDialog extends BaseDialog implements PlanetInterfaceRenderer{
    private final SharedCampaignService service;
    private final PlanetRenderer planets = renderer.planets;
    private final PlanetParams state = new PlanetParams();
    private final Table inspector = new Table();
    private final Table operationsPanel = new Table();
    private final Label hoverLabel = new Label("");
    private final ObjectSet<String> knownAvailable = new ObjectSet<>();
    private final Seq<Sector> newlyAvailable = new Seq<>();
    private SharedCampaignState campaign = new SharedCampaignState();
    private Operations operations = Operations.noop();
    private @Nullable Sector hovered, selected;
    private float zoom = 1f;
    private Element viewport;
    private float revealTime;
    private boolean revealAnnounced;
    // Authoritative action/sector snapshots can arrive frequently while several Actions are live. Rebuilding the
    // entire planet page for every snapshot destroys/recreates top-bar actors and can swallow a real click that
    // lands during the rebuild. Only rebuild page chrome when a capability that changes chrome actually changes;
    // ordinary strategic updates only need the inspector to be refreshed.
    private boolean chromeStateInitialized, chromeOwner, chromeMayInvite, chromeAuthoritative;
    private boolean actionAudioSuppressed;

    public SharedCampaignPlanetDialog(SharedCampaignService service){
        super("@sharedcampaign.planetoperations", Styles.fullDialog);
        this.service = Objects.requireNonNull(service, "service");
        name = "sharedCampaign.planetMap";
        inspector.name = "sharedCampaign.planet.inspector";
        operationsPanel.name = "sharedCampaign.planet.operations";
        hoverLabel.name = "sharedCampaign.planet.hoverLabel";
        hoverLabel.setStyle(Styles.outlineLabel);
        hoverLabel.setAlignment(Align.center);
        hoverLabel.touchable = Touchable.disabled;
        shouldPause = true;
        state.renderer = this;
        state.drawUi = true;
        state.planet = Planets.serpulo;
        shown(this::setup);
        hidden(this::releaseActionAudioSuppression);
        // Orientation changes must re-run setup(): the compact flag and the inspector/operation slab sizes are
        // all captured from the viewport dimensions at build time. Without this the page stays laid out for the
        // old orientation until some unrelated capability change happens to trigger a chrome rebuild.
        Events.on(EventType.ResizeEvent.class, event -> {
            if(isShown()) Core.app.post(this::setup);
        });
    }

    public void show(SharedCampaignState snapshot, @Nullable Planet initial, Operations operations){
        campaign = snapshot == null ? new SharedCampaignState() : SharedCampaignStateCopy.copy(snapshot);
        this.operations = operations == null ? Operations.noop() : operations;
        chromeStateInitialized = false;
        state.planet = initial != null && usablePlanet(initial) ? initial : preferredPlanet();
        selected = preferredSector(state.planet);
        hovered = null;
        state.otherCamPos = null;
        zoom = state.zoom = 1f;
        state.uiAlpha = 1f;
        knownAvailable.clear();
        knownAvailable.addAll(availableSectorKeys(campaign));
        newlyAvailable.clear();
        revealTime = 0f;
        revealAnnounced = false;
        if(selected != null) lookAt(selected);
        acquireActionAudioSuppression();
        show();
    }

    /** Refreshes the strategic model without rebuilding the entire dialog stack. */
    public void updateCampaign(SharedCampaignState snapshot){
        if(snapshot == null) return;
        String selectedPlanet = selected == null ? "" : selected.planet.name;
        String selectedSector = selected == null ? "" : SharedCampaignProgress.sectorId(selected);
        SharedCampaignState next = SharedCampaignStateCopy.copy(snapshot);
        ObjectSet<String> nextAvailable = availableSectorKeys(next);
        for(String key : nextAvailable){
            if(knownAvailable.contains(key)) continue;
            SectorState state = next.sectors.get(key);
            Sector candidate = state == null ? findSectorByKey(key) : SharedCampaignProgress.findSector(state.planetName, state.sectorName);
            if(candidate != null && usablePlanet(candidate.planet) && !newlyAvailable.contains(candidate, true)) newlyAvailable.add(candidate);
        }
        knownAvailable.clear();
        knownAvailable.addAll(nextAvailable);
        campaign = next;
        if(!selectedPlanet.isBlank() && !selectedSector.isBlank()) selected = SharedCampaignProgress.findSector(selectedPlanet, selectedSector);
        if(selected == null || selected.planet != state.planet || !inspectable(selected)) selected = preferredSector(state.planet);
        // Capabilities such as owner/mayInvite/authoritative can change on a refresh (role transfer,
        // reconnect, host migration), and those changes do require rebuilding the top-bar chrome. Ordinary
        // Action heartbeat/state snapshots do not. Rebuilding the whole dialog on every such snapshot can
        // replace a button between pointer-down and pointer-up, making a real user click disappear.
        if(isShown()){
            boolean owner = operations.owner();
            boolean mayInvite = operations.mayInvite();
            boolean authoritative = operations.authoritative();
            if(!chromeStateInitialized || chromeOwner != owner || chromeMayInvite != mayInvite || chromeAuthoritative != authoritative) setup();
            else rebuildInspector();
        }else{
            rebuildInspector();
        }
    }

    private void acquireActionAudioSuppression(){
        if(actionAudioSuppressed || !service.sharedModeActive() || control == null || control.sound == null) return;
        mindustry.Vars.game().pushGameplayAudioSuppression();
        actionAudioSuppressed = true;
    }

    private void releaseActionAudioSuppression(){
        if(!actionAudioSuppressed) return;
        actionAudioSuppressed = false;
        mindustry.Vars.game().popGameplayAudioSuppression();
    }

    /** Planet picking is valid only when the pointer is actually over the viewport rather than over sibling UI chrome. */
    private boolean pointerOverPlanetViewport(){
        Element hit = scene == null ? null : scene.getHoverElement();
        return hit == viewport || hit != null && viewport != null && hit.isDescendantOf(viewport);
    }

    /** Converts the current input position into a disclosure-authorized Sector. Used by both mouse hover and touch tap. */
    private @Nullable Sector pickInspectableSector(){
        if(state.planet == null) return null;
        Sector candidate = state.planet.hasGrid() ?
            state.planet.getSector(planets.cam.getMouseRay(), outlineRad * state.planet.radius) :
            state.planet.sectors.any() ? state.planet.sectors.first() : null;
        return inspectable(candidate) ? candidate : null;
    }

    private ObjectSet<String> availableSectorKeys(SharedCampaignState model){
        ObjectSet<String> out = new ObjectSet<>();
        if(model == null) return out;
        for(Planet planet : content.planets()){
            if(!usablePlanet(model, planet)) continue;
            for(Sector sector : planet.sectors){
                if(SharedCampaignProgress.sectorAvailability(model, sector).available()) out.add(SharedCampaignProgress.sectorKey(sector));
            }
        }
        return out;
    }

    private @Nullable Sector findSectorByKey(String key){
        return SharedCampaignProgress.findSectorKey(key);
    }

    private String displaySectorName(Sector sector){
        SectorState saved = sectorState(sector);
        if(saved != null && saved.displayName != null && !saved.displayName.isBlank()) return saved.displayName;
        if(sector.preset != null && (sector.preset.requireUnlock || sector.preset.showHidden)) return sector.preset.localizedName;
        if(sector.planet.sectors.size == 1) return sector.planet.localizedName;
        return Integer.toString(sector.id);
    }

    private void updateHoverLabel(){
        if(hovered == null || !state.planet.hasGrid() || state.uiAlpha <= 0.01f){
            hoverLabel.remove();
            return;
        }
        SharedCampaignPlanetDialog.this.addChild(hoverLabel);
        hoverLabel.toFront();
        hoverLabel.color.a = state.uiAlpha;
        Vec3 pos = hovered.planet.project(hovered, planets.cam, Tmp.v31());
        hoverLabel.setPosition(pos.x - Core.scene.marginLeft, pos.y - Core.scene.marginBottom, Align.center);
        StringBuilder text = hoverLabel.getText();
        text.setLength(0);
        SharedCampaignProgress.Availability availability = SharedCampaignProgress.sectorAvailability(campaign, hovered);
        if(availability.available()){
            text.append("[accent][[ [white]").append(displaySectorName(hovered)).append("[accent] ]");
        }else{
            text.append("[gray]").append(Iconc.lock).append(" ").append(Core.bundle.get("locked", "Locked"));
            if(availability.missing() != null){
                for(String missing : availability.missing()) text.append("\n[lightgray]").append(missing);
            }
        }
        hoverLabel.invalidateHierarchy();
    }

    private @Nullable UnlockableContent resolveContent(String identity){
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

    private @Nullable TextureRegion sharedCustomIcon(SectorState saved){
        if(saved == null) return null;
        UnlockableContent contentIcon = resolveContent(saved.contentIcon);
        if(contentIcon != null && contentIcon.uiIcon != null && contentIcon.uiIcon.found()) return contentIcon.uiIcon;
        if(saved.icon != null && !saved.icon.isBlank()) return Fonts.getLargeIcon(saved.icon);
        return null;
    }

    private boolean vulnerable(Sector sector, SectorState saved){
        if(saved == null || !saved.hasBase || !sector.planet.campaignRules.sectorInvasion) return false;
        return sector.near().contains(neighbor -> {
            SectorState enemy = sectorState(neighbor);
            return enemy != null && enemy.hasEnemyBase && (neighbor.preset == null || !neighbor.preset.requireUnlock);
        });
    }

    private String displayThreat(Sector sector, @Nullable SectorState saved){
        float threat = saved != null ? saved.threat : 0f;
        if(threat <= 0.0001f && sector.preset != null) threat = sector.preset.difficulty / 10f;
        float step = 0.25f;
        String[] levels = {"low", "medium", "high", "extreme", "eradication"};
        String key = sector.preset != null && Mathf.equal(sector.preset.difficulty, SectorDifficulty.unreasonable) ?
            "unreasonable" : levels[Math.min(Math.max((int)(threat / step), 0), levels.length - 1)];
        String color = Tmp.c1().set(Color.white).lerp(Color.scarlet, Mathf.clamp(Mathf.round(threat, step), 0f, 1f)).toString();
        return "[#" + color + "]" + Core.bundle.get("threat." + key);
    }

    private void addVanillaStrategicInfo(Sector sector, @Nullable SectorState saved, SharedCampaignProgress.Availability availability){
        if(saved == null || !saved.hasBase){
            if(availability != null && availability.available()){
                inspector.add(Core.bundle.get("sectors.threat") + " [accent]" + displayThreat(sector, saved)).left().row();
            }
        }
        if(saved != null){
            if(vulnerable(sector, saved)){
                inspector.add("@sectors.vulnerable").color(Pal.remove).left().row();
            }else if(!saved.hasBase && saved.hasEnemyBase){
                inspector.add("@sectors.enemybase").color(Pal.remove).left().row();
            }
            if(saved.resources != null && saved.resources.any()){
                inspector.table(resources -> {
                    resources.left();
                    resources.add("@sectors.resources").padRight(4f);
                    for(String identity : saved.resources){
                        UnlockableContent value = resolveContent(identity);
                        if(value != null && value.uiIcon != null && value.uiIcon.found()){
                            resources.image(value.uiIcon).padRight(3f).scaling(Scaling.fit).size(iconSmall);
                        }
                    }
                }).left().growX().padTop(3f).row();
            }
        }
    }

    private Planet preferredPlanet(){
        Planet preferred = content.planet(campaign.primaryPlanetName);
        if(preferred != null && usablePlanet(preferred)) return preferred;
        if(usablePlanet(Planets.serpulo)) return Planets.serpulo;
        Planet found = content.planets().find(this::usablePlanet);
        return found == null ? Planets.serpulo : found;
    }

    private boolean usablePlanet(Planet planet){
        return usablePlanet(campaign, planet);
    }

    private boolean usablePlanet(SharedCampaignState model, Planet planet){
        if(planet == null || planet.generator == null || planet.sectors.isEmpty() || model == null) return false;
        var policy = model.planetPolicies.get(planet.name);
        if(policy == null || "unsupported".equals(policy.mode)) return false;
        // A campaign stores compatibility snapshots for every installed policy, but the strategic UI should only
        // expose planets that actually belong to this campaign's progression. Primary is always relevant; other
        // planets appear only after they have durable sector/action/mission state.
        if(Objects.equals(model.primaryPlanetName, planet.name)) return true;
        if(model.sectors.values().toSeq().contains(sector -> Objects.equals(sector.planetName, planet.name))) return true;
        if(model.actions.values().toSeq().contains(action -> Objects.equals(action.planetName, planet.name))) return true;
        return model.missions.values().toSeq().contains(mission -> Objects.equals(mission.planetName, planet.name));
    }

    private void setup(){
        ui.minimapfrag.hide();
        clearChildren();
        margin(0f);

        boolean owner = operations.owner();
        boolean mayInvite = operations.mayInvite();
        boolean authoritative = operations.authoritative();

        viewport = new Element(){
            {
                name = "sharedCampaign.planetViewport";
                addListener(new ElementGestureListener(){
                    @Override public void tap(InputEvent event, float x, float y, int count, KeyCode button){
                        if(button != KeyCode.mouseLeft) return;
                        // Touch devices do not necessarily maintain Scene2D hover state. Pick from the actual tap
                        // position and apply the same disclosure gate used by mouse hover.
                        Sector hit = pickInspectableSector();
                        if(hit == null) return;
                        selected = hit;
                        rebuildInspector();
                        if(count >= 2) SharedCampaignPlanetDialog.this.operations.details(selected);
                    }

                    @Override public void pan(InputEvent event, float x, float y, float deltaX, float deltaY){
                        rotate(deltaX, deltaY);
                    }
                });
                addListener(new InputListener(){
                    @Override public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY){
                        zoom = Mathf.clamp(zoom + amountY / 10f, state.planet.minZoom, state.planet.maxZoom);
                        return true;
                    }
                });
            }

            @Override public void act(float delta){
                super.act(delta);
                if(state.otherCamPos != null){
                    state.otherCamAlpha = Mathf.lerpDelta(state.otherCamAlpha, 1f, 0.05f);
                    if(Mathf.equal(state.otherCamAlpha, 1f, 0.01f)){
                        state.camPos.set(Tmp.v31().set(state.otherCamPos).slerp(state.planet.position, state.otherCamAlpha).add(state.camPos).sub(state.planet.position));
                        state.otherCamPos = null;
                    }
                }
                if(scene.getDialog() == SharedCampaignPlanetDialog.this && pointerOverPlanetViewport()){
                    // The geometric ray hit is not a disclosure authority. Unknown/hidden sectors never enter the
                    // presentation state, so labels/icons/borders/arcs cannot leak their identity or prerequisites.
                    hovered = pickInspectableSector();
                }else{
                    hovered = null;
                }

                if(newlyAvailable.any()){
                    Sector reveal = newlyAvailable.first();
                    if(reveal.planet != state.planet) viewPlanet(reveal.planet);
                    lookAt(reveal);
                    zoom = 0.75f;
                    revealTime += Time.delta();
                    if(revealTime >= 20f && !revealAnnounced){
                        revealAnnounced = true;
                        ui.announce(Iconc.lockOpen + " [accent]" + displaySectorName(reveal), 2f);
                    }
                    if(revealTime > 90f){
                        newlyAvailable.remove(0);
                        revealTime = 0f;
                        revealAnnounced = false;
                    }
                }

                state.zoom = Mathf.lerpDelta(state.zoom, zoom, 0.4f);
                state.uiAlpha = Mathf.lerpDelta(state.uiAlpha, Mathf.num(state.zoom < 1.9f || newlyAvailable.any()), 0.1f);
                updateHoverLabel();
            }

            @Override public void draw(){ planets.render(state); }
        };

        boolean compact = sceneWidth() < 980 || Core.graphics.isPortrait();
        Table topBar = new Table(Styles.black8);
        topBar.name = "sharedCampaign.planet.topbar";
        topBar.left();
        topBar.add("@sharedcampaign.planetoperations").style(Styles.defaultLabel).pad(10f).growX().left();
        // A long campaign name must never widen the top bar past the screen edge. Keep the default layout path
        // byte-identical (no wrap: a wrapped label's prefWidth is 0 and would shift every sibling) and only clamp
        // on compact layouts, where fillX makes the label honor the capped cell and ellipsis truncates in one line.
        Cell<Label> campaignLabelCell = topBar.label(() -> campaign.displayName == null ? "" : campaign.displayName)
            .color(Pal.gray).padRight(8f);
        campaignLabelCell.get().setEllipsis(true);
        if(compact) campaignLabelCell.maxWidth(260f).fillX();
        if(compact){
            topBar.button(Icon.planet, this::showPlanetChooserDialog).size(44f).tooltip("@planet").name("sharedCampaign.planet.choosePlanet");
        }
        topBar.button(Icon.refresh, operations::refresh).size(44f).tooltip("@refresh").name("sharedCampaign.planet.refresh");
        topBar.button(Icon.list, () -> operations.list(state.planet)).size(44f).tooltip("@sharedcampaign.strategy.operations").name("sharedCampaign.planet.list");
        topBar.button(Icon.tree, () -> operations.research(state.planet)).size(44f).tooltip("@research").name("sharedCampaign.planet.research");
        if(mayInvite) topBar.button(Icon.players, operations::invite).size(44f).tooltip("@sharedcampaign.invitecode").name("sharedCampaign.planet.invite");
        if(owner) topBar.button(Icon.settings, operations::settings).size(44f).tooltip("@settings").name("sharedCampaign.planet.settings");
        topBar.button(Icon.info, operations::compatibility).size(44f).tooltip("@sharedcampaign.compatibility").name("sharedCampaign.planet.compatibility");
        if(authoritative) topBar.button(Icon.export, operations::migrate).size(44f).tooltip("@sharedcampaign.migratehost").name("sharedCampaign.planet.migrate");

        Table top = new Table();
        top.top().left();
        top.add(topBar).growX();

        Table bottom = new Table();
        bottom.bottom().left();
        bottom.button("@back", Icon.left, this::hide).size(compact ? 150f : 200f, 54f).pad(8f).name("sharedCampaign.planet.back");
        bottom.add().growX();

        // Information is a read-only spatial overlay and belongs in the upper-right. Commands stay in a separate
        // lower action strip so selecting a Sector no longer covers the planet with a mixed dashboard/card.
        Table infoLayer = new Table();
        infoLayer.top().right();
        infoLayer.marginTop(72f).marginRight(12f);
        float inspectorWidth = Math.min(compact ? 390f : 410f, Math.max(260f, availW(24f)));
        float inspectorHeight = Math.max(180f, Math.min(compact ? 300f : 500f, availH(165f)));
        ScrollPane inspectorPane = new ScrollPane(inspector, Styles.smallPane);
        inspectorPane.name = "sharedCampaign.planet.inspectorPane";
        inspectorPane.setScrollingDisabled(true, false);
        inspectorPane.setOverscroll(false, false);
        infoLayer.add(inspectorPane).width(inspectorWidth).height(inspectorHeight);

        Table operationLayer = new Table();
        operationLayer.bottom();
        operationLayer.marginBottom(68f);
        // Commands are a compact action strip, not a second dashboard. Keep enough room for two side-by-side
        // actions without stretching a small selection into the former 620/760px slab on desktop.
        float operationWidth = Math.min(compact ? 500f : 560f, Math.max(300f, availW(24f)));
        operationLayer.add(operationsPanel).width(operationWidth).maxHeight(Math.min(220f, sceneHeight() * 0.30f));

        if(compact){
            stack(viewport, top, infoLayer, operationLayer, bottom).grow();
        }else{
            Table left = new Table();
            buildPlanetChooser(left);
            stack(viewport, top, left, infoLayer, operationLayer, bottom).grow();
        }
        chromeOwner = owner;
        chromeMayInvite = mayInvite;
        chromeAuthoritative = authoritative;
        chromeStateInitialized = true;
        rebuildInspector();
    }

    private void buildPlanetChooser(Table root){
        root.top().left();
        root.marginTop(72f).marginLeft(12f);
        root.table(Styles.black8, stars -> {
            stars.top().left().margin(6f);
            int starCount = 0;
            for(Planet star : content.planets()){
                if(star.solarSystem != star || !content.planets().contains(planet -> planet.solarSystem == star && usablePlanet(planet))) continue;
                if(starCount++ > 0) stars.add(star.localizedName).color(Pal.gray).pad(6f).left().width(210f).row();
                for(Planet planet : content.planets()){
                    if(planet.solarSystem != star || !usablePlanet(planet)) continue;
                    Button button = stars.button(planet.localizedName,
                        Icon.icons.get(planet.icon + "Small", Icon.planet), Styles.flatTogglet,
                        () -> viewPlanet(planet)).width(210f).height(44f)
                        .checked(value -> state.planet == planet).name("sharedCampaign.planet.choice." + planet.name).get();
                    button.setColor(planet.iconColor);
                    if(button.getChildren().size > 1) button.getChildren().get(1).setColor(planet.iconColor);
                    stars.row();
                }
            }
        });
    }

    private void showPlanetChooserDialog(){
        BaseDialog dialog = new BaseDialog("@planet");
        dialog.name = "sharedCampaign.planet.chooser";
        dialog.addCloseButton();
        dialog.cont.pane(list -> {
            list.top().left();
            int starCount = 0;
            for(Planet star : content.planets()){
                if(star.solarSystem != star || !content.planets().contains(planet -> planet.solarSystem == star && usablePlanet(planet))) continue;
                if(starCount++ > 0) list.add(star.localizedName).color(Pal.gray).padTop(8f).left().growX().row();
                for(Planet planet : content.planets()){
                    if(planet.solarSystem != star || !usablePlanet(planet)) continue;
                    Button button = list.button(planet.localizedName, Icon.icons.get(planet.icon + "Small", Icon.planet), Styles.flatt, () -> {
                        dialog.hide();
                        viewPlanet(planet);
                    }).height(52f).growX().name("sharedCampaign.planet.choice." + planet.name).get();
                    button.setColor(planet.iconColor);
                    if(button.getChildren().size > 1) button.getChildren().get(1).setColor(planet.iconColor);
                    list.row();
                }
            }
        }).width(Math.min(520f, availW(40f))).height(Math.min(620f, availH(140f)));
        dialog.show();
    }

    private void viewPlanet(Planet planet){
        if(planet == null || !usablePlanet(planet) || planet == state.planet) return;
        state.otherCamPos = state.planet.position;
        state.otherCamAlpha = 0f;
        state.planet = planet;
        zoom = state.zoom = 1f;
        selected = preferredSector(planet);
        hovered = null;
        if(selected != null) lookAt(selected);
        rebuildInspector();
    }

    private @Nullable Sector preferredSector(Planet planet){
        ActionState active = campaign.actions.values().toSeq().find(action -> planet.name.equals(action.planetName) && action.status.isLive());
        if(active != null){
            Sector result = SharedCampaignProgress.findSector(active.planetName, active.sectorName);
            if(result != null) return result;
        }
        SectorState base = campaign.sectors.values().toSeq().find(sector -> planet.name.equals(sector.planetName) && (sector.attacked || sector.hasBase));
        if(base != null){
            Sector result = SharedCampaignProgress.findSector(base.planetName, base.sectorName);
            if(result != null) return result;
        }
        if(planet.sectors.size > planet.startSector){
            Sector start = planet.sectors.get(planet.startSector);
            if(inspectable(start)) return start;
        }
        return planet.sectors.find(this::inspectable);
    }

    private void rebuildInspector(){
        inspector.clear();
        operationsPanel.clear();
        inspector.background(Styles.black8).top().left().margin(12f);
        operationsPanel.background(Styles.black8).top().left().margin(10f);
        // Never let a stale selection survive a snapshot/planet transition after it ceases to be inspectable.
        if(selected != null && !inspectable(selected)) selected = null;
        if(selected == null){
            inspector.add("@sharedcampaign.nosectors").color(Pal.gray).wrap().left().growX();
            operationsPanel.background(null);
            return;
        }
        SectorState sector = sectorState(selected);
        ActionState action = actionState(selected);
        SharedCampaignProgress.Availability availability = SharedCampaignProgress.sectorAvailability(campaign, selected);

        inspector.add(displaySectorName(selected)).style(Styles.defaultLabel).left().growX().row();
        inspector.add(statusText(selected, sector, action, availability)).color(statusColor(selected, sector, action, availability)).left().padBottom(8f).row();
        if(availability != null && !availability.available() && availability.missing() != null && !availability.missing().isEmpty()){
            inspector.add("@sharedcampaign.unlockrequirements").color(Pal.accent).left().growX().padTop(2f).padBottom(3f).row();
            for(String missing : availability.missing()) inspector.add("[scarlet]" + missing + "[]").wrap().left().growX().padBottom(2f).row();
        }
        addVanillaStrategicInfo(selected, sector, availability);
        metric("@sharedcampaign.sector", selected.planet.localizedName + " / " + SharedCampaignProgress.sectorId(selected));
        if(action != null){
            metric("@status", actionStatusLabel(action.status));
            metric("@sharedcampaign.players", Integer.toString(action.connectedPlayers));
            metric("@sharedcampaign.wave", Integer.toString(action.summary == null ? 0 : action.summary.wave));
        }
        if(sector != null){
            metric("@sharedcampaign.wave", Integer.toString(sector.summary == null ? 0 : sector.summary.wave));
            metric("@sharedcampaign.coretype", sector.summary == null ? "" : sector.summary.coreType);
            metric("@sharedcampaign.power", sector.summary == null ? "" : Strings.fixed(sector.summary.powerProduced, 1) + " / " + Strings.fixed(sector.summary.powerConsumed, 1));
            metric("@sharedcampaign.lastsaved", sector.lastSavedAt <= 0 ? Core.bundle.get("none") : new Date(sector.lastSavedAt).toString());
            if(sector.logisticsWarning != null && !sector.logisticsWarning.isBlank()) inspector.add(sector.logisticsWarning).color(Pal.remove).wrap().left().growX().padTop(6f).row();
            if(sector.destinationSector != null && !sector.destinationSector.isBlank()) metric("@sharedcampaign.logisticsdestination", sector.destinationSector);
        }
        buildOperations(sector, action, availability);
        operationsPanel.table(details -> {
            details.left();
            details.button("@sharedcampaign.sectordetails", Icon.info, () -> operations.details(selected)).height(44f).growX()
                .name("sharedCampaign.planet.sector.details");
            if(action != null){
                details.button("@sharedcampaign.actiondetails", Icon.info, () -> operations.actionDetails(action)).height(44f).growX()
                    .name("sharedCampaign.planet.action.details");
            }
        }).growX().padTop(6f).row();
        service.uiExtensions().build(mindustry.campaign.shared.api.SharedCampaignUiRegistry.Surface.planetOverview, operationsPanel,
            new mindustry.campaign.shared.api.SharedCampaignUiRegistry.Context(service, SharedCampaignStateCopy.copy(campaign), sector, action, state.planet.name));
    }

    private void buildOperations(@Nullable SectorState sector, @Nullable ActionState action, SharedCampaignProgress.Availability availability){
        if(selected == null) return;
        operationsPanel.add("@sharedcampaign.strategy.operations").color(Pal.accent).left().row();
        operationsPanel.table(actions -> {
            actions.left();
            if(action != null && action.status == ActionStatus.running){
                actions.button("@join", Icon.play, () -> operations.join(action)).height(46f).minWidth(120f).growX().name("sharedCampaign.planet.sector.join");
                if(action.spectatorsAllowed) actions.button("@sharedcampaign.spectate", Icon.eye, () -> operations.spectate(action)).height(46f).minWidth(120f).growX().name("sharedCampaign.planet.sector.spectate");
                if(action.connectedPlayers == 0 && operations.owner()) actions.button(Icon.pause, () -> operations.suspend(action)).size(46f).name("sharedCampaign.planet.sector.suspend").tooltip("@sharedcampaign.suspend");
            }else if(action != null && action.status == ActionStatus.suspended){
                actions.button("@sharedcampaign.wake", Icon.play, () -> operations.launch(selected)).height(46f).minWidth(120f).growX().name("sharedCampaign.planet.sector.wake");
            }else if(action != null && (action.status == ActionStatus.preparing || action.status == ActionStatus.starting || action.status == ActionStatus.suspending)){
                String label = action.status == ActionStatus.suspending ? "@sharedcampaign.sectorstate.suspending" : "@sharedcampaign.sectorstate.preparing";
                actions.button(label, Icon.refresh, () -> {}).height(46f).minWidth(140f).growX().disabled(true).name("sharedCampaign.planet.sector.transitioning");
            }else if(action != null && action.status == ActionStatus.incompatible){
                actions.button("@sharedcampaign.sectorstate.incompatible", Icon.cancel, operations::compatibility).height(46f).minWidth(140f).growX().name("sharedCampaign.planet.sector.incompatible");
            }else if((action == null || action.status == ActionStatus.failed) && availability != null && availability.available()){
                actions.button(sector != null && sector.saveRelativePath != null && !sector.saveRelativePath.isBlank() ? "@sharedcampaign.wake" : "@launch.text", Icon.play,
                    () -> operations.launch(selected)).height(46f).minWidth(120f).growX().disabled(campaign.runningActions() >= campaign.maxActiveActions).name("sharedCampaign.planet.sector.launch");
            }
        }).growX().row();

        if(sector != null && sector.hasBase){
            operationsPanel.button("@sharedcampaign.logistics.configure", Icon.export, () -> operations.logistics(selected)).height(44f).growX()
                .name("sharedCampaign.planet.sector.logistics").row();
        }
        ActionState current = operations.currentAction(campaign);
        if(current != null && current.status.isLive() && (action == null || !Objects.equals(current.actionId, action.actionId))){
            operationsPanel.button("@sharedcampaign.returnaction", Icon.left, () -> operations.join(current)).height(44f).growX()
                .name("sharedCampaign.planet.returnAction").row();
        }
    }

    private void metric(String label, String value){
        inspector.table(line -> {
            line.left();
            line.add(label).color(Pal.accent).wrap().width(170f).fillX().left().padRight(10f);
            line.add(value == null || value.isBlank() ? Core.bundle.get("none") : value).wrap().left().growX();
        }).growX().pad(2f).row();
    }


    private String actionStatusLabel(ActionStatus status){
        return Core.bundle.get("sharedcampaign.actionstatus." + status.name(), status.name());
    }

    private @Nullable SectorState sectorState(Sector sector){ return campaign.sectors.get(SharedCampaignProgress.sectorKey(sector)); }

    private @Nullable ActionState actionState(Sector sector){
        ActionState best = null;
        int bestRank = -1;
        for(ActionState action : campaign.actions.values()){
            if(!Objects.equals(action.planetName, sector.planet.name) || !Objects.equals(action.sectorName, SharedCampaignProgress.sectorId(sector))) continue;
            int rank = action.status == ActionStatus.preparing ? 5 :
                action.status.isLive() ? 4 :
                action.status == ActionStatus.suspended ? 3 :
                action.status == ActionStatus.incompatible ? 2 :
                action.status == ActionStatus.failed ? 1 : -1;
            if(rank < 0) continue;
            if(best == null || rank > bestRank || rank == bestRank && action.updatedAt > best.updatedAt){
                best = action;
                bestRank = rank;
            }
        }
        return best;
    }

    /** Sectors with current Shared strategic relevance receive full status highlighting/icons. */
    private boolean strategicallyRelevant(Sector sector){
        if(sector == null || sector.planet != state.planet) return false;
        if(sectorState(sector) != null || actionState(sector) != null || inspectable(sector)) return true;
        SharedCampaignProgress.Availability availability = SharedCampaignProgress.sectorAvailability(campaign, sector);
        if(availability.available()) return true;
        boolean anyBase = campaign.sectors.values().toSeq().contains(value -> sector.planet.name.equals(value.planetName) && value.hasBase);
        return !anyBase && sector.id == sector.planet.startSector;
    }

    /** Selection/inspection is broader than enterability, mirroring vanilla PlanetDialog.canSelect(). */
    private boolean inspectable(Sector sector){
        return sector != null && sector.planet == state.planet && SharedCampaignProgress.sectorInspectable(campaign, sector);
    }

    private String statusText(Sector sector, @Nullable SectorState saved, @Nullable ActionState action, SharedCampaignProgress.Availability availability){
        String id;
        if(action != null && action.status == ActionStatus.incompatible) id = "incompatible";
        else if(action != null && action.status == ActionStatus.failed) id = "failed";
        else if(action != null && action.status == ActionStatus.running) id = saved != null && saved.attacked ? "defending" : "running";
        else if(action != null && (action.status == ActionStatus.preparing || action.status == ActionStatus.starting)) id = "preparing";
        else if(action != null && action.status == ActionStatus.suspending) id = "suspending";
        else if(saved != null && saved.waitingSettlement) id = "settlement";
        else if(saved != null && saved.attacked) id = "threatened";
        else if(action != null && action.status == ActionStatus.suspended || saved != null && saved.hasBase && action == null) id = "dormant";
        else if(saved != null && saved.captured) id = "captured";
        else if(availability != null && availability.available()) id = "expedition";
        else id = "undiscovered";
        return Core.bundle.get("sharedcampaign.sectorstate." + id, id);
    }

    private Color statusColor(Sector sector, @Nullable SectorState saved, @Nullable ActionState action, SharedCampaignProgress.Availability availability){
        if(action != null && (action.status == ActionStatus.failed || action.status == ActionStatus.incompatible)) return Pal.remove;
        if(saved != null && saved.attacked) return Pal.remove;
        if(action != null && action.status == ActionStatus.running) return Pal.heal;
        if(action != null && (action.status == ActionStatus.preparing || action.status == ActionStatus.starting)) return Pal.accent;
        if(action != null && action.status == ActionStatus.suspending) return Color.slate;
        if(action != null && action.status == ActionStatus.suspended) return Color.slate;
        if(saved != null && saved.waitingSettlement) return Pal.accent;
        if(saved != null && (saved.captured || saved.hasBase)) return Team.sharded.color;
        if(availability != null && availability.available()) return Pal.place;
        return Color.gray;
    }

    private void rotate(float deltaX, float deltaY){
        Vec3 pos = state.camPos;
        float up = pos.angle(Vec3.Y);
        float speed = 1f - Math.abs(up - 90f) / 90f;
        pos.rotate(state.camUp, deltaX / 9f * speed);
        float amount = Mathf.clamp(up + deltaY / 10f, 1f, 179f) - up;
        pos.rotate(Tmp.v31().set(state.camUp).rotate(state.camDir, 90f), amount);
    }

    private void lookAt(Sector sector){
        if(sector == null) return;
        sector.planet.lookAt(sector, state.camPos);
        zoom = Mathf.clamp(zoom, state.planet.minZoom, state.planet.maxZoom);
    }

    private @Nullable TextureRegion primarySectorIcon(Sector sector, @Nullable SectorState saved, SharedCampaignProgress.Availability availability){
        if(saved != null && saved.attacked) return Fonts.getLargeIcon("warning");

        // Match vanilla precedence: a known-but-locked preset is identified as locked even if an older/custom
        // presentation icon exists in its strategic metadata.  The icon must not imply enterability.
        if(!availability.available() && inspectable(sector)){
            return Fonts.getLargeIcon("lock");
        }

        TextureRegion custom = sharedCustomIcon(saved);
        if(custom != null) return custom;

        if(sector.preset != null && (availability.available() || sector.preset.showHidden)){
            if(sector.preset.uiIcon != null && sector.preset.uiIcon.found()) return sector.preset.uiIcon;
            return Fonts.getLargeIcon("terrain");
        }
        return null;
    }

    private @Nullable TextureRegion sharedStatusBadge(@Nullable SectorState saved, @Nullable ActionState action){
        if(saved != null && saved.attacked) return null; // warning is the primary icon and must never be masked by Play.
        if(action != null){
            if(action.status == ActionStatus.running || action.status == ActionStatus.preparing || action.status == ActionStatus.starting) return Fonts.getLargeIcon("play");
            if(action.status == ActionStatus.suspending || action.status == ActionStatus.suspended) return Fonts.getLargeIcon("pause");
            if(action.status == ActionStatus.failed || action.status == ActionStatus.incompatible) return Fonts.getLargeIcon("warning");
        }
        return saved != null && saved.hasBase ? Fonts.getLargeIcon("home") : null;
    }

    private boolean isCurrentAction(@Nullable ActionState action){
        ActionState current = operations.currentAction(campaign);
        return action != null && current != null && Objects.equals(current.actionId, action.actionId);
    }

    private boolean sharedCaptured(Sector sector){
        SectorState saved = sectorState(sector);
        return saved != null && saved.captured && saved.hasBase;
    }

    private boolean anyExports(@Nullable SectorState state){
        return state != null && state.exportPerSecond != null && state.exportPerSecond.values().toSeq().contains(value -> value != null && value > 0.0001f);
    }

    @Override public void renderSectors(Planet planet){
        if(planet != state.planet) return;

        if(state.uiAlpha > 0.01f){
            for(Sector sector : planet.sectors){
                if(sector == selected) continue;
                SectorState saved = sectorState(sector);
                ActionState action = actionState(sector);
                SharedCampaignProgress.Availability availability = SharedCampaignProgress.sectorAvailability(campaign, sector);

                if(!inspectable(sector) && !strategicallyRelevant(sector)){
                    // Only shade sectors that could theoretically become selectable later.
                    // Planets with allowLaunchToNumbered=false (e.g. aerial-frontier) have hundreds
                    // of procedural sectors that can never be landed on; shading them all creates
                    // a large dark polygon artifact on the planet mesh.
                    if(sector.preset != null || sector.planet.allowLaunchToNumbered){
                        planets.fill(sector, Tmp.c1().set(shadowColor).mul(1f, 1f, 1f, state.uiAlpha), -0.001f);
                    }
                    continue;
                }

                Color color = null;
                if(saved != null && saved.hasBase){
                    color = Tmp.c2().set(Team.sharded.color).lerp(Team.crux.color, saved.hasEnemyBase ? 0.5f : 0f);
                }else if(saved != null && saved.hasEnemyBase){
                    color = Team.crux.color;
                }else if(action != null && (action.status == ActionStatus.failed || action.status == ActionStatus.incompatible)){
                    color = Pal.remove;
                }else if(action != null && action.status == ActionStatus.running){
                    color = Pal.heal;
                }else if(sector.preset != null && sector.preset.requireUnlock){
                    color = availability.available() ? Tmp.c2().set(Team.derelict.color).lerp(Color.white, Mathf.absin(Time.time(), 10f, 1f)) : Color.gray;
                }else if(availability.available()){
                    color = Color.gray;
                }

                if(color != null){
                    Color draw = Tmp.c1().set(color).mul(0.8f).a(state.uiAlpha);
                    if(!sharedCaptured(sector) && sector.preset != null && sector.preset.showHidden){
                        planets.drawSpecialSelection(sector, draw, 0.026f, -0.001f);
                    }else{
                        planets.drawSelection(sector, draw, 0.026f, -0.001f);
                    }
                }
            }
        }

        ActionState current = operations.currentAction(campaign);
        if(current != null && current.status.isLive()){
            Sector currentSector = SharedCampaignProgress.findSector(current.planetName, current.sectorName);
            if(currentSector != null && currentSector.planet == planet){
                planets.fill(currentSector, hoverColor.write(Tmp.c1()).mulA(state.uiAlpha), -0.001f);
            }
        }

        if(hovered != null){
            planets.fill(hovered, hoverColor.write(Tmp.c1()).mulA(state.uiAlpha), -0.003f);
            planets.drawBorders(hovered, borderColor, state.uiAlpha);
        }

        if(selected != null && inspectable(selected)){
            planets.drawSelection(selected, state.uiAlpha);
            planets.drawBorders(selected, borderColor, state.uiAlpha);
        }
        planets.batch.flush(arc.graphics.Gl.triangles);
    }

    @Override public void renderProjections(Planet planet){
        if(planet != state.planet || state.uiAlpha <= 0.01f) return;
        float iw = 64f / 4f;

        for(Sector sector : planet.sectors){
            if(!strategicallyRelevant(sector) && !inspectable(sector)) continue;
            ActionState action = actionState(sector);
            SectorState saved = sectorState(sector);
            SharedCampaignProgress.Availability availability = SharedCampaignProgress.sectorAvailability(campaign, sector);
            TextureRegion icon = primarySectorIcon(sector, saved, availability);
            TextureRegion badge = sharedStatusBadge(saved, action);
            boolean current = isCurrentAction(action);

            if(icon != null || badge != null){
                planets.drawPlane(sector, () -> {
                    Draw.color(Color.white, state.uiAlpha);
                    if(icon != null){
                        Draw.rect(icon, 0f, 0f, iw, iw * icon.height / Math.max(1f, icon.width));
                    }
                    if(badge != null){
                        float size = icon == null ? iw * 0.75f : iw * 0.54f;
                        float offset = icon == null ? 0f : iw * 0.43f;
                        if(current){
                            Draw.color(Pal.accent, state.uiAlpha);
                            Fill.circle(offset, offset, size * 0.62f);
                            Draw.color(Color.black, state.uiAlpha);
                            Fill.circle(offset, offset, size * 0.50f);
                        }
                        Draw.color(Color.white, state.uiAlpha);
                        Draw.rect(badge, offset, offset, size, size * badge.height / Math.max(1f, badge.width));
                    }
                    Draw.reset();
                });
            }
        }

        if(hovered != null && state.uiAlpha > 0.01f){
            Sector hover = hovered;
            SectorState saved = sectorState(hover);
            SharedCampaignProgress.Availability availability = SharedCampaignProgress.sectorAvailability(campaign, hover);
            TextureRegion icon = primarySectorIcon(hover, saved, availability);
            if(icon != null){
                planets.drawPlane(hover, () -> {
                    Draw.color(saved != null && saved.attacked ? Pal.remove : Color.white, Pal.accent, Mathf.absin(5f, 1f));
                    Draw.alpha(state.uiAlpha);
                    Draw.rect(icon, 0f, 0f, iw, iw * icon.height / Math.max(1f, icon.width));
                    Draw.reset();
                });
            }
        }
        Draw.reset();
    }

    @Override public void renderOverProjections(Planet planet){
        if(planet != state.planet || state.uiAlpha <= 0.001f) return;

        Sector hoverOrSelect = hovered != null ? hovered : selected;
        if(hoverOrSelect != null){
            SectorState targetState = sectorState(hoverOrSelect);
            SharedCampaignProgress.Availability availability = SharedCampaignProgress.sectorAvailability(campaign, hoverOrSelect);
            if((targetState == null || !targetState.hasBase) && availability.available()){
                Seq<SectorState> launchers = SharedCampaignProgress.launchSources(campaign, hoverOrSelect);
                if(launchers.any()){
                    SectorState sourceState = launchers.first();
                    Sector source = SharedCampaignProgress.findSector(sourceState.planetName, sourceState.sectorName);
                    if(source != null && source != hoverOrSelect && source.planet == planet){
                        planets.drawArcLine(planet, source.tile.v, hoverOrSelect.tile.v,
                            Team.sharded.color.write(Tmp.c2()).a(state.uiAlpha),
                            Tmp.c3().set(Team.sharded.color).mulA(0.5f * state.uiAlpha), 0.3f, 110f, 25, 0.006f);
                    }
                }
            }
        }

        // Vanilla shield topology, evaluated against Shared-authoritative captured/base state.
        for(Sector source : planet.sectors){
            Sector target = source.shieldTarget;
            if(target == null || target.planet != planet) continue;
            if(sharedCaptured(source) || sharedCaptured(target)) continue;
            if(!SharedCampaignProgress.sectorShielded(campaign, target)) continue;
            planets.drawArcLine(planet, source.tile.v, target.tile.v,
                Team.crux.color.write(Tmp.c2()).a(state.uiAlpha),
                Tmp.c3().set(Team.crux.color).mulA(0.5f * state.uiAlpha), 0.3f, 110f, 25, 0.006f);
        }

        // Vanilla vulnerable-base invasion relation, using Shared-owned enemy-base summaries.
        if(planet.campaignRules.sectorInvasion){
            for(Sector target : planet.sectors){
                SectorState targetState = sectorState(target);
                if(targetState == null || !targetState.hasBase) continue;
                for(Sector enemy : target.near()){
                    SectorState enemyState = sectorState(enemy);
                    if(enemyState != null && enemyState.hasEnemyBase && (enemy.preset == null || !enemy.preset.requireUnlock)){
                        planets.drawArcLine(planet, enemy.tile.v, target.tile.v,
                            Team.crux.color.write(Tmp.c2()).a(state.uiAlpha), Color.clear,
                            0.24f, 110f, 25, 0.005f);
                    }
                }
            }
        }

        // Show both inbound and outbound logistics for the selected base, not only its own destination.
        if(selected != null){
            String selectedKey = SharedCampaignProgress.sectorKey(selected);
            SectorState selectedState = sectorState(selected);
            if(selectedState != null && selectedState.hasBase){
                for(SectorState otherState : campaign.sectors.values()){
                    if(!Objects.equals(otherState.planetName, planet.name) || !otherState.hasBase) continue;
                    Sector other = SharedCampaignProgress.findSector(otherState.planetName, otherState.sectorName);
                    if(other == null || other == selected) continue;
                    String otherKey = SharedCampaignProgress.sectorKey(other);
                    if(Objects.equals(otherState.destinationSector, selectedKey) && anyExports(otherState)){
                        planets.drawArc(planet, other.tile.v, selected.tile.v,
                            Color.gray.write(Tmp.c2()).a(state.uiAlpha), Pal.accent.write(Tmp.c3()).a(state.uiAlpha), 0.4f, 90f, 25);
                    }
                    if(Objects.equals(selectedState.destinationSector, otherKey) && anyExports(selectedState)){
                        planets.drawArc(planet, selected.tile.v, other.tile.v,
                            Pal.place.write(Tmp.c2()).a(state.uiAlpha), Pal.accent.write(Tmp.c3()).a(state.uiAlpha), 0.4f, 90f, 25);
                    }
                }
            }
        }
    }

    /** Read-only UI-test hook: projects a Shared strategic sector to native client coordinates. */
    public Vec2 projectSectorForUiTest(Sector sector, Vec2 out){
        if(sector == null || sector.planet != state.planet) return null;
        Vec3 projected = sector.planet.project(sector, planets.cam, Tmp.v31());
        return out.set(projected.x, Core.graphics.getHeight() - projected.y);
    }

    /** Read-only UI-test hook for the exact Shared selection predicate. */
    public boolean inspectableForUiTest(Sector sector){ return inspectable(sector); }

    /** Read-only UI-test hook for the real Shared mouse-picking path. */
    public @Nullable Sector hoveredSectorForUiTest(){ return hovered; }

    /** Read-only selected-sector identity for product tests. */
    public @Nullable Sector selectedSectorForUiTest(){ return selected; }

    /**
     * Navigation/command bridge into the existing SharedCampaignDialog.  The planet page is a strategic spatial
     * surface, not a second implementation of campaign commands; every button delegates to the already hardened
     * command/dialog path so local-host and remote-coordinator clients share the same semantics.
     */
    private static float sceneWidth(){
        if(Core.scene != null && Core.scene.root != null && Core.scene.root.getWidth() > 0f) return Core.scene.root.getWidth() / Scl.scl();
        return Core.graphics == null ? 1280f : Core.graphics.getWidth() / Scl.scl();
    }

    private static float sceneHeight(){
        if(Core.scene != null && Core.scene.root != null && Core.scene.root.getHeight() > 0f) return Core.scene.root.getHeight() / Scl.scl();
        return Core.graphics == null ? 720f : Core.graphics.getHeight() / Scl.scl();
    }

    private static float availW(float pad){ return Math.max(120f, sceneWidth() - pad); }
    private static float availH(float pad){ return Math.max(120f, sceneHeight() - pad); }

    public interface Operations{
        String memberId();
        boolean owner();
        boolean mayInvite();
        boolean authoritative();
        void refresh();
        void list(Planet planet);
        void research(Planet planet);
        void settings();
        void invite();
        void compatibility();
        void migrate();
        void details(Sector sector);
        void actionDetails(ActionState action);
        void launch(Sector sector);
        void join(ActionState action);
        void spectate(ActionState action);
        void suspend(ActionState action);
        void logistics(Sector sector);

        default @Nullable ActionState currentAction(SharedCampaignState campaign){
            return SharedCampaignProgress.currentMemberAction(campaign, memberId(), true);
        }

        static Operations noop(){
            return new Operations(){
                public String memberId(){ return ""; }
                public boolean owner(){ return false; }
                public boolean mayInvite(){ return false; }
                public boolean authoritative(){ return false; }
                public void refresh(){} public void list(Planet planet){} public void research(Planet planet){} public void settings(){} public void invite(){}
                public void compatibility(){} public void migrate(){} public void details(Sector sector){} public void actionDetails(ActionState action){}
                public void launch(Sector sector){} public void join(ActionState action){} public void spectate(ActionState action){} public void suspend(ActionState action){} public void logistics(Sector sector){}
            };
        }
    }

}
