package hawk.index.core.directory;


import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;


@Slf4j
public class FSDirectory extends Directory{

    private Path path;

    private Set<String> files;

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
        this.path = path;
        this.files = new HashSet<>();
        File folder = path.toFile();
        File[] fileList= folder.listFiles();
        for (int i = 0; i < fileList.length; i++) {
            files.add(fileList[i].getName());
        }

        segmentInfo = new SegmentInfo(path.toAbsolutePath(), files);
    }

    public static FSDirectory open(Path path) throws IOException{
        return new FSDirectory(path);
    }

    @Override
    public void updateSegInfo() {
        segmentInfo.update();
    }

    @Override
    public int getDocBase() {
        return segmentInfo.getPreMaxID();
    }

    @Override
    public String[] generateSegFiles() {
        int segCount = segmentInfo.getSegCount();
        int curSeg = segCount + 1;
        segmentInfo.setSegCount(curSeg);
        String prfix = path.toString();
        String[] fileNames = new String[]{prfix + curSeg + ".fdt", prfix + curSeg + ".fdx", prfix + curSeg + ".tim",
                prfix + curSeg + ".frq"};
        // create files
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxrwxrwx");
        for (int i = 0; i < fileNames.length; i++) {
            Path path = Paths.get(fileNames[i]);
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(perms));
                this.files.add(path.getFileName().toString());
            } catch (IOException e) {
                log.error("create segment files failed");
                System.exit(1);
            }

        }
        return fileNames;
    }
}
