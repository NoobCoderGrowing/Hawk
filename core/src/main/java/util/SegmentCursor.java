package util;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * 在 {@link MemorySegment} 上顺序读取的游标，用于替代原先 MappedByteBuffer 的相对读写
 * （{@code buffer.position()} / {@code getInt()} / {@code readVint()} 那一套）。
 *
 * <p>索引格式里的 Int/Long 一律是 **big-endian**，且数据是紧凑排布的（例如 .fdm 的
 * {@code Byte fieldType} 之后紧跟 {@code Int fieldLengthSum}），所以这里必须同时
 * 指定 {@code BIG_ENDIAN} 与 {@code withByteAlignment(1)}：
 * <ul>
 *   <li>{@code ValueLayout.JAVA_INT} 默认是 native order（x86 小端），直接用会**静默读出错值**；</li>
 *   <li>默认还要求 4 字节对齐，紧凑排布下会抛 {@code IllegalArgumentException}。</li>
 * </ul>
 * 单字节的 {@code JAVA_BYTE} 两个问题都没有。
 */
public final class SegmentCursor {

    private static final ValueLayout.OfInt JAVA_INT_BE =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
    private static final ValueLayout.OfLong JAVA_LONG_BE =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);

    private final MemorySegment segment;

    private final WrapLong pos;

    public SegmentCursor(MemorySegment segment) {
        this(segment, 0L);
    }

    public SegmentCursor(MemorySegment segment, long start) {
        this.segment = segment;
        this.pos = new WrapLong(start);
    }

    public boolean hasRemaining() {
        return pos.getValue() < segment.byteSize();
    }

    public long position() {
        return pos.getValue();
    }

    public byte get() {
        byte b = segment.get(ValueLayout.JAVA_BYTE, pos.getValue());
        pos.setValue(pos.getValue() + 1);
        return b;
    }

    public int getInt() {
        int v = segment.get(JAVA_INT_BE, pos.getValue());
        pos.setValue(pos.getValue() + 4);
        return v;
    }

    public long getLong() {
        long v = segment.get(JAVA_LONG_BE, pos.getValue());
        pos.setValue(pos.getValue() + 8);
        return v;
    }

    public int readVint() {
        return DataInput.readVintAt(segment, pos);
    }

    public long readVlong() {
        return DataInput.readVlongAt(segment, pos);
    }

    /** 跳过 n 字节（对应旧的 {@code buffer.position(buffer.position() + n)}）。 */
    public void skip(long n) {
        pos.setValue(pos.getValue() + n);
    }

    public byte[] readBytes(int length) {
        byte[] ret = DataInput.readBytes(segment, pos.getValue(), length);
        pos.setValue(pos.getValue() + length);
        return ret;
    }
}
