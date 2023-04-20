package hawk.index.core.util;

import hawk.index.core.writer.PrefixedNumber;

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
    public static long double2SortableLong(double input){
        long b = Double.doubleToLongBits(input);
        if(b>=0){ // if positive, set signed bit to 1
            b |= 0x8000000000000000L;
        }else{ // if negative, negate all
            b = ~b;
        }
        return b;
    }

    public static double sortableLong2Double(long input){
        if(input >= 0){ // if positive, set signed bit to 1
            input = ~input;
        }else{ // if negative, negate all
            input &= 0x7fffffffffffffffL;
        }

        return Double.longBitsToDouble(input);
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


    public static void main(String[] args) {
        long2PrefixString(123,4);
    }


}
