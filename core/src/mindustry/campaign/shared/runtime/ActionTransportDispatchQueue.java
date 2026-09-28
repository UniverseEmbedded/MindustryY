package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.struct.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/**
 * Crash-recoverable outbound LaunchPad dispatch journal owned by one Action runtime.
 *
 * LaunchPad inventory is removed by normal authoritative gameplay before {@code LaunchItemEvent}. The resulting
 * transport intent therefore has to survive coordinator/control-connection loss or an Action restart; otherwise a
 * successfully launched pod can vanish from the Shared Campaign economy. Entries remain durable until the coordinator
 * acknowledges the idempotent dispatch ID.
 */
final class ActionTransportDispatchQueue{
    private static final int magic = 0x53435444; // SCTD
    private static final int version = 2;
    private static final int legacyMagic = 0x4d595444;
    private static final int legacyVersion = 1;
    private final Fi file;
    private final ObjectMap<String, RuntimePayloads.TransportDispatch> pending = new ObjectMap<>();
    private boolean loaded;

    ActionTransportDispatchQueue(Fi file){ this.file = file; }

    synchronized RuntimePayloads.TransportDispatch enqueue(String actionId, String dispatchId, String sourceSector,
                                                            String destinationSector, String itemName, int amount,
                                                            long travelTurns){
        ensureLoaded();
        if(dispatchId == null || dispatchId.isBlank()) throw new IllegalArgumentException("Transport dispatch ID is required");
        if(sourceSector == null || sourceSector.isBlank() || destinationSector == null || destinationSector.isBlank()) throw new IllegalArgumentException("Transport dispatch routing is required");
        if(sourceSector.equals(destinationSector)) throw new IllegalArgumentException("Transport source and destination must differ");
        if(itemName == null || itemName.isBlank() || amount <= 0) throw new IllegalArgumentException("Transport dispatch cargo is invalid");
        RuntimePayloads.TransportDispatch value = new RuntimePayloads.TransportDispatch(actionId, dispatchId, sourceSector,
            destinationSector, itemName, amount, Math.max(1L, travelTurns));
        RuntimePayloads.TransportDispatch existing = pending.get(dispatchId);
        if(existing != null){
            if(!same(existing, value)) throw new IllegalStateException("Transport dispatch ID was reused with different contents: " + dispatchId);
            return existing;
        }
        pending.put(dispatchId, value);
        persist();
        return value;
    }

    synchronized void acknowledge(String dispatchId){
        ensureLoaded();
        if(dispatchId == null || dispatchId.isBlank()) return;
        if(pending.remove(dispatchId) != null) persist();
    }

    synchronized Seq<RuntimePayloads.TransportDispatch> pending(){
        ensureLoaded();
        Seq<String> ids = pending.keys().toSeq().sort();
        Seq<RuntimePayloads.TransportDispatch> out = new Seq<>(ids.size);
        for(String id : ids) out.add(pending.get(id));
        return out;
    }

    synchronized int size(){ ensureLoaded(); return pending.size; }

    private static boolean same(RuntimePayloads.TransportDispatch a, RuntimePayloads.TransportDispatch b){
        return a.actionId().equals(b.actionId()) && a.dispatchId().equals(b.dispatchId()) &&
            a.sourceSector().equals(b.sourceSector()) && a.destinationSector().equals(b.destinationSector()) &&
            a.itemName().equals(b.itemName()) && a.amount() == b.amount() && a.travelTurns() == b.travelTurns();
    }

    private void ensureLoaded(){
        if(loaded) return;
        loaded = true;
        if(!file.exists()) return;
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(file.read()))){
            int foundMagic = in.readInt(), foundVersion = in.readInt();
            boolean clean = foundMagic == magic && foundVersion == version;
            boolean legacy = foundMagic == legacyMagic && foundVersion == legacyVersion;
            if(!clean && !legacy) throw new IOException("Unsupported outbound transport dispatch journal");
            int count = checked(in.readInt());
            for(int i = 0; i < count; i++){
                RuntimePayloads.TransportDispatch value = new RuntimePayloads.TransportDispatch(
                    in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readInt(), in.readLong());
                if(pending.put(value.dispatchId(), value) != null) throw new IOException("Duplicate outbound transport dispatch ID");
            }
            if(in.read() != -1) throw new IOException("Trailing outbound transport dispatch journal data");
        }catch(IOException e){ throw new UncheckedIOException(e); }
    }

    private void persist(){
        file.parent().mkdirs();
        Path target = file.file().toPath(), temp = target.resolveSibling(target.getFileName() + ".tmp");
        try{
            if(pending.isEmpty()){
                Files.deleteIfExists(temp); Files.deleteIfExists(target); forceParent(target); return;
            }
            try(FileOutputStream fos = new FileOutputStream(temp.toFile()); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))){
                out.writeInt(magic); out.writeInt(version); out.writeInt(pending.size);
                for(String id : pending.keys().toSeq().sort()){
                    RuntimePayloads.TransportDispatch value = pending.get(id);
                    out.writeUTF(value.actionId()); out.writeUTF(value.dispatchId()); out.writeUTF(value.sourceSector());
                    out.writeUTF(value.destinationSector()); out.writeUTF(value.itemName()); out.writeInt(value.amount()); out.writeLong(value.travelTurns());
                }
                out.flush(); fos.getFD().sync();
            }
            try{ Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ignored){ Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            Path parent = target.toAbsolutePath().getParent();
            if(parent != null) try(FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)){ channel.force(true); }catch(Exception ignored){}
        }catch(IOException e){ throw new UncheckedIOException(e); }
    }

    private static int checked(int value) throws IOException{
        if(value < 0 || value > 100_000) throw new IOException("Invalid outbound transport dispatch count");
        return value;
    }

    /** Directory metadata is part of the transaction journal: a removed PREPARED sidecar must survive power loss. */
    private static void forceParent(Path target){
        Path parent=target.toAbsolutePath().getParent();
        if(parent!=null) try(FileChannel channel=FileChannel.open(parent,StandardOpenOption.READ)){channel.force(true);}catch(Exception ignored){}
    }

}
