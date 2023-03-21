package hawk.index.core.writer;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class FlushControl extends Thread{

    private IndexWriterConfig config;
    private IndexWriter indexWriter;

    private Condition ramNotFull;

    private Condition ramNotEmpty;

    private ReentrantLock ramUsageLock;

    public FlushControl(IndexWriterConfig config, IndexWriter indexWriter, Condition ramNotFull,
                        ReentrantLock ramUsageLock, Condition ramNotEmpty) {
        this.config = config;
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
            indexWriter.flush();//every flush creates a new segment and may incur segement merge
            indexWriter.reset();
            ramNotFull.signal();
            ramUsageLock.unlock();
        }
    }

}
