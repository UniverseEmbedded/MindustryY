import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Campaign-client protocol gates for strategic research and launch-pad logistics. */
public class SharedCampaignResearchLogisticsControlTests{
    @TempDir Path temp;

    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    void researchRequestDebitsSuspendedBaseAndCommitsUnlock() throws Exception{
        try(SharedCampaignService service = create("research")){
            TechTree.TechNode node = TechTree.all.find(candidate -> candidate.content != null && !candidate.content.alwaysUnlocked && candidate.requirements != null && candidate.requirements.length > 0
                && SharedCampaignProgress.researchPlanetContains(candidate, Planets.serpulo.name));
            assertNotNull(node, "expected at least one resource-backed Serpulo technology node");
            Sector sector = Planets.serpulo.sectors.get(Planets.serpulo.startSector);
            String key = SharedCampaignProgress.sectorKey(sector);
            String owner = service.activeMemberId();
            service.authority().coordinator().store().transact(owner, "test:seed-research", state -> {
                for(TechTree.TechNode current = node.parent; current != null; current = current.parent) state.researched.add(current.content.name);
                SectorState base = new SectorState();
                base.planetName = sector.planet.name;
                base.sectorName = SharedCampaignProgress.sectorId(sector);
                base.hasBase = true;
                base.captured = true;
                for(ItemStack requirement : node.requirements){
                    base.items.put(requirement.item.name, requirement.amount + 25);
                    base.summary.items.put(requirement.item.name, requirement.amount + 25);
                }
                state.sectors.put(key, base);
            });

            SharedCampaignState updated = service.controlClient().research(node.content.name, Planets.serpulo.name);
            assertTrue(updated.researched.contains(node.content.name), "completed contribution should unlock target technology");
            ResearchState progress = updated.research.get(node.content.name);
            assertNotNull(progress);
            assertTrue(progress.complete());
            SectorState base = updated.sectors.get(key);
            for(ItemStack requirement : node.requirements){
                assertEquals(25, base.items.get(requirement.item.name, 0), "research should debit the exact vanilla requirement");
            }
            assertTrue(updated.researchTransactions.values().toSeq().contains(tx -> tx.contentName.equals(node.content.name) && tx.status == ResearchTransactionStatus.committed));
            assertTrue(service.authority().state().controlRequestReceipts.values().toSeq().contains(receipt ->
                ControlProtocol.Type.researchRequest.name().equals(receipt.requestType)),
                "successful campaign-client research must atomically retain a replay receipt");
        }
    }

    @Test
    @Timeout(30)
    void committedResearchPushesToSecondClientWithoutManualRefresh() throws Exception{
        try(SharedCampaignService service = create("research-push")){
            TechTree.TechNode node = TechTree.all.find(candidate -> candidate.content != null && !candidate.content.alwaysUnlocked && candidate.requirements != null && candidate.requirements.length > 0
                && SharedCampaignProgress.researchPlanetContains(candidate, Planets.serpulo.name));
            assertNotNull(node);
            Sector sector = Planets.serpulo.sectors.get(Planets.serpulo.startSector);
            String key = SharedCampaignProgress.sectorKey(sector);
            String owner = service.activeMemberId();
            service.authority().coordinator().store().transact(owner, "test:seed-research-push", state -> {
                for(TechTree.TechNode current = node.parent; current != null; current = current.parent) state.researched.add(current.content.name);
                SectorState base = new SectorState();
                base.planetName = sector.planet.name;
                base.sectorName = SharedCampaignProgress.sectorId(sector);
                base.hasBase = true;
                base.captured = true;
                for(ItemStack requirement : node.requirements){
                    base.items.put(requirement.item.name, requirement.amount + 25);
                    base.summary.items.put(requirement.item.name, requirement.amount + 25);
                }
                state.sectors.put(key, base);
            });

            int port = service.authority().coordinator().publicEntryPort();
            SharedCampaignClient.Credential observerCredential = SharedCampaignClient.enroll("127.0.0.1", port, service.localInviteCode(), "observer");
            try(SharedCampaignClient researcher = new SharedCampaignClient("127.0.0.1", port, service.localMemberCredential(owner));
                SharedCampaignClient observer = new SharedCampaignClient("127.0.0.1", port, observerCredential)){
                observer.snapshot();
                CountDownLatch pushed = new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<SharedCampaignState> observed = new java.util.concurrent.atomic.AtomicReference<>();
                observer.onSnapshot(snapshot -> {
                    if(snapshot.researched.contains(node.content.name)){
                        observed.set(snapshot);
                        pushed.countDown();
                    }
                });

                SharedCampaignState committed = researcher.research(node.content.name, Planets.serpulo.name);
                assertTrue(committed.researched.contains(node.content.name));
                assertTrue(pushed.await(5L, TimeUnit.SECONDS), "second client should receive committed Shared research by snapshot push, without manual refresh");
                assertNotNull(observed.get());
                assertTrue(observed.get().researched.contains(node.content.name));
            }
        }
    }

    @Test
    void logisticsRequestCommitsSamePlanetAndRejectsCrossPlanet() throws Exception{
        try(SharedCampaignService service = create("logistics")){
            String owner = service.activeMemberId();
            Sector a = Planets.serpulo.sectors.get(Planets.serpulo.startSector);
            Sector b = Planets.serpulo.sectors.find(sector -> sector != a);
            assertNotNull(b);
            Sector foreign = Planets.erekir.sectors.get(Planets.erekir.startSector);
            String aKey = SharedCampaignProgress.sectorKey(a), bKey = SharedCampaignProgress.sectorKey(b), foreignKey = SharedCampaignProgress.sectorKey(foreign);
            service.authority().coordinator().store().transact(owner, "test:seed-logistics", state -> {
                state.sectors.put(aKey, base(a));
                state.sectors.put(bKey, base(b));
                state.sectors.put(foreignKey, base(foreign));
            });

            SharedCampaignState updated = service.controlClient().updateSectorLogistics(aKey, bKey);
            assertEquals(bKey, updated.sectors.get(aKey).destinationSector);
            assertEquals(bKey, updated.sectors.get(aKey).summary.destinationSector);
            assertTrue(service.authority().state().controlRequestReceipts.values().toSeq().contains(receipt ->
                ControlProtocol.Type.sectorLogisticsRequest.name().equals(receipt.requestType)),
                "successful logistics mutation must atomically retain a replay receipt");
            assertThrows(java.io.IOException.class, () -> service.controlClient().updateSectorLogistics(aKey, foreignKey));
            SharedCampaignState afterReject = service.controlClient().snapshot();
            assertEquals(bKey, afterReject.sectors.get(aKey).destinationSector, "rejected cross-planet request must not change durable target");
        }
    }

    private SharedCampaignService create(String name){
        Fi root = new Fi(temp.resolve(name).toFile());
        Fi mods = new Fi(temp.resolve(name + "-mods").toFile()); mods.mkdirs();
        SharedCampaignService service = new SharedCampaignService(Vars.game(), mods);
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.ownerId = "owner-" + name;
        options.ownerDisplayName = "Owner";
        options.displayName = name;
        options.primaryPlanetName = Planets.serpulo.name;
        service.createLocal(root, options, "127.0.0.1", 0, 0);
        return service;
    }

    private static SectorState base(Sector sector){
        SectorState out = new SectorState();
        out.planetName = sector.planet.name;
        out.sectorName = SharedCampaignProgress.sectorId(sector);
        out.hasBase = true;
        out.captured = true;
        return out;
    }
}
