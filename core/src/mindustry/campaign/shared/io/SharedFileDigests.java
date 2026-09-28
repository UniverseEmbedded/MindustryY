package mindustry.campaign.shared.io;

import arc.files.*;

import java.io.*;
import java.security.*;
import java.util.*;

/** Streaming digest helpers for potentially large Shared Campaign saves/archives. */
public final class SharedFileDigests{
    private SharedFileDigests(){}

    public static String sha256(Fi file){
        Objects.requireNonNull(file, "file");
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try(InputStream input = new BufferedInputStream(file.read())){
                byte[] buffer = new byte[64 * 1024];
                for(int read; (read = input.read(buffer)) >= 0; ) if(read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){
            throw new AssertionError(impossible);
        }catch(IOException error){
            throw new UncheckedIOException(error);
        }
    }
}
