package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.struct.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Resolves the product Sector launcher without consulting JAVA_HOME or PATH. */
public final class PackagedSectorLauncher{
    public static final String launcherProperty = "sharedCampaign.sector.launcher";
    public static final String developmentFallbackProperty = "sharedCampaign.allowDevelopmentJavaFallback";
    public static final String jpackageAppPathProperty = "jpackage.app-path";
    public static final String launcherBaseName = "MindustrySharedSector";
    /** Development/direct-java Action heap cap; packaged launchers bake the same default into their jpackage config. */
    public static final String actionMaxHeapMiBProperty = "sharedCampaign.actionMaxHeapMiB";
    public static final int defaultActionMaxHeapMiB = 1024;

    private PackagedSectorLauncher(){}

    public static Seq<String> command(Fi descriptor){
        Objects.requireNonNull(descriptor);
        Fi packaged = packagedLauncher();
        if(packaged != null){
            Seq<String> command = Seq.with(packaged.absolutePath(), ActionRuntimeDescriptor.argumentPrefix + descriptor.absolutePath());
            appendStrictModSupport(command);
            return command;
        }
        // Desktop product artifacts include the dedicated server runtime in the same classpath. Launch it with the
        // exact JVM that is already running Mindustry; this keeps Packr/bundled-JRE installs self-contained and does
        // not consult JAVA_HOME or PATH. Development runners (gradle run/runDual) must also put :server on the
        // parent classpath — never spawn a child that cannot load SharedSectorLauncher just because the build is "custom".
        boolean allowDevelopmentJava = Boolean.getBoolean(developmentFallbackProperty);
        if(classAvailable("mindustry.server.SharedSectorLauncher") || allowDevelopmentJava){
            String classpath = developmentClasspath(System.getProperty("java.class.path", ""));
            if(classpath.isBlank()) throw new IllegalStateException("Action runtime has no Java classpath");
            int maxHeapMiB = configuredActionMaxHeapMiB();
            Seq<String> command = Seq.with(
                currentJavaExecutable(),
                "-Xms64m", "-Xmx" + maxHeapMiB + "m",
                "-XX:+UseG1GC", "-XX:+UseStringDeduplication",
                "-cp", classpath,
                "mindustry.server.SharedSectorLauncher",
                ActionRuntimeDescriptor.argumentPrefix + descriptor.absolutePath()
            );
            appendStrictModSupport(command);
            return command;
        }
        throw new IllegalStateException("Packaged Shared Campaign Sector launcher is unavailable and the current product classpath does not contain "
            + "mindustry.server.SharedSectorLauncher. Launch from Mindustry.jar/dist, include :server on the runtime classpath, or set -D"
            + developmentFallbackProperty + "=true.");
    }

    /**
     * A graphical coordinator resolves mod support strictly (outdated/blacklisted/old Java mods drop out of the
     * enabled set and the content registry). Tell a separately launched Action child to resolve the same way so
     * its actionHello content fingerprint matches the coordinator's; a headless coordinator stays vanilla-lenient
     * and omits the token. See {@code mindustryY.sharedCampaign.strictModSupport}.
     */
    private static void appendStrictModSupport(Seq<String> command){
        if(!mindustry.Vars.headless) command.add("--mindustry-y-strict-mod-support");
    }

    private static boolean classAvailable(String name){
        try{
            Class.forName(name, false, PackagedSectorLauncher.class.getClassLoader());
            return true;
        }catch(ClassNotFoundException ignored){
            return false;
        }
    }

    private static String currentJavaExecutable(){
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path path = Path.of(System.getProperty("java.home", ""), "bin", executable).toAbsolutePath().normalize();
        if(!Files.isRegularFile(path)) throw new IllegalStateException("Current JVM executable is unavailable: " + path);
        return path.toString();
    }


    public static int configuredActionMaxHeapMiB(){
        int configured = Integer.getInteger(actionMaxHeapMiBProperty, defaultActionMaxHeapMiB);
        if(configured < 256 || configured > 16 * 1024){
            throw new IllegalArgumentException(actionMaxHeapMiBProperty + " must be between 256 and 16384 MiB: " + configured);
        }
        return configured;
    }

    static Fi packagedLauncher(){
        String explicit = System.getProperty(launcherProperty, "").trim();
        if(!explicit.isEmpty()){
            Fi value = new Fi(explicit);
            if(!value.exists()) throw new IllegalStateException("Configured Sector launcher does not exist: " + value.absolutePath());
            return value;
        }
        String appPath = System.getProperty(jpackageAppPathProperty, "").trim();
        if(appPath.isEmpty()) return null;
        Fi app = new Fi(appPath);
        String suffix = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "";
        Fi sibling = app.parent().child(launcherBaseName + suffix);
        if(!sibling.exists()) throw new IllegalStateException("jpackage application is missing its Shared Campaign Sector launcher: " + sibling.absolutePath());
        return sibling;
    }

    private static String developmentClasspath(String classpath){
        String base = System.getProperty("user.dir", ".");
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for(String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)){
            if(entry.isEmpty()) continue;
            Path path = Path.of(entry);
            entries.add(path.isAbsolute() ? path.normalize().toString() : Path.of(base).resolve(path).normalize().toString());
        }

        // ActionProcess deliberately changes the child working directory to the action runtime root so config/saves/mods
        // stay isolated. In development builds Mindustry's internal assets are usually exposed by the parent's working
        // directory rather than packaged inside a jar. Add that asset root to the child classpath so Fi(FileType.internal)
        // can still fall back to classpath resources after the cwd changes.
        Path internalRoot = developmentInternalRoot();
        if(internalRoot != null) entries.add(internalRoot.toString());
        return String.join(File.pathSeparator, entries);
    }

    private static Path developmentInternalRoot(){
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if(java.nio.file.Files.isRegularFile(cwd.resolve("version.properties"))) return cwd;

        // Some test runners launch from a project/module directory instead of core/assets. Only accept a candidate that
        // contains Mindustry's generated version.properties; never guess an unrelated directory.
        Path candidate = cwd.resolve("core/assets").normalize();
        if(java.nio.file.Files.isRegularFile(candidate.resolve("version.properties"))) return candidate;
        candidate = cwd.resolve("../core/assets").normalize();
        if(java.nio.file.Files.isRegularFile(candidate.resolve("version.properties"))) return candidate;
        return null;
    }
}
