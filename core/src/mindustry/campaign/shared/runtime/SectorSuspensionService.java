package mindustry.campaign.shared.runtime;

import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.api.*;
import mindustry.campaign.shared.api.SharedCampaignPlanetRegistry.*;
import mindustry.type.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Deterministic vanilla-style strategic settlement for sectors that do not currently own a live world runtime.
 *
 * <p>This class deliberately contains no Industrial/Simulation/Tantros model. A suspended vanilla sector advances
 * only from the authoritative summary captured when its real world was last running: measured production/import/
 * export rates, durable transport orders, capture time, discovery, and invasion scheduling. Live Actions, story
 * missions, and attacked sectors never receive duplicate offline production. Invasions are queued for a real Action;
 * combat is never fabricated from a strategic summary.</p>
 */
public final class SectorSuspensionService{
    private final SharedCampaignPlanetRegistry planetPolicies;
    private static final long vanillaTurnTicks = (long)turnDuration;
    private static final int vanillaTurnSeconds = (int)(turnDuration / 60f);

    public SectorSuspensionService(SharedCampaignPlanetRegistry planetPolicies){
        this.planetPolicies = Objects.requireNonNull(planetPolicies, "planetPolicies");
    }

    public boolean needsAdvance(SharedCampaignState state){
        if(state == null) return false;
        if(state.settlementRemainderTicks > 0L) return true;
        if(state.transports.contains(order -> order.status != TransportStatus.cancelled && order.status != TransportStatus.delivered)) return true;
        return state.sectors.values().toSeq().contains(sector -> sector.hasBase);
    }

    public void advance(SharedCampaignState state, long elapsedCampaignTicks){
        Objects.requireNonNull(state, "state");
        if(elapsedCampaignTicks <= 0L) return;
        state.campaignTick = Math.addExact(state.campaignTick, elapsedCampaignTicks);
        state.settlementRemainderTicks = Math.addExact(state.settlementRemainderTicks, elapsedCampaignTicks);

        while(state.settlementRemainderTicks >= vanillaTurnTicks){
            state.settlementRemainderTicks -= vanillaTurnTicks;
            runVanillaTurn(state);
        }

        // Explicit transport ETAs are campaign-tick based and may mature between full vanilla turns.
        deliverToSuspendedDestinations(state);
        mirrorStrategicCoreDiscovery(state);
    }

    private void runVanillaTurn(SharedCampaignState state){
        // Action worlds disable their own Universe strategic turn. The coordinator owns capture time for every base,
        // including a currently-played one, so there is one campaign clock rather than one clock per runtime.
        for(SectorState sector : state.sectors.values()){
            if(!sector.hasBase) continue;
            if(sector.attacked){
                sector.minutesCaptured = 0f;
                sector.summary.minutesCaptured = 0f;
            }else{
                sector.minutesCaptured += turnDuration / 60f / 60f;
                sector.summary.minutesCaptured = sector.minutesCaptured;
            }
            synchronizeSummary(sector);
        }

        // Vanilla resets the previous turn's imports before computing the new turn.
        for(SectorState sector : state.sectors.values()){
            if(sector.hasBase && !hasLiveAction(state, sectorKey(sector)) && sector.legacyLaunchPads){
                sector.lastImportedItems.clear();
                sector.importPerSecond.clear();
                sector.summary.importPerSecond.clear();
            }
        }

        // Legacy LaunchPad compatibility: measured export already reflects the source-core debit, so only the
        // destination receives a credit/order here. New explicit transport orders use the path below instead.
        for(SectorState source : state.sectors.values()){
            if(!isEligible(state, source) || !source.legacyLaunchPads || source.destinationSector.isBlank()) continue;
            SectorState destination = state.sectors.get(source.destinationSector);
            if(destination == null || !destination.hasBase || !Objects.equals(source.planetName, destination.planetName)) continue;

            for(ObjectMap.Entry<String, Float> entry : source.exportPerSecond){
                int amount = Math.max(0, (int)(entry.value * vanillaTurnSeconds));
                if(amount <= 0) continue;
                destination.lastImportedItems.put(entry.key, Math.addExact(destination.lastImportedItems.get(entry.key, 0), amount));
                if(hasLiveAction(state, sectorKey(destination))){
                    TransportOrder order = new TransportOrder();
                    order.sourceSector = sectorKey(source);
                    order.destinationSector = sectorKey(destination);
                    order.itemName = entry.key;
                    order.amount = amount;
                    order.loaded = amount;
                    order.createdAt = Time.millis();
                    order.etaCampaignTick = state.campaignTick;
                    order.status = TransportStatus.inTransit;
                    state.transports.add(order);
                }else{
                    credit(destination, entry.key, amount);
                }
            }
        }

        // Suspended-sector economics use the measured vanilla rates captured from the last authoritative live world.
        for(SectorState sector : state.sectors.values()){
            if(!isEligible(state, sector)) continue;
            int capacity = Math.max(sector.summary.storageCapacity, 0);
            for(ObjectMap.Entry<String, Float> entry : sector.productionPerSecond){
                int current = sector.items.get(entry.key, 0);
                int delta = (int)(entry.value * vanillaTurnSeconds);
                int next = current + delta;
                if(capacity > 0) next = Math.min(next, capacity);
                sector.items.put(entry.key, Math.max(0, next));
            }

            if(sector.legacyLaunchPads){
                for(ObjectMap.Entry<String, Float> export : sector.exportPerSecond){
                    float production = sector.productionPerSecond.get(export.key, 0f);
                    if(sector.items.get(export.key, 0) <= 0 && production < 0f && export.value > 0f){
                        float imported = sector.lastImportedItems.get(export.key, 0) / (float)vanillaTurnSeconds;
                        export.value = Math.min(imported, export.value);
                    }
                }
                for(ObjectMap.Entry<String, Integer> imported : sector.lastImportedItems){
                    float rate = imported.value / (float)vanillaTurnSeconds;
                    if(rate > 0f) sector.importPerSecond.put(imported.key, rate);
                }
            }

            sector.lastSummaryTick = Math.addExact(sector.lastSummaryTick, vanillaTurnTicks);
            synchronizeSummary(sector);
            planetPolicies.settleSuspendedSector(state, sector, vanillaTurnTicks);
            queueInvasionIfNeeded(state, sector);
        }

        trimDeliveredOrders(state);
    }

    /** Applies due transports only when the destination is coordinator-owned. Live destinations use action-side 2PC. */
    private void deliverToSuspendedDestinations(SharedCampaignState state){
        for(TransportOrder order : state.transports){
            if(order.status == TransportStatus.cancelled || order.status == TransportStatus.delivered) continue;
            SectorState source = state.sectors.get(order.sourceSector), destination = state.sectors.get(order.destinationSector);
            if(source == null || destination == null || destination.attacked) continue;
            // A PREPARING/COMMITTED live-side reservation may already have changed the Action core even if that Action
            // just disconnected. Never let suspended settlement race or duplicate an in-doubt 2PC credit.
            if(hasActiveTransportTransaction(state, order.orderId)) continue;
            if(hasLiveAction(state, sectorKey(destination))) continue;

            if(order.status == TransportStatus.queued){
                // Queued cargo has not departed yet; live/attacked source inventory remains authoritative elsewhere.
                if(source.attacked || hasLiveAction(state, sectorKey(source))) continue;
                int available = source.items.get(order.itemName, 0);
                if(available < order.amount) continue;
                source.items.put(order.itemName, available - order.amount);
                synchronizeSummary(source);
                order.loaded = order.amount;
                order.status = TransportStatus.inTransit;
            }

            if(order.status == TransportStatus.inTransit && order.etaCampaignTick <= state.campaignTick){
                int remaining = order.loaded - order.delivered;
                int delivered = credit(destination, order.itemName, remaining);
                order.delivered += delivered;
                synchronizeSummary(destination);
                if(order.delivered >= order.loaded) order.status = TransportStatus.delivered;
            }
        }
        trimDeliveredOrders(state);
    }


    private static boolean hasActiveTransportTransaction(SharedCampaignState state, String orderId){
        return state.transportTransactions.values().toSeq().contains(transaction -> Objects.equals(orderId, transaction.orderId) &&
            (transaction.status == TransportTransactionStatus.preparing || transaction.status == TransportTransactionStatus.committed));
    }

    private static int credit(SectorState sector, String itemName, int amount){
        if(amount <= 0) return 0;
        int capacity = Math.max(sector.summary.storageCapacity, 0);
        int current = sector.items.get(itemName, 0);
        int accepted = capacity > 0 ? Math.min(amount, Math.max(0, capacity - current)) : amount;
        if(accepted > 0) sector.items.put(itemName, current + accepted);
        return accepted;
    }

    /** Online-protected vanilla invasions become durable attacked state; no world combat is simulated offline. */
    private void queueInvasionIfNeeded(SharedCampaignState state, SectorState sector){
        if(sector.attacked || !sector.hasSpawns || sector.minutesCaptured <= invasionGracePeriod) return;
        Sector actual = SharedCampaignProgress.findSector(sector.planetName, sector.sectorName);
        if(actual == null || !actual.planet.campaignRules.sectorInvasion) return;

        int nearbyEnemyBases = 0;
        for(Sector neighbor : actual.near()){
            boolean configuredEnemyBase = (neighbor.generateEnemyBase && neighbor.preset == null)
                || (neighbor.preset != null && neighbor.preset.captureWave == 0 && !neighbor.preset.requireUnlock);
            if(!configuredEnemyBase) continue;
            SectorState sharedNeighbor = state.sectors.get(SharedCampaignProgress.sectorKey(neighbor));
            if(sharedNeighbor == null || !sharedNeighbor.hasBase) nearbyEnemyBases++;
        }
        if(nearbyEnemyBases <= 0) return;

        long seed = 0xcbf29ce484222325L;
        String identity = state.campaignId + "\n" + state.campaignTick + "\n" + sector.planetName + "\n" + sector.sectorName;
        for(int i = 0; i < identity.length(); i++){ seed ^= identity.charAt(i); seed *= 0x100000001b3L; }
        Rand random = new Rand(seed);
        float chance = baseInvasionChance * (0.8f + (nearbyEnemyBases - 1) * 0.3f);
        if(!random.chance(chance)) return;

        int waveMax = Math.max(sector.summary.winWave, sector.summary.wave) + random.random(2, 4) * 5;
        InvasionDisposition disposition = planetPolicies.invasionDisposition(state, sector, waveMax);
        if(disposition == InvasionDisposition.ignore) return;
        // Clean Shared Campaign deliberately does not resolve strategic combat from summaries. Even policies that ask
        // for continuous settlement fail toward a real defense Action until an explicit deterministic combat model exists.
        sector.attacked = true;
        sector.minutesCaptured = 0f;
        sector.summary.attacked = true;
        sector.summary.minutesCaptured = 0f;
        sector.summary.winWave = waveMax;
        sector.summary.waves = true;
        sector.summary.attackMode = false;
        appendEvent(state, "shared-campaign:sector-invasion-queued", sectorKey(sector), Integer.toString(waveMax));
    }

    private static void mirrorStrategicCoreDiscovery(SharedCampaignState state){
        for(SectorState sector : state.sectors.values()){
            if(!sector.hasBase) continue;
            for(ObjectMap.Entry<String, Integer> entry : sector.items){
                if(entry.key != null && !entry.key.isBlank() && entry.value != null && entry.value > 0){
                    SharedCampaignProgress.recordDiscovery(state, entry.key);
                }
            }
        }
    }

    private static void synchronizeSummary(SectorState sector){
        sector.summary.sampledAtTick = sector.lastSummaryTick;
        sector.summary.items.clear(); sector.summary.items.putAll(sector.items);
        sector.summary.productionPerSecond.clear(); sector.summary.productionPerSecond.putAll(sector.productionPerSecond);
        sector.summary.exportPerSecond.clear(); sector.summary.exportPerSecond.putAll(sector.exportPerSecond);
        sector.summary.importPerSecond.clear(); sector.summary.importPerSecond.putAll(sector.importPerSecond);
        sector.summary.destinationSector = sector.destinationSector;
        sector.summary.legacyLaunchPads = sector.legacyLaunchPads;
        sector.summary.attacked = sector.attacked;
        sector.summary.hasSpawns = sector.hasSpawns;
        sector.summary.minutesCaptured = sector.minutesCaptured;
    }

    private static void trimDeliveredOrders(SharedCampaignState state){
        int completed = state.transports.count(order -> order.status == TransportStatus.delivered || order.status == TransportStatus.cancelled);
        int remove = completed - 4096;
        for(int i = 0; i < state.transports.size && remove > 0;){
            TransportOrder order = state.transports.get(i);
            if(order.status == TransportStatus.delivered || order.status == TransportStatus.cancelled){
                state.transports.remove(i);
                remove--;
            }else i++;
        }
    }

    private static void appendEvent(SharedCampaignState state, String type, String subject, String payload){
        CampaignEvent event = new CampaignEvent();
        event.sequence = state.recentEvents.isEmpty() ? 1L : state.recentEvents.peek().sequence + 1L;
        event.timestamp = Time.millis();
        event.type = type;
        event.subjectId = subject;
        event.payload = payload;
        state.recentEvents.add(event);
        while(state.recentEvents.size > 2048) state.recentEvents.remove(0);
    }

    private boolean isEligible(SharedCampaignState state, SectorState sector){
        return sector.hasBase && !sector.attacked && !hasLiveAction(state, sectorKey(sector)) && !isStoryMission(state, sectorKey(sector));
    }

    private static String sectorKey(SectorState sector){
        return SharedCampaignProgress.sectorKey(sector.planetName, sector.sectorName);
    }

    private static boolean isStoryMission(SharedCampaignState state, String sectorKey){
        return state.missions.values().toSeq().contains(mission ->
            SharedCampaignProgress.sectorKey(mission.planetName, mission.sectorName).equals(sectorKey)
                && (mission.status == MissionStatus.preparing || mission.status == MissionStatus.running || mission.status == MissionStatus.paused));
    }

    private static boolean hasLiveAction(SharedCampaignState state, String sectorKey){
        return state.actions.values().toSeq().contains(action ->
            SharedCampaignProgress.sectorKey(action.planetName, action.sectorName).equals(sectorKey) && action.status.isLive());
    }
}
