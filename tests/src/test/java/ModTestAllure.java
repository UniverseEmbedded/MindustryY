import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

//grabs a version-locked exotic-mod commit and makes sure its content is parsed correctly
//this mod was chosen because:
//- it is written solely in (h)json
//- it is probably the mod with the most json, and as such covers a lot of classes
//- it is popular enough in the mod browser
//- I am somewhat familiar with its files & the type of content it adds
public class ModTestAllure extends GenericModTest{
    /** Restore a clean mod folder so later suites' Action children never inherit this downloaded mod.
     *  The loaded zip stays locked by this JVM's classloader on Windows, so it cannot be deleted here:
     *  quarantine it out of the mods directory by rename (allowed while open) and best-effort delete at exit. */
    @org.junit.jupiter.api.AfterAll
    static void cleanupMods(){
        try{
            java.nio.file.Path mods = ApplicationTests.testDataFolder.child("mods").file().getAbsoluteFile().toPath();
            boolean clean = true;
            if(java.nio.file.Files.exists(mods)){
                try(var walk = java.nio.file.Files.walk(mods)){
                    java.util.List<java.nio.file.Path> entries = new java.util.ArrayList<>();
                    walk.forEach(entries::add);
                    java.util.Collections.sort(entries, java.util.Comparator.reverseOrder());
                    for(java.nio.file.Path p : entries){
                        if(java.nio.file.Files.isDirectory(p)){
                            try{ java.nio.file.Files.deleteIfExists(p); }catch(java.io.IOException e){ clean = false; }
                        }else{
                            try{
                                java.nio.file.Path quarantine = java.nio.file.Files.createTempFile("allure-test-mod-", ".zip");
                                try{
                                    java.nio.file.Files.move(p, quarantine, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }catch(java.io.IOException moveFailed){
                                    java.nio.file.Files.move(p, quarantine, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                                }
                                quarantine.toFile().deleteOnExit();
                            }catch(Throwable moveFailed){
                                try{ java.nio.file.Files.deleteIfExists(p); }catch(java.io.IOException deleteFailed){ clean = false; }
                            }
                        }
                    }
                }
            }
            boolean stillThere = false; if(java.nio.file.Files.exists(mods)){ try(var s = java.nio.file.Files.list(mods)){ stillThere = s.findAny().isPresent(); } }
            System.out.println("[ModTestAllure cleanup] path=" + mods + " clean=" + clean + " stillThere=" + stillThere);
        }catch(Throwable t){
            System.out.println("[ModTestAllure cleanup] failed: " + t);
        }
    }


    @Test
    public void begin(){
        //As of September 5 2026, the Allure repository has been deleted or made private. I do not know why.
        // Archive version of Allure (archive last pull date: 30 July 2026 - latest commit before archive)
        grabMod("https://github.com/JasonP01/AllureMod/archive/b6e3d4b831b6c8f81d8d366560029aec8759de89.zip");
        checkExistence("allure");

        UnitType type = Vars.content.unit("allure-0b11-exodus");
        assertNotNull(type, "A mod unit must be loaded.");
        assertTrue(type.weapons.size > 0, "A mod unit must have a weapon.");

        Vars.world.loadMap(maps.loadInternalMap("serpulo/groundZero"));

        Unit unit = type.spawn(0, 0);

        //check for crash
        unit.update();

        assertTrue(unit.health > 0, "Unit must be spawned and alive.");
        assertTrue(Groups.current().unit.size() > 0, "Unit must be spawned and alive.");

        //just an extra sanity check
        Log.info("Modded units: @", Vars.content.units().select(u -> u.minfo.mod != null));
    }

}
