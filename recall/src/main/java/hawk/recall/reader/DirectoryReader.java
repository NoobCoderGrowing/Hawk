package hawk.recall.reader;

import directory.Directory;
import directory.MMapDirectory;
import io.github.noobcodergrowing.JFST.FST;
import util.bkd.BkdReader;
import common.Pair;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.List;
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

    public abstract MemorySegment getFRQBuffer();

    public abstract MemorySegment getFDTBuffer();

    public abstract HashMap<String, BkdReader> getBkdReaders();

    public abstract HashMap<String, Pair<byte[], Float>> getFDMMap();

    /**
     * 字段名 → 该字段当初用的 **tokenizer 名**（来自 fdm，见 {@code DESIGN.md} §6.7）。
     *
     * <p>只有**被分词过**的字段才有条目（数值字段、只存原文的字段不在里面）。召回时按这份记录
     * 去解析出分词器实例，保证查询侧和索引侧用**同一组** —— 否则切出来的词在倒排里不存在，
     * 不报错、只是静默搜不到。
     *
     * <p>与 {@link #getFDMMap()} 并存而不是并进去，是为了不牵动它的 5 个消费者
     * （Schema / TermScorer / Searcher / constructTermFST）—— 它们只关心类型位和平均长度。
     */
    public abstract Map<String, List<String>> getFieldTokenizers();

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
