package directory;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class DeletedIdsStore {

    public static final String FILE_NAME = "deleted.ids";

    public static HashSet<Long> load(Path dir) throws IOException {
        Path path = dir.resolve(FILE_NAME);
        HashSet<Long> deletedIds = new HashSet<>();
        if (!Files.exists(path)) {
            return deletedIds;
        }
        try (FileChannel fc = new RandomAccessFile(path.toFile(), "r").getChannel()) {
            int size = (int) fc.size();
            if (size < 4) {
                return deletedIds;
            }
            ByteBuffer buffer = ByteBuffer.allocate(size);
            fc.read(buffer);
            buffer.flip();
            int count = buffer.getInt();
            for (int i = 0; i < count; i++) {
                deletedIds.add(buffer.getLong());
            }
        }
        return deletedIds;
    }

    public static void save(Path dir, Set<Long> deletedUniqueIds) throws IOException {
        Path path = dir.resolve(FILE_NAME);
        List<Long> ids = new ArrayList<>(deletedUniqueIds);
        Collections.sort(ids);
        int byteSize = 4 + ids.size() * 8;
        ByteBuffer buffer = ByteBuffer.allocate(byteSize);
        buffer.putInt(ids.size());
        for (Long id : ids) {
            buffer.putLong(id);
        }
        buffer.flip();
        try (FileChannel fc = new RandomAccessFile(path.toFile(), "rw").getChannel()) {
            fc.truncate(0);
            fc.write(buffer);
            fc.force(false);
        }
        log.info("saved deleted.ids with {} entries", ids.size());
    }
}
