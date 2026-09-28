package mindustry.campaign.shared;

import arc.files.*;
import arc.struct.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.mod.Mods.LoadedMod;

import java.io.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

import static mindustry.Vars.*;

/** Deterministic digest of the game build, loaded content registry and enabled Mod bytes used by a shared campaign. */
public final class SharedContentFingerprint{
    private SharedContentFingerprint(){}

    /** Both creation fingerprints from one pass over content registry + enabled Mod bytes. */
    public record Fingerprints(String runtime, String compatibility){}

    /**
     * Strict runtime identity. Every live Action host must match this exact installation, including the executable
     * build identity, so mixed-version authorities cannot participate in one running Shared Campaign.
     */
    public static String calculate(){
        MessageDigest digest = sha256();
        put(digest, "mindustry-shared-runtime:v4");
        put(digest, Version.number + ":" + Version.build + ":" + Version.revision + ":" + Version.commitHash);
        writeStableContentAndMods(digest::update);
        return "runtime-v4:" + hex(digest.digest());
    }

    /**
     * Durable campaign compatibility identity. It intentionally excludes the executable build/commit so a long-lived
     * campaign can be reopened after an ordinary game update when the stable content registry and enabled Mod
     * bytes remain identical. Runtime hosts still use {@link #calculate()} and therefore remain exact-version gated.
     */
    public static String calculateCompatibility(){
        MessageDigest digest = sha256();
        put(digest, "mindustry-shared-campaign-compat:v2");
        writeStableContentAndMods(digest::update);
        return "compat-v2:" + hex(digest.digest());
    }

    /**
     * Computes runtime + compatibility identities with a single scan of the content registry and every enabled Mod
     * byte. Both digests are updated in lockstep so large Mod trees are never buffered in heap (a full-byte
     * ByteArrayOutputStream copy froze createLocal at the fingerprint step on real profiles). The digest byte stream
     * is identical to sequential {@link #calculate()} / {@link #calculateCompatibility()} calls.
     */
    public static Fingerprints calculateBoth(){
        return calculateBoth(null);
    }

    /** Same as {@link #calculateBoth()} with optional per-Mod progress for long desktop hash runs. */
    public static Fingerprints calculateBoth(java.util.function.BiConsumer<String, Float> modProgress){
        MessageDigest runtime = sha256();
        put(runtime, "mindustry-shared-runtime:v4");
        put(runtime, Version.number + ":" + Version.build + ":" + Version.revision + ":" + Version.commitHash);

        MessageDigest compatibility = sha256();
        put(compatibility, "mindustry-shared-campaign-compat:v2");

        writeStableContentAndMods((bytes, offset, length) -> {
            runtime.update(bytes, offset, length);
            compatibility.update(bytes, offset, length);
        }, modProgress);

        return new Fingerprints("runtime-v4:" + hex(runtime.digest()), "compat-v2:" + hex(compatibility.digest()));
    }

    /** MDT-Y strict runtime fingerprint retained only for validating imported historical campaigns. */
    public static String calculateLegacyMdtYRuntimeV3(){
        MessageDigest digest = sha256();
        put(digest, "mindustry-y-shared-runtime:v3");
        put(digest, Version.number + ":" + Version.build + ":" + Version.revision + ":" + Version.commitHash);
        writeStableContentAndMods(digest::update);
        return "runtime-v3:" + hex(digest.digest());
    }

    /** MDT-Y durable compatibility fingerprint retained only for validating imported historical campaigns. */
    public static String calculateLegacyMdtYCompatibilityV1(){
        MessageDigest digest = sha256();
        put(digest, "mindustry-y-shared-campaign-compat:v1");
        writeStableContentAndMods(digest::update);
        return "compat-v1:" + hex(digest.digest());
    }

    /** Pre-split v2 strict algorithm retained for one-time migration of campaigns opened on the same installation. */
    public static String calculateLegacyV2(){
        MessageDigest digest = sha256();
        put(digest, "mindustry-y-shared-content:v2");
        put(digest, Version.number + ":" + Version.build + ":" + Version.revision + ":" + Version.commitHash);
        Seq<String> registry = new Seq<>();
        content.each(value -> registry.add(stableContentIdentity(value)));
        registry.sort(Comparator.naturalOrder());
        for(String value : registry) put(digest, value);
        writeStableModsOnly(digest::update);
        return "v2:" + hex(digest.digest());
    }

    private static void hashStableContentAndMods(MessageDigest digest){
        writeStableContentAndMods(digest::update);
    }

    @FunctionalInterface
    private interface ByteSink{
        void accept(byte[] bytes, int offset, int length);
    }

    private static void writeStableContentAndMods(ByteSink sink){
        writeStableContentAndMods(sink, null);
    }

    private static void writeStableContentAndMods(ByteSink sink, java.util.function.BiConsumer<String, Float> modProgress){
        Seq<String> registry = new Seq<>();
        content.each(value -> registry.add(stableContentIdentity(value)));
        registry.sort(Comparator.naturalOrder());
        for(String value : registry) put(sink, value);
        hashEnabledMods(sink, modProgress);
    }

    private static void writeStableModsOnly(ByteSink sink){
        hashEnabledMods(sink);
    }

    /** Legacy pre-v2 algorithm retained only to prove whether an old campaign can be safely migrated. */
    public static String calculateLegacyV1(){
        MessageDigest digest = sha256();
        put(digest, "mindustry-y-shared-schema:2");
        put(digest, Version.number + ":" + Version.build + ":" + Version.revision + ":" + Version.commitHash);

        Seq<String> registry = new Seq<>();
        content.each(value -> registry.add(value.getContentType().name() + ":" + (value instanceof UnlockableContent unlockable ? unlockable.localizedName : value.toString())));
        registry.sort(Comparator.naturalOrder());
        for(String value : registry) put(digest, value);
        writeStableModsOnly(digest::update);
        return hex(digest.digest());
    }

    public static boolean isLegacyV1(String fingerprint){
        return fingerprint != null && fingerprint.matches("[0-9a-fA-F]{64}");
    }

    private static String stableContentIdentity(Content value){
        String name = value instanceof MappableContent mappable ? mappable.name : value.getClass().getName();
        return value.getContentType().name() + ":" + value.id + ":" + name;
    }

    private static void hashEnabledMods(ByteSink sink){
        hashEnabledMods(sink, null);
    }

    private static void hashEnabledMods(ByteSink sink, java.util.function.BiConsumer<String, Float> modProgress){
        if(mods == null) return;
        Seq<LoadedMod> enabled = mods.orderedMods();
        int index = 0;
        for(LoadedMod mod : enabled){
            index++;
            if(modProgress != null){
                modProgress.accept(mod == null || mod.name == null ? "?" : mod.name, index / (float)Math.max(1, enabled.size));
            }
            put(sink, "mod:" + mod.name);
            put(sink, "version:" + Objects.toString(mod.meta.version, ""));
            put(sink, "minGameVersion:" + Objects.toString(mod.meta.minGameVersion, ""));
            Fi source = fingerprintSource(mod);
            put(sink, "source-kind:" + (source == mod.file ? "file" : "root"));
            hashMod(sink, source);
        }
    }

    /**
     * Returns the bytes that actually back a loaded mod. Some internal mods may use a synthetic
     * {@code mod.file} identity so they participate in the ordinary Mod lifecycle; that path is not a physical JAR.
     * Fingerprinting must therefore prefer an existing archive/directory and fall back to the real loaded root.
     * Missing sources are fatal: silently skipping one would allow mismatched Action hosts into a campaign.
     */
    public static Fi fingerprintSource(LoadedMod mod){
        if(mod == null) throw new IllegalArgumentException("Loaded mod is required");
        if(mod.file != null && mod.file.exists()) return mod.file;
        if(mod.root != null && mod.root.exists()) return mod.root;
        String identity = mod.name == null ? "<unnamed>" : mod.name;
        throw new IllegalStateException("Enabled mod has no fingerprintable source: " + identity
            + " (file=" + mod.file + ", root=" + mod.root + ")");
    }

    private static void hashMod(ByteSink sink, Fi source){
        try{
            if(!source.isDirectory()){
                put(sink, "archive:" + source.name());
                hashFile(sink, source);
                return;
            }
            Seq<Fi> files = source.findAll(file -> !file.isDirectory());
            files.sort(Comparator.comparing(file -> relative(source, file)));
            for(Fi file : files){
                put(sink, "path:" + relative(source, file));
                hashFile(sink, file);
            }
        }catch(IOException e){
            throw new UncheckedIOException("Failed to fingerprint Mod " + source, e);
        }
    }

    private static void hashFile(ByteSink sink, Fi file) throws IOException{
        put(sink, "length:" + file.length());
        try(InputStream input = new BufferedInputStream(file.read())){
            byte[] buffer = new byte[64 * 1024];
            int read;
            while((read = input.read(buffer)) >= 0) if(read > 0) sink.accept(buffer, 0, read);
        }
        sink.accept(new byte[]{(byte)0xff}, 0, 1);
    }

    private static String relative(Fi root, Fi file){
        return root.file().toPath().relativize(file.file().toPath()).toString().replace('\\', '/');
    }

    private static void put(MessageDigest digest, String value){
        put(digest::update, value);
    }

    private static void put(ByteSink sink, String value){
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        sink.accept(bytes, 0, bytes.length);
        sink.accept(new byte[]{(byte)0}, 0, 1);
    }

    private static MessageDigest sha256(){
        try{ return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException e){ throw new AssertionError(e); }
    }

    private static String hex(byte[] bytes){
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for(byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
