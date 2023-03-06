package hawk.index.core.writer;

import hawk.index.core.directory.Directory;

public class IndexWriter {

    private final IndexWriterConfig config;

    private final Directory directory;

    public IndexWriter(IndexWriterConfig config, Directory directory) {
        this.config = config;
        this.directory = directory;
    }

    public static void main(String[] args) {
        System.out.println(1<<15);
    }
}
