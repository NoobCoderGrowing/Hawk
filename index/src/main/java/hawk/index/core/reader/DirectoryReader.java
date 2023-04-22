package hawk.index.core.reader;

import hawk.index.core.directory.Directory;
import hawk.index.core.directory.MMapDirectory;
import hawk.index.core.util.NumericTrie;
import hawk.index.core.writer.Pair;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.FST;

import java.nio.MappedByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

public abstract class DirectoryReader {

    public static DirectoryReader open(Directory directory){
        if(directory instanceof MMapDirectory){
            return new MMapDirectoryReader(directory);
        }
        return null;
    }

    public abstract FST<BytesRef> getTermFST();

    public abstract MappedByteBuffer getFRQBuffer();

    public abstract MappedByteBuffer getFDTBuffer();

    public abstract HashMap<String, NumericTrie> getNumericTrieMap();

    public abstract HashMap<String, Pair<byte[], Float>> getFDMMap();

    public abstract TreeMap<Integer, byte[]> getFDXMap();

    public abstract int getTotalDoc();

    public abstract void close();
}
