package hawk.index.core.directory;
import java.io.IOException;
import java.nio.file.Path;

public class MMapDirectory extends FSDirectory {

    public MMapDirectory(Path path) throws IOException {
        super(path);
    }

    public static MMapDirectory open(Path path) throws IOException{
        return new MMapDirectory(path);
    }

    public static void main(String[] args) {
        System.out.println(31 - Integer.numberOfTrailingZeros(1 << 28));
    }
}
