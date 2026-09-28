import mindustry.campaign.shared.ui.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SharedCampaignOperationCompletenessTests{
    @Test
    void operationCatalogHasUniqueStableSelectorsAndExplicitStateContracts(){
        Set<String> selectors = new HashSet<>();
        for(SharedCampaignOperation operation : SharedCampaignOperation.values()){
            assertFalse(operation.selector().isBlank(), operation + " has no stable selector");
            assertTrue(selectors.add(operation.selector()), "Duplicate Shared Campaign selector contract: " + operation.selector());
            if(operation.stateSensitive()){
                assertFalse(operation.allowedStatuses().isEmpty(), operation + " is state-sensitive without a positive state");
                assertTrue(operation.failureJourneyRequired(), operation + " needs explicit negative-state/failure behavior coverage");
            }
        }
    }

    @Test
    void everyCatalogSelectorIsBackedByProductUiSource() throws Exception{
        String text = Files.readString(locate("core/src/mindustry/campaign/shared/ui/SharedCampaignDialog.java"))
            + "\n" + Files.readString(locate("core/src/mindustry/campaign/shared/ui/SharedCampaignPlanetDialog.java"))
            + "\n" + Files.readString(locate("core/src/mindustry/ui/dialogs/ResearchDialog.java"));
        for(SharedCampaignOperation operation : SharedCampaignOperation.values()){
            String selector = operation.selector();
            // Prefix selectors identify dynamic Sector/action/logistics entries.
            assertTrue(text.contains(selector), operation + " selector is no longer present in SharedCampaignDialog: " + selector);
        }
    }

    private static Path locate(String relative){
        Path cursor = Path.of("").toAbsolutePath();
        for(int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()){
            Path candidate = cursor.resolve(relative);
            if(Files.exists(candidate)) return candidate;
            candidate = cursor.resolve("Mindustry-Y-62dbbe3").resolve(relative);
            if(Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate project source " + relative);
    }
}
