package hawk.recall.search;

import common.IndexFormatConfig;
import common.Pair;
import document.Document;
import field.Field;
import field.FieldType;
import field.LongField;
import field.NumberField;
import field.PrimaryKeyField;
import field.StringField;
import hawk.recall.query.BooleanQuery;
import hawk.recall.query.LongRangeQuery;
import hawk.recall.query.MatchAllQuery;
import hawk.recall.query.MatchNoneQuery;
import hawk.recall.query.Occur;
import hawk.recall.query.Query;
import hawk.recall.query.RangeQuery;
import hawk.recall.query.TermQuery;
import hawk.recall.reader.DirectoryReader;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.jpountz.lz4.LZ4FastDecompressor;
import util.DataInput;
import util.NumberUtil;
import util.bkd.BkdReader;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 检索执行 —— 把一棵 {@link Query} 跑成 {@link TopDocs}。
 *
 * <h2>Searcher 不认识"文本"</h2>
 * 这里只处理**已经分好词**的 {@link Query}。分词是"请求层"的事
 * （{@code StringQuery#getTermQueryList(analyzer)} 或 {@code RequestCompiler}），
 * 所以本类**不需要 analyzer** —— 构造器也不收 {@code SearchConfig}。
 *
 * <h2>执行模型：拉取式迭代器</h2>
 * 检索是"拉"的：{@link DocIdIterator} 按**升序 docID** 逐个吐文档，收集器逐个收。
 * 组合器（{@link ConjunctionScorer} leapfrog 求交、{@link DisjunctionScorer} 堆求并、
 * {@link BooleanScorer} 合成四种角色）全程只持有游标，**不物化中间结果**。
 *
 * <p>对比改前：每个叶子先出完整 {@code ScoreDoc[]}，布尔组合在数组上做集合运算。
 * 那是 O(命中数) 内存；现在是 O(子句数)。
 *
 * <h2>已知局限</h2>
 * {@link ScoreMode#TOP_SCORES} 目前**还没有真正省分数计算** —— 提前终止需要块最大分
 * （block-max WAND）那类上界信息，尚未实现。所以 TOP_SCORES 与 COMPLETE 当前等价，
 * 但接口形状已经就位，实现可以后补而不动调用方。
 */
@Slf4j
@Data
public class Searcher {

    private DirectoryReader directoryReader;

    private IndexFormatConfig indexFormatConfig;

    public Searcher(DirectoryReader directoryReader, IndexFormatConfig indexFormatConfig) {
        this.directoryReader = directoryReader;
        this.indexFormatConfig = indexFormatConfig;
    }

    // ------------------------------------------------------------------ 入口

    /** 默认 {@link ScoreMode#TOP_SCORES}。 */
    public TopDocs search(Query query, int limit) {
        return search(query, limit, ScoreMode.TOP_SCORES);
    }

    /**
     * 检索。
     *
     * @param limit     最多返回多少条；**总数不受它影响**
     * @param scoreMode 要不要打分。纯筛选查询用 {@link ScoreMode#NO_SCORES} 可省掉整条打分链路
     */
    public TopDocs search(Query query, int limit, ScoreMode scoreMode) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(scoreMode, "scoreMode");

        boolean scoring = scoreMode.needsScores();
        Scorer scorer = eval(query, scoring);
        TopScoreDocCollector collector = new TopScoreDocCollector(limit, scoring);

        for (int doc = scorer.nextDoc(); doc != DocIdIterator.NO_MORE_DOCS; doc = scorer.nextDoc()) {
            collector.collect(doc, scorer.score());
        }
        return collector.topDocs();
    }

    // ------------------------------------------------------------------ 把 Query 编成 Scorer 树

    private Scorer eval(Query query, boolean scoring) {
        return switch (query) {
            case TermQuery t -> termScorer(t, scoring);
            case RangeQuery r -> new ConstantScorer(rangeIterator(r), 0f);
            case LongRangeQuery r -> new ConstantScorer(longRangeIterator(r), 0f);
            case BooleanQuery b -> booleanScorer(b, scoring);
            case MatchAllQuery ignored -> new ConstantScorer(matchAllIterator(), 0f);
            case MatchNoneQuery ignored -> ConstantScorer.empty();
        };
    }

    private Scorer termScorer(TermQuery query, boolean scoring) {
        TermScorer scorer = TermScorer.of(directoryReader, query.field(), query.term(), scoring);
        // 词不存在是**正常情况**（这个字段里没这个词），返回空而不是报错
        return scorer == null ? ConstantScorer.empty() : scorer;
    }

    private Scorer booleanScorer(BooleanQuery query, boolean scoring) {
        List<Scorer> requiredAll = new ArrayList<>();       // MUST + FILTER：都要参加交集
        List<Scorer> requiredScoring = new ArrayList<>();   // 其中 MUST 才算分
        List<Scorer> optional = new ArrayList<>();          // SHOULD
        List<Scorer> excluded = new ArrayList<>();          // MUST_NOT

        for (BooleanQuery.Clause clause : query.clauses()) {
            Scorer sub = eval(clause.query(), scoring);
            switch (clause.occur()) {
                case MUST -> {
                    requiredAll.add(sub);
                    requiredScoring.add(sub);
                }
                case FILTER -> requiredAll.add(sub);
                case SHOULD -> optional.add(sub);
                case MUST_NOT -> excluded.add(sub);
            }
        }

        ConjunctionScorer conjunction = requiredAll.isEmpty()
                ? null : new ConjunctionScorer(requiredAll, requiredScoring);
        DisjunctionScorer disjunction = optional.isEmpty()
                ? null : new DisjunctionScorer(optional);

        // 只有 SHOULD 子句时，至少得命中一个 —— 否则整个查询等价于 MatchAll
        int minShould = query.minShouldMatch() > 0 ? query.minShouldMatch()
                : (conjunction == null && disjunction != null ? 1 : 0);

        return new BooleanScorer(conjunction, disjunction, excluded, minShould);
    }

    // ------------------------------------------------------------------ 叶子：数值 / 全部

    /**
     * 数值区间 → docID 升序数组。
     *
     * <p>{@code BkdReader.intersect} 是**回调式**的（push），拿不到拉取式游标。
     * 它的输出只是 docID、数量有限，物化成数组再用 {@link ArrayDocIdIterator}
     * 包起来，就保持了"叶子都是迭代器"的统一形状。
     * 真正需要省内存的是词项 posting，那个走 {@link TermScorer} 顺序读、不物化。
     */
    private ArrayDocIdIterator rangeIterator(RangeQuery query) {
        BkdReader bkdReader = requireNumericField(query.field(), FieldType.NUMBER.bit(), "double");

        // 开闭区间在 sortable long 上实现：该编码单调，±1 等价于"下一个可表示的 double"
        long minValue = NumberUtil.double2SortableLong(query.lower());
        if (!query.includeLower()) {
            minValue++;
        }
        long maxValue = NumberUtil.double2SortableLong(query.upper());
        if (!query.includeUpper()) {
            maxValue--;
        }
        if (minValue > maxValue) {
            return new ArrayDocIdIterator(new int[0]);
        }

        List<Integer> ids = new ArrayList<>();
        bkdReader.intersect(minValue, maxValue, docId -> {
            if (directoryReader.isLive(docId)) {
                ids.add(docId);
            }
            return true;
        });
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ids.get(i);
        }
        // ⚠️ 必须排序：BKD 的 intersect 是**树序遍历**，回调出来的 docID **不保证升序**
        //    （实测 2026 条里有 200 处逆序）。ArrayDocIdIterator 的契约是严格升序，
        //    单独查范围时看不出问题（顺序迭代不需要顺序），一旦进交集就会让二分查找给出错答案。
        return new ArrayDocIdIterator(sortedDistinct(arr));
    }

    /**
     * 取数值字段的 BKD，并**校验它的类型**。
     *
     * <p>只查"有没有 BKD"是不够的：double 字段和 long 字段**都有 BKD**，
     * 但落盘编码不同（一个走 {@code double2SortableLong}，一个走 {@code long2SortableLong}）。
     * 拿一种的区间去比另一种存的值，结果毫无意义却不会报错。
     * 所以这里按 fdm 的类型位判断，把类型错配挡在执行层 ——
     * 这一层是给**绕过请求层、直接构造 Query** 的调用方兜底的。
     */
    private BkdReader requireNumericField(String field, byte typeBit, String kind) {
        HashMap<String, Pair<byte[], Float>> fdmMap = directoryReader.getFDMMap();
        Pair<byte[], Float> fdm = fdmMap.get(field);
        if (fdm == null || (fdm.getLeft()[0] & typeBit) == 0) {
            List<String> known = new ArrayList<>(directoryReader.getBkdReaders().keySet());
            known.sort(String::compareTo);
            throw new IllegalArgumentException(
                    "索引里没有可作 " + kind + " 范围检索的字段「" + field + "」。"
                            + "有 BKD 的字段是：" + known
                            + "（注意 double 与 long 是两种字段类型，不能混用）");
        }
        BkdReader bkdReader = directoryReader.getBkdReaders().get(field);
        if (bkdReader == null) {
            throw new IllegalArgumentException("字段「" + field + "」标了 " + kind + " 类型但没有 BKD 数据");
        }
        return bkdReader;
    }

    /** 排序 + 去重，满足 {@link ArrayDocIdIterator} 的**严格**升序契约。 */
    private static int[] sortedDistinct(int[] raw) {
        if (raw.length == 0) {
            return raw;
        }
        java.util.Arrays.sort(raw);
        int n = 1;
        for (int i = 1; i < raw.length; i++) {
            if (raw[i] != raw[n - 1]) {
                raw[n++] = raw[i];
            }
        }
        return n == raw.length ? raw : java.util.Arrays.copyOf(raw, n);
    }

    /**
     * long 区间 → docID 升序数组。与 double 那条的唯一差别是**编码函数不同**
     * （{@code long2SortableLong} 只翻符号位，无精度损失），并且开区间是精确的 ±1。
     */
    private ArrayDocIdIterator longRangeIterator(LongRangeQuery query) {
        BkdReader bkdReader = requireNumericField(query.field(), FieldType.LONG.bit(), "long");

        // long 的编码是稠密的，开区间 ±1 精确；注意边界溢出（MIN/MAX 的开区间等于空集）
        long minValue = NumberUtil.long2SortableLong(query.lower());
        if (!query.includeLower()) {
            if (minValue == Long.MAX_VALUE) return new ArrayDocIdIterator(new int[0]);
            minValue++;
        }
        long maxValue = NumberUtil.long2SortableLong(query.upper());
        if (!query.includeUpper()) {
            if (maxValue == Long.MIN_VALUE) return new ArrayDocIdIterator(new int[0]);
            maxValue--;
        }
        if (minValue > maxValue) {
            return new ArrayDocIdIterator(new int[0]);
        }

        List<Integer> ids = new ArrayList<>();
        bkdReader.intersect(minValue, maxValue, docId -> {
            if (directoryReader.isLive(docId)) {
                ids.add(docId);
            }
            return true;
        });
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ids.get(i);
        }
        // 同 rangeIterator：BKD 回调是树序，必须排序去重
        return new ArrayDocIdIterator(sortedDistinct(arr));
    }

    private ArrayDocIdIterator matchAllIterator() {
        int total = directoryReader.getTotalDoc();
        List<Integer> ids = new ArrayList<>();
        for (int docId = 0; docId < total; docId++) {
            if (directoryReader.isLive(docId)) {
                ids.add(docId);
            }
        }
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ids.get(i);
        }
        return new ArrayDocIdIterator(arr);
    }

    // ------------------------------------------------------------------ 取原文

    public Field createField(String fieldName, byte[] fieldValue) {
        byte typeByte = this.directoryReader.getFDMMap().get(fieldName).getLeft()[0];
        // 拆字节的写法收敛在 FieldType.decodeStored —— 拼/拆是同一个约定，别在两个模块里各写一份
        return FieldType.decodeStored(typeByte, fieldName, fieldValue);
    }

    // return: left offset + right offset
    public byte[][] searchFDTOffset(int docID, TreeMap<Integer, byte[]> fdxMap) {
        int leftKey = fdxMap.floorKey(docID);
        byte[] left = fdxMap.get(leftKey);
        Map.Entry<Integer, byte[]> rightEntry = fdxMap.higherEntry(leftKey);
        if (rightEntry != null) {
            return new byte[][]{left, rightEntry.getValue()};
        }
        return new byte[][]{left, null};
    }

    public Document doc(ScoreDoc scoreDoc) {
        if (scoreDoc == null) {
            return null;
        }
        int docID = scoreDoc.docId();
        if (!directoryReader.isLive(docID)) {
            return null;
        }
        Document document = new Document(scoreDoc.score());
        TreeMap<Integer, byte[]> fdxMap = this.directoryReader.getFDXMap();
        MemorySegment fdtBuffer = this.directoryReader.getFDTBuffer();
        // calculate fdt buffer offset
        byte[][] vlongOffsets = searchFDTOffset(docID, fdxMap);

        // 偏移全程 long：.fdt 可超过 2 GiB
        long offsetLeft = DataInput.readVlong(vlongOffsets[0]);
        long offsetRight;
        if (vlongOffsets[1] != null) {
            offsetRight = DataInput.readVlong(vlongOffsets[1]);
        } else {
            offsetRight = fdtBuffer.byteSize();
        }
        // 单个压缩块最大约 blocSize(16 KiB)，int 足够
        int blockLength = (int) (offsetRight - offsetLeft);
        // read compressed bloc into buffer
        byte[] fdtBloc = DataInput.readBytes(fdtBuffer, offsetLeft, blockLength);

        byte[] unCompressedBloc = new byte[indexFormatConfig.getBlocSize()];
        LZ4FastDecompressor decompressor = indexFormatConfig.getDecompressor();
        // decompress buffer to unCompressedBloc
        decompressor.decompress(fdtBloc, unCompressedBloc);
        ByteBuffer buffer = ByteBuffer.wrap(unCompressedBloc);
        // read unCompressd block until the document is found
        while (buffer.position() < buffer.limit()) {
            int curDocID = DataInput.readVint(buffer);
            int fieldCount = DataInput.readVint(buffer);
            for (int i = 0; i < fieldCount; i++) {
                int fieldLength = DataInput.readVint(buffer);
                byte[] fieldName = new byte[fieldLength];
                buffer.get(fieldName);
                int valueLength = DataInput.readVint(buffer);
                byte[] fieldValue = new byte[valueLength];
                buffer.get(fieldValue);
                if (docID == curDocID) {
                    Field field = createField(new String(fieldName, StandardCharsets.UTF_8), fieldValue);
                    document.add(field);
                }
            }
            if (docID == curDocID) {
                return document;
            }
        }
        return null;
    }

    /**
     * 按**商品唯一 ID**取回原文。
     *
     * <p>参数是 {@code PrimaryKeyField} 的值，也就是 {@code pk.map} 里的 **key**，
     * <b>不是全局 docID</b>。两者在数值上没有任何关系 —— 全局 docID 是按索引线程的
     * **完成顺序**分配的，不是提交顺序。混用不会抛异常，只会**静默返回另一个商品**，
     * 所以对外只暴露这个按业务主键取文的方法，调用方没有机会把两者写混。
     *
     * @param uniqueId 商品唯一 ID（{@code PrimaryKeyField}）
     * @return 文档；该 ID 不存在、或对应文档已被软删除时返回 {@code null}
     */
    public Document docByUniqueId(long uniqueId) {
        Integer docId = directoryReader.getPkMap().get(uniqueId);
        if (docId == null) {
            return null;
        }
        // 复用 doc()：它内部会做 isLive 检查，已删除的文档同样返回 null
        return doc(ScoreDoc.noScore(docId));
    }

    public void close() {
        directoryReader.close();
    }
}
