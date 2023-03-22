package hawk.index.core.writer;
import hawk.index.core.document.Document;
import hawk.index.core.field.Field;
import hawk.index.core.field.StringField;
import hawk.segment.core.Term;
import hawk.segment.core.anlyzer.Analyzer;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class DocWriter implements Runnable {

    private AtomicInteger docIDAllocator;

    private Document doc;

    private volatile HashMap<FieldTermPair, int[]> ivt;

    private volatile HashMap<Integer, byte[][]> fdt;

    private volatile Long bytesUsed;

    private long maxRamUsage;

    private ReentrantLock ramUsageLock;

    private Analyzer analyzer;

    public DocWriter(AtomicInteger docIDAllocator, Document doc, HashMap fdt, HashMap<FieldTermPair,
            int[]> ivt, Long bytesUsed, long maxRamUsage, ReentrantLock ramUsageLock, Analyzer analyzer) {
        this.docIDAllocator = docIDAllocator;
        this.doc = doc;
        this.fdt = fdt;
        this.ivt = ivt;
        this.bytesUsed = bytesUsed;
        this.maxRamUsage = maxRamUsage;
        this.ramUsageLock = ramUsageLock;
        this.analyzer = analyzer;
    }

    @Override
    public void run() {
        int docID = docIDAllocator.addAndGet(1);
        Long bytesCurDoc = new Long(0);
        // parallel tokenization here
        Map.Entry<Integer, byte[][]> docFDT = processStoredFields(doc, docID, bytesCurDoc);
        HashMap<FieldTermPair, int[]> docIVT = processIndexedFields(doc, docID, bytesCurDoc);
        // flush when ram usage exceeds configuration
        ramUsageLock.lock();
        while(bytesUsed + bytesCurDoc >= maxRamUsage * 0.95){
            flush();
            reset();
        }
        // assemble memory index
        fdt.put(docFDT.getKey(),docFDT.getValue());
        for (Map.Entry<FieldTermPair, int[] > entry : docIVT.entrySet()) {
            FieldTermPair fieldTermPair = entry.getKey();
            int[] IDFreq = entry.getValue();
            int[] oldVal = ivt.putIfAbsent(fieldTermPair, IDFreq);
            if(oldVal != null){ // if already a posting exists, concatenates old and new
                oldVal = intsConcatenation(oldVal, IDFreq);
                ivt.put(fieldTermPair, oldVal);
            }
        }
        bytesUsed += bytesCurDoc;
        ramUsageLock.unlock();
    }

    public void reset(){
        bytesUsed = new Long(0);
        ivt.clear();
        fdt.clear();
    }

    public void flush(){

    }

    public byte[][] bytePoolGrow(byte[][] old){
        byte[][] ret = new byte[old.length+1][];
        for (int i = 0; i < old.length; i++) {
            ret[i] = old[i];
        }
        return ret;
    }

    public Map.Entry<Integer, byte[][]> processStoredFields(Document doc, int docID, Long bytesCurDoc){
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
                bytesCurDoc += fieldBytes.length;
            }
        }
        Map.Entry<Integer, byte[][]> docFDT = new AbstractMap.SimpleEntry<>(docID, bytePool);
        return docFDT;
    }


    public HashMap<FieldTermPair, int[]> processIndexedFields(Document doc, int docID, Long bytesCurDoc){
        HashMap<FieldTermPair, int[]> ret = new HashMap<>();
        for (int i = 0; i < doc.getFields().size(); i++) {
            Field field = doc.getFields().get(i);
            if(field.isTokenized == Field.Tokenized.YES){
                processIndexedField(field, ret, docID, bytesCurDoc);
            }
        }
        return ret;
    }

    public byte getTermType(Field field){
        byte termType = 0b00000000;
        if(field.isStored == Field.Stored.YES){
            termType |= 0b00000001;
        }
        if(field.isTokenized == Field.Tokenized.YES){
            termType |= 0b00000010;
        }
        return termType;
    }

    public int[] intsConcatenation(int[] a, int[] b){
        int[] temp = new int[a.length + b.length];
        System.arraycopy(a, 0, temp, 0, a.length);
        System.arraycopy(b, 0, temp, a.length, b.length);
        return temp;
    }

    public byte[] bytesConcatenation(byte[] bytes1, byte[] bytes2){
        byte[] temp = new byte[bytes1.length + bytes2.length];
        System.arraycopy(bytes1, 0, temp, 0, bytes1.length);
        System.arraycopy(bytes2, 0, temp, bytes1.length, bytes2.length);
        return temp;
    }

    public void processIndexedField(Field field, HashMap<FieldTermPair, int[]> result, int docID, Long bytesCurDoc){
        if (field instanceof StringField){
            // since analyzer returns position information, terms in same field
            //with same value may be identified as different terms as their positions differ
            HashSet<Term> termSet = analyzer.anlyze(((StringField) field).getValue(),
                    ((StringField) field).getName());
            byte termType = getTermType(field);
            for (Term t : termSet) {
                byte[] filedName = t.getFieldName().getBytes(StandardCharsets.UTF_16);
                filedName = bytesConcatenation(filedName, new byte[termType]);
                byte[] filedValue = t.getValue().getBytes(StandardCharsets.UTF_16);
                FieldTermPair fieldTermPair = new FieldTermPair(filedName, filedValue);
                int[] preValue = result.putIfAbsent(fieldTermPair, new int[]{docID, 1});
                if(preValue != null){
                    result.put(fieldTermPair, new int[]{docID, preValue[1] + 1});
                }else{
                    bytesCurDoc += (filedName.length + filedValue.length + 8); // 2 java ints are 8 bytes
                }
            }
        }
    }

    public static void main(String[] args) {

    }

}
