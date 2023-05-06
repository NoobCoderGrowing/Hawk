package directory;
import java.nio.file.Path;

public class MMapDirectory extends FSDirectory {

    public MMapDirectory(Path path) {
        super(path);
    }

    public static MMapDirectory open(Path path) {
        return new MMapDirectory(path);
    }

    public static void main(String[] args) {
        System.out.println(31 - Integer.numberOfTrailingZeros(1 << 28));
    }
}
