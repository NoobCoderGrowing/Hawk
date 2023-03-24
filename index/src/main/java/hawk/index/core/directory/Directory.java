package hawk.index.core.directory;

import java.nio.file.Path;

public abstract class Directory {

    public abstract void updateSegInfo();

    public abstract int getDocBase();

    // fdt, fdx, tim, frq
    public abstract String[] generateSegFiles();
}
