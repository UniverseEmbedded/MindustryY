package mindustry.campaign.shared.api;

import arc.func.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;

import java.util.*;

/** Namespaced, dynamically removable UI contributions for shared-campaign strategic surfaces. */
public class SharedCampaignUiRegistry{
    private final ObjectMap<Surface, Seq<Contribution>> contributions = new ObjectMap<>();
    private boolean sealed;

    public synchronized void register(Contribution contribution){
        Objects.requireNonNull(contribution, "contribution");
        if(sealed) throw new IllegalStateException("Shared campaign UI registry is sealed");
        if(contribution.ownerId().isBlank() || !contribution.ownerId().contains(":")) throw new IllegalArgumentException("UI contribution owner must be namespaced");
        if(contribution.id().isBlank() || !contribution.id().contains(":")) throw new IllegalArgumentException("UI contribution ID must be namespaced");
        if(contribution.surface() == null || contribution.builder() == null) throw new IllegalArgumentException("UI contribution surface and builder are required");
        Seq<Contribution> list = contributions.get(contribution.surface(), Seq::new);
        if(list.contains(existing -> existing.id().equals(contribution.id()))) throw new IllegalArgumentException("Duplicate shared campaign UI contribution " + contribution.id());
        list.add(contribution);
        list.sort(Comparator.comparingInt(Contribution::order).thenComparing(Contribution::id));
    }

    public synchronized void unregisterOwner(String ownerId){
        if(sealed) throw new IllegalStateException("Shared campaign UI registry is sealed");
        for(Seq<Contribution> list : contributions.values()) list.removeAll(value -> Objects.equals(value.ownerId(), ownerId));
    }

    public synchronized Seq<Contribution> contributions(Surface surface){
        Seq<Contribution> values = contributions.get(surface);
        return values == null ? new Seq<>() : values.copy();
    }

    public void build(Surface surface, Table table, Context context){
        for(Contribution contribution : contributions(surface)){
            if(contribution.visible() != null && !contribution.visible().get(context)) continue;
            Table section = new Table();
            section.name = "sharedCampaign.extension." + contribution.id().replace(':', '.');
            contribution.builder().get(section, context);
            if(section.getChildren().size > 0) table.add(section).growX().left().row();
        }
    }

    public synchronized void seal(){ sealed = true; }
    public synchronized boolean sealed(){ return sealed; }

    public enum Surface{campaignLobby, planetOverview, sectorDetails, actionDetails, campaignSettings}

    public record Context(SharedCampaignApi api, SharedCampaignState campaign, SectorState sector, ActionState action, String viewMode){
        public Context{
            campaign = campaign == null ? null : mindustry.campaign.shared.io.SharedCampaignStateCopy.copy(campaign);
            sector = sector == null ? null : mindustry.campaign.shared.io.SharedCampaignCodec.copySector(sector);
            action = action == null ? null : mindustry.campaign.shared.io.SharedCampaignCodec.copyAction(action);
            viewMode = viewMode == null ? "" : viewMode;
        }
    }

    public record Contribution(String ownerId, String id, Surface surface, int order, Boolf<Context> visible, Cons2<Table, Context> builder){
        public Contribution{
            ownerId = ownerId == null ? "" : ownerId.trim();
            id = id == null ? "" : id.trim();
        }
    }
}
