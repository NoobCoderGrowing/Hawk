package hawk.index.core.writer;
import hawk.index.core.document.Document;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class IndexChainPerThread implements Runnable {

    private AtomicInteger docIDAllocator;

    private Document doc;

    private volatile ConcurrentHashMap<FieldTermPair, int[]> ivt;

    private AtomicLong bytesUsed;

    private Condition ramFull;

    private long maxRamUsage;

    private ReentrantLock ramUsageLock;


    public IndexChainPerThread(AtomicInteger docIDAllocator, Document doc, ConcurrentHashMap<FieldTermPair,
            int[]> ivt,
                               AtomicLong bytesUsed, Condition ramFull, long maxRamUsage, ReentrantLock ramUsageLock) {
        this.docIDAllocator = docIDAllocator;
        this.doc = doc;
        this.ivt = ivt;
        this.bytesUsed = bytesUsed;
        this.ramFull = ramFull;
        this.maxRamUsage = maxRamUsage;
        this.ramUsageLock = ramUsageLock;
    }

    @Override
    public void run() {
        // tokenization here
        //--------
        long bytesCurDoc = 0;
        ramUsageLock.lock();// though atomic class method is atomic, but between methods are not atomic
        while(bytesUsed.addAndGet(bytesCurDoc) >= maxRamUsage * 0.95){
            bytesUsed.addAndGet(-bytesCurDoc);
            try {
                ramFull.await();// if ram is full, wait for flushing
            } catch (InterruptedException e) {
                log.error("IndexChainPerThread is interrupted");
            }
        }
        ramUsageLock.unlock();
        int docID = docIDAllocator.incrementAndGet();
        // generate in mem ivt here


    }
}
