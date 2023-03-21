package hawk.index.core.directory.memory;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class UnMMap {

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
