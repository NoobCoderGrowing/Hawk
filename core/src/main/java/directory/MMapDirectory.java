package directory;
import java.nio.file.Path;

public class MMapDirectory extends FSDirectory {

    public MMapDirectory(Path path) {
        super(path);
    }

    public static MMapDirectory open(Path path) {
        return new MMapDirectory(path);
    }
}
