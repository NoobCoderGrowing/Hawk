package hawk.index.core.writer;

import hawk.index.core.directory.Directory;
import hawk.index.core.document.Document;
import lombok.Data;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Data
public class IndexWriter {
    private final IndexWriterConfig config;

    private final Directory directory;

    private ThreadPoolExecutor threadPoolExecutor;

    private volatile ConcurrentHashMap<FieldTermPair, int[]> ivt;

    private volatile ConcurrentHashMap<Integer, byte[][]> fdt;

    private volatile Long bytesUsed;

    private ReentrantLock ramUsageLock;

    private Condition ramNotFull;

    private Condition ramNotEmpty;

    private AtomicInteger docIDAllocator;

    public IndexWriter(IndexWriterConfig config, Directory directory) {
        this.config = config;
        this.directory = directory;
        this.ivt = new ConcurrentHashMap<>();
        this.fdt = new ConcurrentHashMap<>();
        this.bytesUsed = new Long(0);
        this.ramUsageLock = new ReentrantLock();
        this.ramNotFull = ramUsageLock.newCondition();
        this.ramNotEmpty = ramUsageLock.newCondition();
        this.docIDAllocator = new AtomicInteger(0);
        this.threadPoolExecutor =  new ThreadPoolExecutor( config.getIndexerThreadNum(),
                config.getIndexerThreadNum(), 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
    }

// indexing is by default multithreaded. User specified multi-thread-indexing leads to unpredictable outcome.
    public void addDoc(Document doc){
        threadPoolExecutor.execute(new Inverter(docIDAllocator, doc, ivt, bytesUsed, ramNotFull, ramNotEmpty,
                config.getMaxRamUsage(), ramUsageLock, config.getAnalyzer(), fdt));
    }

    public void reset(){
        bytesUsed = new Long(0);
        ivt.clear();
        fdt.clear();
    }

    public void flush(){
    }


    public void commit(){
        flush();
        threadPoolExecutor.shutdown();
    }

    public static void main(String[] args) {
    }
}
