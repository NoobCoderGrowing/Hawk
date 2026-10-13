package hawk.recall.search;

/**
 * 升序 docID 迭代器 —— 执行层的基本单位。
 *
 * <h2>为什么要把顺序契约写成接口</h2>
 * 这次重构前的 bug（"AND 里套 OR 静默漏结果"）根因就是：{@code andSearch} 用二分查找求交，
 * **隐含假设**子结果按 docID 升序，而这个假设既没写在任何地方、也不成立
 * （{@code orSearch} 返回的是哈希容器倒出来的顺序）。
 *
 * <p>所以这次把"**docID 单调递增**"变成**接口的一部分**：任何实现都必须满足，
 * 任何组合操作都可以依赖。交集于是可以用 leapfrog —— 只推进、不回头，
 * 零额外内存、O(总 posting 数) 而不是物化后求集合。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>初始状态 {@code docId()} 返回 {@code -1}</li>
 *   <li>每次 {@link #nextDoc()} 返回严格更大的 docID，或 {@link #NO_MORE_DOCS}</li>
 *   <li>{@link #advance(int)} 跳到 {@code >= target} 的第一个 docID（也必须严格前进）</li>
 *   <li>到达 {@link #NO_MORE_DOCS} 后**不可再调用** nextDoc/advance</li>
 * </ul>
 */
public abstract class DocIdIterator {

    /** 迭代结束的哨兵值。它比任何真实 docID 都大。 */
    public static final int NO_MORE_DOCS = Integer.MAX_VALUE;

    /** 当前 docID；未开始为 {@code -1}，结束为 {@link #NO_MORE_DOCS}。 */
    public abstract int docId();

    /** 前进到下一个 docID。 */
    public abstract int nextDoc();

    /**
     * 前进到第一个 {@code >= target} 的 docID。
     *
     * <p>默认实现是"逐个 nextDoc"—— 正确但不快。**叶子应当覆写它**：
     * 数组型的用二分（O(log n)），posting 型的顺序扫（本来就只能往前），
     * 组合型的才用这个默认实现（它的加速来自子迭代器各自的 advance）。
     */
    public int advance(int target) {
        int d = docId();
        while (d < target) {
            d = nextDoc();
        }
        return d;
    }

    /**
     * 预计要访问多少个 docID —— 组合操作可以据此把**代价小的放前面**，
     * 让 leapfrog 的第一次推进就砍掉最多候选。
     *
     * @return 估计值；数组型返回数组长度，posting 型返回 posting 条数
     */
    public abstract long cost();
}
