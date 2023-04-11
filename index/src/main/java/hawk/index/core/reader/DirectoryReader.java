package hawk.index.core.reader;

import hawk.index.core.directory.Directory;
import hawk.index.core.directory.MMapDirectory;

public class DirectoryReader {

    public static DirectoryReader open(Directory directory){
        if(directory instanceof MMapDirectory){
            return new MMapDirectoryReader(directory);
        }
        return null;
    }
}
