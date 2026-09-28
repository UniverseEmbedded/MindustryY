package mindustry.campaign.shared.ui;

import arc.*;
import arc.func.*;
import arc.math.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.ui.dialogs.SchematicsDialog.*;
import mindustry.world.blocks.storage.*;

import mindustry.world.meta.*;

import static mindustry.Vars.*;

/** Launch planner backed exclusively by authoritative shared-campaign inventory snapshots. */
public class SharedLaunchLoadoutDialog extends BaseDialog{
    private final LoadoutDialog resourceDialog = new LoadoutDialog();
    private SharedCampaignState campaign;
    private Sector destination;
    private Cons<RuntimePayloads.LaunchPlan> confirm;
    private Seq<SectorState> sources = new Seq<>();
    private SectorState source;
    private Schematic selected;
    private final ItemSeq launchResources = new ItemSeq();
    private final ItemSeq total = new ItemSeq();
    private boolean valid;
    private boolean maxResources = true;
    private int capacity;

    public SharedLaunchLoadoutDialog(){
        super("@sharedcampaign.launchplan");
        name = "sharedCampaign.launchPlan.dialog";
    }

    public void show(SharedCampaignState campaign, Sector destination, Cons<RuntimePayloads.LaunchPlan> confirm){
        this.campaign = campaign;
        this.destination = destination;
        this.confirm = confirm;
        this.sources = SharedCampaignProgress.launchSources(campaign, destination);
        if(sources.isEmpty()){
            confirm.get(RuntimePayloads.LaunchPlan.empty());
            return;
        }
        source = sources.first();
        selected = null;
        launchResources.clear();
        rebuild();
        show();
    }

    private void rebuild(){
        cont.clear();
        buttons.clear();
        buttons.defaults().size(160f, 64f);
        buttons.button("@back", Icon.left, this::hide);
        addCloseListener();

        cont.add(destination.name()).style(Styles.defaultLabel).row();
        cont.add("@sharedcampaign.selectorigin").color(Pal.accent).padTop(8f).row();
        cont.pane(origins -> {
            // Keep the origin grid visually tied to the centered launch plan. With one source, left alignment
            // pushed the only choice to the extreme viewport edge; multiple rows still lay out as a grid.
            origins.center();
            int columns = Math.max(1, (int)(Core.graphics.getWidth() / Scl.scl(270f)));
            int index = 0;
            for(SectorState candidate : sources){
                String key = SharedCampaignProgress.sectorKey(candidate.planetName, candidate.sectorName);
                Sector actual = SharedCampaignProgress.findSector(candidate.planetName, candidate.sectorName);
                String title = actual == null ? key : actual.name();
                origins.button(button -> {
                    button.left();
                    button.add(title).growX().left().row();
                    button.add(Core.bundle.format("sharedcampaign.originitems", totalItems(candidate))).color(Pal.gray).left();
                }, Styles.togglet, () -> {
                    if(source == candidate) return;
                    source = candidate;
                    selected = null;
                    launchResources.clear();
                    rebuild();
                }).checked(source == candidate).width(250f).pad(3f);
                if(++index % columns == 0) origins.row();
            }
        }).growX().maxHeight(180f).row();

        CoreBlock sourceCore = sourceCore();
        if(selected == null || selected.findCore().size > sourceCore.size || !validSchematic(selected)){
            selected = schematics.getDefaultLoadout(sourceCore);
            if(selected == null || !validSchematic(selected)) selected = destination.planet.generator.defaultLoadout;
        }
        if(selected == null || !validSchematic(selected)){
            cont.add("@sharedcampaign.novalidloadout").color(Pal.remove).wrap().growX().row();
            return;
        }

        if(destination.allowLaunchSchematics()){
            cont.add("@sharedcampaign.selectloadout").color(Pal.accent).padTop(8f).row();
            int columns = Math.max((int)(Core.graphics.getWidth() / Scl.scl(230f)), 1);
            cont.pane(list -> {
                int index = 0;
                for(var entry : schematics.getLoadouts()){
                    if(entry.key.size > sourceCore.size) continue;
                    for(Schematic schematic : entry.value){
                        if(!validSchematic(schematic)) continue;
                        list.button(button -> button.add(new SchematicImage(schematic)), Styles.togglet, () -> {
                            selected = schematic;
                            launchResources.clear();
                            rebuild();
                        }).checked(schematic == selected).size(200f).pad(4f);
                        if(++index % columns == 0) list.row();
                    }
                }
            }).growX().maxHeight(300f).row();
        }else if(destination.preset != null && destination.preset.description != null){
            cont.add(destination.preset.description).wrap().center().growX().pad(8f).row();
        }

        updateTotals();
        Table itemTable = new Table();
        rebuildItems(itemTable);
        cont.add(Core.bundle.format("launch.capacity", capacity)).color(Pal.gray).padTop(6f).row();
        cont.pane(itemTable).growX().maxHeight(180f).row();
        cont.add("@sector.missingresources").color(Pal.remove).visible(() -> !valid).row();

        if(destination.allowLaunchLoadout()){
            buttons.button("@resources.max", Icon.add, Styles.togglet, () -> {
                maxResources = !maxResources;
                rebuild();
            }).checked(maxResources);
            buttons.button("@resources", Icon.edit, () -> editResources()).disabled(maxResources);
        }
        buttons.button("@launch.text", Icon.ok, () -> {
            updateTotals();
            if(!valid) return;
            ObjectMap<String, Integer> resources = new ObjectMap<>();
            for(ItemStack stack : launchResources) if(stack.amount > 0) resources.put(stack.item.name, stack.amount);
            confirm.get(new RuntimePayloads.LaunchPlan(SharedCampaignProgress.sectorKey(source.planetName, source.sectorName), schematics.writeBase64(selected), resources));
            hide();
        }).disabled(button -> !valid).name("sharedCampaign.launchPlan.confirm");
    }

    private void editResources(){
        ItemSeq available = sourceItems();
        selected.requirements().each(available::remove);
        Seq<ItemStack> selectedStacks = launchResources.toSeq();
        resourceDialog.show(capacity, available, selectedStacks,
            item -> item.unlocked() && item != null && !item.hidden,
            selectedStacks::clear, () -> {}, () -> {
                launchResources.clear();
                for(ItemStack stack : selectedStacks) launchResources.set(stack.item, stack.amount);
                maxResources = false;
                rebuild();
            });
    }

    private void updateTotals(){
        capacity = (int)(destination.planet.launchCapacityMultiplier * selected.findCore().itemCapacity);
        if(!destination.allowLaunchLoadout()){
            launchResources.clear();
            if(destination.preset != null){
                for(ItemStack stack : destination.preset.generator.map.rules().loadout){
                    if(stack.item != null && !stack.item.hidden) launchResources.add(stack.item, stack.amount);
                }
            }
        }else if(maxResources){
            ItemSeq available = sourceItems();
            ItemSeq requirements = selected.requirements();
            launchResources.clear();
            for(Item item : content.items()){
                if(item == null || item.hidden) continue;
                launchResources.set(item, Mathf.clamp(available.get(item) - requirements.get(item), 0, capacity));
            }
        }else{
            launchResources.min(capacity);
        }

        total.clear();
        selected.requirements().each(total::add);
        launchResources.each(total::add);
        valid = sourceItems().has(total);
    }

    private void rebuildItems(Table table){
        table.clearChildren();
        ItemSeq available = sourceItems();
        ItemSeq schematicItems = selected.requirements();
        int index = 0;
        for(ItemStack stack : total){
            int schematic = schematicItems.get(stack.item), carried = launchResources.get(stack.item);
            if(schematic + carried == 0) continue;
            table.image(stack.item.uiIcon).size(iconSmall).left();
            String amount = (schematic + carried) + (destination.allowLaunchLoadout() ? "[gray] (" + carried + " + " + schematic + ")" : "");
            table.add(available.has(stack.item, stack.amount) ? amount : "[scarlet]" + Math.min(available.get(stack.item), stack.amount) + "[lightgray]/" + amount).left().padRight(8f);
            if(++index % 4 == 0) table.row();
        }
    }

    private int totalItems(SectorState sector){
        int total = 0;
        for(Integer value : sector.items.values()) if(value != null && value > 0) total = Math.addExact(total, value);
        return total;
    }

    private ItemSeq sourceItems(){
        ItemSeq result = new ItemSeq();
        for(ObjectMap.Entry<String, Integer> entry : source.items){
            Item item = content.item(entry.key);
            if(item != null && entry.value > 0) result.set(item, entry.value);
        }
        return result;
    }

    private CoreBlock sourceCore(){
        if(source.summary != null && source.summary.coreType != null && !source.summary.coreType.isBlank()){
            var block = content.block(source.summary.coreType);
            if(block instanceof CoreBlock core) return core;
        }
        if(destination.planet.defaultCore instanceof CoreBlock core) return core;
        throw new IllegalStateException("Planet has no campaign core: " + destination.planet.name);
    }

    private boolean validSchematic(Schematic schematic){
        if(schematic == null || !schematic.hasCore()) return false;
        for(Schematic.Stile tile : schematic.tiles){
            if(tile.block == null || !tile.block.supportsEnv(destination.planet.defaultEnv)) return false;
        }
        return true;
    }
}
