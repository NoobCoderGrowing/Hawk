package util;

import common.PrefixedNumber;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;

public class NumberUtil {

    public static HashSet<PrefixedNumber> long2PrefixFormat(long rawBits, int precisionStep){

        StringBuilder sb = new StringBuilder();
        int preFixCount = 64%precisionStep==0?64/precisionStep:64/precisionStep+1;
        long[] retLong = new long[preFixCount];
        //construct a 64-bit prefix mask
        for (int i = 0; i < preFixCount; i++) {
            int reservedBits = precisionStep*(i+1);
            for (int j = 0; j < reservedBits; j++) {
                sb.append("1");
            }
            for (int j = 0; j < 64-reservedBits; j++) {
                sb.append(0);
            }
            long mask = Long.parseUnsignedLong(sb.toString(),2);
            sb.setLength(0);
            retLong[i] = rawBits & mask;
        }
        HashSet<PrefixedNumber> ret = new HashSet<>();
        for (int i = 0; i < preFixCount; i++) {
            ret.add(new PrefixedNumber(long2Bytes(retLong[i])));
        }
        return ret;
    }
    /**
     * double 的位模式 → **保序**的 long。区间查询靠它：BKD 里存的是这个 long，
     * 比较 min/max 就等价于比较 double。
     *
     * <h2>为什么是「翻掉除符号位以外的位」</h2>
     * IEEE 754 的位模式里，正数部分本身已经升序（0 → +∞ 对应 0 → 0x7FF0…）；
     * 负数部分则是**反的**（-0.0 的位模式最小，-∞ 最大），所以负数要整体翻转。
     *
     * <p>翻的必须是**除符号位以外**的位 —— 这样负数翻完仍是负数，自然会排在所有正数前面。
     *
     * <p><b>旧实现翻的是全部 64 位（{@code b = ~b}），把负数也翻成了正数</b>，
     * 于是所有负数都排到正数后面，跨零的区间查询全错（实测 {@code atMost(field, 100)}
     * 恒返回空）。见 {@link directory.IndexFormatVersion}：这个修复**改了落盘编码**，
     * 旧索引必须重建。
     */
    public static long double2SortableLong(double input){
        long bits = Double.doubleToLongBits(input);
        // bits >> 63 对正数是 0、对负数是全 1；& MAX_VALUE 后只剩低位部分，
        // 于是「正数不变、负数翻低位而保留符号位」
        return bits ^ ((bits >> 63) & 0x7fffffffffffffffL);
    }

    /** {@link #double2SortableLong} 的逆变换（同一个公式，它自己就是自己的逆）。 */
    public static double sortableLong2Double(long input){
        long bits = input ^ ((input >> 63) & 0x7fffffffffffffffL);
        return Double.longBitsToDouble(bits);
    }

    /**
     * long → **保序**的 long。**恒等映射** —— 对 long 来说有符号序就是我们要的序。
     *
     * <p>⚠️ 常见写法是 {@code v ^ Long.MIN_VALUE}，但那只在**无符号**比较下单调：
     * 它把 {@code Long.MIN_VALUE} 映到 0、把 0 映到 {@code Long.MIN_VALUE}，
     * 于是 -5 排到了 0 的后面。而 BKD 的建树（{@code FieldBkdWriter.sortPoints}）
     * 与剪枝（{@code BkdReader.intersectNode}）都按**有符号**比较，
     * 用那个写法会让 {@code atMost(ts, 0)} 算出 min > max、恒返回空。
     *
     * <p>恒等映射则天然满足"按有符号序单调"，且**没有任何精度损失** ——
     * 这正是 {@code LongField} 与 {@code NumberField}（double，只有 52 位尾数）的区别。
     */
    public static long long2SortableLong(long input){
        return input;
    }

    /** {@link #long2SortableLong} 的逆变换 —— 恒等映射的逆还是它自己。 */
    public static long sortableLong2Long(long input){
        return input;
    }

    public static byte[] int2Bytes(int i){
        byte[] ret = new byte[]{
                (byte) (i >> 24),
                (byte) (i >> 16),
                (byte) (i >> 8),
                (byte) i
        };
        return ret;
    }

    public static byte[] long2Bytes(long i){
        byte[] ret = new byte[]{
                (byte) (i >> 56),
                (byte) (i >> 48),
                (byte) (i >> 40),
                (byte) (i >> 32),
                (byte) (i >> 24),
                (byte) (i >> 16),
                (byte) (i >> 8),
                (byte) i,
        };
        return ret;
    }

    public static byte[] long2Vlong(long input){
        byte[] ret = new byte[1];
        int j = 0;
        while((input & ~0x7fL) != 0){
            ret[j] = (byte)((input & 0x7fL) | 0x80L);
            input >>>= 7;
            ret = ArrayUtil.growByteArray(ret);
            j ++;
        }
        ret[j] = (byte) input;
        return ret;
    }

    public static byte[] int2Vint(int input){
        byte[] ret = new byte[1];
        int j = 0;
        while((input & ~0x7f) != 0){
            ret[j] = (byte)((input & 0x7f) | 0x80);
            input >>>= 7;
            ret = ArrayUtil.growByteArray(ret);
            j ++;
        }
        ret[j] = (byte) input;
        return ret;
    }

    public static int compareSortableBytes(byte[] a, byte[] b){
        int aInt, bInt;
        for (int i = 0; i < a.length && i < b.length; i++) {
            aInt = a[i] & 0xff;
            bInt = b[i] & 0xff;
            if(aInt > bInt){
                return 1;
            } else if (aInt < bInt) {
                return -1;
            }
        }
        if(a.length < b.length){
            return 1;
        } else if (a.length > b.length) {
            return -1;
        }
        return 0;
    }

    public static String long2String(long i){
        byte[] strBytes = new byte[]{
                (byte) ((i >> 57) & 0x7f),
                (byte) ((i >> 50) & 0x7f),
                (byte) ((i >> 43) & 0x7f),
                (byte) ((i >> 36) & 0x7f),
                (byte) ((i >> 29) & 0x7f),
                (byte) ((i >> 22) & 0x7f),
                (byte) ((i >> 15) & 0x7f),
                (byte) ((i >> 8) & 0x7f),
                (byte) ((i >> 1) & 0x7f),
                (byte) (i & 0x01),
        };
        return new String(strBytes, StandardCharsets.UTF_8);
    }

    public static String long2StringWithShift(int shift, long input){
        StringBuilder sb = new StringBuilder();
        sb.append((char) shift);
        sb.append(long2String(input));
        return sb.toString();
    }

    public static long getLongMask(int maskLength){
        if(maskLength >= 64){
            return 0xffffffffffffffffL;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maskLength; i++) {
            sb.append("1");
        }
        for (int i = 0; i < 64 - maskLength; i++) {
            sb.append("0");
        }
        return Long.parseUnsignedLong(sb.toString(), 2);
    }

    public static String[] long2PrefixString(long rawBits, int precisionStep){

        StringBuilder sb = new StringBuilder();
        int preFixCount = 64%precisionStep==0?64/precisionStep:64/precisionStep+1;
        String[] retString = new String[preFixCount];
        char shift;
        //construct a 64-bit prefix mask
        for (int i = 0; i < preFixCount; i++) {
            int reservedBits = precisionStep*(i+1);
            for (int j = 0; j < reservedBits; j++) {
                sb.append("1");
            }
            for (int j = 0; j < 64-reservedBits; j++) {
                sb.append(0);
            }
            String maskString = sb.toString();
            if(maskString.length() > 64) maskString = maskString.substring(0, 64);
            long mask = Long.parseUnsignedLong(maskString,2);
            sb.setLength(0);
            shift = (char) i;
            String prefixString = long2String(rawBits & mask);
            sb.append(shift);
            sb.append(prefixString);
            retString[i] = sb.toString();
            sb.setLength(0);
        }
        return retString;
    }
}
