package directory;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

@Slf4j
public class LiveDocsStore {

    public static final String FILE_NAME = "live.docs";

    public static BitSet load(Path dir, int preMaxID) throws IOException {
        Path path = dir.resolve(FILE_NAME);
        if (!Files.exists(path)) {
            return defaultLiveDocs(preMaxID);
        }
        try (FileChannel fc = new RandomAccessFile(path.toFile(), "r").getChannel()) {
            int size = (int) fc.size();
            if (size < 8) {
                return defaultLiveDocs(preMaxID);
            }
            ByteBuffer buffer = ByteBuffer.allocate(size);
            fc.read(buffer);
            buffer.flip();
            int maxDocID = buffer.getInt();
            int longCount = buffer.getInt();
            long[] words = new long[longCount];
            for (int i = 0; i < longCount; i++) {
                words[i] = buffer.getLong();
            }
            BitSet liveDocs = BitSet.valueOf(words);
            return liveDocs;
        }
    }

    public static BitSet defaultLiveDocs(int preMaxID) {
        BitSet liveDocs = new BitSet(preMaxID + 1);
        if (preMaxID > 0) {
            liveDocs.set(1, preMaxID + 1);
        }
        return liveDocs;
    }

    public static void save(Path dir, BitSet liveDocs) throws IOException {
        Path path = dir.resolve(FILE_NAME);
        int maxDocID = liveDocs.length() > 0 ? liveDocs.length() - 1 : 0;
        long[] words = liveDocs.toLongArray();
        int byteSize = 8 + words.length * 8;
        ByteBuffer buffer = ByteBuffer.allocate(byteSize);
        buffer.putInt(maxDocID);
        buffer.putInt(words.length);
        for (long word : words) {
            buffer.putLong(word);
        }
        buffer.flip();
        try (FileChannel fc = new RandomAccessFile(path.toFile(), "rw").getChannel()) {
            fc.truncate(0);
            fc.write(buffer);
            fc.force(false);
        }
        log.info("saved live.docs, maxDocID={}, alive={}", maxDocID, liveDocs.cardinality());
    }
}
