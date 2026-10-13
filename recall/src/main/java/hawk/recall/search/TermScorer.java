package hawk.recall.search;

import common.Pair;
import hawk.recall.reader.DirectoryReader;
import hawk.recall.similarity.Similarity;
import io.github.noobcodergrowing.JFST.FST;
import io.github.noobcodergrowing.JFST.fstNode;
import io.github.noobcodergrowing.JFST.fstPair;
import hawk.recall.reader.TermFstUtil;
import util.DataInput;
import util.WrapLong;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 词项打分器：**顺序读倒排 posting**，不物化。
 *
 * <p>这是迭代器化真正的收益所在 —— 一个高频词的 posting 可能有几十万条，
 * 旧实现把它们全读进 {@code ScoreDoc[]} 再交给上层，内存与 docID 数成正比；
 * 这里只在游标处往前读，用到哪条读哪条。
 *
 * <p>{@link #advance} 会**跳过中间 postings 而不算 BM25** —— 交集里用不到的文档不该白算分。
 */
public final class TermScorer extends Scorer {

    private final DirectoryReader reader;
    private final MemorySegment frqBuffer;
    private final WrapLong frqOffset;
    /** 这个词一共有多少条 posting —— BM25 的 termFrequency 用的是它，不是"还剩几条"。 */
    private final int termFrequency;
    private final int totalDoc;
    private final float averageDocLength;
    /** 要不要算 BM25。{@code ScoreMode.NO_SCORES} 时为 false —— 省掉整条打分计算。 */
    private final boolean scoring;

    private int remaining;
    private int docId = -1;
    private float score;

    private TermScorer(DirectoryReader reader, MemorySegment frqBuffer, WrapLong frqOffset,
                       int termFrequency, int totalDoc, float averageDocLength, boolean scoring) {
        this.reader = reader;
        this.frqBuffer = frqBuffer;
        this.frqOffset = frqOffset;
        this.termFrequency = termFrequency;
        this.totalDoc = totalDoc;
        this.averageDocLength = averageDocLength;
        this.scoring = scoring;
        this.remaining = termFrequency;
    }

    /**
     * 在这个字段里找这个词，建一个打分器。
     *
     * @param scoring 要不要算 BM25；{@code false} 时 {@link #score()} 恒为 0
     * @return 词不存在时返回 {@code null}（**这不是错误**，是正常的"没这个词"）
     * @throws IllegalArgumentException 字段不存在 —— 这是**用错了**，要报出来
     */
    public static TermScorer of(DirectoryReader reader, String field, String term, boolean scoring) {
        HashMap<String, Pair<byte[], Float>> fdmMap = reader.getFDMMap();
        Pair<byte[], Float> fdm = fdmMap.get(field);
        if (fdm == null) {
            List<String> known = new ArrayList<>(fdmMap.keySet());
            known.sort(String::compareTo);
            throw new IllegalArgumentException(
                    "索引里没有文本字段「" + field + "」。可用的是：" + known);
        }
        FST termFST = reader.getTermFST();
        fstPair<ArrayList<fstNode>, Long> found =
                termFST.search(TermFstUtil.toCharArray(TermFstUtil.termKey(field, term)));
        if (found == null) {
            return null;
        }
        WrapLong offset = new WrapLong(found.getValue());
        int termFrequency = DataInput.readVintAt(reader.getFRQBuffer(), offset);
        return new TermScorer(reader, reader.getFRQBuffer(), offset,
                termFrequency, reader.numDocs(), fdm.getRight(), scoring);
    }

    /** 当前文档的 BM25。不算分时直接返回 0，省掉计算。 */
    private float bm25(int docFrequency, int docFieldLength) {
        return scoring
                ? Similarity.BM25(totalDoc, termFrequency, docFrequency, docFieldLength, averageDocLength)
                : 0f;
    }

    @Override
    public int docId() {
        return docId;
    }

    @Override
    public int nextDoc() {
        while (remaining > 0) {
            int id = DataInput.readVintAt(frqBuffer, frqOffset);
            int docFrequency = DataInput.readVintAt(frqBuffer, frqOffset);
            int docFieldLength = DataInput.readVintAt(frqBuffer, frqOffset);
            remaining--;
            if (reader.isLive(id)) {
                docId = id;
                score = bm25(docFrequency, docFieldLength);
                return docId;
            }
        }
        docId = NO_MORE_DOCS;
        score = 0f;
        return docId;
    }

    @Override
    public int advance(int target) {
        if (docId >= target) {
            return docId;
        }
        // posting 只能往前读，所以是顺序扫；但**不跳过的那些才算分**，省掉无用的 BM25
        while (remaining > 0) {
            int id = DataInput.readVintAt(frqBuffer, frqOffset);
            int docFrequency = DataInput.readVintAt(frqBuffer, frqOffset);
            int docFieldLength = DataInput.readVintAt(frqBuffer, frqOffset);
            remaining--;
            if (id < target || !reader.isLive(id)) {
                continue;
            }
            docId = id;
            score = bm25(docFrequency, docFieldLength);
            return docId;
        }
        docId = NO_MORE_DOCS;
        score = 0f;
        return docId;
    }

    @Override
    public float score() {
        return score;
    }

    @Override
    public long cost() {
        return remaining;
    }
}
