package mindustry;

import arc.*;
import mindustry.core.*;
import mindustry.runtime.*;

/** Minimal, deterministic bootstrap for focused tests that construct content without launching the full app. */
public final class TestBootstrap{
    private TestBootstrap(){}

    private static ContentLoader initializedLoader;

    public static synchronized void ensureBaseContent(){
        RuntimeContexts.bindPrimaryThread();
        if(Core.settings == null) Core.settings = new Settings();

        if(Vars.content == null){
            Vars.content = new ContentLoader();
        }

        // A non-null loader may still be a fresh, empty loader left behind by another test.
        if(Vars.content.units().isEmpty() || Vars.content.blocks().isEmpty() || Vars.content.items().isEmpty() || Vars.content.planets().isEmpty()){
            Vars.content.createBaseContent();
        }

        // Content#init() establishes movement/environment contracts; repeat only when a different loader is installed.
        if(initializedLoader != Vars.content){
            Vars.content.init();
            initializedLoader = Vars.content;
        }
    }
}
