package hawk.index.core.directory;


import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;


@Slf4j
public class FSDirectory extends Directory{

    //创建索引文件夹
    public FSDirectory(Path path) {
        //create index folder directory
        if(!Files.isDirectory(path)){
            createDirectory(path);
        }
        this.path = path;
        this.files = new ArrayList<>();
        File folder = path.toFile();
        File[] fileList= folder.listFiles();
        for (int i = 0; i < fileList.length; i++) {
            files.add(fileList[i].toPath().toString());
        }

        this.segmentInfo = new SegmentInfo(path.toAbsolutePath(), files);
    }

    public static FSDirectory open(Path path) throws IOException{
        return new FSDirectory(path);
    }

    @Override
    public void updateSegInfo(int lastDocID, int segCountInc) {
        segmentInfo.update(lastDocID, segCountInc);
    }

    @Override
    public String[] generateSegFiles() {
        int segCount = segmentInfo.getSegCount();
        int curSeg = segCount + 1;
        String prfix = path.toString() + "/";
        String[] fileNames = new String[]{prfix + curSeg + ".fdt", prfix + curSeg + ".fdx",
                prfix + curSeg + ".tim", prfix + curSeg + ".frq", prfix +curSeg + ".fdm"};
        for (int i = 0; i < fileNames.length; i++) {
            Path path = Paths.get(fileNames[i]);
            createFile(path);
            this.files.add(fileNames[i]);
        }
        return fileNames;
    }
}
