package mindustry.runtime;

import arc.files.*;

import java.util.*;

/**
 * Explicit filesystem ownership for one live game runtime.
 *
 * Embedded game runtimes must never inherit the desktop process save/config roots merely because they run
 * in the same JVM. All action-local files are resolved from this object.
 */
public final class RuntimeStorage{
    private final Fi root;
    private final Fi config;
    private final Fi saves;
    private final Fi mods;

    public RuntimeStorage(Fi root){
        this.root = Objects.requireNonNull(root, "runtime storage root");
        this.config = root.child("config");
        this.saves = config.child("saves");
        this.mods = config.child("mods");
    }

    public static RuntimeStorage processDefault(){
        Fi data = mindustry.Vars.dataDirectory;
        if(data == null) throw new IllegalStateException("Process data directory is not initialized");
        return new RuntimeStorage(data.parent());
    }

    public Fi root(){ return root; }
    public Fi config(){ return config; }
    public Fi saves(){ return saves; }
    public Fi mods(){ return mods; }

    /** Creates only directories owned by this runtime. */
    public void prepare(){
        root.mkdirs();
        config.mkdirs();
        saves.mkdirs();
        mods.mkdirs();
    }

    public Fi actionSave(String slot){
        if(slot == null || slot.isBlank()) throw new IllegalArgumentException("Action save slot is required");
        if(slot.contains("/") || slot.contains("\\") || slot.contains("..")) throw new IllegalArgumentException("Unsafe action save slot: " + slot);
        return saves.child(slot + "." + mindustry.Vars.saveExtension);
    }

    /** Resolves descriptor paths. Absolute paths remain absolute; relative paths are rooted at this runtime. */
    public Fi resolve(String path){
        if(path == null || path.isBlank()) throw new IllegalArgumentException("Runtime path is required");
        Fi file = new Fi(path);
        return file.file().isAbsolute() ? file : root.child(path);
    }

    public boolean contains(Fi file){
        if(file == null) return false;
        try{
            var base = root.file().toPath().toAbsolutePath().normalize();
            var value = file.file().toPath().toAbsolutePath().normalize();
            return value.startsWith(base);
        }catch(Throwable ignored){
            return false;
        }
    }
}
