package hawk.index.core.writer;



public class DataOutput {

    public static void wrteByte(byte input){}

    //input must be positive
    public static void writeVInt(int input){
        while((input & ~0b00000000000000000000000001111111) != 0){
            wrteByte((byte )((input & 0b00000000000000000000000001111111) | 0b00000000000000000000000010000000));
            input >>>= 7; // fill sign-bit with 0
        }
        wrteByte((byte)(input));
    }

    public static byte[] int2bytes(int i){
        byte[] ret = new byte[]{
                (byte) (i >> 24),
                (byte) (i >> 16),
                (byte) (i >> 8),
                (byte) i
        };
        return ret;
    }

}
