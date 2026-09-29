import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.api.SharedCampaignPlanetRegistry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Functional gate ensuring the Authority/Coordinator path actually invokes persisted planet-policy runtime hooks. */
public class SharedCampaignPlanetPolicyHookTests{
    @TempDir Path temp;

    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    void actionAndSettingsValidationReachSelectedPlanetRuntime() throws Exception{
        TrackingRuntime runtime = new TrackingRuntime();
        try(SharedCampaignService service = create("policy-validation", runtime)){
            runtime.rejectAction = true;
            IOException rejected = assertThrows(IOException.class,
                () -> service.controlClient().startAction(Planets.serpulo.name, "groundZero", ""));
            assertTrue(rejected.getMessage().contains("policy rejected action"));
            assertTrue(runtime.actionValidations.get() >= 1, "selected planet runtime must validate Action start");
            assertTrue(service.state().actions.isEmpty(), "rejected policy validation must not allocate an Action");

            SharedCampaignState before = service.state();
            SharedCampaignState accepted = service.controlClient().updateSettings(2, false, true, InvitePolicy.members, before.persistenceProfile);
            assertTrue(accepted.multiFrontEnabled);
            assertEquals(2, accepted.maxActiveActions);
            assertEquals(1, runtime.settingsValidations.get(), "settings update must reach selected planet runtime");

            runtime.rejectSettings = true;
            assertThrows(IOException.class,
                () -> service.controlClient().updateSettings(3, true, true, InvitePolicy.members, accepted.persistenceProfile));
            SharedCampaignState afterReject = service.controlClient().snapshot();
            assertEquals(2, afterReject.maxActiveActions, "rejected settings must not mutate durable campaign state");
            assertFalse(afterReject.freezeWhenEmpty, "rejected settings must preserve the last committed value");
        }
    }

    @Test
    void researchCommitInvokesBeforeAndAfterHooksForResearchPlanet() throws Exception{
        TrackingRuntime runtime = new TrackingRuntime();
        try(SharedCampaignService service = create("policy-research", runtime)){
            TechTree.TechNode node = TechTree.all.find(candidate -> candidate.content != null && !candidate.content.alwaysUnlocked
                && candidate.requirements != null && candidate.requirements.length > 0
                && SharedCampaignProgress.researchPlanetContains(candidate, Planets.serpulo.name));
            assertNotNull(node);
            Sector sector = Planets.serpulo.sectors.get(Planets.serpulo.startSector);
            String key = SharedCampaignProgress.sectorKey(sector);
            String owner = service.activeMemberId();
            service.authority().coordinator().store().transact(owner, "test:seed-policy-research", state -> {
                for(TechTree.TechNode current = node.parent; current != null; current = current.parent) state.researched.add(current.content.name);
                SectorState base = new SectorState();
                base.planetName = sector.planet.name;
                base.sectorName = SharedCampaignProgress.sectorId(sector);
                base.hasBase = true;
                base.captured = true;
                for(ItemStack requirement : node.requirements){
                    base.items.put(requirement.item.name, requirement.amount + 5);
                    base.summary.items.put(requirement.item.name, requirement.amount + 5);
                }
                state.sectors.put(key, base);
            });

            SharedCampaignState committed = service.controlClient().research(node.content.name, Planets.serpulo.name);
            assertTrue(committed.researched.contains(node.content.name));
            assertEquals(1, runtime.beforeResearch.get(), "research transaction must invoke before hook once");
            assertEquals(1, runtime.afterResearch.get(), "research transaction must invoke after hook once");
            assertEquals(node.content.name, runtime.lastResearchContent);
            assertTrue(runtime.lastResearchCompleted, "after hook must observe committed completion state");
        }
    }

    private SharedCampaignService create(String name, TrackingRuntime runtime){
        SharedCampaignService service = new SharedCampaignService(Vars.game(), new Fi(temp.resolve(name + "-mods").toFile()));
        String policyId = "test:" + name;
        service.planetPolicies().register(new PlanetPolicy(
            "test:hooks", policyId, Planets.serpulo.name, 1, policyId + "-compat-v1",
            MultiplayerMode.limitedMultiFront, 2, false, true,
            ResearchSharing.perPlanet, ResourceOwnership.perSector, FailureRule.sectorOnly, InvasionRule.onlineProtected
        ), runtime);
        SharedCampaignCreationOptions options = new SharedCampaignCreationOptions();
        options.ownerId = "owner-" + name;
        options.ownerDisplayName = "Owner";
        options.displayName = name;
        options.primaryPlanetName = Planets.serpulo.name;
        options.planetPolicyIds.put(Planets.serpulo.name, policyId);
        service.createLocal(new Fi(temp.resolve(name).toFile()), options, "127.0.0.1", 0, 0);
        return service;
    }

    private static final class TrackingRuntime implements PlanetRuntime{
        final AtomicInteger actionValidations = new AtomicInteger();
        final AtomicInteger settingsValidations = new AtomicInteger();
        final AtomicInteger beforeResearch = new AtomicInteger();
        final AtomicInteger afterResearch = new AtomicInteger();
        volatile boolean rejectAction;
        volatile boolean rejectSettings;
        volatile String lastResearchContent = "";
        volatile boolean lastResearchCompleted;

        @Override public void validateActionStart(ActionStartContext context){
            actionValidations.incrementAndGet();
            if(rejectAction) throw new IllegalStateException("policy rejected action");
        }

        @Override public void validateSettings(SettingsContext context){
            settingsValidations.incrementAndGet();
            if(rejectSettings) throw new IllegalArgumentException("policy rejected settings");
        }

        @Override public void beforeResearchCommit(ResearchMutationContext context){
            beforeResearch.incrementAndGet();
            lastResearchContent = context.contentName();
        }

        @Override public void afterResearchCommit(ResearchMutationContext context){
            afterResearch.incrementAndGet();
            lastResearchContent = context.contentName();
            lastResearchCompleted = context.completed();
        }
    }
}
