package mindustry.campaign.shared;

import mindustry.*;
import mindustry.type.*;

/** Stable vanilla planet/sector identity helpers shared by coordinator and Action runtimes. */
public final class SharedCampaignSectors{
    private SharedCampaignSectors(){}

    public static String sectorId(Sector sector){
        return sector == null ? "" : sector.preset == null ? Integer.toString(sector.id) : sector.preset.name;
    }

    public static String sectorKey(String planetName, String sectorId){
        return SharedCampaignState.sectorKey(planetName, sectorId);
    }

    public static String sectorKey(Sector sector){
        return sector == null ? "" : sectorKey(sector.planet.name, sectorId(sector));
    }

    /**
     * Parses the clean schema-26 {@code planet:sector} identity while accepting donor-era {@code planet/sector}
     * strings at read boundaries. New state is always written through {@link #sectorKey(String, String)} and therefore
     * canonicalizes to the colon form.
     */
    public static String planetFromKey(String sectorKey){
        int separator = separatorIndex(sectorKey);
        return separator <= 0 ? "" : sectorKey.substring(0, separator);
    }

    public static String sectorIdFromKey(String sectorKey){
        int separator = separatorIndex(sectorKey);
        return separator < 0 || separator + 1 >= sectorKey.length() ? "" : sectorKey.substring(separator + 1);
    }

    public static String canonicalKey(String sectorKey){
        String planet = planetFromKey(sectorKey), sector = sectorIdFromKey(sectorKey);
        return planet.isBlank() || sector.isBlank() ? "" : sectorKey(planet, sector);
    }

    private static int separatorIndex(String sectorKey){
        if(sectorKey == null) return -1;
        int colon = sectorKey.indexOf(':');
        int slash = sectorKey.indexOf('/');
        if(colon < 0) return slash;
        if(slash < 0) return colon;
        return Math.min(colon, slash);
    }

    public static Sector findSectorKey(String sectorKey){
        return findSector(planetFromKey(sectorKey), sectorIdFromKey(sectorKey));
    }

    public static Sector findSector(String planetName, String sectorId){
        if(planetName == null || planetName.isBlank() || sectorId == null || sectorId.isBlank()) return null;
        Planet planet = Vars.content.planet(planetName);
        if(planet == null) return null;
        return planet.sectors.find(sector -> sectorId(sector).equals(sectorId));
    }
}
