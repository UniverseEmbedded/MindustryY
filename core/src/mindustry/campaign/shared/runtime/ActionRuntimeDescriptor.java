package mindustry.campaign.shared.runtime;

import arc.files.*;

import java.io.*;
import java.nio.charset.*;

/**
 * Stable coordinator-to-action launch descriptor shared by isolated JVM and in-process backends.
 * Product-specific campaign extensions must not add fields here; put extension state behind the Shared Campaign
 * protocol instead so every backend boots from the same vanilla-capable runtime contract.
 */
public record ActionRuntimeDescriptor(
    String actionId,
    String coordinatorHost,
    int coordinatorPort,
    long authorityGeneration,
    long runtimeIncarnation,
    String saveSlot,
    String summaryPath,
    String launchPath,
    String planetName,
    String sectorName,
    String attemptId,
    int gamePort,
    boolean newAction,
    String reservationsPath,
    String transportReservationsPath,
    int autosaveSeconds,
    String serverCommand
){
    /** Schema 4 carries the Shared Campaign persistence policy's Action autosave cadence. */
    public static final int schema = 4;
    public static final String argumentPrefix = "--shared-campaign-action-runtime=";

    public ActionRuntimeDescriptor{
        actionId = required(actionId, "actionId");
        coordinatorHost = required(coordinatorHost, "coordinatorHost");
        saveSlot = required(saveSlot, "saveSlot");
        summaryPath = required(summaryPath, "summaryPath");
        launchPath = required(launchPath, "launchPath");
        planetName = required(planetName, "planetName");
        sectorName = required(sectorName, "sectorName");
        attemptId = attemptId == null ? "" : attemptId;
        reservationsPath = required(reservationsPath, "reservationsPath");
        transportReservationsPath = required(transportReservationsPath, "transportReservationsPath");
        serverCommand = required(serverCommand, "serverCommand");
        if(coordinatorPort <= 0 || coordinatorPort > 65535) throw new IllegalArgumentException("Invalid coordinatorPort: " + coordinatorPort);
        if(gamePort <= 0 || gamePort > 65535) throw new IllegalArgumentException("Invalid gamePort: " + gamePort);
        if(autosaveSeconds < 15) throw new IllegalArgumentException("autosaveSeconds must be at least 15");
        if(authorityGeneration <= 0L) throw new IllegalArgumentException("authorityGeneration must be positive");
        if(runtimeIncarnation <= 0L) throw new IllegalArgumentException("runtimeIncarnation must be positive");
    }

    public void write(Fi target) throws IOException{
        target.parent().mkdirs();
        try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(target.write(false)))){
            out.writeInt(schema);
            write(out, actionId); write(out, coordinatorHost); out.writeInt(coordinatorPort);
            out.writeLong(authorityGeneration); out.writeLong(runtimeIncarnation);
            write(out, saveSlot); write(out, summaryPath); write(out, launchPath);
            write(out, planetName); write(out, sectorName); write(out, attemptId);
            out.writeInt(gamePort); out.writeBoolean(newAction);
            write(out, reservationsPath); write(out, transportReservationsPath); out.writeInt(autosaveSeconds); write(out, serverCommand);
        }
    }

    public static ActionRuntimeDescriptor read(Fi source) throws IOException{
        if(source == null || !source.exists()) throw new FileNotFoundException("Action runtime descriptor is missing: " + source);
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(source.read()))){
            int incoming = in.readInt();
            if(incoming < 1 || incoming > schema) throw new IOException("Unsupported action runtime descriptor schema " + incoming + ", expected <= " + schema);
            String actionId = read(in), host = read(in);
            int port = in.readInt();
            long authorityGeneration = in.readLong();
            long runtimeIncarnation = incoming >= 2 ? in.readLong() : 1L;
            String saveSlot = read(in), summary = read(in), launch = read(in), planet = read(in), sector = read(in), attempt = read(in);
            int gamePort = in.readInt(); boolean fresh = in.readBoolean();
            String researchReservations = read(in), transportReservations = read(in);
            if(incoming <= 2) read(in); // donor-only extension reservation path; intentionally discarded.
            int autosaveSeconds = incoming >= 4 ? in.readInt() : 5 * 60;
            String serverCommand = read(in);
            return new ActionRuntimeDescriptor(actionId, host, port, authorityGeneration, runtimeIncarnation,
                saveSlot, summary, launch, planet, sector, attempt, gamePort, fresh,
                researchReservations, transportReservations, autosaveSeconds, serverCommand);
        }
    }

    public void installSystemProperties(){
        System.setProperty(ActionRuntimeConfig.actionIdProperty, actionId);
        System.setProperty(ActionRuntimeConfig.coordinatorHostProperty, coordinatorHost);
        System.setProperty(ActionRuntimeConfig.coordinatorPortProperty, Integer.toString(coordinatorPort));
        System.setProperty(ActionRuntimeConfig.authorityGenerationProperty, Long.toString(authorityGeneration));
        System.setProperty(ActionRuntimeConfig.runtimeIncarnationProperty, Long.toString(runtimeIncarnation));
        System.setProperty(ActionRuntimeConfig.actionSaveProperty, saveSlot);
        System.setProperty(ActionRuntimeConfig.actionSummaryProperty, summaryPath);
        System.setProperty(ActionRuntimeConfig.actionLaunchProperty, launchPath);
        System.setProperty(ActionRuntimeConfig.actionPlanetProperty, planetName);
        System.setProperty(ActionRuntimeConfig.actionSectorProperty, sectorName);
        System.setProperty(ActionRuntimeConfig.actionAttemptProperty, attemptId);
        System.setProperty(ActionRuntimeConfig.actionGamePortProperty, Integer.toString(gamePort));
        System.setProperty(ActionRuntimeConfig.actionNewProperty, Boolean.toString(newAction));
        System.setProperty(ActionRuntimeConfig.actionReservationsProperty, reservationsPath);
        System.setProperty(ActionRuntimeConfig.actionTransportReservationsProperty, transportReservationsPath);
        System.setProperty(ActionRuntimeConfig.actionServerCommandProperty, serverCommand);
    }

    public static Fi argumentFile(String[] args){
        if(args == null) return null;
        for(String arg : args){
            if(arg != null && arg.startsWith(argumentPrefix)){
                String path = arg.substring(argumentPrefix.length());
                if(path.isBlank()) throw new IllegalArgumentException("Empty Action runtime descriptor path");
                return new Fi(path);
            }
        }
        return null;
    }

    private static String required(String value, String field){
        if(value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static void write(DataOutput out, String value) throws IOException{
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String read(DataInput in) throws IOException{
        int length = in.readInt();
        if(length < 0 || length > 16 * 1024 * 1024) throw new IOException("Invalid descriptor string length: " + length);
        byte[] bytes = new byte[length]; in.readFully(bytes); return new String(bytes, StandardCharsets.UTF_8);
    }
}
