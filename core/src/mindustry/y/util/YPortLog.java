package mindustry.y.util;

/**
 * Shared-entry vs Action-listen port labels for operator logs.
 * Format is always {@code entry@actual} (e.g. {@code 6570@6567}): left is the public/coordinator
 * single-entry port players dial; right is the Action Arc {@code NetServer} listen port.
 * An unknown side prints as {@code ?}.
 */
public final class YPortLog{
    private YPortLog(){}

    /** {@code entry@actual}; non-positive ports become {@code ?}. */
    public static String entryAtActual(int entryPort, int actualPort){
        return side(entryPort) + "@" + side(actualPort);
    }

    /** Same as {@link #entryAtActual(int, int)} but only when both sides are known. */
    public static String entryAtActualOr(int entryPort, int actualPort, String fallback){
        if(entryPort <= 0 || actualPort <= 0) return fallback;
        return entryAtActual(entryPort, actualPort);
    }

    /** Parses the TCP port from {@code host:port} / {@code /host:port}, or {@code -1} when absent. */
    public static int portOf(String hostPort){
        if(hostPort == null || hostPort.isEmpty()) return -1;
        int colon = hostPort.lastIndexOf(':');
        if(colon < 0 || colon == hostPort.length() - 1) return -1;
        try{
            int port = Integer.parseInt(hostPort.substring(colon + 1).trim());
            return port > 0 && port <= 65535 ? port : -1;
        }catch(NumberFormatException ignored){
            return -1;
        }
    }

    private static String side(int port){
        return port > 0 && port <= 65535 ? String.valueOf(port) : "?";
    }
}
