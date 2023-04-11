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

    public static int compareDoubleBytes(byte[] a , byte[] b ){
        int aInt = 0;
        int bInt = 0;
        for (int i = 0; i < 8; i++) {
            aInt = Byte.toUnsignedInt(a[i]);
            bInt = Byte.toUnsignedInt(b[i]);
            if(aInt > bInt){
                return 1;
            }else if (aInt < bInt){
                return -1;
            }
        }
        return 0;
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
    public static String long2String(long input){
        StringBuilder sb = new StringBuilder();
        int roll = 64/7 + 1;
        char cur;
        for (int i = 0; i < roll; i++) {
            cur = (char) (input & 0x7f);
            sb.append(cur);
            input >>>= 7;
        }
        return sb.reverse().toString();
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
            long mask = Long.parseUnsignedLong(sb.toString(),2);
            sb.setLength(0);
            shift = (char) ((preFixCount - i - 1) & 0xff);
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
