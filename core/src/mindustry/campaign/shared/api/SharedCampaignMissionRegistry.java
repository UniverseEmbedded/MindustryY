package mindustry.campaign.shared.api;

import arc.struct.*;

import java.nio.charset.*;
import java.security.*;
import java.util.*;

import static java.util.Comparator.comparing;

/** Deterministic registry for versioned campaign mission definitions supplied by the base game and Mods. */
public class SharedCampaignMissionRegistry{
    private final ObjectMap<String, MissionDefinition> definitions = new ObjectMap<>();
    private final ObjectMap<String, MissionDefinition> sectorDefinitions = new ObjectMap<>();

    public MissionRegistry forExtension(String extensionId){
        return (stableMissionId, definitionVersion, definition) -> register(extensionId, stableMissionId, definitionVersion, definition);
    }

    public synchronized void register(String ownerId, String stableMissionId, int definitionVersion, MissionSpec definition){
        if(ownerId == null || ownerId.isBlank() || !ownerId.contains(":")) throw new IllegalArgumentException("Mission owner ID must be namespaced");
        if(stableMissionId == null || stableMissionId.isBlank() || !stableMissionId.contains(":")) throw new IllegalArgumentException("Mission ID must be stable and namespaced");
        if(definitionVersion < 1) throw new IllegalArgumentException("Mission definition version must be positive");
        if(definition == null) throw new IllegalArgumentException("Mission definition is required");
        validateBinding(stableMissionId, definition);
        String fingerprint = fingerprint(ownerId, stableMissionId, definitionVersion, definition);
        MissionDefinition registered = new MissionDefinition(ownerId, stableMissionId, definitionVersion, fingerprint, definition);
        MissionDefinition previous = definitions.put(stableMissionId, registered);
        if(previous != null){
            definitions.put(stableMissionId, previous);
            throw new IllegalArgumentException("Duplicate shared campaign mission definition: " + stableMissionId);
        }
        String sectorKey = sectorKey(definition.planetName(), definition.sectorName());
        if(!sectorKey.isBlank()){
            MissionDefinition sectorPrevious = sectorDefinitions.put(sectorKey, registered);
            if(sectorPrevious != null){
                sectorDefinitions.put(sectorKey, sectorPrevious);
                definitions.remove(stableMissionId);
                throw new IllegalArgumentException("Duplicate shared campaign mission binding for " + sectorKey + ": " + sectorPrevious.id() + " and " + stableMissionId);
            }
        }
    }

    public synchronized MissionDefinition require(String id){
        MissionDefinition definition = definitions.get(id);
        if(definition == null) throw new IllegalArgumentException("No shared campaign mission definition is registered for " + id);
        return definition;
    }

    public synchronized MissionDefinition get(String id){ return definitions.get(id); }
    public synchronized MissionDefinition forSector(String planetName, String sectorName){ return sectorDefinitions.get(sectorKey(planetName, sectorName)); }
    public synchronized Seq<MissionDefinition> all(){
        Seq<MissionDefinition> out = definitions.values().toSeq(); out.sort(comparing(MissionDefinition::id)); return out;
    }

    public static String fingerprint(String ownerId, String id, int version, MissionSpec definition){
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String compatibility = definition.compatibilityId();
            if(compatibility == null || compatibility.isBlank()) throw new IllegalArgumentException("Mission compatibility identity is required: " + id);
            String source = ownerId + "\n" + id + "\n" + version + "\n" + compatibility + "\n"
                + clean(definition.planetName()) + "\n" + clean(definition.sectorName()) + "\n" + definition.freezeWhenEmpty();
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for(byte value : hash) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        }catch(NoSuchAlgorithmException e){ throw new AssertionError(e); }
    }

    private static void validateBinding(String id, MissionSpec definition){
        String planet = clean(definition.planetName());
        String sector = clean(definition.sectorName());
        if(planet.isBlank() != sector.isBlank()) throw new IllegalArgumentException("Mission " + id + " must bind both planet and sector, or neither");
        if(planet.contains("/") || sector.contains("/")) throw new IllegalArgumentException("Mission planet and sector bindings may not contain '/': " + id);
    }

    private static String sectorKey(String planetName, String sectorName){
        String planet = clean(planetName), sector = clean(sectorName);
        return planet.isBlank() || sector.isBlank() ? "" : planet + "/" + sector;
    }

    private static String clean(String value){ return value == null ? "" : value.trim(); }

    public interface MissionSpec{
        String compatibilityId();
        default String planetName(){ return ""; }
        default String sectorName(){ return ""; }
        default String displayName(){ return sectorName().isBlank() ? compatibilityId() : sectorName(); }
        default boolean freezeWhenEmpty(){ return true; }
    }

    @FunctionalInterface
    public interface MissionRegistry{
        void register(String stableMissionId, int definitionVersion, MissionSpec definition);
    }

    public record MissionDefinition(String ownerId, String id, int version, String fingerprint, MissionSpec definition){}
}
