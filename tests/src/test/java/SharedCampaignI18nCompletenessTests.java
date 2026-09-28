import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;

public class SharedCampaignI18nCompletenessTests{
    @Test
    void everyLiteralSharedUiBundleReferenceExistsInBaseBundle() throws Exception{
        Path root = locate("core/src/mindustry");
        String bundle = Files.readString(locate("core/assets/bundles/bundle.properties"));
        Set<String> available = new HashSet<>();
        Matcher properties = Pattern.compile("(?m)^([^#\\s][^=\\n]*?)\\s*=").matcher(bundle);
        while(properties.find()) available.add(properties.group(1).trim());

        Pattern literal = Pattern.compile("\\\"@(sharedcampaign\\.[A-Za-z0-9_.-]+)\\\"");
        List<String> missing = new ArrayList<>();
        try(var paths = Files.walk(root)){
            for(Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()){
                Matcher matcher = literal.matcher(Files.readString(file));
                while(matcher.find()) if(!available.contains(matcher.group(1))) missing.add(matcher.group(1) + " @ " + root.relativize(file));
            }
        }
        assertTrue(missing.isEmpty(), "Missing Shared Campaign base bundle keys: " + missing);
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
