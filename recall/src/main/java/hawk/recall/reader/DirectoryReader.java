package hawk.recall.reader;

import directory.Directory;
import directory.MMapDirectory;
import io.github.noobcodergrowing.JFST.FST;
import util.NumericTrie;
import common.Pair;

import java.nio.MappedByteBuffer;
import java.util.HashMap;
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

    public abstract HashMap<String, NumericTrie> getNumericTrieMap();

    public abstract HashMap<String, Pair<byte[], Float>> getFDMMap();

    public abstract TreeMap<Integer, byte[]> getFDXMap();

    public abstract int getTotalDoc();

    public abstract void close();
}
