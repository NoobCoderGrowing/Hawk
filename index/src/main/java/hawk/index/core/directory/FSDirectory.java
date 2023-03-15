package hawk.index.core.directory;


import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


@Slf4j
public class FSDirectory extends Directory{

    private SegmentInfo segmentInfo;

    //创建索引文件夹
    public FSDirectory(Path path) {
        //create index folder directory
        if(!Files.isDirectory(path)){
            try {
                Files.createDirectory(path);
            }catch (IOException e){
                log.error("create directory failed");
            }
        }
        // set index folder permission
        String strPath = path.toString();
        String cmdString = "chmod 777 " + strPath;
        try {
            Process process = Runtime.getRuntime().exec(cmdString);
            int exitVal = -1;
            exitVal = process.waitFor();
            if(exitVal != 0){
                log.error("set index folder permission failed");
                System.exit(1);
            }
        }catch (InterruptedException e){
            log.error("wait for file permission setting failed");
        }catch (IOException e){
            log.error("set file permission generate IOException");
        }
        //segment.info file's representation
        segmentInfo = new SegmentInfo(path.toAbsolutePath());
    }

    public static FSDirectory open(Path path) throws IOException{
        return new FSDirectory(path);
    }

}
