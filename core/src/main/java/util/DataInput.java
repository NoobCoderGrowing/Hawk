package util;


import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import java.nio.ByteBuffer;



@Slf4j
public class DataInput {
    //first byte the most significant
    public static long readLong(byte[] buffer){
        long value = 0;
        for (int i = 0; i < buffer.length; i++)
        {
            value = (value << 8) + (buffer[i] & 0xff);
        }
        return value;
    }


    public static long readVlong(byte[] buffer){
        long ret = 0;
        for (int i = 0; i < buffer.length; i++) {
            ret |= ((buffer[i] & 0x7f) << (7*i) ) ;
        }
        return ret;
    }

    public static byte[] readBytes(ByteBuffer buffer, int offset, int length){
        byte[] ret = new byte[length];
        for (int i = 0; i < length; i++) {
            ret[i] = buffer.get(offset++);
        }
        return ret;
    }

    public static byte[] readBytes(ByteBuffer buffer, int length){
        byte[] ret = new byte[length];
        buffer.get(ret);
        return ret;
    }



    public static long readVlong(ByteBuffer buffer){
        byte b = buffer.get();
        if(b >= 0) return b ;
        long i = b & 0x7fL;
        b = buffer.get();
        i |= ((b & 0x7fL) << 7);
        if(b >= 0) return i;
        b = buffer.get();
        i |= ((b & 0x7fL) << 14);
        if(b > 0) return i;
        b = buffer.get();
        i |= ((b & 0x7fL) << 21);
        if(b >= 0) return i;
        b = buffer.get();
        i |= ((b & 0x7fL) << 28);
        if(b > 0) return i;
        b = buffer.get();
        i |= ((b & 0x7fL) << 35);
        if(b >= 0) return i;
        b = buffer.get();
        i |= ((b & 0x7fL) << 42);
        if(b >= 0) return i;
        b = buffer.get();
        i |= ((b & 0x7fL) << 49);
        if(b >= 0) return i;
        b = buffer.get();
        i |= ((b & 0x7fL) << 56);
        if(b >= 0) return i;
        b = buffer.get();
        i |= ((b & 0x7fL) << 63);
        if((b & 0x2L) == 0) return i;
        log.error("too many bits");
        System.exit(1);
        return -1;
    }


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

    public static int readVintAtIndex(ByteBuffer buffer, int index){
        byte b = buffer.get(index++);
        if(b >= 0) {
            return b;
        }
        int i = b & 0x7f;
        b = buffer.get(index++);
        i |= ((b & 0x7f) << 7);
        if(b >= 0){
            return i;
        }
        b = buffer.get(index++);
        i |= ((b & 0x7f) << 14);
        if(b > 0){
            return i;
        }
        b = buffer.get(index++);
        i |= ((b & 0x7f) << 21);
        if(b >= 0){
            return i;
        }
        b = buffer.get(index++);
        i |= ((b & 0x7f) << 28);
        if((b & 0xF0) == 0){
            return i;
        }
        log.error("too many bits");
        System.exit(1);
        return -1;
    }


    public static int readVintAtIndex(ByteBuffer buffer, WrapInt indexWrapper){
        int index = indexWrapper.getValue();
        byte b = buffer.get(index++);
        if(b >= 0) {
            indexWrapper.setValue(index);
            return b;
        }
        int i = b & 0x7f;
        b = buffer.get(index++);
        i |= ((b & 0x7f) << 7);
        if(b >= 0){
            indexWrapper.setValue(index);
            return i;
        }
        b = buffer.get(index++);
        i |= ((b & 0x7f) << 14);
        if(b > 0){
            indexWrapper.setValue(index);
            return i;
        }
        b = buffer.get(index++);
        i |= ((b & 0x7f) << 21);
        if(b >= 0){
            indexWrapper.setValue(index);
            return i;
        }
        b = buffer.get(index++);
        i |= ((b & 0x7f) << 28);
        if((b & 0xF0) == 0){
            indexWrapper.setValue(index);
            return i;
        }
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
        byte[] vLong = new byte[1];
        byte b;
        int index = 0;
        while ((b = buffer.get()) < 0){
            vLong = growBytes(vLong);
            vLong[index] = b;
            index ++;
        }
        vLong[index] = b;
        return vLong;
    }

    public static void main(String[] args) {

    }

}
