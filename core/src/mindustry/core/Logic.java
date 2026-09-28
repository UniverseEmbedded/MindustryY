package mindustry.core;

import arc.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.*;
import mindustry.annotations.Annotations.*;
import mindustry.campaign.shared.net.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.maps.*;
import mindustry.runtime.*;
import mindustry.type.*;
import mindustry.type.Weather.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Logic module.
 * Handles all logic for entities and waves.
 * Handles game state events.
 * Does not store any game state itself.
 * <p>
 * This class should <i>not</i> call any outside methods to change state of modules, but instead fire events.
 */
public class Logic implements ApplicationListener{

    public Logic(){

        Events.on(BlockDestroyEvent.class, event -> {
            //skip if rule is off
            if(!mindustry.Vars.game().state.rules.ghostBlocks) return;

            //blocks that get broken are appended to the team's broken block queue
            Tile tile = event.tile;
            //skip null entities or un-rebuildables, for obvious reasons
            if(tile.build == null || !tile.block().rebuildable) return;

            tile.build.addPlan(true);
        });

        Events.on(BlockBuildEndEvent.class, event -> {
            if(!event.breaking){
                checkOverlappingPlans(event.team, event.tile);

                if(event.team == mindustry.Vars.game().state.rules.defaultTeam){
                    mindustry.Vars.game().state.stats.placedBlockCount.increment(event.tile.block());
                }
            }
        });

        Events.on(PayloadDropEvent.class, e -> {
            if(e.build != null){
                checkOverlappingPlans(e.build.team, e.build.tile);
            }
        });

        //when loading a 'damaged' sector, propagate the damage
        Events.on(SaveLoadEvent.class, e -> {
            if(mindustry.Vars.game().state.isCampaign()){
                mindustry.Vars.game().state.rules.coreIncinerates = true;
                mindustry.Vars.game().state.rules.canGameOver = true;
                mindustry.Vars.game().state.rules.allowEditRules = false;

                //fresh map has no sector info
                if(!e.isMap){
                    SectorInfo info = mindustry.Vars.game().state.rules.sector.info();
                    info.write();

                    mindustry.Vars.game().state.getSector().planet.applyRules(mindustry.Vars.game().state.rules);

                    info.hasCore = true;

                    mindustry.Vars.game().state.rules.sector.saveInfo();
                }
            }
        });

        Events.on(PlayEvent.class, e -> {
            //reset weather on play
            var randomWeather = mindustry.Vars.game().state.rules.weather.copy().shuffle();
            float sum = 0f;
            for(var weather : randomWeather){
                weather.cooldown = sum + Mathf.random(weather.maxFrequency);
                sum += weather.cooldown;
            }
            //tick resets on new save play
            mindustry.Vars.game().state.tick = 0f;
        });

        Events.on(WorldLoadEvent.class, e -> {

            if(mindustry.Vars.game().state.isCampaign()){
                //enable building AI on campaign unless the preset disables it
                mindustry.Vars.game().state.rules.coreIncinerates = true;
                mindustry.Vars.game().state.rules.infiniteResources = false;
                mindustry.Vars.game().state.rules.allowEditRules = false;
                mindustry.Vars.game().state.rules.allowEditWorldProcessors = false;
                mindustry.Vars.game().state.rules.worldProcessorPlayerLink = false;

                if(mindustry.Vars.game().state.getPlanet().enemyInfiniteItems){
                    mindustry.Vars.game().state.rules.waveTeam.rules().infiniteResources = true;
                    mindustry.Vars.game().state.rules.waveTeam.rules().fillItems = true;
                }
                mindustry.Vars.game().state.rules.waveTeam.rules().buildSpeedMultiplier *= mindustry.Vars.game().state.getPlanet().enemyBuildSpeedMultiplier;

                if(mindustry.Vars.game().state.getPlanet().enemyFactoryActivationDelay > 0f && mindustry.Vars.game().state.rules.waveTeam.rules().unitFactoryActivationDelay == 0f){
                    mindustry.Vars.game().state.rules.waveTeam.rules().unitFactoryActivationDelay = mindustry.Vars.game().state.getPlanet().enemyFactoryActivationDelay;
                }
            }

            //save settings
            if(RuntimeContexts.isPrimary()) Core.settings.manualSave();
        });

        //sync research
        Events.on(UnlockEvent.class, e -> {
            if(mindustry.Vars.game().net.server()){
                Call.researched(e.content);
            }
        });

        Events.on(SectorCaptureEvent.class, e -> {
            if(!mindustry.Vars.game().net.client() && e.sector == mindustry.Vars.game().state.getSector() && e.sector.isBeingPlayed()){
                mindustry.Vars.game().state.rules.waveTeam.data().destroyToDerelict();
            }

            if(e.sector.planet.sectorCaptureReplacements.size > 0){
                boolean any = false;
                //faster mapping, avoids objectmap per-tile
                Floor[] map = new Floor[content.blocks().size];
                for(var entry : e.sector.planet.sectorCaptureReplacements){
                    if(mindustry.Vars.game().indexer.isBlockPresent(entry.key)){
                        map[entry.key.id] = entry.value.asFloor();
                        any = true;
                    }
                }
                if(any){
                    mindustry.Vars.game().world.tiles.eachTile(t -> {
                        Floor result = map[t.floor().id];
                        if(result != null){
                            t.setFloor(result);
                        }
                    });
                }
            }

            if(!mindustry.Vars.game().net.client() && e.sector.planet.generator != null){
                e.sector.planet.generator.onSectorCaptured(e.sector);
            }

            if(checkCampaignStats()){
                mindustry.Vars.game().state.getPlanet().stats().sectorsCaptured ++;
            }
        });

        Events.on(SectorLoseEvent.class, e -> {
            if(!mindustry.Vars.game().net.client() && e.sector.planet.generator != null){
                e.sector.planet.generator.onSectorLost(e.sector);
            }
        });

        Events.on(BlockDestroyEvent.class, e -> {
            if(e.tile.build instanceof CoreBuild core && core.team.isAI() && mindustry.Vars.game().state.rules.coreDestroyClear){
                RuntimeContexts.post(() -> core.team.data().timeDestroy(core.x, core.y, mindustry.Vars.game().state.rules.enemyCoreBuildRadius));
            }
        });

        //listen to core changes; if all cores have been destroyed, set to derelict.
        Events.on(CoreChangeEvent.class, e -> RuntimeContexts.post(() -> {
            if(mindustry.Vars.game().state.rules.cleanupDeadTeams && mindustry.Vars.game().state.rules.pvp && !e.core.isAdded() && e.core.team != Team.derelict && e.core.team.cores().isEmpty()){
                e.core.team.data().destroyToDerelict();
            }
        }));

        Events.on(BlockBuildEndEvent.class, e -> {

            if((e.team == mindustry.Vars.game().state.rules.defaultTeam || e.unit != null && e.unit.team == mindustry.Vars.game().state.rules.defaultTeam)){
                if(e.breaking){
                    mindustry.Vars.game().state.stats.buildingsDeconstructed++;
                }else{
                    mindustry.Vars.game().state.stats.buildingsBuilt++;
                }

                if(checkCampaignStats()){
                    (e.breaking ? mindustry.Vars.game().state.getPlanet().stats().buildingsDeconstructed : mindustry.Vars.game().state.getPlanet().stats().buildingsBuilt).increment(e.tile.block());
                }
            }
        });

        Events.on(BlockDestroyEvent.class, e -> {
            if(e.tile.team() == mindustry.Vars.game().state.rules.defaultTeam){
                mindustry.Vars.game().state.stats.buildingsDestroyed ++;

                if(checkCampaignStats()){
                    mindustry.Vars.game().state.getPlanet().stats().buildingsDestroyed.increment(e.tile.block());
                }
            }else{ //...should derelict blocks count as 'destroyed'? technically, they could be destroyed by the enemy, but that is very rare
                mindustry.Vars.game().state.stats.destroyedBlockCount.increment(e.tile.block());

                if(checkCampaignStats()){
                    mindustry.Vars.game().state.getPlanet().stats().enemyBuildingsDestroyed.increment(e.tile.block());
                }
            }
        });

        Events.on(UnitDestroyEvent.class, e -> {
            if(e.unit.team != mindustry.Vars.game().state.rules.defaultTeam){
                mindustry.Vars.game().state.stats.enemyUnitsDestroyed ++;
            }

            if(checkCampaignStats()){
                (e.unit.team != mindustry.Vars.game().state.rules.defaultTeam ? mindustry.Vars.game().state.getPlanet().stats().enemyUnitsDestroyed : mindustry.Vars.game().state.getPlanet().stats().unitsDestroyed).increment(e.unit.type);
            }
        });

        Events.on(UnitCreateEvent.class, e -> {
            if(e.unit.team == mindustry.Vars.game().state.rules.defaultTeam){
                mindustry.Vars.game().state.stats.unitsCreated++;

                if(checkCampaignStats()){
                    mindustry.Vars.game().state.getPlanet().stats().unitsProduced.increment(e.unit.type);
                }
            }
        });

        Events.on(WaveEvent.class, e -> {
            if(checkCampaignStats()){
                mindustry.Vars.game().state.getPlanet().stats().wavesLasted ++;
            }
        });

        Events.on(GameOverEvent.class, e -> {
            if(checkCampaignStats()){
                mindustry.Vars.game().state.getPlanet().stats().sectorsLost ++;
            }
        });
    }

    private boolean checkCampaignStats(){
        return mindustry.Vars.game().state.isCampaign() && !mindustry.Vars.game().net.client();
    }

    private void checkOverlappingPlans(Team team, Tile tile){
        TeamData data = team.data();
        Iterator<BlockPlan> it = data.plans.iterator();
        var bounds = tile.block().bounds(tile.x, tile.y, Tmp.r1());
        while(it.hasNext()){
            BlockPlan b = it.next();
            if(bounds.overlaps(b.block.bounds(b.x, b.y, Tmp.r2()))){
                b.removed = true;
                it.remove();
            }
        }
    }

    /** Adds starting items, resets wave time, and sets state to playing. */
    public void play(){
        mindustry.Vars.game().state.set(State.playing);
        //grace period of 2x wave time before game starts
        mindustry.Vars.game().state.wavetime = (mindustry.Vars.game().state.rules.initialWaveSpacing <= 0 ? mindustry.Vars.game().state.rules.waveSpacing * 2 : mindustry.Vars.game().state.rules.initialWaveSpacing) * (mindustry.Vars.game().state.isCampaign() ? mindustry.Vars.game().state.getPlanet().campaignRules.difficulty.waveTimeMultiplier : 1f);
        mindustry.Vars.game().state.stats = new GameStats();
        Events.fire(new PlayEvent());

        //add starting items
        if(!mindustry.Vars.game().state.isCampaign() || !mindustry.Vars.game().state.rules.sector.planet.allowLaunchLoadout || (mindustry.Vars.game().state.rules.sector.preset != null && mindustry.Vars.game().state.rules.sector.preset.addStartingItems)){
            for(TeamData team : mindustry.Vars.game().state.teams.getActive()){
                if(team.hasCore()){
                    CoreBuild entity = team.core();
                    entity.items.clear();

                    for(ItemStack stack : mindustry.Vars.game().state.rules.loadout){
                        //make sure to cap storage
                        entity.items.add(stack.item, Math.min(stack.amount, entity.storageCapacity - entity.items.get(stack.item)));
                    }
                }
            }
        }

        //heal all cores on game start
        for(TeamData team : mindustry.Vars.game().state.teams.getActive()){
            for(var entity : team.cores){
                entity.heal();
            }
        }
    }

    public void reset(){
        Groups.clear();
        Time.clear();
        Events.fire(new ResetEvent());
        mindustry.Vars.game().world.tiles = new Tiles(0, 0);

        mindustry.Vars.game().state.data.unload();
        State prev = mindustry.Vars.game().state.getState();
        //recreate gamestate - sets state to menu
        GameState replacement = new GameState();
        mindustry.Vars.game().state = replacement;
        // Preserve the legacy static primary alias while the rest of the engine is migrated incrementally.
        if(RuntimeContexts.isPrimary()) Vars.state = replacement;
        //fire change event, since it was technically changed
        Events.fire(new StateChangeEvent(prev, State.menu));

        if(RuntimeContexts.isPrimary()) Core.settings.manualSave();
    }

    public void skipWave(){
        runWave();
    }

    public void runWave(){
        mindustry.Vars.game().spawner.spawnEnemies();
        mindustry.Vars.game().state.wave++;
        mindustry.Vars.game().state.wavetime = mindustry.Vars.game().state.rules.waveSpacing * (mindustry.Vars.game().state.isCampaign() ? mindustry.Vars.game().state.getPlanet().campaignRules.difficulty.waveTimeMultiplier : 1f);

        Events.fire(new WaveEvent());
    }

    private void checkGameState(){
        //campaign maps do not have a 'win' state!
        if(mindustry.Vars.game().state.isCampaign()){
            //gameover only when cores are dead
            if(mindustry.Vars.game().state.teams.playerCores().size == 0 && !mindustry.Vars.game().state.gameOver){
                mindustry.Vars.game().state.gameOver = true;
                Events.fire(new GameOverEvent(mindustry.Vars.game().state.rules.waveTeam));
            }

            //check if there are no enemy spawns
            if(mindustry.Vars.game().state.rules.waves && mindustry.Vars.game().spawner.countSpawns() + mindustry.Vars.game().state.teams.cores(mindustry.Vars.game().state.rules.waveTeam).size <= 0){
                //if yes, waves get disabled
                mindustry.Vars.game().state.rules.waves = false;
            }

            //if there's a "win" wave and no enemies are present, win automatically
            if(mindustry.Vars.game().state.rules.waves && (mindustry.Vars.game().state.enemies == 0 && mindustry.Vars.game().state.rules.winWave > 0 && mindustry.Vars.game().state.wave >= mindustry.Vars.game().state.rules.winWave && !mindustry.Vars.game().spawner.isSpawning()) ||
                (mindustry.Vars.game().state.rules.attackMode && !mindustry.Vars.game().state.rules.waveTeam.isAlive())){

                if(mindustry.Vars.game().state.rules.sector.preset != null && mindustry.Vars.game().state.rules.sector.preset.attackAfterWaves && !mindustry.Vars.game().state.rules.attackMode){
                    //activate attack mode to destroy cores after waves are done.
                    mindustry.Vars.game().state.rules.attackMode = true;
                    mindustry.Vars.game().state.rules.waves = false;
                    Call.setRules(mindustry.Vars.game().state.rules);
                }else{
                    Call.sectorCapture();
                }
            }
        }else{
            if(!mindustry.Vars.game().state.rules.attackMode && mindustry.Vars.game().state.teams.playerCores().size == 0 && !mindustry.Vars.game().state.gameOver){
                mindustry.Vars.game().state.gameOver = true;
                Events.fire(new GameOverEvent(mindustry.Vars.game().state.rules.waveTeam));
            }else if(mindustry.Vars.game().state.rules.attackMode){
                //count # of teams alive
                int countAlive = mindustry.Vars.game().state.teams.getActive().count(t -> t.isAlive() && t.team != Team.derelict);

                if((countAlive <= 1 || (!mindustry.Vars.game().state.rules.pvp && mindustry.Vars.game().state.rules.defaultTeam.core() == null)) && !mindustry.Vars.game().state.gameOver){
                    //find team that won
                    TeamData left = mindustry.Vars.game().state.teams.getActive().find(t -> t.isAlive() && t.team != Team.derelict);
                    Events.fire(new GameOverEvent(left == null ? Team.derelict : left.team));
                    mindustry.Vars.game().state.gameOver = true;
                }
            }else if(!mindustry.Vars.game().state.gameOver && mindustry.Vars.game().state.rules.waves && (mindustry.Vars.game().state.enemies == 0 && mindustry.Vars.game().state.rules.winWave > 0 && mindustry.Vars.game().state.wave >= mindustry.Vars.game().state.rules.winWave && !mindustry.Vars.game().spawner.isSpawning())){
                mindustry.Vars.game().state.gameOver = true;
                Events.fire(new GameOverEvent(mindustry.Vars.game().state.rules.defaultTeam));
            }
        }
    }

    protected void updateWeather(){
        mindustry.Vars.game().state.rules.weather.removeAll(w -> w.weather == null);

        for(WeatherEntry entry : mindustry.Vars.game().state.rules.weather){
            //update cooldown
            entry.cooldown -= Time.delta();

            //create new event when not active
            if((entry.cooldown < 0 || entry.always) && !entry.weather.isActive()){
                float duration = entry.always ? Float.POSITIVE_INFINITY : Mathf.random(entry.minDuration, entry.maxDuration);
                entry.cooldown = duration + Mathf.random(entry.minFrequency, entry.maxFrequency);
                Tmp.v1().setToRandomDirection();
                Call.createWeather(entry.weather, entry.intensity, duration, Tmp.v1().x, Tmp.v1().y);
            }
        }
    }

    @Remote(called = Loc.server)
    public static void sectorCapture(){
        //the sector has been conquered - waves get disabled
        mindustry.Vars.game().state.rules.waves = false;

        if(mindustry.Vars.game().state.rules.sector == null){
            //disable attack mode
            mindustry.Vars.game().state.rules.attackMode = false;
            return;
        }

        boolean initial = !mindustry.Vars.game().state.rules.sector.info().wasCaptured;

        mindustry.Vars.game().state.rules.sector.info().wasCaptured = true;

        //fire capture event
        Events.fire(new SectorCaptureEvent(mindustry.Vars.game().state.rules.sector, initial));

        //disable attack mode
        mindustry.Vars.game().state.rules.attackMode = false;

        //map is over, no more world processor objective stuff
        mindustry.Vars.game().state.rules.disableWorldProcessors = true;
        mindustry.Vars.game().state.markers.clear(); //TODO: should this optional?

        Call.clearObjectives();

        //save, just in case
        if(!runtimeHeadless() && !mindustry.Vars.game().net.client()){
            control.saves.saveSector(mindustry.Vars.game().state.rules.sector);
        }
    }

    @Remote(called = Loc.both)
    public static void updateGameOver(Team winner){
        mindustry.Vars.game().state.gameOver = true;
        if(!runtimeHeadless()){
            mindustry.Vars.game().state.won = player.team() == winner;
        }
    }

    @Remote(called = Loc.both)
    public static void gameOver(Team winner){
        mindustry.Vars.game().state.stats.wavesLasted = mindustry.Vars.game().state.wave;
        if(runtimeHeadless()){
            mindustry.Vars.game().state.won = mindustry.Vars.game().state.rules.defaultTeam == winner;
            return;
        }
        mindustry.Vars.game().state.won = player != null ? player.team() == winner : mindustry.Vars.game().state.rules.defaultTeam == winner;
        if(shouldReturnToSharedCampaignLobby()){
            if(netClient != null) netClient.setQuiet();
            if(Core.app != null) Core.app.post(Logic::returnToSharedCampaignLobby);
            else returnToSharedCampaignLobby();
            return;
        }
        if(ui != null) Time.run(60f * 3f, () -> ui.restart.show(winner));
        if(netClient != null) netClient.setQuiet();
    }

    /** Shared Campaign Action clients return to their strategic lobby instead of the ordinary restart flow. */
    static boolean shouldReturnToSharedCampaignLobby(){
        SharedCampaignNet shared = SharedCampaignNet.find(game());
        return shared != null && shared.activeClientAction();
    }

    private static void returnToSharedCampaignLobby(){
        if(ui != null && ui.sharedCampaignDialog != null){
            ui.sharedCampaignDialog.leaveActionAndShowLobby();
        }else{
            if(netClient != null && net != null && net.client()) netClient.disconnectQuietly();
            SharedCampaignNet shared = SharedCampaignNet.find(game());
            if(shared != null) shared.clearClientActionConnection();
            if(logic != null) logic.reset();
        }
    }

    //called when the remote server researches something
    @Remote
    public static void researched(Content content){
        if(!(content instanceof UnlockableContent u)) return;

        boolean was = u.unlockedNowHost();
        mindustry.Vars.game().state.rules.researched.add(u);

        if(!was){
            Events.fire(new UnlockEvent(u));
        }
    }

    @Override
    public void dispose(){
        // Process settings/admin configuration belong to the primary runtime only.
        if(RuntimeContexts.isPrimary()){
            if(mindustry.Vars.game().netServer != null) mindustry.Vars.game().netServer.admins.forceSave();
            Core.settings.manualSave();
        }
    }

    protected void updateEntities(){
        boolean editor = mindustry.Vars.game().state.isEditor();
        boolean profile = RuntimeContexts.isPrimary();

        if(profile) PerfCounter.entityUpdate.begin();

        if(profile) PerfCounter.entityMisc.begin();
        Groups.updatePooling();
        Groups.current().bullet.updatePhysics();
        Groups.current().unit.updatePhysics();
        Groups.current().player.update();
        Groups.current().effect.update();
        if(!editor) Groups.current().all.update();
        if(profile) PerfCounter.entityMisc.end();

        if(profile) PerfCounter.unitUpdate.begin();
        if(editor){
            Groups.current().unit.update(u -> u.isPlayer() || u.spawnedByCore);
        }else{
            Groups.current().unit.update();
        }
        if(profile) PerfCounter.unitUpdate.end();

        if(profile) PerfCounter.powerUpdate.begin();
        if(!editor) Groups.current().powerGraph.update();
        if(profile) PerfCounter.powerUpdate.end();

        if(profile) PerfCounter.buildingUpdate.begin();
        if(!editor) Groups.current().build.update();
        if(profile) PerfCounter.buildingUpdate.end();

        if(profile) PerfCounter.bulletUpdate.begin();
        if(!editor) Groups.current().bullet.update();
        if(!editor) Groups.current().bullet.collide();
        if(profile) PerfCounter.bulletUpdate.end();

        if(profile) PerfCounter.entityUpdate.end();
    }

    @Override
    public void update(){
        boolean profile = RuntimeContexts.isPrimary();
        if(profile){
            PerfCounter.frame.end();
            PerfCounter.frame.begin();
            PerfCounter.stateUpdate.begin();
        }

        Events.fire(Trigger.update);
        mindustry.Vars.game().universe.updateGlobal();

        if(RuntimeContexts.isPrimary() && Core.settings.modified() && !mindustry.Vars.game().state.isPlaying()){
            if(mindustry.Vars.game().netServer != null) mindustry.Vars.game().netServer.admins.forceSave();
            Core.settings.forceSave();
        }

        boolean runStateCheck = !mindustry.Vars.game().net.client() && !mindustry.Vars.game().world.isInvalidMap() && !mindustry.Vars.game().state.isEditor() && mindustry.Vars.game().state.rules.canGameOver;

        if(mindustry.Vars.game().state.isGame()){
            if(!mindustry.Vars.game().net.client()){
                mindustry.Vars.game().state.enemies = Groups.current().unit.count(u -> u.team() == mindustry.Vars.game().state.rules.waveTeam && u.isEnemy());
            }

            if(!mindustry.Vars.game().state.isPaused()){
                Events.fire(Trigger.beforeGameUpdate);

                float delta = Time.delta();
                mindustry.Vars.game().state.tick += Float.isNaN(delta) || Float.isInfinite(delta) ? 0f : delta;
                mindustry.Vars.game().state.updateId ++;
                mindustry.Vars.game().state.teams.updateTeamStats();
                if(runtimeVisualsEnabled()) MapPreviewLoader.checkPreviews();

                if(mindustry.Vars.game().state.rules.fog){
                    mindustry.Vars.game().fogControl.update();
                }

                if(mindustry.Vars.game().state.isCampaign()){
                    mindustry.Vars.game().state.rules.sector.info().update();
                }

                if(mindustry.Vars.game().state.isCampaign()){
                    mindustry.Vars.game().universe.update();
                }
                Time.update();

                mindustry.Vars.game().logicVars.update();

                //weather is serverside
                if(!mindustry.Vars.game().net.client() && !mindustry.Vars.game().state.isEditor()){
                    updateWeather();

                    for(TeamData data : mindustry.Vars.game().state.teams.getActive()){
                        var rules = data.team.rules();
                        if(rules.fillItems && data.cores.size > 0){
                            var core = data.cores.first();
                            content.items().each(i -> {
                                if(i.isOnPlanet(mindustry.Vars.game().state.getPlanet()) && !i.isHidden()){
                                    core.items.set(i, core.getMaximumAccepted(i));
                                }
                            });
                        }
                        //does not work on PvP so built-in attack maps can have it on by default without issues
                        if(rules.buildAi && !mindustry.Vars.game().state.rules.pvp){
                            if(data.buildAi == null) data.buildAi = new BaseBuilderAI(data);
                            data.buildAi.update();
                        }

                        if(rules.rtsAi){
                            if(data.rtsAi == null) data.rtsAi = new RtsAI(data);
                            data.rtsAi.update();
                        }

                        //spawn units for prebuild AI cores
                        if(rules.prebuildAi && !mindustry.Vars.game().state.isEditor()){
                            for(var core : data.cores){
                                var units = data.getUnits(((CoreBlock)core.block).unitType);
                                if(units == null || !units.contains(u -> u.flag == core.pos())){
                                    Unit unit = ((CoreBlock)core.block).unitType.spawn(core, data.team);
                                    unit.flag = core.pos();
                                    unit.add();
                                    Units.notifyUnitSpawn(unit);
                                    Fx.spawn.at(unit);
                                }
                            }
                        }
                    }
                }

                if(!mindustry.Vars.game().state.isEditor()){
                    mindustry.Vars.game().state.rules.objectives.update();
                }

                if(mindustry.Vars.game().state.rules.waves && mindustry.Vars.game().state.rules.waveTimer && !mindustry.Vars.game().state.gameOver){
                    if(!isWaitingWave()){
                        mindustry.Vars.game().state.wavetime = Math.max(mindustry.Vars.game().state.wavetime - Time.delta(), 0);
                    }
                }

                if(!mindustry.Vars.game().net.client() && mindustry.Vars.game().state.wavetime <= 0 && mindustry.Vars.game().state.rules.waves){
                    runWave();
                }

                //apply weather attributes
                mindustry.Vars.game().state.envAttrs.clear();
                mindustry.Vars.game().state.envAttrs.add(mindustry.Vars.game().state.rules.attributes);
                Groups.current().weather.each(w -> mindustry.Vars.game().state.envAttrs.add(w.weather.attrs, w.opacity));

                updateEntities();

                Events.fire(Trigger.afterGameUpdate);
            }

            if(runStateCheck){
                checkGameState();
            }
        }else if(mindustry.Vars.game().netServer.isWaitingForPlayers() && runStateCheck){
            checkGameState();
        }

        if(profile) PerfCounter.stateUpdate.end(PerfCounter.entityUpdate.latestValueNs());
    }

    /** @return whether the wave timer is paused due to enemies */
    public boolean isWaitingWave(){
        return (mindustry.Vars.game().state.rules.waitEnemies || (mindustry.Vars.game().state.wave >= mindustry.Vars.game().state.rules.winWave && mindustry.Vars.game().state.rules.winWave > 0)) && mindustry.Vars.game().state.enemies > 0;
    }
}
