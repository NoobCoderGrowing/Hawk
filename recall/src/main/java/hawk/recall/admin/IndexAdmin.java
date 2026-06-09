package hawk.recall.admin;

import directory.DeletedIdsStore;
import directory.Directory;
import hawk.recall.reader.DirectoryReader;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Slf4j
@Data
public class IndexAdmin {

    private final DirectoryReader directoryReader;

    private final Directory directory;

    private final Map<Long, Integer> pkMap;

    private final Set<Long> deletedUniqueIds;

    public IndexAdmin(DirectoryReader directoryReader, Directory directory) {
        this.directoryReader = directoryReader;
        this.directory = directory;
        this.pkMap = directoryReader.getPkMap();
        this.deletedUniqueIds = directoryReader.getDeletedUniqueIds();
    }

    public boolean deleteDoc(long uniqueID) {
        if (!pkMap.containsKey(uniqueID)) {
            return false;
        }
        if (!deletedUniqueIds.add(uniqueID)) {
            return false;
        }
        try {
            DeletedIdsStore.save(directory.getPath(), deletedUniqueIds);
        } catch (IOException e) {
            throw new RuntimeException("failed to save deleted.ids", e);
        }
        log.info("deleted doc uniqueID={}, docID={}", uniqueID, pkMap.get(uniqueID));
        return true;
    }
}
