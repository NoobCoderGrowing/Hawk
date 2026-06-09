package hawk.recall.admin;

import directory.Directory;
import directory.LiveDocsStore;
import hawk.recall.reader.DirectoryReader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.BitSet;
import java.util.Map;

@Slf4j
public class IndexAdmin {

    private final DirectoryReader directoryReader;

    private final Directory directory;

    private final Map<Long, Integer> pkMap;

    private final BitSet liveDocs;

    public IndexAdmin(DirectoryReader directoryReader, Directory directory) {
        this.directoryReader = directoryReader;
        this.directory = directory;
        this.pkMap = directoryReader.getPkMap();
        this.liveDocs = directoryReader.getLiveDocs();
    }

    public boolean deleteDoc(long uniqueID) {
        Integer docID = pkMap.get(uniqueID);
        if (docID == null) {
            return false;
        }
        if (!liveDocs.get(docID)) {
            return false;
        }
        liveDocs.clear(docID);
        try {
            LiveDocsStore.save(directory.getPath(), liveDocs);
        } catch (IOException e) {
            throw new RuntimeException("failed to save live.docs", e);
        }
        log.info("deleted doc uniqueID={}, docID={}", uniqueID, docID);
        return true;
    }
}
