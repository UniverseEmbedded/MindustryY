package mindustry.android.shared;

import mindustry.campaign.shared.SharedCampaignState.*;
import mindustry.campaign.shared.io.*;

import java.io.*;

/** File-backed bridge payload. Keeping the heavy ActionState out of Binder avoids transaction-size coupling. */
final class AndroidSharedHostRuntimeRequest{
    private static final int magic = 0x4d594148; // MYAH
    private static final int version = 1;

    final ActionState action;
    final String directory, coordinatorHost, controlSecret, joinSecret, modsSource, sourceSave;
    final int coordinatorPort;

    AndroidSharedHostRuntimeRequest(ActionState action, String directory, String coordinatorHost, int coordinatorPort,
                                    String controlSecret, String joinSecret, String modsSource, String sourceSave){
        this.action = action;
        this.directory = nn(directory);
        this.coordinatorHost = nn(coordinatorHost);
        this.coordinatorPort = coordinatorPort;
        this.controlSecret = nn(controlSecret);
        this.joinSecret = nn(joinSecret);
        this.modsSource = nn(modsSource);
        this.sourceSave = nn(sourceSave);
    }

    void write(File file) throws IOException{
        File parent = file.getParentFile();
        if(parent != null) parent.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))){
            out.writeInt(magic);
            out.writeInt(version);
            byte[] actionBytes = SharedCampaignCodec.encodeAction(action);
            out.writeInt(actionBytes.length);
            out.write(actionBytes);
            out.writeUTF(directory);
            out.writeUTF(coordinatorHost);
            out.writeInt(coordinatorPort);
            writeString(out, controlSecret);
            writeString(out, joinSecret);
            writeString(out, modsSource);
            writeString(out, sourceSave);
        }
        if(file.exists() && !file.delete()) throw new IOException("Unable to replace Android shared-host request " + file);
        if(!tmp.renameTo(file)) throw new IOException("Unable to commit Android shared-host request " + file);
    }

    static AndroidSharedHostRuntimeRequest read(File file) throws IOException{
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readInt() != magic) throw new IOException("Invalid Android shared-host request");
            int format = in.readInt();
            if(format != version) throw new IOException("Unsupported Android shared-host request format " + format);
            int length = in.readInt();
            if(length < 1 || length > 64 * 1024 * 1024) throw new IOException("Invalid ActionState payload length " + length);
            byte[] actionBytes = new byte[length];
            in.readFully(actionBytes);
            ActionState action = SharedCampaignCodec.decodeAction(actionBytes);
            return new AndroidSharedHostRuntimeRequest(action, in.readUTF(), in.readUTF(), in.readInt(),
                readString(in), readString(in), readString(in), readString(in));
        }
    }

    private static void writeString(DataOutput out, String value) throws IOException{
        byte[] bytes = nn(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length > 16 * 1024 * 1024) throw new IOException("Android shared-host request string is too large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInput in) throws IOException{
        int length = in.readInt();
        if(length < 0 || length > 16 * 1024 * 1024) throw new IOException("Invalid Android shared-host request string length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String nn(String value){ return value == null ? "" : value; }
}
