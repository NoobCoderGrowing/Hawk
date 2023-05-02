package directory;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

@Slf4j
@Data
public abstract class Directory {

    public Path path;

    public HashMap<String, Path> files;

    public SegmentInfo segmentInfo;

    public abstract void updateSegInfo(int lastDodID, int segCountInc);

    // fdt, fdx, tim, frq
    public abstract Path[] generateSegFiles();

    public abstract String generateSegFile(String fileName);


    public static Path createDirectory(Path path){
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxrwxrwx");
        Path fp = null;
        try {
            fp = Files.createDirectories(path);
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

    public void cleanDir(){
        for (Map.Entry<String, Path> entry: files.entrySet()) {
            Path path = entry.getValue();
            try {
                Files.delete(path);
            } catch (IOException e) {
                log.error("delete file " + path.toString() + " failed during clean dir");
                System.exit(1);
            }
        }
        files.clear();
    }

//    public  void cleanDir(){
//        Iterator<String> it = files.iterator();
//        while(it.hasNext()){
//            Path file = Paths.get(it.next());
//            try {
//                Files.delete(file);
//                it.remove();
//            } catch (IOException e) {
//                log.error("delete file " + file.toString() + " failed during clean dir");
//                System.exit(1);
//            }
//        }
//    }
}
