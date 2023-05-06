package directory;


import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;


@Slf4j
public class FSDirectory extends Directory{

    //创建索引文件夹
    public FSDirectory(Path path) {
        //create index folder directory
        if(!Files.isDirectory(path)){
            createDirectory(path);
        }
        this.path = path;
        this.files = new HashMap<>();
        File folder = path.toFile();
        File[] fileList= folder.listFiles();
        for (int i = 0; i < fileList.length; i++) {
            String filename = fileList[i].getName();
            Path filePath = fileList[i].toPath();
            files.put(filename, filePath);
        }
        if(files.containsKey("segment.info")){
            Path segmentInfoPath = files.get("segment.info");
            this.segmentInfo = new SegmentInfo(segmentInfoPath);
        }else{
            this.segmentInfo = new SegmentInfo();
        }
    }

    public static FSDirectory open(Path path) throws IOException{
        return new FSDirectory(path);
    }

    @Override
    public void updateSegInfo(int lastDocID, int segCountInc) {
        segmentInfo.update(lastDocID, segCountInc);
    }

    @Override
    public Path[] generateSegFiles() {
        int segCount = segmentInfo.getSegCount();
        int curSeg = segCount + 1;
        String prefix = path.toString() + "/";
        String[] fileNames = new String[]{".fdt",".fdx", ".tim",".frq",".fdm"};
        Path[] filePaths = new Path[5];
        for (int i = 0; i < fileNames.length; i++) {
            String fileName = Integer.toString(curSeg).concat(fileNames[i]);
            String filePath = prefix.concat(fileName);
            Path path = Paths.get(filePath);
            filePaths[i] = path;
            createFile(path);
            this.files.put(fileName, path);
        }
        if(segmentInfo.getSegmentInfoPath() == null){
            String segmentInfoPath = prefix.concat("segment.info");
            Path path = Paths.get(segmentInfoPath);
            createFile(path);
            segmentInfo.setSegmentInfoPath(path);
            this.files.put("segment.info", path);
        }

        return filePaths;
    }

    @Override
    public String generateSegFile(String fileName) {
        String prfix = path.toString() + "/";
        String filePath = prfix.concat(fileName);
        Path path = Paths.get(filePath);
        createFile(path);
        this.files.put(fileName, path);
        return filePath;
    }
}
