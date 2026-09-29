import org.junit.jupiter.api.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** Source-level Android product gates remain runnable even when CI has no Android SDK installed. */
public class SharedCampaignAndroidProductSurfaceTests{
    @Test
    void androidUsesPrivateSharedHostWithStrictGraphicalModSelection() throws Exception{
        String manifest = source("android/AndroidManifest.xml");
        String launcher = source("android/src/mindustry/android/AndroidLauncher.java");
        String host = source("android/src/mindustry/android/shared/AndroidSharedCampaignHostService.java");
        assertTrue(manifest.contains("android:process=\":sharedhost\""));
        assertTrue(manifest.contains("android:exported=\"false\""));
        assertTrue(launcher.contains("AndroidSharedHostClient.install(this)"));
        assertTrue(host.contains("System.setProperty(\"mindustryY.sharedCampaign.strictModSupport\", \"true\")"),
            "headless Android host must select the same supported mod set as the graphical coordinator");
        assertTrue(host.indexOf("System.setProperty(\"mindustryY.sharedCampaign.strictModSupport") < host.indexOf("Vars.init();"),
            "strict mod policy must be installed before content/mod discovery");
    }

    private static String source(String relative) throws Exception{
        Path cursor = Path.of("").toAbsolutePath();
        for(int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()){
            Path candidate = cursor.resolve(relative);
            if(Files.exists(candidate)) return Files.readString(candidate);
        }
        throw new IllegalStateException("Cannot locate project source " + relative);
    }
}
