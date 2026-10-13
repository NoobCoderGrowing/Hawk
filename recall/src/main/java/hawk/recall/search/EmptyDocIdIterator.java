package hawk.recall.search;

/**
 * 空迭代器：一条都没有。
 *
 * <p>有了它，"没有匹配"就是一个**正常的迭代器**，不需要用 {@code null} 表示。
 * 旧实现里 {@code andSearch}/{@code orSearch} 用 {@code null} 表示空结果，
 * 于是"空结果"和"传了个 null 进来"混在一起，每个调用点都得判空。
 */
public final class EmptyDocIdIterator extends DocIdIterator {

    public static final EmptyDocIdIterator INSTANCE = new EmptyDocIdIterator();

    private EmptyDocIdIterator() {
    }

    @Override
    public int docId() {
        return NO_MORE_DOCS;
    }

    @Override
    public int nextDoc() {
        return NO_MORE_DOCS;
    }

    @Override
    public int advance(int target) {
        return NO_MORE_DOCS;
    }

    @Override
    public long cost() {
        return 0;
    }
}
