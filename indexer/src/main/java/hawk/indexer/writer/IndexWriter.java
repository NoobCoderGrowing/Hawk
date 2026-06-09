package hawk.indexer.writer;
import common.ByteReference;
import common.Pair;
import directory.Directory;
import document.Document;
import hawk.indexer.writer.config.IndexConfig;
import directory.LiveDocsStore;
import directory.PkMapStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.BitSet;

@Data
@Slf4j
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

    private final BitSet liveDocs;

    public IndexWriter(IndexConfig config, Directory directory) {
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
        try {
            this.pkMap = new ConcurrentHashMap<>(PkMapStore.load(directory.getPath()));
            this.liveDocs = LiveDocsStore.load(directory.getPath(), directory.getSegmentInfo().getPreMaxID());
        } catch (IOException e) {
            throw new RuntimeException("failed to load pk.map or live.docs", e);
        }
    }

// indexing is by default multithreaded.
    public void addDoc(Document doc){
        Future<?> future = threadPoolExecutor.submit(new DocWriter(docIDAllocator, doc, fdt, ivt, bytesUsed,
                config.getMaxRamUsage(), ramUsageLock, directory, config, fdm, pkMap, liveDocs));
        futures.add(future);
    }

    // must call after all addDoc
    public void commit(){
        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.poll().get();
            } catch (InterruptedException e) {
                log.info("met InterruptedException during commit ");
            } catch (ExecutionException e) {
                log.error("met ExecutionException during commit", e.getCause());
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new RuntimeException(cause);
            }
        }
        threadPoolExecutor.shutdown();
        if(ivt.size() != 0 || fdt.size() != 0) {
            DocWriter lastDocWriter = new DocWriter(docIDAllocator, null, fdt, ivt, bytesUsed, config.getMaxRamUsage(),
                    ramUsageLock, directory, config, fdm, pkMap, liveDocs);
            lastDocWriter.flush();
        }
        try {
            PkMapStore.save(directory.getPath(), pkMap);
            LiveDocsStore.save(directory.getPath(), liveDocs);
        } catch (IOException e) {
            throw new RuntimeException("failed to save pk.map or live.docs", e);
        }
    }
}
