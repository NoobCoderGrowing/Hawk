package hawk.index.core.writer;
import hawk.index.core.directory.Directory;
import hawk.index.core.document.Document;
import hawk.index.core.field.DoubleField;
import hawk.index.core.field.Field;
import hawk.index.core.field.StringField;
import hawk.index.core.util.ArrayUtil;
import hawk.index.core.util.NumberUtil;
import hawk.index.core.util.WrapInt;
import hawk.index.core.util.WrapLong;
import hawk.segment.core.Term;
import lombok.Data;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Data
public class DocWriter implements Runnable {

    private AtomicInteger docIDAllocator;

    private Document doc;

    private volatile HashMap<FieldTermPair, int[]> ivt;
    private volatile List<Pair> fdt;

    private AtomicLong bytesUsed;

    private long maxRamUsage;

    private ReentrantLock ramUsageLock;

    private Directory directory;

    private IndexWriterConfig config;

    private HashMap<ByteReference, Pair<byte[], int[]>> fdm;




    public DocWriter(AtomicInteger docIDAllocator, Document doc, List fdt, HashMap<FieldTermPair,
            int[]> ivt, AtomicLong bytesUsed, long maxRamUsage, ReentrantLock ramUsageLock, Directory directory,
                     IndexWriterConfig config, HashMap<ByteReference, Pair<byte[], int[]>> fdm) {
        this.docIDAllocator = docIDAllocator;
        this.doc = doc;
        this.fdt = fdt;
        this.ivt = ivt;
        this.bytesUsed = bytesUsed;
        this.maxRamUsage = maxRamUsage;
        this.ramUsageLock = ramUsageLock;
        this.directory = directory;
        this.config = config;
        this.fdm = fdm;
    }

    @Override
    public void run() {
        int docID = docIDAllocator.addAndGet(1);
        WrapLong bytesCurDoc = new WrapLong(0);
        // parallel tokenization here
        Pair<Integer, byte[][]>  docFDT = processStoredFields(doc, docID, bytesCurDoc);
        Pair docFDMIVT =  processIndexedFields(doc, docID, bytesCurDoc);
        HashMap<ByteReference, Pair<byte[], Integer>> docFDM = (HashMap<ByteReference, Pair<byte[], Integer>>) docFDMIVT.getLeft();
        HashMap<FieldTermPair, int[]> docIVT = (HashMap<FieldTermPair, int[]>) docFDMIVT.getRight();
        // flush when ram usage exceeds configuration
        ramUsageLock.lock();
        while(bytesUsed.get() + bytesCurDoc.getValue() >= maxRamUsage * 0.95){
            flush();
            reset();
        }
        // assemble memory index
        fdt.add(docFDT);
        assembleFDM(docFDM);
        assembleIVT(docIVT);
        bytesUsed.addAndGet(bytesCurDoc.getValue());
        ramUsageLock.unlock();
    }

    public void assembleFDM(HashMap<ByteReference, Pair<byte[], Integer>> docFDM){
        for (Map.Entry<ByteReference, Pair<byte[], Integer>> entry : docFDM.entrySet()){
            Pair<byte[], int[]> pair = fdm.putIfAbsent(entry.getKey(), new Pair<>(entry.getValue().getLeft(),
                    new int[]{entry.getValue().getRight(), 1}));
            if (pair != null){
                int filedLengthSum = pair.getRight()[0];
                filedLengthSum += entry.getValue().getRight();
                int docCount = pair.getRight()[1] + 1;
                pair.setRight(new int[]{filedLengthSum, docCount});
            }
        }
    }

    public void  assembleIVT(HashMap<FieldTermPair, int[]> docIVT){
        for (Map.Entry<FieldTermPair, int[] > entry : docIVT.entrySet()) {
            FieldTermPair fieldTermPair = entry.getKey();
            //assemble ivt
            int[] IDFreqLength = entry.getValue();
            int[] oldVal = ivt.putIfAbsent(fieldTermPair, IDFreqLength);
            if(oldVal != null){ // if already a posting exists, concatenates old and new
                oldVal = ArrayUtil.intsConcatenation(oldVal, IDFreqLength);
                ivt.put(fieldTermPair, oldVal);
            }
        }
    }

    public void reset(){
        bytesUsed.set(0);
        ivt.clear();
        fdt.clear();
        fdm.clear();
    }

    // write fdt into a buffer of 16kb
    // return false if the buffer can't fit
    public boolean insertChunk(int docID, byte[][] data, byte[] buffer, WrapInt pos){
        int remains = buffer.length - pos.getValue();
        int need = 0;
        int notEmpty = 0;
        for (int i = 0; i < data.length; i++) {
            if(data[i]!=null){
                need += data[i].length;
                notEmpty ++;
            }else{
                break;
            }
        }
        if(need <= remains){
            // write doc length
            DataOutput.writeVInt(need + 4, buffer, pos);
            // write docID
            DataOutput.writeInt(docID, buffer, pos);
            for (int i = 0; i < notEmpty; i++) {
                //write doc data
                DataOutput.writeBytes(data[i], buffer, pos);
            }
            return true;
        }
        return false;
    }

    // write a block into .fdt file
    public void writeFDTBloc(byte[] buffer, byte[] compressedBuffer, int maxCompressedLength, FileChannel fdtChannel,
                         WrapLong fdtPos, WrapInt bufferPos){
        //compress
        int compressedLength = config.getCompressor().compress(buffer, 0, buffer.length, compressedBuffer,
                0, maxCompressedLength);
        //write to .fdt
        ByteBuffer byteBuffer = ByteBuffer.wrap(compressedBuffer, 0, compressedLength);
        DataOutput.writeBytes(byteBuffer, fdtChannel, fdtPos);
        // clear buffer and buffer pos
        Arrays.fill(buffer, (byte) 0);
        bufferPos.setValue(0);
        Arrays.fill(compressedBuffer, (byte) 0);
    }

    public void writeFDX(FileChannel fc, int docID, WrapLong fdtPos, WrapLong fdxPos){
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
            WrapInt bufferPos = new WrapInt(0);
            WrapLong fdtPos = new WrapLong(0);
            WrapLong fdxPos = new WrapLong(0);
            Integer docID = null;
            for (int i = 0; i < fdt.size(); i++) {
                docID = (Integer) fdt.get(i).getLeft() + docBase;
                byte[][] data = (byte[][]) fdt.get(i).getRight();
                while(!insertChunk(docID, data, buffer, bufferPos)){ // if buffer is full, write to disk
                    writeFDX(fdxChannel, docID, fdtPos, fdxPos);
                    writeFDTBloc(buffer, compressedBuffer, maxCompressedLength, fdtChannel, fdtPos, bufferPos);
                }
            } //last write
            if(bufferPos.getValue() > 0){
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

    public void writeFDM(FileChannel fc, ArrayList<Map.Entry<ByteReference, Pair<byte[], int[]>>> fdmList){
        WrapLong pos = new WrapLong(0);
        for (int i = 0; i < fdmList.size(); i++) {
            byte[] field = fdmList.get(i).getKey().getBytes();
            byte type = fdmList.get(i).getValue().getLeft()[0];
            int fieldLengthSum = fdmList.get(i).getValue().getRight()[0];
            int docCount = fdmList.get(i).getValue().getRight()[1];
            int length = field.length + 9; // 1 for field type, 4 for fieldLengthSum and 4 for docCount
            DataOutput.writeInt(length, fc, pos);
            DataOutput.writeBytes(field, fc, pos);
            DataOutput.writeByte(type, fc, pos);
            DataOutput.writeInt(fieldLengthSum,fc,pos);
            DataOutput.writeInt(docCount, fc, pos);
        }
    }

    public void writeTIM(FileChannel fc, FieldTermPair fieldTermPair, WrapLong timPos, WrapLong frqPos){
        byte[] field = fieldTermPair.getField();
        byte[] term = fieldTermPair.getTerm();
        DataOutput.writeInt(field.length, fc, timPos);
        DataOutput.writeBytes(field, fc, timPos);
        DataOutput.writeInt(term.length, fc, timPos);
        DataOutput.writeBytes(term, fc, timPos);
        DataOutput.writeVLong(frqPos, fc, timPos);
    }

    public void writeFRQ(FileChannel fc, int[] posting, WrapLong frqPos){
        int length = posting.length/3; //  triple of docId, frequency and doc field length
        DataOutput.writeVInt(length, fc, frqPos);
        for (int i = 0; i < length; i++) {
            DataOutput.writeVInt(posting[i * 3 ], fc, frqPos);
            DataOutput.writeVInt(posting[i * 3 + 1], fc, frqPos);
            DataOutput.writeVInt(posting[i * 3 + 2], fc, frqPos);
        }
    }

    public void flushIndexed(Path timPath, Path frqPath, Path fdmPath, int docBase,
                             ArrayList<Map.Entry<FieldTermPair, int[]>> ivtList, ArrayList<Map.Entry<ByteReference, Pair<byte[], int[]>>>
                                     fdmList){
        try {
            FileChannel timChannel = new RandomAccessFile(timPath.toAbsolutePath().toString(),
                    "rw").getChannel();
            FileChannel frqChannel = new RandomAccessFile(frqPath.toAbsolutePath().toString(),
                    "rw").getChannel();
            FileChannel fdmChannel = new RandomAccessFile(fdmPath.toAbsolutePath().toString(),
                    "rw").getChannel();
            // write fdm
            writeFDM(fdmChannel, fdmList);
            WrapLong frqPos = new WrapLong(0);
            WrapLong timPos = new WrapLong(0);
            for (int i = 0; i < ivtList.size(); i++) { // write .tim and .frq
                FieldTermPair fieldTermPair = ivtList.get(i).getKey();
                int[] posting = ivtList.get(i).getValue();
                writeTIM(timChannel, fieldTermPair, timPos, frqPos);
                writeFRQ(frqChannel, posting, frqPos);
            }
            timChannel.force(false);
            frqChannel.force(false);
            fdmChannel.force(false);
            timChannel.close();
            frqChannel.close();
            fdmChannel.close();
        } catch (FileNotFoundException e) {
            log.error(".tim / .frq / .fdm file not found");
            System.exit(1);
        } catch (IOException e) {
            log.error("force flush .tim / .frq / .fdm file errored");
        }

    }

    public void sortFDM(ArrayList<Map.Entry<ByteReference, Pair<byte[], int[]>>> fdmList){
        Collections.sort(fdmList, (a, b) -> {
            byte[] aField = a.getKey().getBytes();
            byte[] bField = b.getKey().getBytes();
            for (int i = 0; i < aField.length && i < bField.length; i++) {
                int aFieldByte = (aField[i] & 0xff);
                int bFieldByte = (bField[i] & 0xff);
                if(aFieldByte != bFieldByte) {
                    return aFieldByte - bFieldByte;
                }
            }
            return aField.length - bField.length;
        });
    }

    public void sortIVT(ArrayList<Map.Entry<FieldTermPair, int[]>> ivtList){
        Collections.sort(ivtList,(a, b)->{
            FieldTermPair aP = a.getKey();
            FieldTermPair bP = b.getKey();
            byte[] aField = aP.getField();
            byte[] aTerm = aP.getTerm();
            byte[] bField = bP.getField();
            byte[] bTerm = bP.getTerm();
            for (int i = 0; i < aField.length && i < bField.length; i++) {
                int aFieldByte = (aField[i] & 0xff);
                int bFieldByte = (bField[i] & 0xff);
                if(aFieldByte != bFieldByte) {
                    return aFieldByte - bFieldByte;
                }
            }
            if(aField.length != bField.length){
                return aField.length - bField.length;
            }else{
                for (int i = 0; i < aTerm.length && i < bTerm.length; i++) {
                    int aTermByte = (aTerm[i] & 0xff);
                    int bTermByte = (bTerm[i] & 0xff);
                    if(aTermByte != bTermByte) {
                        return aTermByte - bTermByte;
                    }
                }
                return aTerm.length - bTerm.length;
            }
        });
    }

    public void flush(){
        log.info("start flush fdt and fdx");
        int docBase = directory.getDocBase();
        String[] files = directory.generateSegFiles();
        Path fdtPath = Paths.get(files[0]);
        Path fdxPath = Paths.get(files[1]);
        Path timPath = Paths.get(files[2]);
        Path frqPath = Paths.get(files[3]);
        Path fdmPath = Paths.get(files[4]);
        // sort fdt
        Collections.sort(fdt, (o1, o2) -> {
            Integer a = (Integer) o1.getLeft();
            Integer b = (Integer) o2.getLeft();
            return  a - b;
        });
        // sort fdm (by field lexicographically)
        ArrayList<Map.Entry<ByteReference, Pair<byte[], int[]>>> fdmList = new ArrayList<>(fdm.entrySet());
        sortFDM(fdmList);
        // sort ivt ( sort field first and then term lexicographically)
        ArrayList<Map.Entry<FieldTermPair, int[]>> ivtList = new ArrayList<>(ivt.entrySet());
        sortIVT(ivtList);
        flushStored(fdtPath, fdxPath, docBase);
        flushIndexed(timPath, frqPath, fdmPath, docBase, ivtList, fdmList);
    }



    public Pair<Integer, byte[][]> processStoredFields(Document doc, int docID, WrapLong bytesCurDoc){
        List<Field> fields = doc.getFields();
        byte[][] bytePool = new byte[10][];
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if(field.isStored == Field.Stored.YES){
                byte[] fieldBytes = field.getBytes();
                if(bytePool.length < i + 1){
                    bytePool = ArrayUtil.bytePoolGrow(bytePool);
                }
                bytePool[i] = fieldBytes;
                bytesCurDoc.setValue(bytesCurDoc.getValue() + fieldBytes.length);
            }
        }
        Pair<Integer, byte[][]> docFDT = new Pair<>(docID, bytePool);
        return docFDT;
    }


    public Pair processIndexedFields(Document doc, int docID, WrapLong bytesCurDoc){
        Pair<HashMap<ByteReference, Pair<byte[], Integer>>, HashMap<FieldTermPair, int[]>> ret = new Pair<>(new HashMap<>(),
                new HashMap<>());
        for (int i = 0; i < doc.getFields().size(); i++) {
            Field field = doc.getFields().get(i);
            if(field.isTokenized == Field.Tokenized.YES){
                processIndexedField(field, ret, docID, bytesCurDoc);
            }
        }
        return ret;
    }

    public byte getFieldType(Field field){
        byte termType = 0b00000000;
        if(field.isStored == Field.Stored.YES){
            termType |= 0b00000001;
        }
        if(field.isTokenized == Field.Tokenized.YES){
            termType |= 0b00000010;
        }
        if(field instanceof DoubleField){
            termType |= 0b00000100;
        } else if (field instanceof StringField) {
            termType |= 0b00001000;
        }
        return termType;
    }



    public void assembleFieldTypeMap(HashMap<ByteReference, Pair<byte[], Integer>> fieldTypeMap, byte[] fieldName, byte[] type,
                                     int fieldLegnth, WrapLong bytesCurDoc){
        Pair ret = fieldTypeMap.putIfAbsent(new ByteReference(fieldName), new Pair<>(type, fieldLegnth));
        if(ret != null){
            bytesCurDoc.setValue(bytesCurDoc.getValue() + fieldName.length + type.length + 4);
        }
    }

    public void assembleFieldTermMap(HashMap<FieldTermPair, int[]> fieldTermMap, byte[] filedName, byte[] filedValue,
                                     int docID, WrapLong bytesCurDoc, int fieldLength){
        FieldTermPair fieldTermPair = new FieldTermPair(filedName, filedValue);
        int[] preValue = fieldTermMap.putIfAbsent(fieldTermPair, new int[]{docID, 1, fieldLength});
        if(preValue != null){
            fieldTermMap.put(fieldTermPair, new int[]{docID, preValue[1] + 1, fieldLength});
        }else{// 12 bytes from docID, frequency and fieldLength
            bytesCurDoc.setValue(bytesCurDoc.getValue() + filedName.length + filedValue.length + 12);
        }
    }

    public void processIndexedField(Field field, Pair pair, int docID,
                                    WrapLong bytesCurDoc){
        HashMap<ByteReference, Pair<byte[], Integer>> fieldTypeMap = (HashMap) pair.getLeft();
        HashMap<FieldTermPair, int[]> fieldTermMap = (HashMap) pair.getRight();
        byte termType = getFieldType(field);
        byte[] filedName = field.getNameBytes();
        int filedLength = 0;
        if (field instanceof StringField){
            // since analyzer returns position information, terms in same field
            //with same value may be identified as different terms as their positions differ
            HashSet<Term> termSet = config.getAnalyzer().anlyze(((StringField) field).getValue(),
                    ((StringField) field).getName());
            for (Term t : termSet) {
                byte[] filedValue = t.getValue().getBytes(StandardCharsets.UTF_8);
                filedLength = ((StringField) field).getValue().length();
                assembleFieldTermMap(fieldTermMap,filedName,filedValue,docID,bytesCurDoc, filedLength);
            }
        } else if (field instanceof DoubleField) {
            double value = ((DoubleField) field).getValue();
            long sortableLong = NumberUtil.double2SortableLong(value);// double to sort
            String[] prefixString = NumberUtil.long2PrefixString(sortableLong,config.getPrecisionStep());
            filedLength = 1;
            for (int i = 0; i < prefixString.length; i++) {
                assembleFieldTermMap(fieldTermMap, filedName,prefixString[i].getBytes(StandardCharsets.UTF_8), docID, bytesCurDoc, filedLength);
            }
        }
        assembleFieldTypeMap(fieldTypeMap, filedName, new byte[]{termType},filedLength, bytesCurDoc);
    }

    public static void main(String[] args) {
    }

}
