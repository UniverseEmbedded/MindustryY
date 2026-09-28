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

/** Crash-recoverable credits applied to a live action by the campaign transport coordinator. */
final class ActionTransportReservations{
    private static final int magic = 0x53435452;
    private static final int version = 2;
    private static final int legacyMagic = 0x4d595452;
    private static final int legacyVersion = 1;
    private final Fi file;
    private final String saveSlot;
    private final Supplier<Fi> saveFile;
    private final ObjectMap<String, Reservation> reservations = new ObjectMap<>();
    private boolean loaded;

    ActionTransportReservations(Fi file, String saveSlot){
        this(file, saveSlot, () -> saveDirectory.child(saveSlot + "." + saveExtension));
    }

    ActionTransportReservations(Fi file, String saveSlot, Supplier<Fi> saveFile){
        this.file = file;
        this.saveSlot = saveSlot;
        this.saveFile = java.util.Objects.requireNonNull(saveFile);
    }

    synchronized RuntimePayloads.TransportPrepareResult prepare(RuntimePayloads.TransportPrepare request){
        try{
            ensureLoaded();
            if(request.amount() <= 0) throw new IllegalArgumentException("Transport credit must be positive");
            Reservation existing = reservations.get(request.transactionId());
            if(existing != null){
                if(!existing.itemName.equals(request.itemName()) || existing.requested != request.amount()) throw new IllegalStateException("Transport transaction payload changed after preparation");
                recover(existing);
                return new RuntimePayloads.TransportPrepareResult(request.transactionId(), true, "", existing.applied, SectorSummaryCollector.collectStrategic());
            }
            if(!mindustry.Vars.game().state.isGame()) throw new IllegalStateException("Action world is not running");
            var core = mindustry.Vars.game().state.rules.defaultTeam.core();
            if(core == null) throw new IllegalStateException("Action has no campaign core");
            Item item = content.item(request.itemName());
            if(item == null) throw new IllegalArgumentException("Unknown transport item: " + request.itemName());

            Reservation reservation = new Reservation();
            reservation.transactionId = request.transactionId();
            reservation.itemName = item.name;
            reservation.requested = request.amount();
            reservation.before = core.items.get(item);
            reservation.applied = Math.min(request.amount(), Math.max(0, core.storageCapacity - reservation.before));
            reservation.after = reservation.before + reservation.applied;
            reservation.status = Status.preparing;
            reservations.put(reservation.transactionId, reservation);
            persist();
            try{
                core.items.set(item, reservation.after);
                applyImportedMetadata(item, reservation.applied);
                saveWorld();
                reservation.status = Status.prepared;
                persist();
            }catch(Throwable failure){
                core.items.set(item, reservation.before);
                applyImportedMetadata(item, -reservation.applied);
                try{ saveWorld(); }catch(Throwable ignored){}
                reservations.remove(reservation.transactionId);
                persist();
                throw failure;
            }
            return new RuntimePayloads.TransportPrepareResult(request.transactionId(), true, "", reservation.applied, SectorSummaryCollector.collectStrategic());
        }catch(Throwable error){
            return new RuntimePayloads.TransportPrepareResult(request.transactionId(), false, error.getMessage() == null ? error.toString() : error.getMessage(), 0, null);
        }
    }

    synchronized RuntimePayloads.TransportDecisionResult decide(RuntimePayloads.TransportDecision decision){
        try{
            ensureLoaded();
            Reservation reservation = reservations.get(decision.transactionId());
            if(reservation == null) return new RuntimePayloads.TransportDecisionResult(decision.transactionId(), true, "");
            recover(reservation);
            if(!decision.commit() && reservation.applied > 0){
                var core = mindustry.Vars.game().state.rules.defaultTeam.core();
                Item item = content.item(reservation.itemName);
                if(core == null || item == null) throw new IllegalStateException("Cannot roll back transport credit");
                int current = core.items.get(item);
                if(current < reservation.applied) throw new IllegalStateException("Transport credit was consumed before rollback; coordinator must recover and commit it");
                core.items.set(item, current - reservation.applied);
                applyImportedMetadata(item, -reservation.applied);
                saveWorld();
            }
            reservations.remove(reservation.transactionId);
            persist();
            return new RuntimePayloads.TransportDecisionResult(decision.transactionId(), true, "");
        }catch(Throwable error){
            return new RuntimePayloads.TransportDecisionResult(decision.transactionId(), false, error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    synchronized ObjectSet<String> pending(){
        ensureLoaded();
        ObjectSet<String> result = new ObjectSet<>();
        for(String transactionId : reservations.keys()) result.add(transactionId);
        return result;
    }
    synchronized void recoverAll(){ ensureLoaded(); for(Reservation reservation : reservations.values()) recover(reservation); }

    private void recover(Reservation reservation){
        if(!mindustry.Vars.game().state.isGame()) throw new IllegalStateException("Cannot recover transport reservation without a loaded world");
        var core = mindustry.Vars.game().state.rules.defaultTeam.core();
        Item item = content.item(reservation.itemName);
        if(core == null || item == null) throw new IllegalStateException("Cannot recover transport reservation item " + reservation.itemName);
        int current = core.items.get(item);
        if(reservation.status == Status.preparing){
            if(current == reservation.before){
                core.items.set(item, reservation.after);
                applyImportedMetadata(item, reservation.applied);
                saveWorld(); reservation.status = Status.prepared; persist();
            }else if(current == reservation.after){
                reservation.status = Status.prepared; persist();
            }else{
                throw new IllegalStateException("Ambiguous inventory while recovering transport transaction " + reservation.transactionId);
            }
        }else{
            // PREPARED is fsynced only after saveWorld() succeeds. Current inventory may legitimately return to the
            // numeric pre-credit value before the coordinator decision is replayed, so equality is not rollback proof.
        }
    }

    private void applyImportedMetadata(Item item, int amount){
        if(amount == 0 || mindustry.Vars.game().state.rules.sector == null) return;
        mindustry.Vars.game().state.rules.sector.info().lastImported.add(item, amount);
        mindustry.Vars.game().state.rules.sector.info().lastImported.checkNegative();
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
            if(!clean && !legacy) throw new IOException("Unsupported transport reservation file");
            int count = checked(in.readInt());
            for(int i = 0; i < count; i++){
                Reservation reservation = new Reservation();
                reservation.transactionId = in.readUTF(); reservation.itemName = in.readUTF(); reservation.requested = in.readInt(); reservation.applied = in.readInt();
                reservation.before = in.readInt(); reservation.after = in.readInt();
                int status = in.readInt(); if(status < 0 || status >= Status.values().length) throw new IOException("Invalid transport reservation status");
                reservation.status = Status.values()[status]; reservations.put(reservation.transactionId, reservation);
            }
            if(in.read() != -1) throw new IOException("Trailing transport reservation data");
        }catch(IOException e){ throw new UncheckedIOException(e); }
    }

    private void persist(){
        file.parent().mkdirs();
        Path target = file.file().toPath(), temp = target.resolveSibling(target.getFileName() + ".tmp");
        try{
            if(reservations.isEmpty()){ Files.deleteIfExists(temp); Files.deleteIfExists(target); forceParent(target); return; }
            try(FileOutputStream fos = new FileOutputStream(temp.toFile()); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))){
                out.writeInt(magic); out.writeInt(version); out.writeInt(reservations.size);
                for(String id : reservations.keys().toSeq().sort()){
                    Reservation r = reservations.get(id);
                    out.writeUTF(r.transactionId); out.writeUTF(r.itemName); out.writeInt(r.requested); out.writeInt(r.applied); out.writeInt(r.before); out.writeInt(r.after); out.writeInt(r.status.ordinal());
                }
                out.flush(); fos.getFD().sync();
            }
            try{ Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ignored){ Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            Path parent = target.toAbsolutePath().getParent();
            if(parent != null) try(FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)){ channel.force(true); }catch(Exception ignored){}
        }catch(IOException e){ throw new UncheckedIOException(e); }
    }

    private static int checked(int value) throws IOException{ if(value < 0 || value > 100_000) throw new IOException("Invalid transport reservation count"); return value; }
    private enum Status{preparing, prepared}
    private static class Reservation{ String transactionId = "", itemName = ""; int requested, applied, before, after; Status status; }

    /** Directory metadata is part of the transaction journal: a removed PREPARED sidecar must survive power loss. */
    private static void forceParent(Path target){
        Path parent=target.toAbsolutePath().getParent();
        if(parent!=null) try(FileChannel channel=FileChannel.open(parent,StandardOpenOption.READ)){channel.force(true);}catch(Exception ignored){}
    }

}
