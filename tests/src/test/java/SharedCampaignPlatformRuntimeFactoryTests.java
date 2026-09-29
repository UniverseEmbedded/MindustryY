import arc.files.*;
import mindustry.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.game.*;
import mindustry.core.*;
import mindustry.runtime.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Platform bootstrap gate used by Android's private :sharedhost runtime factory. */
public class SharedCampaignPlatformRuntimeFactoryTests{
    @BeforeAll static void bootstrap(){ ApplicationTests.launchApplication(false); }

    @Test
    void installUsesPlatformRuntimeFactoryBeforeCampaignActivation() throws Exception{
        AtomicInteger suppliers = new AtomicInteger();
        SectorRuntimeFactory marker = SectorRuntimeFactory.inProcess();
        GameContext context = new GameContext("platform-runtime-factory", new GameState(), null);
        try{
            SharedCampaignService.installPlatformRuntimeFactory(() -> {
                suppliers.incrementAndGet();
                return marker;
            });
            SharedCampaignService service = SharedCampaignService.install(context, new Fi("."));
            Field field = SharedCampaignService.class.getDeclaredField("runtimeFactory");
            field.setAccessible(true);
            assertSame(marker, field.get(service));
            assertEquals(1, suppliers.get(), "platform factory must be resolved once for the newly attached service");

            assertSame(service, SharedCampaignService.install(context, new Fi(".")));
            assertEquals(1, suppliers.get(), "re-fetching the attached service must not replace its runtime backend");
        }finally{
            SharedCampaignService.clearPlatformRuntimeFactory();
            context.dispose();
        }
    }

    @Test
    void nullPlatformFactoryFailsClosedInsteadOfFallingBackToJvm(){
        GameContext context = new GameContext("platform-runtime-null", new GameState(), null);
        try{
            SharedCampaignService.installPlatformRuntimeFactory(() -> null);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SharedCampaignService.install(context, new Fi(".")));
            assertTrue(failure.getMessage().contains("returned null"));
        }finally{
            SharedCampaignService.clearPlatformRuntimeFactory();
            context.dispose();
        }
    }
}
