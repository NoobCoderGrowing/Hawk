package hawk.index.core.directory;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Set;

@Slf4j
@Data
public abstract class Directory {

    public Path path;

    public ArrayList<String> files;

    public SegmentInfo segmentInfo;

    public abstract void updateSegInfo(int lastDodID, int segCountInc);

    // fdt, fdx, tim, frq
    public abstract String[] generateSegFiles();

    public abstract String generateSegFile(String fileName);


    public static Path createDirectory(Path path){
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxrwxrwx");
        Path fp = null;
        try {
            fp = Files.createDirectory(path);
            Files.setPosixFilePermissions(fp, perms);
        } catch (IOException e) {
            log.error("create file " + fp.toString() + "failed");
            System.exit(1);
        }
        return fp;
    }

    public static Path createFile(Path path){
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxrwxrwx");
        Path fp = null;
        try {
            fp = Files.createFile(path);
            Files.setPosixFilePermissions(fp, perms);
        } catch (IOException e) {
            log.error("create file " + fp.toString() + "failed");
            System.exit(1);
        }
        return fp;
    }
}
