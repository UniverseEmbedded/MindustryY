package mindustry.net;

import arc.util.io.*;

import java.io.*;

public abstract class Packet{
    //internally used by generated code
    protected static final byte[] NODATA = {};
    //Generated packet handled() methods reuse these decode objects. They must be packet-instance
    //owned: process-global streams let concurrent GameContexts overwrite each other's payload.
    protected final ReusableByteInStream BAIS = new ReusableByteInStream();
    protected final Reads READ = new Reads(new DataInputStream(BAIS));

    //these are constants because I don't want to bother making an enum to mirror the annotation enum

    /** Does not get handled unless client is connected. */
    public static final int priorityLow = 0;
    /** Gets put in a queue and processed if not connected. */
    public static final int priorityNormal = 1;
    /** Gets handled immediately, regardless of connection status. */
    public static final int priorityHigh = 2;

    public void read(Reads read){}
    public void write(Writes write){}

    public boolean allow(boolean server){
        return true;
    }

    public void read(Reads read, int length){
        read(read);
    }

    public void handled(){}

    public int getPriority(){
        return priorityNormal;
    }

    public void handleClient(){}
    public void handleServer(NetConnection con){}
}
