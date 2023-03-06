package hawk.index.core.directory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FSDirectory {
    private final Path directory;

    //创建索引文件夹
    public FSDirectory(Path path) throws IOException {
        if(!Files.isDirectory(path)){
            Files.createFile(path);
        }
        directory = path.toRealPath();
    }

    public static FSDirectory open(Path path) throws IOException{
        if(Constants.JRE_IS_64BIT){
            return new MMapDirectory(path);
        }
        return null;
    }

}
