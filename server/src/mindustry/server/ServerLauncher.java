package mindustry.server;

import arc.*;
import arc.backend.headless.*;
import arc.util.*;
import mindustry.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.mod.*;
import mindustry.mod.Mods.*;
import mindustry.net.Net;
import mindustry.net.*;
import mindustry.runtime.*;
import mindustry.ui.*;

import java.time.*;

import static arc.util.Log.*;
import static mindustry.Vars.*;
import static mindustry.server.ServerControl.*;

public class ServerLauncher implements ApplicationListener{
    static String[] args;

    public static void main(String[] args){
        RuntimeContexts.bindPrimaryThread();
        try{
            ServerLauncher.args = args;
            Vars.platform = new Platform(){};
            Vars.net = Vars.game().net = new Net(platform.getNet());

            logger = (level1, text) -> {
                String result = "[" + dateTime.format(LocalDateTime.now()) + "] " + format(tags[level1.ordinal()] + " " + text + "&fr");
                System.out.println(result);
            };
            new HeadlessApplication(new ServerLauncher(), throwable -> CrashHandler.handle(throwable, f -> {}));
        }catch(Throwable t){
            CrashHandler.handle(t, f -> {});
        }
    }

    @Override
    public void init(){
        // HeadlessApplication owns the application lane; bind it explicitly before any runtime-owned access.
        RuntimeContexts.bindPrimaryThread();
        Core.settings.setDataDirectory(Core.files.local("config"));
        loadLocales = false;
        headless = true;

        Vars.loadSettings();
        Vars.init();
        // Shared Action fresh-sector bootstrap needs the same schematic registry lifecycle as the desktop client.
        schematics = new Schematics();

        UI.loadColors();
        Fonts.loadContentIconsHeadless();

        content.createBaseContent();
        mods.loadScripts();
        content.createModContent();
        content.init();
        schematics.load();

        if(mods.hasContentErrors()){
            err("Error occurred loading mod content:");
            for(LoadedMod mod : mods.list()){
                if(mod.hasContentErrors()){
                    err("| &ly[@]", mod.name);
                    for(Content cont : mod.erroredContent){
                        err("| | &y@: &c@", cont.minfo.sourceFile.name(), Strings.getSimpleMessage(cont.minfo.baseError).replace("\n", " "));
                    }
                }
            }
            err("The server will now exit.");
            System.exit(1);
        }

        bases.load();

        Core.app.addListener(new ApplicationListener(){public void update(){ Vars.game().asyncCore.begin(); }});
        Core.app.addListener(logic = Vars.game().logic = new Logic());
        Core.app.addListener(netServer = Vars.game().netServer = new NetServer());

        ActionRuntimeConfig actionRuntime = ActionRuntimeConfig.fromProcessBootstrap();
        if(actionRuntime.enabled()){
            SharedActionBootstrap.installCurrent(actionRuntime, Core.app::exit);
            Core.app.addListener(new ApplicationListener(){
                @Override public void update(){
                    SharedCampaignRuntimeState state = SharedCampaignRuntimeState.find(Vars.game());
                    if(state != null) state.tick();
                }
                @Override public void dispose(){
                    SharedCampaignRuntimeState state = SharedCampaignRuntimeState.find(Vars.game());
                    if(state != null) state.dispose();
                }
            });
        }

        Core.app.addListener(new ServerControl(args));
        Core.app.addListener(new ApplicationListener(){public void update(){ Vars.game().asyncCore.end(); }});

        mods.eachClass(Mod::init);

        Events.fire(new ServerLoadEvent());
    }
}
