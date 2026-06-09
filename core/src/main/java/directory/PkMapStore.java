package directory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PkMapStore {

    public static final String FILE_NAME = "pk.map";

    public static Map<Long, Integer> load(Path dir) throws IOException {
        Path path = dir.resolve(FILE_NAME);
        if (!Files.exists(path)) {
            return new HashMap<>();
        }
        Map<Long, Integer> map = new HashMap<>();
        try (FileChannel fc = new RandomAccessFile(path.toFile(), "r").getChannel()) {
            int size = (int) fc.size();
            if (size < 4) {
                return map;
            }
            ByteBuffer buffer = ByteBuffer.allocate(size);
            fc.read(buffer);
            buffer.flip();
            int count = buffer.getInt();
            for (int i = 0; i < count; i++) {
                long uniqueID = buffer.getLong();
                int docID = buffer.getInt();
                map.put(uniqueID, docID);
            }
        }
        return map;
    }

    public static void save(Path dir, Map<Long, Integer> map) throws IOException {
        Path path = dir.resolve(FILE_NAME);
        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(map.entrySet());
        Collections.sort(entries, Comparator.comparingLong(Map.Entry::getKey));
        int byteSize = 4 + entries.size() * 12;
        ByteBuffer buffer = ByteBuffer.allocate(byteSize);
        buffer.putInt(entries.size());
        for (Map.Entry<Long, Integer> entry : entries) {
            buffer.putLong(entry.getKey());
            buffer.putInt(entry.getValue());
        }
        buffer.flip();
        try (FileChannel fc = new RandomAccessFile(path.toFile(), "rw").getChannel()) {
            fc.truncate(0);
            fc.write(buffer);
            fc.force(false);
        }
    }
}
