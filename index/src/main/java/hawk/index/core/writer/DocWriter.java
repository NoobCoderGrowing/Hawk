package hawk.index.core.writer;
import hawk.index.core.directory.Directory;
import hawk.index.core.document.Document;
import hawk.index.core.field.Field;
import hawk.index.core.field.StringField;
import hawk.segment.core.Term;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class DocWriter implements Runnable {

    private AtomicInteger docIDAllocator;

    private Document doc;

    private volatile HashMap<FieldTermPair, int[]> ivt;
    private volatile List<Pair> fdt;

    private volatile Long bytesUsed;

    private long maxRamUsage;

    private ReentrantLock ramUsageLock;

    private Directory directory;

    private IndexWriterConfig config;




    public DocWriter(AtomicInteger docIDAllocator, Document doc, List fdt, HashMap<FieldTermPair,
            int[]> ivt, Long bytesUsed, long maxRamUsage, ReentrantLock ramUsageLock, Directory directory,
                     IndexWriterConfig config) {
        this.docIDAllocator = docIDAllocator;
        this.doc = doc;
        this.fdt = fdt;
        this.ivt = ivt;
        this.bytesUsed = bytesUsed;
        this.maxRamUsage = maxRamUsage;
        this.ramUsageLock = ramUsageLock;
        this.directory = directory;
        this.config = config;
    }

    @Override
    public void run() {
        int docID = docIDAllocator.addAndGet(1);
        Long bytesCurDoc = new Long(0);
        // parallel tokenization here
        Pair<Integer, byte[][]>  docFDT = processStoredFields(doc, docID, bytesCurDoc);
        HashMap<FieldTermPair, int[]> docIVT = processIndexedFields(doc, docID, bytesCurDoc);
        // flush when ram usage exceeds configuration
        ramUsageLock.lock();
        while(bytesUsed + bytesCurDoc >= maxRamUsage * 0.95){
            flush();
            reset();
        }
        // assemble memory index
        fdt.add(docFDT);
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

    // write fdt into a buffer of 16kb
    // return false if the buffer can't fit
    public boolean insertChunk(int docID, byte[][] data, byte[] buffer, Integer pos){
        int remains = buffer.length - pos;
        int need = 0;
        int notEmpty = 0;
        for (int i = 0; i < data.length; i++) {
            if(data[i]!=null){
                need += data[i].length;
                notEmpty ++;
            }
        }
        if(need <= remains){
            byte[] idBytes = DataOutput.int2bytes(docID);
            System.arraycopy(idBytes, 0, buffer, pos, 4);
            pos += 4;
            for (int i = 0; i < notEmpty; i++) {
                int length = data[i].length;
                System.arraycopy(data[i], 0, buffer, pos, length);
                pos += length;
            }
            return true;
        }
        return false;
    }

    public void writeFDTBloc(byte[] buffer, byte[] compressedBuffer, int maxCompressedLength, FileChannel fdtChannel,
                         Long filePos, Integer bufferPos){
        try {
            int compressedLength = config.getCompressor().compress(buffer, 0, buffer.length, compressedBuffer,
                    0, maxCompressedLength);
            DataOutput.writeVInt(compressedLength, fdtChannel, filePos);
            ByteBuffer byteBuffer = ByteBuffer.wrap(compressedBuffer, 0, compressedLength);
            fdtChannel.write(byteBuffer, filePos);
            filePos += compressedLength;
            // clear buffer and buffer pos
            Arrays.fill(buffer, (byte) 0);
            bufferPos = 0;
            Arrays.fill(compressedBuffer, (byte) 0);
        } catch (IOException e) {
            log.error("write fdt or fdx failed");
            System.exit(1);
        }
    }

    public void writeFDX(FileChannel fc, int docID, Long fdtPos, Long fdxPos){
        DataOutput.writeVInt(docID, fc, fdxPos);
        DataOutput.writeVLong(fdtPos, fc, fdxPos);
    }

    public void flushStored(Path fdtPath, Path fdxPath, int docBase){
        try {
            FileChannel fdtChannel = new RandomAccessFile(fdtPath.toAbsolutePath().toString(), "rw").getChannel();
            FileChannel fdxChannel = new RandomAccessFile(fdxPath.toAbsolutePath().toString(), "rw").getChannel();
            // get compression config
            byte[] buffer = new byte[config.getBlocSize()];
            int maxCompressedLength = config.getCompressor().maxCompressedLength(buffer.length);
            byte[] compressedBuffer = new byte[maxCompressedLength];
            Integer bufferPos = new Integer(0);
            Long fdtPos = new Long(0);
            Long fdxPos = new Long(0);
            Integer docID = null;
            for (int i = 0; i < fdt.size(); i++) {
                docID = (Integer) fdt.get(i).getLeft() + docBase;
                byte[][] data = (byte[][]) fdt.get(i).getRight();
                while(!insertChunk(docID, data, buffer, bufferPos)){ // if buffer is full, write to disk
                    writeFDX(fdxChannel, docID, fdtPos, fdxPos);
                    writeFDTBloc(buffer, compressedBuffer, maxCompressedLength, fdtChannel, fdtPos, bufferPos);
                }
            } //last write
            if(bufferPos > 0){
                writeFDX(fdxChannel, docID, fdtPos, fdxPos);
                writeFDTBloc(buffer, compressedBuffer, maxCompressedLength, fdtChannel, fdtPos, bufferPos);
            }
            //close channel
            fdxChannel.force(false);
            fdtChannel.force(false);
            fdtChannel.close();
            fdtChannel.close();
        } catch (FileNotFoundException e) {
            log.error("fdt or fdx file has not been generated");
            System.exit(1);
        } catch (IOException e) {
            log.error("sth wrong when close fdx or fdx file channel");
        }
    }

    public void flush(){
        log.info("start flush fdt and fdx");
        int docBase = directory.getDocBase();
        String[] files = directory.generateSegFiles();
        Path fdtPath = Paths.get(files[0]);
        Path fdxPath = Paths.get(files[1]);
        Path timPath = Paths.get(files[2]);
        Path frqPath = Paths.get(files[3]);

        Collections.sort(fdt, (o1, o2) -> {
            Integer a = (Integer) o1.getLeft();
            Integer b = (Integer) o2. getLeft();
            return  a - b;
        });
        flushStored(fdtPath, fdxPath, docBase);
    }

    public byte[][] bytePoolGrow(byte[][] old){
        byte[][] ret = new byte[old.length+1][];
        for (int i = 0; i < old.length; i++) {
            ret[i] = old[i];
        }
        return ret;
    }

    public Pair<Integer, byte[][]> processStoredFields(Document doc, int docID, Long bytesCurDoc){
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
        Pair<Integer, byte[][]> docFDT = new Pair<>(docID, bytePool);
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
            HashSet<Term> termSet = config.getAnalyzer().anlyze(((StringField) field).getValue(),
                    ((StringField) field).getName());
            byte termType = getTermType(field);
            for (Term t : termSet) {
                byte[] filedName = t.getFieldName().getBytes(StandardCharsets.UTF_16);
                filedName = bytesConcatenation(filedName, new byte[]{termType});
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
