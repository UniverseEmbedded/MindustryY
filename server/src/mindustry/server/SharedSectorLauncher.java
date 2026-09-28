package mindustry.server;

import arc.files.*;
import mindustry.campaign.shared.runtime.*;

/**
 * Packaged Shared Campaign Sector entry point. It reads the stable Action descriptor before normal server startup so
 * the child process receives exactly the same runtime identity contract as the in-process backend.
 */
public final class SharedSectorLauncher{
    private SharedSectorLauncher(){}

    public static void main(String[] args){
        try{
            Fi descriptorFile = ActionRuntimeDescriptor.argumentFile(args);
            if(descriptorFile == null) throw new IllegalArgumentException("Missing " + ActionRuntimeDescriptor.argumentPrefix + "<file>");
            ActionRuntimeDescriptor descriptor = ActionRuntimeDescriptor.read(descriptorFile);
            descriptor.installSystemProperties();
            if(args != null){
                for(String arg : args){
                    // Emitted by a graphical coordinator: resolve mod support/content identically to that
                    // coordinator before Vars.init() loads mods, so actionHello fingerprints agree.
                    if(arg != null && "--mindustry-y-strict-mod-support".equals(arg)){
                        System.setProperty("mindustryY.sharedCampaign.strictModSupport", "true");
                    }
                }
            }
            ServerLauncher.main(new String[]{descriptor.serverCommand()});
        }catch(Throwable error){
            error.printStackTrace();
            System.exit(2);
        }
    }
}
