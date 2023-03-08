package hawk.index.core.writer;

import hawk.index.core.directory.Constants;
import hawk.index.core.directory.Directory;
import hawk.index.core.document.Document;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class IndexWriter {

    //max docID from last index write, docID grows linearly
    private int preMaxDocID = 1;

    private int curDocID = preMaxDocID + 1;

    private final IndexWriterConfig config;

    private final Directory directory;

    private ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor( Constants.PROCESSOR_NUM,
            Constants.PROCESSOR_NUM, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

    public IndexWriter(IndexWriterConfig config, Directory directory) {
        this.config = config;
        this.directory = directory;
        setPreMaxDocID();
    }

    //only allow set during writer construction
    private void setPreMaxDocID(){

    }

    public void addDoc(Document doc){

    }

    public void commit(){}

    public static void main(String[] args) {
        System.out.println(Constants.PROCESSOR_NUM);
    }
}
