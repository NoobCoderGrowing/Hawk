package directory.memory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class MMap {
    public static MappedByteBuffer mmapFile(String files) throws IOException {
        FileChannel fc = new RandomAccessFile(files, "r").getChannel();
        MappedByteBuffer  buffer = fc.map(FileChannel.MapMode.READ_ONLY,0, fc.size());
        fc.close();
        return buffer;
    }


    public static MappedByteBuffer[] mmapFils(String[] files) throws IOException {
        MappedByteBuffer[] mappedByteBuffers = new MappedByteBuffer[files.length];
        for (int i = 0; i < files.length; i++) {
            FileChannel fc = new RandomAccessFile(files[i], "rw").getChannel();
            MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_WRITE,0, fc.size());
            mappedByteBuffers[i] = buffer;
            fc.close();
        }
        return mappedByteBuffers;
    }


    public static void unMMap(ByteBuffer bb) {
        if (null==bb || !bb.isDirect()) {
            return;
        }
        // we could use this type cast and call functions without reflection code,
        // but static import from sun.* package is risky for non-SUN virtual machine.
        //try { ((sun.nio.ch.DirectBuffer)cb).cleaner().clean(); } catch (Exception ex) { }
        try {
            Method cleaner = bb.getClass().getMethod("cleaner");
            cleaner.setAccessible(true);
            Method clean = Class.forName("sun.misc.Cleaner").getMethod("clean");
            clean.setAccessible(true);
            clean.invoke(cleaner.invoke(bb));
        } catch (Exception ex) {
        }
        bb = null;
    }
}
