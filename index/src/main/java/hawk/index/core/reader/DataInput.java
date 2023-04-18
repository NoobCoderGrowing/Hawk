package hawk.index.core.reader;

import hawk.index.core.util.NumberUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Slf4j
public class DataInput {

    public static int readVint(ByteBuffer buffer){
        byte b = buffer.get();
        if(b >= 0) return b;
        int i = b & 0x7f;
        b = buffer.get();
        i |= ((b & 0x7f) << 7);
        if(b >= 0) return i;
        b = buffer.get();
        i |= ((b & 0x7f) << 14);
        if(b > 0) return i;
        b = buffer.get();
        i |= ((b & 0x7f) << 21);
        if(b >= 0) return i;
        b = buffer.get();
        i |= ((b & 0x7f) << 28);
        if((b & 0xF0) == 0) return i;
        log.error("too many bits");
        System.exit(1);
        return -1;
    }

    public static byte[] growBytes(byte[] bytes){
        byte[] newBytes = new byte[bytes.length + 1];
        System.arraycopy(bytes,0,newBytes,0,bytes.length);
        return newBytes;
    }

    public static long readUnsignedLong(byte[] bytes){
        return ByteBuffer.wrap(bytes).getLong();
    }

    public static long read7bitBytes2Long(byte[] bytes, int start){
        long ret = 0;
        ret |= ((bytes[start] &0x7fL) << 57);
        ret |= ((bytes[start+1] &0x7fL) << 50);
        ret |= ((bytes[start+2] &0x7fL) << 43);
        ret |= ((bytes[start+3] &0x7fL) << 36);
        ret |= ((bytes[start+4] &0x7fL) << 29);
        ret |= ((bytes[start+5] &0x7fL) << 22);
        ret |= ((bytes[start+6] &0x7fL) << 15);
        ret |= ((bytes[start+7] &0x7fL) << 8);
        ret |= ((bytes[start+8] &0x7fL) << 1);
        ret |= ((bytes[start+9] &0x7fL) );
        return ret;
    }


    public static byte[] readVlongBytes(ByteBuffer buffer){
        byte[] vLong = new byte[0];
        byte b;
        int index = 0;
        while ((b = buffer.get()) < 0){
            vLong = growBytes(vLong);
            vLong[index] = b;
            index ++;
        }
        return vLong;
    }
}
