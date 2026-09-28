package mindustry.campaign.shared.runtime;

import arc.files.*;
import arc.struct.*;
import mindustry.io.*;
import mindustry.type.*;

import java.io.*;
import java.util.function.*;
import java.nio.channels.*;
import java.nio.file.*;

import static mindustry.Vars.*;

/**
 * Durable resource reservations for cross-process research transactions.
 *
 * <p>A reservation is written as PREPARING before the world is changed, then the core inventory and .msav are
 * committed, and finally the record becomes PREPARED. A durable PREPARED sidecar is itself proof that the world save
 * completed before that marker was fsynced; later gameplay may legitimately move inventory back to the same numeric
 * value as the pre-transaction snapshot, so recovery must not infer rollback from aggregate inventory equality. Commit
 * removes the reservation; abort refunds the reserved amount while preserving later production/consumption.</p>
 */
final class ActionResearchReservations{
    private static final int magic = 0x53435252;
    private static final int version = 2;
    private static final int legacyMagic = 0x4d595252;
    private static final int legacyVersion = 1;
    private final Fi file;
    private final String saveSlot;
    private final Supplier<Fi> saveFile;
    private final ObjectMap<String, Reservation> reservations = new ObjectMap<>();
    private boolean loaded;

    ActionResearchReservations(Fi file, String saveSlot){
        this(file, saveSlot, () -> saveDirectory.child(saveSlot + "." + saveExtension));
    }

    ActionResearchReservations(Fi file, String saveSlot, Supplier<Fi> saveFile){
        this.file = file;
        this.saveSlot = saveSlot;
        this.saveFile = java.util.Objects.requireNonNull(saveFile);
    }

    synchronized RuntimePayloads.ResearchPrepareResult prepare(RuntimePayloads.ResearchPrepare request){
        try{
            ensureLoaded();
            Reservation existing = reservations.get(request.transactionId());
            if(existing != null){
                if(!same(existing.amounts, request.items())) throw new IllegalStateException("Research transaction was prepared with different item amounts");
                recover(existing);
                return new RuntimePayloads.ResearchPrepareResult(request.transactionId(), true, "", SectorSummaryCollector.collectStrategic());
            }
            if(!mindustry.Vars.game().state.isGame()) throw new IllegalStateException("Action world is not running");
            var core = mindustry.Vars.game().state.rules.defaultTeam.core();
            if(core == null) throw new IllegalStateException("Action has no campaign core");

            Reservation reservation = new Reservation();
            reservation.transactionId = request.transactionId();
            reservation.status = Status.preparing;
            for(ObjectMap.Entry<String, Integer> entry : request.items()){
                Item item = content.item(entry.key);
                if(item == null || entry.value <= 0) throw new IllegalArgumentException("Invalid research debit item: " + entry.key);
                int before = core.items.get(item);
                if(before < entry.value){
                    // The Coordinator plans from a strategic snapshot while the live Core remains available to normal
                    // gameplay. A factory/player consuming items in between is expected contention, not corruption.
                    return new RuntimePayloads.ResearchPrepareResult(request.transactionId(), false, true,
                        "Live Core inventory changed while preparing research", SectorSummaryCollector.collectStrategic());
                }
                reservation.amounts.put(item.name, entry.value);
                reservation.before.put(item.name, before);
                reservation.after.put(item.name, before - entry.value);
            }
            reservations.put(reservation.transactionId, reservation);
            persist();
            try{
                applyAfter(reservation);
                saveWorld();
                reservation.status = Status.prepared;
                persist();
            }catch(Throwable failure){
                applyBefore(reservation);
                try{ saveWorld(); }catch(Throwable ignored){}
                reservations.remove(reservation.transactionId);
                persist();
                throw failure;
            }
            return new RuntimePayloads.ResearchPrepareResult(request.transactionId(), true, "", SectorSummaryCollector.collectStrategic());
        }catch(Throwable error){
            return new RuntimePayloads.ResearchPrepareResult(request.transactionId(), false, error.getMessage() == null ? error.toString() : error.getMessage(), null);
        }
    }

    synchronized RuntimePayloads.ResearchDecisionResult decide(RuntimePayloads.ResearchDecision decision){
        try{
            ensureLoaded();
            Reservation reservation = reservations.get(decision.transactionId());
            if(reservation == null) return new RuntimePayloads.ResearchDecisionResult(decision.transactionId(), true, "");
            recover(reservation);
            if(!decision.commit()){
                var core = mindustry.Vars.game().state.rules.defaultTeam.core();
                if(core == null) throw new IllegalStateException("Action has no campaign core during research rollback");
                for(ObjectMap.Entry<String, Integer> entry : reservation.amounts){
                    Item item = content.item(entry.key);
                    if(item == null) throw new IllegalStateException("Missing item during research rollback: " + entry.key);
                    core.items.set(item, Math.addExact(core.items.get(item), entry.value));
                }
                saveWorld();
            }
            reservations.remove(reservation.transactionId);
            persist();
            return new RuntimePayloads.ResearchDecisionResult(decision.transactionId(), true, "");
        }catch(Throwable error){
            return new RuntimePayloads.ResearchDecisionResult(decision.transactionId(), false, error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    synchronized ObjectSet<String> pending(){
        ensureLoaded();
        ObjectSet<String> result = new ObjectSet<>();
        for(String id : reservations.keys()) result.add(id);
        return result;
    }

    synchronized void recoverAll(){
        ensureLoaded();
        for(Reservation reservation : reservations.values()) recover(reservation);
    }

    private void recover(Reservation reservation){
        if(!mindustry.Vars.game().state.isGame()) throw new IllegalStateException("Cannot recover research reservation without a loaded world");
        var core = mindustry.Vars.game().state.rules.defaultTeam.core();
        if(core == null) throw new IllegalStateException("Cannot recover research reservation without a core");
        boolean allBefore = true, allAfter = true;
        for(String name : reservation.amounts.keys()){
            Item item = content.item(name);
            if(item == null) throw new IllegalStateException("Missing reserved item: " + name);
            int current = core.items.get(item);
            allBefore &= current == reservation.before.get(name, Integer.MIN_VALUE);
            allAfter &= current == reservation.after.get(name, Integer.MIN_VALUE);
        }
        if(reservation.status == Status.preparing){
            if(allBefore){
                applyAfter(reservation);
                saveWorld();
                reservation.status = Status.prepared;
                persist();
            }else if(allAfter){
                reservation.status = Status.prepared;
                persist();
            }else{
                throw new IllegalStateException("Ambiguous inventory while recovering research transaction " + reservation.transactionId);
            }
        }else{
            // PREPARED was persisted only after saveWorld() completed. The current aggregate inventory is therefore
            // not a recovery oracle: production/consumption after PREPARE may equal either the old before/after value.
            // Keep the reservation pending until the coordinator replays COMMIT/ABORT.
        }
    }

    private void applyAfter(Reservation reservation){
        var core = mindustry.Vars.game().state.rules.defaultTeam.core();
        for(ObjectMap.Entry<String, Integer> entry : reservation.after){
            Item item = content.item(entry.key); if(item == null) throw new IllegalStateException("Missing reserved item: " + entry.key);
            core.items.set(item, entry.value);
        }
    }

    private void applyBefore(Reservation reservation){
        var core = mindustry.Vars.game().state.rules.defaultTeam.core();
        if(core == null) return;
        for(ObjectMap.Entry<String, Integer> entry : reservation.before){
            Item item = content.item(entry.key); if(item != null) core.items.set(item, entry.value);
        }
    }

    private void saveWorld(){ SaveIO.save(saveFile.get()); }

    private void ensureLoaded(){
        if(loaded) return;
        loaded = true;
        if(!file.exists()) return;
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(file.read()))){
            int foundMagic = in.readInt(), foundVersion = in.readInt();
            boolean clean = foundMagic == magic && foundVersion == version;
            boolean legacy = foundMagic == legacyMagic && foundVersion == legacyVersion;
            if(!clean && !legacy) throw new IOException("Unsupported research reservation file");
            int count = checked(in.readInt(), 100_000);
            for(int i = 0; i < count; i++){
                Reservation reservation = new Reservation();
                reservation.transactionId = in.readUTF();
                int ordinal = in.readInt(); if(ordinal < 0 || ordinal >= Status.values().length) throw new IOException("Invalid reservation status");
                reservation.status = Status.values()[ordinal];
                readMap(in, reservation.amounts); readMap(in, reservation.before); readMap(in, reservation.after);
                reservations.put(reservation.transactionId, reservation);
            }
            if(in.read() != -1) throw new IOException("Trailing research reservation data");
        }catch(IOException e){ throw new UncheckedIOException(e); }
    }

    private void persist(){
        file.parent().mkdirs();
        Path target = file.file().toPath(), temp = target.resolveSibling(target.getFileName() + ".tmp");
        try{
            if(reservations.isEmpty()){
                Files.deleteIfExists(temp); Files.deleteIfExists(target); forceParent(target); return;
            }
            try(FileOutputStream fos = new FileOutputStream(temp.toFile()); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))){
                out.writeInt(magic); out.writeInt(version); out.writeInt(reservations.size);
                Seq<String> ids = reservations.keys().toSeq().sort();
                for(String id : ids){
                    Reservation reservation = reservations.get(id);
                    out.writeUTF(id); out.writeInt(reservation.status.ordinal());
                    writeMap(out, reservation.amounts); writeMap(out, reservation.before); writeMap(out, reservation.after);
                }
                out.flush(); fos.getFD().sync();
            }
            try{ Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ignored){ Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            Path parent = target.toAbsolutePath().getParent();
            if(parent != null) try(FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)){ channel.force(true); }catch(Exception ignored){}
        }catch(IOException e){ throw new UncheckedIOException(e); }
    }

    private static boolean same(ObjectMap<String, Integer> a, ObjectMap<String, Integer> b){
        if(a.size != b.size) return false;
        for(ObjectMap.Entry<String, Integer> entry : a) if(!entry.value.equals(b.get(entry.key))) return false;
        return true;
    }
    private static void writeMap(DataOutput out, ObjectMap<String, Integer> values) throws IOException{
        Seq<String> keys = values.keys().toSeq().sort(); out.writeInt(keys.size);
        for(String key : keys){ out.writeUTF(key); out.writeInt(values.get(key)); }
    }
    private static void readMap(DataInput in, ObjectMap<String, Integer> values) throws IOException{
        int count = checked(in.readInt(), 100_000); for(int i = 0; i < count; i++) values.put(in.readUTF(), in.readInt());
    }
    private static int checked(int value, int max) throws IOException{ if(value < 0 || value > max) throw new IOException("Invalid reservation collection size"); return value; }

    private enum Status{preparing, prepared}
    private static class Reservation{
        String transactionId;
        Status status;
        ObjectMap<String, Integer> amounts = new ObjectMap<>(), before = new ObjectMap<>(), after = new ObjectMap<>();
    }

    /** Directory metadata is part of the transaction journal: a removed PREPARED sidecar must survive power loss. */
    private static void forceParent(Path target){
        Path parent=target.toAbsolutePath().getParent();
        if(parent!=null) try(FileChannel channel=FileChannel.open(parent,StandardOpenOption.READ)){channel.force(true);}catch(Exception ignored){}
    }

}
