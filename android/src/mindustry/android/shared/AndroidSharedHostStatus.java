package mindustry.android.shared;

import java.io.*;
import java.nio.charset.*;

/** Tiny durable liveness/status marker shared between the UI coordinator and :sharedhost process. */
final class AndroidSharedHostStatus{
    static final String accepted = "accepted", running = "running", stopping = "stopping", stopped = "stopped", failed = "failed";
    final String state, message;
    final int exitCode;
    final long updatedAt;

    AndroidSharedHostStatus(String state, int exitCode, long updatedAt, String message){
        this.state = state == null ? failed : state;
        this.exitCode = exitCode;
        this.updatedAt = updatedAt;
        this.message = message == null ? "" : message;
    }

    static void write(File file, String state, int exitCode, String message){
        try{
            File parent = file.getParentFile(); if(parent != null) parent.mkdirs();
            File temp = new File(file.getPath() + ".tmp");
            String safe = message == null ? "" : java.util.Base64.getEncoder().encodeToString(message.getBytes(StandardCharsets.UTF_8));
            try(Writer out = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)){
                out.write(state + "\n" + exitCode + "\n" + System.currentTimeMillis() + "\n" + safe + "\n");
            }
            if(file.exists()) file.delete();
            if(!temp.renameTo(file)) throw new IOException("Unable to commit host status");
        }catch(Throwable ignored){
            // Runtime liveness must never fail merely because diagnostics could not be persisted.
        }
    }

    static AndroidSharedHostStatus read(File file){
        if(file == null || !file.isFile()) return null;
        try(BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))){
            String state = in.readLine();
            int exit = Integer.parseInt(in.readLine());
            long updated = Long.parseLong(in.readLine());
            String encoded = in.readLine();
            String message = encoded == null || encoded.isEmpty() ? "" : new String(java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            return new AndroidSharedHostStatus(state, exit, updated, message);
        }catch(Throwable ignored){
            return null;
        }
    }
}
