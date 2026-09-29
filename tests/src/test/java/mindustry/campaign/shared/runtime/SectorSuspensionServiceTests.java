package mindustry.campaign.shared.runtime;

import arc.struct.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** Clean vanilla strategic settlement: no Y-only structural/simulation model is involved. */
class SectorSuspensionServiceTests{
    private SharedCampaignPlanetRegistry policies;
    private SectorSuspensionService service;

    @BeforeEach
    void setup(){
        TestBootstrap.ensureBaseContent();
        policies = new SharedCampaignPlanetRegistry();
        VanillaPlanetPolicies.register(policies);
        policies.seal();
        service = new SectorSuspensionService(policies);
    }

    @Test
    void turnRemainderProductionCapacityCaptureAndDiscoveryAreDeterministic(){
        SharedCampaignState state = state();
        SectorState sector = sector(state, "settle-a");
        sector.summary.storageCapacity = 100;
        sector.items.put("copper", 10);
        sector.productionPerSecond.put("copper", 0.25f); // +30 over one 120-second vanilla turn.
        state.settlementRemainderTicks = (long)turnDuration - 60L;

        service.advance(state, 60L);

        assertEquals(60L, state.campaignTick);
        assertEquals(0L, state.settlementRemainderTicks);
        assertEquals(40, sector.items.get("copper", 0));
        assertEquals(40, sector.summary.items.get("copper", 0));
        assertEquals((long)turnDuration, sector.lastSummaryTick);
        assertEquals(turnDuration / 60f / 60f, sector.minutesCaptured, 0.0001f);
        assertTrue(state.discovered.contains("copper"), "positive strategic inventory should mirror vanilla discovery");

        sector.productionPerSecond.put("copper", 10f);
        service.advance(state, (long)turnDuration);
        assertEquals(100, sector.items.get("copper", 0), "storage capacity must clamp offline production");
    }

    @Test
    void liveStoryAndAttackedSectorsNeverReceiveDuplicateOfflineProduction(){
        SharedCampaignState state = state();
        SectorState live = sector(state, "live");
        SectorState story = sector(state, "story");
        SectorState attacked = sector(state, "attacked");
        SectorState suspended = sector(state, "suspended");
        for(SectorState sector : Seq.with(live, story, attacked, suspended)){
            sector.summary.storageCapacity = 10_000;
            sector.items.put("lead", 10);
            sector.productionPerSecond.put("lead", 1f);
        }
        attacked.attacked = true;
        attacked.minutesCaptured = 99f;

        ActionState action = new ActionState();
        action.actionId = "live-action";
        action.planetName = live.planetName;
        action.sectorName = live.sectorName;
        action.status = ActionStatus.running;
        state.actions.put(action.actionId, action);

        MissionState mission = new MissionState();
        mission.missionId = "story-mission";
        mission.planetName = story.planetName;
        mission.sectorName = story.sectorName;
        mission.status = MissionStatus.running;
        state.missions.put(mission.missionId, mission);

        service.advance(state, (long)turnDuration);

        assertEquals(10, live.items.get("lead", 0));
        assertEquals(10, story.items.get("lead", 0));
        assertEquals(10, attacked.items.get("lead", 0));
        assertEquals(130, suspended.items.get("lead", 0));
        assertEquals(0f, attacked.minutesCaptured, 0.0001f, "attacked sectors reset capture grace instead of simulating combat");
        assertTrue(live.hasBase && story.hasBase && attacked.hasBase, "offline settlement must never fabricate base loss");
    }

    @Test
    void queuedTransportDebitsOnceAndDeliversOnlyToSuspendedDestination(){
        SharedCampaignState state = state();
        SectorState source = sector(state, "source");
        SectorState destination = sector(state, "destination");
        source.items.put("silicon", 50);
        source.summary.storageCapacity = 100;
        destination.summary.storageCapacity = 100;

        TransportOrder order = new TransportOrder();
        order.sourceSector = key(source);
        order.destinationSector = key(destination);
        order.itemName = "silicon";
        order.amount = 20;
        order.createdAt = 1L;
        order.etaCampaignTick = 1L;
        state.transports.add(order);

        service.advance(state, 1L);
        assertEquals(30, source.items.get("silicon", 0));
        assertEquals(20, destination.items.get("silicon", 0));
        assertEquals(20, order.loaded);
        assertEquals(20, order.delivered);
        assertEquals(TransportStatus.delivered, order.status);

        service.advance(state, 1L);
        assertEquals(30, source.items.get("silicon", 0), "delivered order must not debit twice");
        assertEquals(20, destination.items.get("silicon", 0));

        TransportOrder blocked = new TransportOrder();
        blocked.sourceSector = key(source);
        blocked.destinationSector = key(destination);
        blocked.itemName = "silicon";
        blocked.amount = 10;
        blocked.createdAt = 2L;
        blocked.etaCampaignTick = state.campaignTick;
        state.transports.add(blocked);
        ActionState liveSource = new ActionState();
        liveSource.actionId = "source-live";
        liveSource.planetName = source.planetName;
        liveSource.sectorName = source.sectorName;
        liveSource.status = ActionStatus.running;
        state.actions.put(liveSource.actionId, liveSource);

        service.advance(state, 1L);
        assertEquals(TransportStatus.queued, blocked.status);
        assertEquals(30, source.items.get("silicon", 0), "live source remains authoritative for queued cargo");
    }

    @Test
    void suspendedDeliveryDoesNotRaceAnInDoubtLiveTransportTransaction(){
        SharedCampaignState state = state();
        SectorState source = sector(state, "tx-source");
        SectorState destination = sector(state, "tx-destination");
        source.summary.storageCapacity = 100;
        destination.summary.storageCapacity = 100;

        TransportOrder order = new TransportOrder();
        order.orderId = "tx-order";
        order.sourceSector = key(source);
        order.destinationSector = key(destination);
        order.itemName = "silicon";
        order.amount = order.loaded = 20;
        order.createdAt = 1L;
        order.etaCampaignTick = 0L;
        order.status = TransportStatus.inTransit;
        state.transports.add(order);

        TransportTransaction transaction = new TransportTransaction();
        transaction.transactionId = "tx-live-credit";
        transaction.orderId = order.orderId;
        transaction.actionId = "destination-action";
        transaction.itemName = order.itemName;
        transaction.requested = order.amount;
        transaction.status = TransportTransactionStatus.preparing;
        transaction.createdAt = transaction.updatedAt = 1L;
        state.transportTransactions.put(transaction.transactionId, transaction);

        service.advance(state, 1L);

        assertEquals(0, destination.items.get("silicon", 0), "suspension must not duplicate an in-doubt Action-side credit");
        assertEquals(0, order.delivered);
        assertEquals(TransportStatus.inTransit, order.status);
    }

    @Test
    void legacyLaunchPadCreditsSamePlanetWithoutDoubleDebitingMeasuredSource(){
        SharedCampaignState state = state();
        SectorState source = sector(state, "legacy-source");
        SectorState destination = sector(state, "legacy-destination");
        source.items.put("graphite", 25);
        source.summary.storageCapacity = 1000;
        destination.summary.storageCapacity = 1000;
        source.legacyLaunchPads = true;
        source.destinationSector = key(destination);
        source.exportPerSecond.put("graphite", 0.5f); // 60 items per vanilla turn.

        service.advance(state, (long)turnDuration);

        assertEquals(25, source.items.get("graphite", 0), "measured export already includes the source debit");
        assertEquals(60, destination.items.get("graphite", 0));
        assertEquals(60, destination.lastImportedItems.get("graphite", 0));
    }

    private SharedCampaignState state(){
        SharedCampaignState state = new SharedCampaignState();
        state.primaryPlanetName = "serpulo";
        state.freezeWhenEmpty = false;
        policies.installInto(state, null);
        return state;
    }

    private static SectorState sector(SharedCampaignState state, String id){
        SectorState sector = new SectorState();
        sector.planetName = "serpulo";
        sector.sectorName = id;
        sector.hasBase = true;
        sector.captured = true;
        sector.summary.planetName = sector.planetName;
        state.sectors.put(key(sector), sector);
        return sector;
    }

    private static String key(SectorState sector){
        return SharedCampaignProgress.sectorKey(sector.planetName, sector.sectorName);
    }
}
