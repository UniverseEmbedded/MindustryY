package mindustry.campaign.shared.io;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Crash-recoverable sibling-directory replacement used by Shared Campaign restore and host migration. */
public final class SharedDirectoryInstall{
    private static final int magic = 0x4d594449; // MYDI
    private static final int version = 1;
    private static final String markerPrefix = ".mindustry-y-install-";
    private static final String markerSuffix = ".txn";

    private SharedDirectoryInstall(){}

    public static void install(Path source, Path target) throws IOException{
        Path src = source.toAbsolutePath().normalize();
        Path dst = target.toAbsolutePath().normalize();
        Path parent = dst.getParent();
        if(parent == null || src.getParent() == null || !src.getParent().equals(parent))
            throw new IOException("Shared Campaign directory install requires sibling source/target directories");
        Files.createDirectories(parent);
        recoverParent(parent);
        String sourceName = leaf(src), targetName = leaf(dst);
        String backupName = "." + targetName + ".previous-" + UUID.randomUUID();
        Path backup = parent.resolve(backupName);
        Path marker = parent.resolve(markerPrefix + UUID.randomUUID() + markerSuffix);
        writeMarker(marker, sourceName, targetName, backupName);
        try{
            if(Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) move(dst, backup);
            move(src, dst);
            forceDirectory(parent);
            deleteTree(backup);
            Files.deleteIfExists(marker);
            forceDirectory(parent);
        }catch(Throwable failure){
            // Leave marker plus whichever source/backup/target state exists. Recovery is deterministic and also runs
            // immediately here, but the durable marker is what makes an actual process crash recoverable.
            try{ recoverMarker(marker); }catch(Throwable ignored){}
            if(failure instanceof IOException io) throw io;
            throw new IOException("Failed to install Shared Campaign directory", failure);
        }
    }

    /** Recovers every interrupted install transaction in one campaign-storage parent directory. */
    public static void recoverParent(Path parent) throws IOException{
        Path root = parent.toAbsolutePath().normalize();
        if(!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(root, markerPrefix + "*" + markerSuffix)){
            for(Path marker : stream) recoverMarker(marker);
        }
    }

    private static void recoverMarker(Path marker) throws IOException{
        Marker txn = readMarker(marker);
        Path parent = marker.toAbsolutePath().normalize().getParent();
        Path source = safeChild(parent, txn.sourceName);
        Path target = safeChild(parent, txn.targetName);
        Path backup = safeChild(parent, txn.backupName);

        if(Files.exists(target, LinkOption.NOFOLLOW_LINKS)){
            // Target installed: commit cleanup. Source may be an orphan only if a filesystem violated move semantics.
            if(Files.exists(source, LinkOption.NOFOLLOW_LINKS)) deleteTree(source);
            deleteTree(backup);
        }else if(Files.exists(source, LinkOption.NOFOLLOW_LINKS)){
            // Intent was durable before replacement started; complete it rather than silently rolling back a verified restore.
            move(source, target);
            deleteTree(backup);
        }else if(Files.exists(backup, LinkOption.NOFOLLOW_LINKS)){
            // Source vanished before install completed; restore the previous authority/data directory.
            move(backup, target);
        }
        Files.deleteIfExists(marker);
        forceDirectory(parent);
    }

    private static void writeMarker(Path marker, String source, String target, String backup) throws IOException{
        try(FileChannel channel = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel))){
            out.writeInt(magic); out.writeInt(version); out.writeUTF(source); out.writeUTF(target); out.writeUTF(backup); out.flush();
            channel.force(true);
        }
        forceDirectory(marker.toAbsolutePath().getParent());
    }

    private static Marker readMarker(Path marker) throws IOException{
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(marker)))){
            if(in.readInt() != magic || in.readInt() != version) throw new IOException("Invalid Shared Campaign install marker: " + marker);
            Marker result = new Marker(in.readUTF(), in.readUTF(), in.readUTF());
            if(in.read() != -1) throw new IOException("Trailing Shared Campaign install marker data");
            validateLeaf(result.sourceName); validateLeaf(result.targetName); validateLeaf(result.backupName);
            return result;
        }
    }

    private static String leaf(Path path) throws IOException{
        Path name = path.getFileName();
        if(name == null) throw new IOException("Directory install path has no file name: " + path);
        String value = name.toString(); validateLeaf(value); return value;
    }

    private static void validateLeaf(String value) throws IOException{
        if(value == null || value.isBlank() || value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\") || value.indexOf('\0') >= 0)
            throw new IOException("Unsafe Shared Campaign install marker path");
    }

    private static Path safeChild(Path parent, String name) throws IOException{
        validateLeaf(name);
        Path child = parent.resolve(name).normalize();
        if(!child.getParent().equals(parent)) throw new IOException("Shared Campaign install marker escapes parent");
        return child;
    }

    private static void move(Path source, Path target) throws IOException{
        try{ Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException ignored){ Files.move(source, target); }
    }

    private static void deleteTree(Path root) throws IOException{
        if(root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if(Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)){ Files.deleteIfExists(root); return; }
        try(var paths = Files.walk(root)){
            for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void forceDirectory(Path directory){
        try(FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)){ channel.force(true); }
        catch(Exception ignored){}
    }

    private record Marker(String sourceName, String targetName, String backupName){}
}
