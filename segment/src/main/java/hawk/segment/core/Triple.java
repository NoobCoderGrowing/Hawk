package hawk.segment.core;

import lombok.Data;

@Data
public class Triple<T extends Comparable<T>, K, V> implements Comparable<Triple<T, K, V>> {

    private T left;
    private K mid;
    private V right;

    public Triple(T left, K mid, V right) {
        this.left = left;
        this.mid = mid;
        this.right = right;
    }

    @Override
    public int compareTo(Triple<T, K, V> o) {
        return this.left.compareTo(o.left);
    }
}
