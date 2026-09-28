package mindustry.campaign.shared;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.type.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/** Transaction-safe import of local/vanilla single-player campaign progress into an independent shared campaign. */
public final class SharedCampaignImporter{
    private static final long maxArchiveEntryBytes = 512L * 1024L * 1024L;
    private static final long maxArchiveImportBytes = 2L * 1024L * 1024L * 1024L;

    private SharedCampaignImporter(){}

    /** Non-durable UI progress for heavy import loops; never written into campaign snapshots. */
    @FunctionalInterface
    public interface ProgressReporter{
        void report(String planetName, int done, int total, float progress);
    }

    /** Imports all planets represented by the current local profile. */
    public static void importAllSupportedPlanets(Fi campaignDirectory, SharedCampaignState campaign, ProgressReporter progress){
        requireTarget(campaignDirectory, campaign);
        Seq<Planet> planets = new Seq<>();
        for(Planet planet : content.planets()){
            if(supportedPlanet(campaign, planet)) planets.add(planet);
        }
        int imported = 0, done = 0;
        for(Planet planet : planets){
            imported += importCurrentPlanet(campaignDirectory, campaign, planet, progress, done, Math.max(1, planets.size));
            done++;
        }
        importUnlocks(campaign);
        campaign.originDescription = imported == 0 ? "Local campaign profile (no progressed sectors)" : "Local campaign profile · all supported planets";
    }

    public static void importCurrentProfile(Fi campaignDirectory, SharedCampaignState campaign, String planetName, ProgressReporter progress){
        requireTarget(campaignDirectory, campaign);
        Planet planet = content.planet(planetName);
        if(planet == null) throw new IllegalArgumentException("Unknown import planet " + planetName);
        importCurrentPlanet(campaignDirectory, campaign, planet, progress, 0, 1);
        importUnlocks(campaign);
        campaign.originDescription = "Local campaign profile: " + planet.localizedName;
    }

    /**
     * Imports a vanilla/Mindustry data-export ZIP without replacing the running installation's data directory.
     * Only settings.bin and campaign saves are staged; current settings, saves, mods and profile files are untouched.
     */
    public static void importDataExport(Fi campaignDirectory, SharedCampaignState campaign, Fi archive,
                                        String primaryPlanetName, boolean allPlanets){
        requireTarget(campaignDirectory, campaign);
        if(archive == null || (archive.exists() && archive.isDirectory())) throw new IllegalArgumentException("Single-player data export source must be a ZIP archive");
        Planet primary = content.planet(primaryPlanetName);
        if(primary == null) throw new IllegalArgumentException("Unknown import planet " + primaryPlanetName);

        Fi staging = campaignDirectory.child(".singleplayer-import-" + UUID.randomUUID());
        staging.mkdirs();
        try{
            extractCampaignExport(archive, staging);
            Fi settingsFile = staging.child("settings.bin");
            HashMap<String, SaveCandidate> saves = discoverSectorSaves(staging.child("saves"), primaryPlanetName, allPlanets);

            Settings importedSettings = new Settings();
            importedSettings.setJson(mindustry.io.JsonIO.current());
            importedSettings.setAutosave(false);
            if(settingsFile.exists()){
                try{
                    importedSettings.loadValues(settingsFile);
                }catch(IOException error){
                    // Arc deliberately rejects a settings file whose value count is zero because that shape can
                    // indicate corruption. For campaign import, however, authoritative sector .msav files are
                    // independently self-describing and valid. Accept an exactly empty settings payload and import
                    // the saves; still fail closed for truncated/unknown/corrupt settings encodings.
                    if(!zeroValueSettings(settingsFile)) throw error;
                }
            }else if(saves.isEmpty()){
                throw new IllegalArgumentException("Not a Mindustry data export: settings.bin and campaign saves are missing");
            }

            int imported = 0;
            for(Planet planet : content.planets()){
                if(!supportedPlanet(campaign, planet)) continue;
                if(!allPlanets && !Objects.equals(planet.name, primaryPlanetName)) continue;
                imported += importExternalPlanet(campaignDirectory, campaign, planet, importedSettings, saves);
            }
            importUnlocks(campaign, importedSettings);
            campaign.originDescription = "Mindustry data export: " + archive.name() + (allPlanets ? " · all supported planets" : " · " + primary.localizedName);
            if(imported == 0) throw new IllegalArgumentException("The selected data export contains no campaign progress for the requested planet(s)");
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }finally{
            if(staging.exists()) staging.deleteDirectory();
        }
    }


    private static boolean zeroValueSettings(Fi file){
        try{
            byte[] header = new byte[2];
            file.readBytes(header, 0, 2);
            boolean compressed = header[0] == (byte)0x78 && (header[1] == (byte)0x01 || header[1] == (byte)0x5e || header[1] == (byte)0x9c || header[1] == (byte)0xda);
            try(DataInputStream stream = new DataInputStream(compressed ? new InflaterInputStream(file.read(8192)) : file.read(8192))){
                return stream.readInt() == 0 && stream.read() == -1;
            }
        }catch(Throwable ignored){
            return false;
        }
    }

    private static boolean supportedPlanet(SharedCampaignState campaign, Planet planet){
        if(planet == null || planet.sectors == null || planet.sectors.isEmpty()) return false;
        PlanetPolicyState policy = campaign.planetPolicies.get(planet.name);
        return policy == null || !"unsupported".equals(policy.mode);
    }

    private static int importCurrentPlanet(Fi campaignDirectory, SharedCampaignState campaign, Planet planet,
                                           ProgressReporter progress, int planetDone, int planetTotal){
        Fi importedRoot = campaignDirectory.child("imported-sectors");
        importedRoot.mkdirs();
        Seq<Fi> created = new Seq<>();
        int imported = 0;
        int candidates = 0;
        for(Sector sector : planet.sectors){
            if(sector.hasBase() || sector.isCaptured() || sector.save != null) candidates++;
        }
        int seen = 0;
        try{
            for(Sector sector : planet.sectors){
                if(!sector.hasBase() && !sector.isCaptured() && sector.save == null) continue;
                seen++;
                reportSectorProgress(progress, planet, seen, candidates, planetDone, planetTotal);
                SectorInfo info = sector.info();
                SaveMeta meta = sector.save == null ? null : sector.save.meta;
                SectorState target = sectorState(sector, info, meta, sector.save != null, true);
                if(sector.save != null && sector.save.file != null && sector.save.file.exists()){
                    if(!SaveIO.isSaveValid(sector.save.file)) throw new IllegalArgumentException("Invalid local campaign save: " + sector.save.file.name());
                    Fi destination = importedRoot.child(safe(planet.name) + "-" + safe(target.sectorName) + ".msav");
                    copyAtomic(sector.save.file, destination);
                    created.add(destination);
                    target.saveRelativePath = relative(campaignDirectory, destination);
                }
                campaign.sectors.put(SharedCampaignProgress.sectorKey(planet.name, target.sectorName), target);
                imported++;
            }
            return imported;
        }catch(Throwable error){
            for(Fi file : created) if(file.exists()) file.delete();
            throw error instanceof RuntimeException runtime ? runtime : new RuntimeException(error);
        }
    }

    private static void reportSectorProgress(ProgressReporter progress, Planet planet, int seen, int candidates, int planetDone, int planetTotal){
        if(progress == null || candidates <= 0) return;
        float planetShare = planetTotal <= 0 ? 1f : 1f / planetTotal;
        float base = planetTotal <= 0 ? 0f : planetDone * planetShare;
        float sectorShare = planetShare * seen / candidates;
        progress.report(planet.localizedName, seen, candidates, base + sectorShare);
    }

    private static int importExternalPlanet(Fi campaignDirectory, SharedCampaignState campaign, Planet planet,
                                            Settings settings, Map<String, SaveCandidate> saves){
        Fi importedRoot = campaignDirectory.child("imported-sectors");
        importedRoot.mkdirs();
        int imported = 0;
        for(Sector sector : planet.sectors){
            String infoKey = planet.name + "-s-" + sector.id + "-info";
            boolean hasInfo = settings.has(infoKey);
            SaveCandidate save = saves.get(SharedCampaignProgress.sectorKey(planet.name, SharedCampaignProgress.sectorId(sector)));
            if(!hasInfo && save == null) continue;

            SectorInfo info = hasInfo ? settings.getJson(infoKey, SectorInfo.class, SectorInfo::new) : infoFromSave(save == null ? null : save.meta());
            SectorState target = sectorState(sector, info, save == null ? null : save.meta(), save != null, false);
            if(save != null){
                Fi destination = importedRoot.child(safe(planet.name) + "-" + safe(target.sectorName) + ".msav");
                copyAtomic(save.file(), destination);
                target.saveRelativePath = relative(campaignDirectory, destination);
            }
            campaign.sectors.put(SharedCampaignProgress.sectorKey(planet.name, target.sectorName), target);
            imported++;
        }
        return imported;
    }

    private static SectorInfo infoFromSave(SaveMeta meta){
        SectorInfo info = new SectorInfo();
        if(meta == null) return info;
        info.wave = Math.max(1, meta.wave);
        if(meta.rules != null){
            info.waves = meta.rules.waves;
            info.attack = meta.rules.attackMode;
            info.winWave = meta.rules.winWave;
            info.waveSpacing = meta.rules.waveSpacing;
            info.hasCore = true;
        }
        return info;
    }

    private static SectorState sectorState(Sector sector, SectorInfo info, SaveMeta meta, boolean hasSave, boolean localProfile){
        if(info == null) info = new SectorInfo();
        String sectorId = SharedCampaignProgress.sectorId(sector);
        SectorState target = new SectorState();
        target.planetName = sector.planet.name;
        target.sectorName = sectorId;
        target.hasBase = hasSave && info.hasCore;
        target.captured = hasSave && !info.waves && !info.attack;
        target.attacked = hasSave && info.hasCore && (info.waves || info.attack);
        target.hasSpawns = info.hasSpawns;
        target.displayName = info.name == null ? "" : info.name;
        target.icon = info.icon == null ? "" : info.icon;
        target.contentIcon = contentIdentity(info.contentIcon);
        for(UnlockableContent resource : info.resources) if(resource != null) target.resources.add(contentIdentity(resource));
        target.hasEnemyBase = ((sector.generateEnemyBase && sector.preset == null) || (sector.preset != null && sector.preset.captureWave == 0)) && (!hasSave || info.attack || !target.hasBase);
        target.threat = sector.threat;
        target.discovered = target.hasBase || target.captured || info.shown || hasSave;
        target.expeditionAvailable = target.hasBase || target.captured;
        target.minutesCaptured = info.minutesCaptured;
        target.destinationSector = SharedCampaignProgress.sectorKey(info.destination);
        target.legacyLaunchPads = sector.planet.campaignRules.legacyLaunchPads;
        target.lastSavedAt = meta == null ? 0L : meta.timestamp;
        target.summary = summary(sector, info, target, localProfile);
        copyItems(info.items, target.items);
        copyRates(info.production, target.productionPerSecond);
        copyRates(info.export, target.exportPerSecond);
        copyRates(info.imports, target.importPerSecond);
        // progressed local profile. Offline settlement resumes after the first shared launch+suspend rebuilds these
        return target;
    }

    private static SectorSummary summary(Sector sector, SectorInfo info, SectorState state, boolean localProfile){
        SectorSummary out = new SectorSummary();
        out.wave = info.wave;
        out.winWave = info.winWave;
        out.waves = info.waves;
        out.attackMode = info.attack;
        out.coreCount = info.hasCore ? 1 : 0;
        out.storageCapacity = info.storageCapacity;
        out.coreType = info.bestCoreType == null ? "" : info.bestCoreType.name;
        out.planetName = sector.planet.name;
        out.destinationSector = SharedCampaignProgress.sectorKey(info.destination);
        out.legacyLaunchPads = sector.planet.campaignRules.legacyLaunchPads;
        out.attacked = state.attacked;
        out.hasSpawns = info.hasSpawns;
        out.displayName = info.name == null ? "" : info.name;
        out.icon = info.icon == null ? "" : info.icon;
        out.contentIcon = contentIdentity(info.contentIcon);
        for(UnlockableContent resource : info.resources) if(resource != null) out.resources.add(contentIdentity(resource));
        out.hasEnemyBase = state.hasEnemyBase;
        out.threat = state.threat;
        out.minutesCaptured = info.minutesCaptured;
        out.phase = out.attacked ? "defense" : out.attackMode ? "attack" : out.waves ? "building" : "captured";
        copyItems(info.items, out.items);
        copyRates(info.production, out.productionPerSecond);
        copyRates(info.export, out.exportPerSecond);
        copyRates(info.imports, out.importPerSecond);
        return out;
    }

    private static HashMap<String, SaveCandidate> discoverSectorSaves(Fi savesRoot, String primaryPlanetName, boolean allPlanets){
        HashMap<String, SaveCandidate> out = new HashMap<>();
        if(savesRoot == null || !savesRoot.exists()) return out;
        for(Fi file : savesRoot.list()){
            if(file.isDirectory() || !"msav".equalsIgnoreCase(file.extension())) continue;
            if(!SaveIO.isSaveValid(file)) throw new IllegalArgumentException("Invalid campaign save in data export: " + file.name());
            SaveMeta meta;
            try{ meta = SaveIO.getMeta(file); }
            catch(Throwable error){ throw new IllegalArgumentException("Unable to read campaign save metadata: " + file.name(), error); }
            Sector sector = meta == null || meta.rules == null ? null : meta.rules.sector;
            if(sector == null) continue; // ordinary non-campaign save
            if(!allPlanets && !Objects.equals(sector.planet.name, primaryPlanetName)) continue;
            String key = SharedCampaignProgress.sectorKey(sector.planet.name, SharedCampaignProgress.sectorId(sector));
            SaveCandidate previous = out.get(key);
            if(previous == null || meta.timestamp >= previous.meta().timestamp) out.put(key, new SaveCandidate(file, meta));
        }
        return out;
    }

    private static void extractCampaignExport(Fi archive, Fi staging) throws IOException{
        long total = 0L;
        try(ZipInputStream in = new ZipInputStream(new BufferedInputStream(archive.read(64 * 1024)))){
            for(ZipEntry entry; (entry = in.getNextEntry()) != null; ){
                if(entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                while(name.startsWith("/")) name = name.substring(1);
                Path normalized = Path.of(name).normalize();
                if(normalized.isAbsolute() || normalized.startsWith("..")) throw new IllegalArgumentException("Unsafe path in data export: " + entry.getName());
                name = normalized.toString().replace('\\', '/');
                boolean wanted = name.equals("settings.bin") || name.startsWith("saves/") && name.toLowerCase(Locale.ROOT).endsWith(".msav");
                if(!wanted) continue;

                Fi destination = staging.child(name);
                destination.parent().mkdirs();
                long entryBytes = 0L;
                try(OutputStream out = new BufferedOutputStream(destination.write(false, 64 * 1024))){
                    byte[] buffer = new byte[64 * 1024];
                    for(int read; (read = in.read(buffer)) != -1; ){
                        if(read == 0) continue;
                        entryBytes += read;
                        total += read;
                        if(entryBytes > maxArchiveEntryBytes || total > maxArchiveImportBytes) throw new IllegalArgumentException("Single-player data export is too large");
                        out.write(buffer, 0, read);
                    }
                }
                in.closeEntry();
            }
        }
    }

    private static String contentIdentity(UnlockableContent value){
        return value == null ? "" : value.getContentType().name() + ":" + value.name;
    }

    private static void importUnlocks(SharedCampaignState campaign){
        importUnlocks(campaign, null);
    }

    private static void importUnlocks(SharedCampaignState campaign, Settings importedSettings){
        Seq<UnlockableContent> all = new Seq<>();
        all.addAll(content.blocks());
        all.addAll(content.items());
        all.addAll(content.liquids());
        all.addAll(content.units());
        all.addAll(content.sectors());
        for(UnlockableContent value : all){
            boolean unlocked = value.alwaysUnlocked;
            // Do not call value.unlocked(): that routes through SharedCampaignService.sharedModeActive()
            // (service monitor) while createLocal still holds the store write lock and deadlocks the UI lane.
            if(importedSettings == null) unlocked |= Core.settings != null && Core.settings.getBool(value.name + "-unlocked", false);
            else unlocked |= importedSettings.getBool(value.name + "-unlocked", false);
            if(unlocked) campaign.researched.add(value.name);
        }
    }

    private static void copyItems(ItemSeq source, ObjectMap<String, Integer> destination){
        destination.clear();
        if(source == null) return;
        for(Item item : content.items()){
            int amount = source.get(item);
            if(amount != 0) destination.put(item.name, amount);
        }
    }

    private static void copyRates(ObjectMap<Item, SectorInfo.ExportStat> source, ObjectMap<String, Float> destination){
        destination.clear();
        if(source == null) return;
        source.each((item, stat) -> { if(item != null && stat != null && Math.abs(stat.mean) > 0.0001f) destination.put(item.name, stat.mean); });
    }

    private static void copyAtomic(Fi source, Fi destination){
        Fi temporary = destination.sibling(destination.name() + ".tmp-" + Time.millis());
        destination.parent().mkdirs();
        source.copyTo(temporary);
        try{
            try{ java.nio.file.Files.move(temporary.file().toPath(), destination.file().toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ignored){ java.nio.file.Files.move(temporary.file().toPath(), destination.file().toPath(), StandardCopyOption.REPLACE_EXISTING); }
        }catch(IOException error){
            temporary.delete();
            throw new UncheckedIOException(error);
        }
    }

    private static String relative(Fi root, Fi file){
        return root.file().toPath().toAbsolutePath().normalize().relativize(file.file().toPath().toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static void requireTarget(Fi campaignDirectory, SharedCampaignState campaign){
        if(campaignDirectory == null || campaign == null) throw new IllegalArgumentException("Campaign directory and state are required");
    }

    private static String safe(String value){ return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private record SaveCandidate(Fi file, SaveMeta meta){}
}
