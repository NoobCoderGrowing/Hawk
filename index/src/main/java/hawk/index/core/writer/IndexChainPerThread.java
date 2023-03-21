package hawk.index.core.writer;
import hawk.index.core.document.Document;
import hawk.index.core.field.Field;
import hawk.index.core.field.StringField;
import hawk.segment.core.Term;
import hawk.segment.core.anlyzer.Analyzer;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

    private Analyzer analyzer;

    private ConcurrentHashMap<Integer, byte[][]> fdt;


    public IndexChainPerThread(AtomicInteger docIDAllocator, Document doc, ConcurrentHashMap<FieldTermPair,
            int[]> ivt,
                               AtomicLong bytesUsed, Condition ramFull, long maxRamUsage,
                               ReentrantLock ramUsageLock,
                               Analyzer analyzer, ConcurrentHashMap fdt) {
        this.docIDAllocator = docIDAllocator;
        this.doc = doc;
        this.ivt = ivt;
        this.bytesUsed = bytesUsed;
        this.ramFull = ramFull;
        this.maxRamUsage = maxRamUsage;
        this.ramUsageLock = ramUsageLock;
        this.analyzer = analyzer;
        this.fdt = fdt;
    }

    @Override
    public void run() {
        // tokenization here
        byte[][] storedFields = processStoredFields(doc);
        HashMap<FieldTermPair, Integer> result = processIndexedField(doc);
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
        // start constructing memory index
        int docID = docIDAllocator.incrementAndGet();
        fdt.put(docID, storedFields);
        for (Map.Entry<FieldTermPair, Integer> entry : result.entrySet()) {
            FieldTermPair fieldTermPair = entry.getKey();
            int frequency = entry.getValue();
            int[] IDFreq = new int[]{docID, frequency};
            int[] odlVal = ivt.putIfAbsent(fieldTermPair, IDFreq);
            if(odlVal != null){
                int[] newValue = new int[odlVal.length + 2];
                System.arraycopy(odlVal, 0, newValue, 0, odlVal.length);
                System.arraycopy(IDFreq, 0, newValue, odlVal.length, 2);
                ivt.put(fieldTermPair, newValue);
            }
        }

    }

    public byte[][] bytePoolGrow(byte[][] old){
        byte[][] ret = new byte[old.length+1][];
        for (int i = 0; i < old.length; i++) {
            ret[i] = old[i];
        }
        return ret;
    }

    public byte[][] processStoredFields(Document doc){
        List<Field> fields = doc.getFields();
        byte[][] bytePool = new byte[10][];
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if(field.isStored == Field.Stored.YES){
                byte[] fieldBytes = field.getBytes();
                if(bytePool.length < i + 1){
                    bytePool = bytePoolGrow(bytePool);
                }
                bytePool[i] = fieldBytes;
            }
        }
        return bytePool;
    }


    public HashMap<FieldTermPair, Integer> processIndexedField(Document doc){
        HashMap<FieldTermPair, Integer> result = new HashMap<>();
        for (int i = 0; i < doc.getFields().size(); i++) {
            Field field = doc.getFields().get(i);
            if(field.isTokenized == Field.Tokenized.YES){
                processIndexedField(field, result);
            }
        }
        return result;
    }

    public void processIndexedField(Field field, HashMap<FieldTermPair, Integer> result){
        if (field instanceof StringField){
            // since analyzer returns position information, terms in same field
            //with same value may identified as different terms as their positions differ
            HashSet<Term> termSet = analyzer.anlyze(((StringField) field).getValue(),
                    ((StringField) field).getName());
            byte termType = 0b00000000;
            for (Term t : termSet) {
                byte[] filedName = t.getFieldName().getBytes(StandardCharsets.UTF_16);
                byte[] filedValue = t.getValue().getBytes(StandardCharsets.UTF_16);
                FieldTermPair fieldTermPair = new FieldTermPair(filedName, filedValue, termType);
                Integer preValue = result.putIfAbsent(fieldTermPair, 1);
                if(preValue != null){
                    result.put(fieldTermPair, preValue + 1);
                }
            }
        }
    }

    public static void main(String[] args) {

    }

}
