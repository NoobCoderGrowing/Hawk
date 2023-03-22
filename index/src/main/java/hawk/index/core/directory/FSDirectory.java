package hawk.index.core.directory;


import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;


@Slf4j
public class FSDirectory extends Directory{

    private SegmentInfo segmentInfo;

    //创建索引文件夹
    public FSDirectory(Path path) {
        //create index folder directory
        if(!Files.isDirectory(path)){
            try {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxrwxrwx");
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(perms));
            }catch (IOException e){
                log.error("create directory with permissions failed");
            }
        }
        segmentInfo = new SegmentInfo(path.toAbsolutePath());
    }

    public static FSDirectory open(Path path) throws IOException{
        return new FSDirectory(path);
    }

    @Override
    public void updateSegInfo() {

    }
}
