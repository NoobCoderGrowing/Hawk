package demo;

import directory.memory.MMap;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class MemRandomAccess {

    public static final byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value};
    }


    public static void main(String[] args) throws IOException {
        // map file 进memory
        FileChannel fileChannel = new RandomAccessFile(new File("/opt/temp"),"rw").getChannel();
        MappedByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE,0,
                4);
        // 把16当成4个byte写入buffer
        byte[] input = intToByteArray(16);
        mappedByteBuffer.position(0);
        mappedByteBuffer.put(input, 0, 4);
        byte[] output = new byte[4];
        //打印写完后的buffer位置
        System.out.println("current buffer pos is at " + mappedByteBuffer.position());
        mappedByteBuffer.clear();
        //打印重置的buffer位置
        System.out.println("current buffer pos is at " + mappedByteBuffer.position());
        //读取buffer
        mappedByteBuffer.get(output,0,4);
        for (int i = 0; i < output.length; i++) {
            System.out.format("0x%x",output[i]);
        }
        mappedByteBuffer.force();
        MMap.unMMap(mappedByteBuffer);
        fileChannel.close();
    }
}
