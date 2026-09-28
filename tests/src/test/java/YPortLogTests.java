import mindustry.y.util.YPortLog;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Operator-facing port labels: entry@actual must survive Arc's '@' placeholder format. */
public class YPortLogTests{
    @Test
    void entryAtActualLabelsBothSides(){
        assertEquals("6570@6567", YPortLog.entryAtActual(6570, 6567));
        assertEquals("6570@6568", YPortLog.entryAtActual(6570, 6568));
    }

    @Test
    void unknownSidesPrintQuestionMark(){
        assertEquals("?@?", YPortLog.entryAtActual(-1, 0));
        assertEquals("6570@?", YPortLog.entryAtActual(6570, -1));
        assertEquals("?@6567", YPortLog.entryAtActual(0, 6567));
    }

    @Test
    void entryAtActualOrFallsBackWhenEitherSideMissing(){
        assertEquals("6570@6567", YPortLog.entryAtActualOr(6570, 6567, "n/a"));
        assertEquals("n/a", YPortLog.entryAtActualOr(-1, 6567, "n/a"));
        assertEquals("n/a", YPortLog.entryAtActualOr(6570, 0, "n/a"));
    }

    @Test
    void portOfParsesTcpAddressesUsedByConnectLogs(){
        assertEquals(6570, YPortLog.portOf("/127.0.0.1:6570"));
        assertEquals(6567, YPortLog.portOf("127.0.0.1:6567"));
        assertEquals(-1, YPortLog.portOf(""));
        assertEquals(-1, YPortLog.portOf(null));
        assertEquals(-1, YPortLog.portOf("no-port"));
        assertEquals(-1, YPortLog.portOf("host:0"));
    }

    /**
     * Arc Strings.format treats every '@' in the *format* as a placeholder. Building the full
     * {@code entry@actual=...} label as a single argument must not be re-tokenized.
     */
    @Test
    void arcFormatKeepsLiteralAtInsideSingleArgument(){
        String label = "entry@actual=" + YPortLog.entryAtActual(6570, 6567);
        assertEquals("Opened a server on entry@actual=6570@6567.", arc.util.Strings.format("Opened a server on @.", label));
        assertEquals("Connecting to server: /127.0.0.1:6570 [ports=entry@actual=6570@6567]",
            arc.util.Strings.format("Connecting to server: @ [ports=@]", "/127.0.0.1:6570", label));
    }
}
