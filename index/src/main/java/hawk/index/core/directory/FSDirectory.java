package hawk.index.core.directory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FSDirectory extends Directory{
    private final Path directoryPath;

    //创建索引文件夹
    public FSDirectory(Path path) throws IOException {
        if(!Files.isDirectory(path)){
            Files.createFile(path);
        }
        directoryPath = path.toRealPath();
    }

    public static FSDirectory open(Path path) throws IOException{
        return new FSDirectory(path);
    }

}
