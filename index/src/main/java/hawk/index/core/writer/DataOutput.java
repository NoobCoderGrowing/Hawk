package hawk.index.core.writer;


import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

@Slf4j
public class DataOutput {

    public static void writeByte(byte input, FileChannel fc, Long pos){
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{input});
        try {
            fc.write(buffer, pos);
            pos ++;
        } catch (IOException e) {
            log.error("sth wrong with write Vint ");
        }

    }

    //input must be positive
    public static void writeVInt(int input, FileChannel fc, Long pos){
        while((input & ~0b00000000000000000000000001111111) != 0){
            writeByte((byte )((input & 0b00000000000000000000000001111111) | 0b00000000000000000000000010000000), fc, pos);
            input >>>= 7; // fill sign-bit with 0
        }
        writeByte((byte)(input), fc, pos);
    }

    public static void writeVLong(long input, FileChannel fc, Long pos){
        while ((input & ~0x7FL) != 0L) {
            writeByte((byte)((input & 0x7FL) | 0x80L), fc, pos);
            input >>>= 7;
        }
        writeByte((byte) input, fc, pos);
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

    public static void main(String[] args) {
        System.out.println(0x7FL);
        System.out.println(0x7F);
    }

}
