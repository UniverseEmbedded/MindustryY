package mindustry.campaign.shared.runtime;

import arc.files.*;
import mindustry.campaign.shared.*;

import java.nio.file.*;
import java.util.*;

/** Creates one host-local authoritative Sector runtime without coupling the coordinator to a concrete backend. */
@FunctionalInterface
public interface SectorRuntimeFactory{
    SectorRuntime create(SharedCampaignState.ActionState action, Fi directory, String coordinatorHost, int coordinatorPort,
                         String controlSecret, String joinSecret, Fi modsSource, SharedCampaignState.PersistenceProfile persistenceProfile);

    /** Known production backend used for pre-start resource admission; custom test/plugin factories may return null. */
    default SectorRuntime.Backend expectedBackend(){ return null; }

    /**
     * Mirrors the shared mod source into an isolated Action's mods directory.
     *
     * <p>The mirror used to wipe and rewrite the whole directory on every Action start. Repeatedly deleting and
     * rewriting archives under the user profile is exactly the behavior antivirus heuristics flag as ransomware
     * (360 falsely blocked MindustryY.exe on {@code config/mods/*.zip}). A sidecar manifest of the source's
     * path/size/mtime lets an unchanged mirror perform zero writes; any real mod change still falls back to the
     * full rewrite that has always been the correctness path.
     *
     * <p>Only entries backing mods this coordinator process actually loaded and enabled are mirrored. A child
     * boots with fresh settings whose defaults enable every mirrored file; copying files the coordinator never
     * enabled (disabled archives, stale leftovers) lets the child's content registry diverge and makes every
     * {@code actionHello} fail the content fingerprint check.
     */
    static void synchronizeMods(Fi target, Fi source){
        Fi manifest = target.parent().child("mods-sync.manifest");
        String expected = fingerprintMods(source, enabledModEntryNames());
        target.mkdirs();
        if(expected.equals(readModManifest(manifest)) && mirrorMatches(target, source, enabledModEntryNames())) return;
        if(target.exists()) target.deleteDirectory();
        target.mkdirs();
        if(source != null && source.exists()){
            java.util.Set<String> enabled = enabledModEntryNames();
            for(Fi entry : source.list()){
                if(entry.name().startsWith(".")) continue;
                if(!enabled.contains(entry.name())) continue;
                entry.copyTo(target.child(entry.name()));
            }
        }
        manifest.writeString(expected, false);
    }

    /**
     * Top-level source entry names (zip/jar or directory) that back the mods this coordinator process has
     * loaded and enabled right now. Anything else in the source directory must never reach a fresh-settings
     * child, which would default-enable it and break fingerprint parity.
     */
    private static java.util.Set<String> enabledModEntryNames(){
        java.util.Set<String> names = new java.util.HashSet<>();
        mindustry.mod.Mods mods = mindustry.Vars.mods;
        if(mods != null){
            for(mindustry.mod.Mods.LoadedMod mod : mods.orderedMods()){
                if(mod == null) continue;
                Fi identity = mod.file != null && mod.file.exists() ? mod.file : mod.root;
                if(identity != null) names.add(identity.name());
            }
        }
        return names;
    }

    /** Source listing as sorted {@code path|size|mtime} lines of the coordinator-enabled subset only. */
    private static String fingerprintMods(Fi source, java.util.Set<String> enabled){
        TreeMap<String, long[]> entries = new TreeMap<>();
        if(source != null && source.exists()) collectModEntries(source, "", entries, enabled);
        StringBuilder out = new StringBuilder();
        entries.forEach((path, meta) -> out.append(path).append('|').append(meta[0]).append('|').append(meta[1]).append('\n'));
        return out.toString();
    }

    /**
     * {@code skipTopLevelDot} keeps the historical dot-entry exclusion; {@code enabled} additionally restricts
     * top-level entries to the coordinator's loaded mod set (nested files inherit their enabled root).
     */
    private static void collectModEntries(Fi dir, String prefix, TreeMap<String, long[]> out, java.util.Set<String> enabled){
        for(Fi entry : dir.list()){
            String name = entry.name();
            if(prefix.isEmpty()){
                if(name.startsWith(".")) continue;
                if(enabled != null && !enabled.contains(name)) continue;
            }
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if(entry.isDirectory()){
                collectModEntries(entry, path, out, enabled);
            }else{
                out.put(path, new long[]{entry.length(), entry.lastModified()});
            }
        }
    }

    /** True when the target tree still holds exactly the source's enabled file set and sizes; any stray file forces a rewrite. */
    private static boolean mirrorMatches(Fi target, Fi source, java.util.Set<String> enabled){
        TreeMap<String, long[]> expected = new TreeMap<>();
        if(source != null && source.exists()) collectModEntries(source, "", expected, enabled);
        TreeMap<String, long[]> actual = new TreeMap<>();
        if(target.exists()) collectModEntries(target, "", actual, null);
        if(!expected.keySet().equals(actual.keySet())) return false;
        for(String path : expected.keySet()){
            if(expected.get(path)[0] != actual.get(path)[0]) return false;
        }
        return true;
    }

    private static String readModManifest(Fi manifest){
        try{
            return manifest.exists() ? manifest.readString() : "";
        }catch(Throwable ignored){
            // An unreadable/corrupt manifest must degrade to a full rewrite, never to a stale skip.
            return "";
        }
    }

    /**
     * Read-only inputs used to choose where authoritative simulation should run. Keeping the daemon flag in this data
     * object makes backend selection deterministic and unit-testable rather than hiding a System property read inside
     * the decision itself.
     */
    record RuntimeEnvironment(boolean bundledJrePresent, String javaHome, String bundledJavaHome,
                              boolean graphicalMobileClient, boolean canSpawnChildProcess, boolean sharedHostDaemon){
        public RuntimeEnvironment(boolean bundledJrePresent, String javaHome, String bundledJavaHome){
            this(bundledJrePresent, javaHome, bundledJavaHome, false, true, false);
        }

        public boolean hostLocalAuthoritativeAllowed(){
            return !graphicalMobileClient || canSpawnChildProcess || sharedHostDaemon;
        }
    }

    static String hostLocalAuthoritativeBlockedMessage(){
        return "Authoritative Shared Campaign Actions cannot run in-process inside a mobile graphical client. " +
            "Host the Action from a desktop/server process, join a remote Action, or run a dedicated shared-host daemon.";
    }

    /** Pure backend decision; no process spawn or GameContext creation occurs here. */
    static SectorRuntime.Backend resolveBackend(RuntimeEnvironment env){
        Objects.requireNonNull(env, "runtime environment");
        if(env.graphicalMobileClient()){
            if(env.sharedHostDaemon()) return SectorRuntime.Backend.inProcess;
            if(env.canSpawnChildProcess()) return SectorRuntime.Backend.jvmProcess;
            throw new IllegalStateException(hostLocalAuthoritativeBlockedMessage());
        }
        if(env.bundledJrePresent()) return SectorRuntime.Backend.jvmProcess;
        String javaHome = env.javaHome() == null ? "" : env.javaHome().trim();
        String bundledHome = env.bundledJavaHome() == null ? "" : env.bundledJavaHome().trim();
        if(!javaHome.isEmpty() && !bundledHome.isEmpty() && pathContains(bundledHome, javaHome)) return SectorRuntime.Backend.jvmProcess;
        return SectorRuntime.Backend.inProcess;
    }

    private static boolean pathContains(String container, String candidate){
        try{
            String a = normalizeComparablePath(container);
            String b = normalizeComparablePath(candidate);
            return !a.isEmpty() && b.startsWith(a) && (b.length() == a.length() || b.charAt(a.length()) == '/');
        }catch(Throwable ignore){
            return false;
        }
    }

    private static String normalizeComparablePath(String value){
        String portable = value.trim().replace('\\', '/');
        String normalized = Path.of(portable).toAbsolutePath().normalize().toString().replace('\\', '/');
        while(normalized.length() > 1 && normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.toLowerCase(Locale.ROOT);
    }

    /** Production in-process backend hosted by a context-owned embedded Mindustry runtime. */
    static SectorRuntimeFactory inProcess(){
        return inProcess(InProcessSectorScheduler.shared());
    }

    static SectorRuntimeFactory inProcess(InProcessSectorScheduler scheduler){
        Objects.requireNonNull(scheduler, "scheduler");
        return new SectorRuntimeFactory(){
            @Override public SectorRuntime create(SharedCampaignState.ActionState action, Fi directory, String coordinatorHost, int coordinatorPort,
                                                  String controlSecret, String joinSecret, Fi modsSource, SharedCampaignState.PersistenceProfile persistenceProfile){
                return new InProcessSectorRuntime(action, directory, coordinatorHost, coordinatorPort, controlSecret, joinSecret, modsSource, scheduler);
            }
            @Override public SectorRuntime.Backend expectedBackend(){ return SectorRuntime.Backend.inProcess; }
        };
    }

    /** Production child-JVM backend. */
    static SectorRuntimeFactory jvmProcess(){
        return new SectorRuntimeFactory(){
            @Override public SectorRuntime create(SharedCampaignState.ActionState action, Fi directory, String coordinatorHost, int coordinatorPort,
                                                  String controlSecret, String joinSecret, Fi modsSource, SharedCampaignState.PersistenceProfile persistenceProfile){
                return new ActionProcess(action, directory, coordinatorHost, coordinatorPort, controlSecret, joinSecret, modsSource, persistenceProfile);
            }
            @Override public SectorRuntime.Backend expectedBackend(){ return SectorRuntime.Backend.jvmProcess; }
        };
    }
}
