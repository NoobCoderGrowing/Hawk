package hawk.index.core.writer;


import hawk.index.core.util.NumberUtil;
import hawk.index.core.util.WrapInt;
import hawk.index.core.util.WrapLong;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

@Slf4j
public class DataOutput {

    public static void writeByte(byte input, FileChannel fc, WrapLong pos){
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{input});
        try {
            fc.write(buffer, pos.getValue());
            pos.setValue(pos.getValue() + 1);
        } catch (IOException e) {
            log.error("sth wrong with write byte ");
        }
    }

    //input must be positive
    public static void writeVInt(int input, FileChannel fc, WrapLong pos){
        while((input & ~0b00000000000000000000000001111111) != 0){
            writeByte((byte )((input & 0b00000000000000000000000001111111) | 0b00000000000000000000000010000000), fc, pos);
            input >>>= 7; // fill sign-bit with 0
        }
        writeByte((byte)(input), fc, pos);
    }

    public static void writeByte(byte input, byte[] buffer, WrapInt pos){
        buffer[pos.getValue()] = input;
        pos.setValue(pos.getValue() + 1);
    }

    //input must be positive
    public static void writeVInt(int input, byte[] buffer, WrapInt pos){
        while((input & ~0b00000000000000000000000001111111) != 0){
            writeByte((byte )((input & 0b00000000000000000000000001111111) | 0b00000000000000000000000010000000), buffer, pos);
            input >>>= 7; // fill sign-bit with 0
        }
        writeByte((byte)(input), buffer, pos);
    }


    public static void writeVLong(WrapLong input, FileChannel fc, WrapLong pos){
        long i = input.getValue();
        while ((i & ~0x7FL) != 0L) {
            writeByte((byte)((i & 0x7FL) | 0x80L), fc, pos);
            i >>>= 7;
        }
        writeByte((byte) i, fc, pos);
    }

    public static void writeBytes(byte[] bytes, byte[] buffer, WrapInt pos){
        int length = bytes.length;
        System.arraycopy(bytes,0, buffer, pos.getValue(), length);
        pos.setValue(pos.getValue() + length);
    }

    public static void writeBytes(ByteBuffer byteBuffer, FileChannel fc, WrapLong pos){
        try {
            fc.write(byteBuffer, pos.getValue());
            pos.setValue(pos.getValue() + byteBuffer.limit());
        } catch (IOException e) {
            log.error("sth wrong with write byte ");
        }
    }


    public static void writeBytes(byte[] bytes, FileChannel fc, WrapLong pos){
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        try {
            fc.write(byteBuffer, pos.getValue());
            pos.setValue(pos.getValue() + bytes.length);
        } catch (IOException e) {
            log.error("sth wrong with write byte ");
        }
    }

    public static void writeInt(int i, byte[] buffer, WrapInt pos){
        byte[] bytes = NumberUtil.int2Bytes(i);
        writeBytes(bytes, buffer, pos);
    }


    public static void writeInt(int i, FileChannel fc, WrapLong pos){
        byte[] bytes = NumberUtil.int2Bytes(i);
        writeBytes(bytes, fc, pos);
    }

}
