package hawk.index.core.directory;
import java.io.IOException;
import java.nio.file.Path;

public class MMapDirectory extends FSDirectory {

    public static final int DEFAULT_MAX_CHUNK_SIZE = Constants.JRE_IS_64BIT ? (1 << 30) : (1 << 28);

    private final int chunkSizePower;

    public MMapDirectory(Path path) throws IOException {
        super(path);
        if (DEFAULT_MAX_CHUNK_SIZE <= 0) {
            throw new IllegalArgumentException("Maximum chunk size for mmap must be >0");
        }
        this.chunkSizePower = 31 - Integer.numberOfLeadingZeros(DEFAULT_MAX_CHUNK_SIZE);
        assert this.chunkSizePower >= 0 && this.chunkSizePower <= 30;
    }

    public static void main(String[] args) {
        System.out.println(31 - Integer.numberOfTrailingZeros(1 << 28));
    }
}
