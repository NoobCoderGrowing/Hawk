package hawk.recall.reader;

import directory.Directory;
import directory.MMapDirectory;
import io.github.noobcodergrowing.JFST.FST;
import util.bkd.BkdReader;
import common.Pair;

import java.nio.MappedByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public abstract class DirectoryReader {

    public static DirectoryReader open(Directory directory){
        if(directory instanceof MMapDirectory){
            return new MMapDirectoryReader(directory);
        }
        return null;
    }

    public abstract FST getTermFST();

    public abstract MappedByteBuffer getFRQBuffer();

    public abstract MappedByteBuffer getFDTBuffer();

    public abstract HashMap<String, BkdReader> getBkdReaders();

    public abstract HashMap<String, Pair<byte[], Float>> getFDMMap();

    public abstract TreeMap<Integer, byte[]> getFDXMap();

    public abstract int getTotalDoc();

    public abstract boolean isLive(int docID);

    public abstract boolean hasDeletions();

    public abstract int numDocs();

    public abstract Map<Long, Integer> getPkMap();

    public abstract Set<Long> getDeletedUniqueIds();

    public abstract Directory getDirectory();

    public abstract void close();
}
