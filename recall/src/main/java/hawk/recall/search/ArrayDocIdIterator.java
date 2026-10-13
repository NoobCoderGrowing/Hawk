package hawk.recall.search;

/**
 * 数组支撑的迭代器：docID 已经在一个**升序**数组里，{@link #advance} 用二分。
 *
 * <p>用在哪：BKD 数值范围的命中文档。{@code BkdReader.intersect} 是**回调式**的
 * （push），拿不到拉取式游标；但它的输出只是 docID、数量有限，物化成数组
 * 再用这里包起来，代价可接受，而且保住了"叶子都是迭代器"的统一形状。
 * 真正需要"省内存"的是词项 posting（可能几十万条），那个走
 * {@link TermScorer} 顺序读、不物化。
 *
 * <p>{@code MatchAllQuery} 也用它。
 *
 * <p><b>⚠️ 调用方必须自己保证数组严格升序</b> —— 这里不做检查。
 * BKD 的回调是**树序遍历，天然无序**（实测 2026 条里 200 处逆序），
 * 传进来之前必须排序去重，否则 {@link #advance} 的二分查找会给错答案。
 */
public final class ArrayDocIdIterator extends DocIdIterator {

    private final int[] docIds;
    private int cursor = -1;

    /**
     * @param docIds **必须已严格升序**。BKD 的输出是树序，调用方要先排序；
     *                MatchAll 的顺序构造天然满足。本类不做校验（每次构造都扫一遍太贵）。
     */
    public ArrayDocIdIterator(int[] docIds) {
        this.docIds = docIds;
    }

    @Override
    public int docId() {
        return cursor < 0 ? -1 : (cursor >= docIds.length ? NO_MORE_DOCS : docIds[cursor]);
    }

    @Override
    public int nextDoc() {
        cursor++;
        return docId();
    }

    @Override
    public int advance(int target) {
        if (docId() >= target) {
            return docId();
        }
        int lo = Math.max(cursor, 0);
        int hi = docIds.length - 1;
        int found = docIds.length;      // 没找到就是"越界"
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (docIds[mid] < target) {
                lo = mid + 1;
            } else {
                found = mid;
                hi = mid - 1;
            }
        }
        cursor = found;
        return docId();
    }

    @Override
    public long cost() {
        return docIds.length;
    }
}
