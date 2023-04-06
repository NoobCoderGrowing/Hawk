package hawk.index.core.util;
public class NumberUtil {


    public static byte[][] long2PrefixFormat(long rawBits, int precisionStep){
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
        byte[][] ret = new byte[preFixCount][];
        for (int i = 0; i < preFixCount; i++) {
            ret[i] = long2Bytes(retLong[i]);
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

    public static void main(String[] args) {
        double a = -1.0112;
        double b = -1.0111;
        long aLong = double2SortableLong(a);
        long bLong = double2SortableLong(b);
        byte[] aByte = long2Bytes(aLong);
        byte[] bytes = long2Bytes(bLong);
        System.out.println(compareDoubleBytes(aByte,bytes));
    }
}
