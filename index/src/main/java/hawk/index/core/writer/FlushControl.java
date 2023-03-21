package hawk.index.core.writer;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class FlushControl implements Runnable{

    private IndexWriterConfig config;
    private AtomicLong bytesUsed;

    private IndexWriter indexWriter;

    private Condition ramNotFull;

    private Condition ramNotEmpty;

    private ReentrantLock ramUsageLock;

    public FlushControl(IndexWriterConfig config, AtomicLong bytesUsed, IndexWriter indexWriter, Condition ramNotFull,
                        ReentrantLock ramUsageLock, Condition ramNotEmpty) {
        this.config = config;
        this.bytesUsed = bytesUsed;
        this.indexWriter = indexWriter;
        this.ramNotFull = ramNotFull;
        this.ramUsageLock = ramUsageLock;
        this.ramNotEmpty = ramNotEmpty;
    }

    @Override
    public void run() {
        while(true){
            ramUsageLock.lock();
            try {
                ramNotEmpty.await();
            } catch (InterruptedException e) {
                log.info("flush control is woke up");
            }
            indexWriter.flush();//every flush creates a new segment and may incur sgement merge
            indexWriter.reset();
            ramNotFull.signal();
            ramUsageLock.unlock();
        }
    }

}
