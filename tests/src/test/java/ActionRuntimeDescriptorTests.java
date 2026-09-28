import arc.files.*;
import arc.struct.*;
import mindustry.campaign.shared.runtime.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

public class ActionRuntimeDescriptorTests{
    @Test
    void descriptorRoundTripsAndInstallsLegacyAgentProperties() throws Exception{
        Path root = Files.createTempDirectory("action-runtime-descriptor-");
        try{
            Fi file = new Fi(root.resolve("action-runtime.bin").toString());
            ActionRuntimeDescriptor expected = new ActionRuntimeDescriptor(
                "action-a", "127.0.0.1", 6570, 4L, 1L, "action",
                root.resolve("summary.bin").toString(), root.resolve("launch.bin").toString(),
                "serpulo", "groundZero", "attempt-1", 6567, true,
                root.resolve("research.bin").toString(), root.resolve("transport.bin").toString(),
                60, "config port 6567,config autoPause true"
            );
            expected.write(file);
            ActionRuntimeDescriptor actual = ActionRuntimeDescriptor.read(file);
            assertEquals(expected, actual);
            actual.installSystemProperties();
            assertEquals("action-a", System.getProperty(ActionRuntimeConfig.actionIdProperty));
            assertEquals("6570", System.getProperty(ActionRuntimeConfig.coordinatorPortProperty));
            assertEquals("1", System.getProperty(ActionRuntimeConfig.runtimeIncarnationProperty));
            assertEquals("groundZero", System.getProperty(ActionRuntimeConfig.actionSectorProperty));
            assertEquals("attempt-1", System.getProperty(ActionRuntimeConfig.actionAttemptProperty));
        }finally{
            clearProperties();
            deleteTree(root);
        }
    }

    @Test
    void packagedLauncherUsesExplicitProductLauncherWithoutJavaDiscovery() throws Exception{
        Path root = Files.createTempDirectory("packaged-sector-launcher-");
        try{
            Path launcher = Files.createFile(root.resolve("MindustryYSector"));
            Path descriptor = Files.createFile(root.resolve("descriptor.bin"));
            System.setProperty(PackagedSectorLauncher.launcherProperty, launcher.toString());
            Seq<String> command = PackagedSectorLauncher.command(new Fi(descriptor.toString()));
            // Graphical coordinators append the strict-mod-support parity token; headless coordinators stay vanilla.
            int expectedSize = mindustry.Vars.headless ? 2 : 3;
            assertEquals(expectedSize, command.size);
            assertEquals(launcher.toAbsolutePath().toString(), Path.of(command.get(0)).toAbsolutePath().toString());
            // Fi.absolutePath() uses forward slashes on every OS; compare in the same normalized form as production.`n            assertEquals(ActionRuntimeDescriptor.argumentPrefix + new Fi(descriptor.toString()).absolutePath(), command.get(1));
            if(!mindustry.Vars.headless){
                assertEquals("--mindustry-y-strict-mod-support", command.get(2));
            }
            assertFalse(command.contains("-cp"));
        }finally{
            System.clearProperty(PackagedSectorLauncher.launcherProperty);
            deleteTree(root);
        }
    }


    @Test
    void hostMemoryAdmissionIsOptInAndBackendAware(){
        clearMemoryAdmissionProperties();
        assertFalse(ActionMemoryAdmission.evaluate(SectorRuntime.Backend.jvmProcess, 0).enabled());
        assertTrue(ActionMemoryAdmission.evaluate(SectorRuntime.Backend.jvmProcess, 100).allowed(),
            "without an explicit host budget the existing maxActiveActions contract remains authoritative");

        System.setProperty(ActionMemoryAdmission.hostMemoryBudgetMiBProperty, "3072");
        System.setProperty(ActionMemoryAdmission.coordinatorReserveMiBProperty, "768");
        System.setProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty, "1024");
        System.setProperty(ActionMemoryAdmission.jvmNativeReserveMiBProperty, "256");
        ActionMemoryAdmission.Decision firstJvm = ActionMemoryAdmission.evaluate(SectorRuntime.Backend.jvmProcess, 0);
        assertTrue(firstJvm.allowed());
        assertEquals(2048L, firstJvm.requiredMiB());
        ActionMemoryAdmission.Decision secondJvm = ActionMemoryAdmission.evaluate(SectorRuntime.Backend.jvmProcess, 1);
        assertFalse(secondJvm.allowed());
        assertTrue(secondJvm.reason().contains("Host memory budget"));

        System.setProperty(ActionMemoryAdmission.inProcessActionReserveMiBProperty, "512");
        assertTrue(ActionMemoryAdmission.evaluate(SectorRuntime.Backend.inProcess, 3).allowed(),
            "three existing in-process Actions plus one candidate fit exactly below this explicit budget");
        clearMemoryAdmissionProperties();
    }

    @Test
    void actionWorkerHeapBudgetIsBoundedAndConfigurable(){
        System.clearProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty);
        assertEquals(PackagedSectorLauncher.defaultActionMaxHeapMiB, PackagedSectorLauncher.configuredActionMaxHeapMiB());
        System.setProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty, "768");
        assertEquals(768, PackagedSectorLauncher.configuredActionMaxHeapMiB());
        System.setProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty, "128");
        assertThrows(IllegalArgumentException.class, PackagedSectorLauncher::configuredActionMaxHeapMiB);
        System.clearProperty(PackagedSectorLauncher.actionMaxHeapMiBProperty);
    }

    private static void clearMemoryAdmissionProperties(){
        for(String key : new String[]{
            ActionMemoryAdmission.hostMemoryBudgetMiBProperty,
            ActionMemoryAdmission.coordinatorReserveMiBProperty,
            ActionMemoryAdmission.jvmNativeReserveMiBProperty,
            ActionMemoryAdmission.inProcessActionReserveMiBProperty,
            PackagedSectorLauncher.actionMaxHeapMiBProperty
        }) System.clearProperty(key);
    }

    private static void clearProperties(){
        for(String key : new String[]{
            ActionRuntimeConfig.actionIdProperty,
            ActionRuntimeConfig.coordinatorHostProperty,
            ActionRuntimeConfig.coordinatorPortProperty,
            ActionRuntimeConfig.authorityGenerationProperty,
            ActionRuntimeConfig.runtimeIncarnationProperty,
            ActionRuntimeConfig.actionSaveProperty,
            ActionRuntimeConfig.actionSummaryProperty,
            ActionRuntimeConfig.actionLaunchProperty,
            ActionRuntimeConfig.actionPlanetProperty,
            ActionRuntimeConfig.actionSectorProperty,
            ActionRuntimeConfig.actionAttemptProperty,
            ActionRuntimeConfig.actionGamePortProperty,
            ActionRuntimeConfig.actionNewProperty,
            ActionRuntimeConfig.actionReservationsProperty,
            ActionRuntimeConfig.actionTransportReservationsProperty,
            ActionRuntimeConfig.actionServerCommandProperty
        }) System.clearProperty(key);
    }

    private static void deleteTree(Path root) throws Exception{
        if(root == null || !Files.exists(root)) return;
        try(var paths = Files.walk(root)){
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try{ Files.deleteIfExists(path); }catch(Exception error){ throw new RuntimeException(error); }
            });
        }
    }
}
