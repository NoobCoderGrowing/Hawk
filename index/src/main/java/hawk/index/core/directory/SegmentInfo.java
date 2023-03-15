package hawk.index.core.directory;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


@Slf4j
public class SegmentInfo {

    private Path segmentInfoPath;

    public SegmentInfo(Path directoryPath){
        File folder = directoryPath.toFile();
        File[] files = folder.listFiles();
        if(files == null || files.length == 0){
            String strPath = directoryPath.toString() + "/segment.info";
            Path path = Paths.get(strPath);
            try {
                Files.createFile(path);
            } catch (IOException e) {
                log.error("create segment.info file failed");
                System.exit(1);
            }
        }
    }
}
