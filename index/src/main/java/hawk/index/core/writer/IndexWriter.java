package hawk.index.core.writer;

import hawk.index.core.directory.Constants;
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

    private AtomicLong bytesUsed;

    private ReentrantLock ramUsageLock;

    private Condition ramFull;

    private Condition ramEmpty;

    private AtomicInteger docIDAllocator;

    public IndexWriter(IndexWriterConfig config, Directory directory) {
        this.config = config;
        this.directory = directory;
        this.ivt = new ConcurrentHashMap<>();
        this.bytesUsed = new AtomicLong(0);// bytesUsed referrers to how many bytes ivt contains
        this.ramUsageLock = new ReentrantLock();
        this.ramFull = ramUsageLock.newCondition();
        this.ramEmpty = ramUsageLock.newCondition();
        this.docIDAllocator = new AtomicInteger(0);
        this.threadPoolExecutor =  new ThreadPoolExecutor( Constants.PROCESSOR_NUM * 2,
                Constants.PROCESSOR_NUM * 2, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
    }

// indexing is by default multithreaded. User specified multi-thread-indexing leads to unpredictable outcome.
    public void addDoc(Document doc){
        if(bytesUsed.get() >= config.getMaxRamUsage() * 0.95){
            //no one should write into ivt when ram is full until flushing is finished
            flush();//every flush creates a new segment and may incur sgement merge
            reset();
            ramEmpty.signalAll();
        }
        threadPoolExecutor.execute(new IndexChainPerThread(docIDAllocator, doc, ivt, bytesUsed, ramFull,
                config.getMaxRamUsage(), ramUsageLock, config.getAnalyzer()));
    }

    public void reset(){
        docIDAllocator.set(0);
        bytesUsed.set(0);
        ivt.clear();
    }

    public void flush(){
    }


    public void commit(){
        flush();
    }

    public static void main(String[] args) {
    }
}
