package hawk.indexer.writer;
import common.ByteReference;
import common.Pair;
import directory.Directory;
import document.Document;
import hawk.indexer.writer.config.IndexConfig;
import directory.PkMapStore;
import lombok.Data;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Data
public class IndexWriter {

    /** indexWriter configuration
     * Configurable object:
     *  Analyzer
     *  Thread count used to do indexing
     *  Maximum memory allowed to do indexing
     *  **/
    private final IndexConfig config;

    private final Directory directory;

    private ThreadPoolExecutor threadPoolExecutor;

    // in-mem inverted index
    private volatile HashMap<FieldTermPair, int[][]> ivt;

    //stored doc fields
    private volatile List<Pair> fdt;

    // calculation of byte used in fdt and ivt
    private AtomicLong bytesUsed;

    // lock for flush and reset byteUsed
    private ReentrantLock ramUsageLock;

    //documentID, increase linearly from 0 since every time an indexWriter is opened
    private AtomicInteger docIDAllocator;
    private ConcurrentLinkedQueue<Future> futures;

    private HashMap<ByteReference, Pair<byte[], int[]>> fdm;

    private LinkedBlockingQueue<Runnable> blockingQueue;

    private final Map<Long, Integer> pkMap;

    public IndexWriter(IndexConfig config, Directory directory) {
        this(config, directory, true);
    }

    public IndexWriter(IndexConfig config, Directory directory, boolean loadExistingPkMap) {
        this.config = config;
        this.directory = directory;
        this.ivt = new HashMap<>();
        this.fdt = new ArrayList<>();
        this.bytesUsed = new AtomicLong(0);
        this.ramUsageLock = new ReentrantLock();
        this.docIDAllocator = new AtomicInteger(0);
        this.blockingQueue = new LinkedBlockingQueue<>();
        this.threadPoolExecutor =  new ThreadPoolExecutor( config.getIndexerThreadNum(),
                config.getIndexerThreadNum(), 0, TimeUnit.MILLISECONDS,
                this.blockingQueue);
        this.futures = new ConcurrentLinkedQueue<>();
        this.fdm = new HashMap<>();
        if (loadExistingPkMap) {
            try {
                this.pkMap = new ConcurrentHashMap<>(PkMapStore.load(directory.getPath()));
            } catch (IOException e) {
                throw new RuntimeException("failed to load pk.map", e);
            }
        } else {
            this.pkMap = new ConcurrentHashMap<>();
        }
    }

// indexing is by default multithreaded.
    public void addDoc(Document doc){
        Future<?> future = threadPoolExecutor.submit(new DocWriter(docIDAllocator, doc, fdt, ivt, bytesUsed,
                config.getMaxRamUsage(), ramUsageLock, directory, config, fdm, pkMap));
        futures.add(future);
    }

    // must call after all addDoc
    public void commit() throws Exception{
        try {
            Future<?> future;
            while ((future = futures.poll()) != null) {
                future.get();
            }
        } catch (ExecutionException e) {
            threadPoolExecutor.shutdownNow();
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        } catch (Exception e) {
            threadPoolExecutor.shutdownNow();
            throw e;
        }
        threadPoolExecutor.shutdown();
        if(ivt.size() != 0 || fdt.size() != 0) {
            DocWriter lastDocWriter = new DocWriter(docIDAllocator, null, fdt, ivt, bytesUsed, config.getMaxRamUsage(),
                    ramUsageLock, directory, config, fdm, pkMap);
            lastDocWriter.flush();
        }
        try {
            PkMapStore.save(directory.getPath(), pkMap);
        } catch (IOException e) {
            throw new RuntimeException("failed to save pk.map", e);
        }
    }
}
