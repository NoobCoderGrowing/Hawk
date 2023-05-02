package writer;
import directory.Directory;
import document.Document;
import field.DoubleField;
import field.Field;
import field.StringField;
import util.*;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Data
public class DocWriter implements Runnable {

    private AtomicInteger docIDAllocator;

    private Document doc;

    private volatile HashMap<FieldTermPair, int[][]> ivt;
    private volatile List<Pair<Integer, byte[][]>> fdt;

    private AtomicLong bytesUsed;

    private long maxRamUsage;

    private ReentrantLock ramUsageLock;

    private Directory directory;

    private IndexConfig config;

    private HashMap<ByteReference, Pair<byte[], int[]>> fdm;

    public DocWriter(AtomicInteger docIDAllocator, Document doc, List fdt, HashMap<FieldTermPair,
            int[][]> ivt, AtomicLong bytesUsed, long maxRamUsage, ReentrantLock ramUsageLock, Directory directory,
                     IndexConfig config, HashMap<ByteReference, Pair<byte[], int[]>> fdm) {
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
        WrapLong bytesCurDoc = new WrapLong(0);
        // parallel tokenization here
        byte[][]  docFDT = processStoredFields(doc, bytesCurDoc);
        Pair docFDMIVT =  processIndexedFields(doc, bytesCurDoc);
//        Pair docFDMIVT =  processIndexedFields(doc, docID, bytesCurDoc);
        HashMap<ByteReference, Pair<byte[], Integer>> docFDM = (HashMap<ByteReference, Pair<byte[], Integer>>) docFDMIVT.getLeft();
        HashMap<FieldTermPair, int[]> docIVT = (HashMap<FieldTermPair, int[]>) docFDMIVT.getRight();
        // flush when ram usage exceeds configuration
        ramUsageLock.lock();
        while(bytesUsed.get() + bytesCurDoc.getValue() >= maxRamUsage * 0.95){
            flush();
            reset();
        }
        int docID = docIDAllocator.addAndGet(1);
        // assemble memory index
        assembleFDT(docFDT, docID);
        assembleFDM(docFDM);
        assembleIVT(docIVT, docID);
        bytesUsed.addAndGet(bytesCurDoc.getValue() + 8); //8bytes for 2 docID in FDM and IVT
        ramUsageLock.unlock();
    }

    public void assembleFDT(byte[][] docFDT, int docID){
        fdt.add(new Pair<>(docID, docFDT));
    }
    // doc fdm key: filed name; value1:field type, value2: field value length
    // global fdm key: field name; value left: field type; value right1: field value length, value right2: doc count
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


    //key: field term pair; value: doc frequency, field value length
    public void  assembleIVT(HashMap<FieldTermPair, int[]> docIVT, int docID){
        for (Map.Entry<FieldTermPair, int[] > entry : docIVT.entrySet()) {
            FieldTermPair fieldTermPair = entry.getKey();
            //assemble ivt
            int[] IDFreqLength = new int[]{docID, entry.getValue()[0], entry.getValue()[1]};
            int[][] value = new int[][]{IDFreqLength};
            int[][] oldVal = ivt.putIfAbsent(fieldTermPair, value);
            if(oldVal != null){ // if already a posting exists, concatenates old and new
                oldVal = ArrayUtil.grow2DIntArray(oldVal);
                oldVal[oldVal.length-1] = entry.getValue();
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
    public boolean insertBlock(int docID, byte[][] data, byte[] buffer, WrapInt pos){
        int remains = buffer.length - pos.getValue(); // calculate bytes left in the buffer
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

        if(need + 10 <= remains){ // 10 bytes for docID and field count
            // write docID
            DataOutput.writeVInt(docID, buffer, pos);
            // write field count
            DataOutput.writeVInt(notEmpty, buffer, pos);
            for (int i = 0; i < notEmpty; i++) {
                //write each field
                DataOutput.writeBytes(data[i], buffer, pos);
            }
            return true;
        }
        return false;
    }

    // write a block into .fdt file
    public void writeCompressedBloc(byte[] buffer, byte[] compressedBuffer, int maxCompressedLength, FileChannel fdtChannel,
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
        log.info("fdx writing ===> " + "docID is " + docID + ", fdt offset is " + fdtPos.getValue());
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
            log.info("start writing fdx and fdt");
            if(fdt.size() > 0) {
                int docID = fdt.get(0).getLeft() + docBase;
                writeFDX(fdxChannel, docID, fdtPos, fdxPos);// first fdx write
                for (int i = 0; i < fdt.size(); i++) {
                    docID = fdt.get(i).getLeft() + docBase;
                    byte[][] data = (byte[][]) fdt.get(i).getRight();
                    if(!insertBlock(docID, data, buffer, bufferPos)){ // if buffer is full, write to disk
                        writeCompressedBloc(buffer, compressedBuffer, maxCompressedLength, fdtChannel, fdtPos,
                                bufferPos);
                        // current docID is the start id of next bloc
                        writeFDX(fdxChannel, docID, fdtPos, fdxPos);
                    }
                } //last write
                if(bufferPos.getValue() > 0){
                    writeCompressedBloc(buffer, compressedBuffer, maxCompressedLength, fdtChannel, fdtPos, bufferPos);
                }
            }
            log.info("end of writing fdx and fdt");
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
        log.info("start writting fdm");
        for (int i = 0; i < fdmList.size(); i++) {
            byte[] field = fdmList.get(i).getKey().getBytes();
            byte type = fdmList.get(i).getValue().getLeft()[0];
            int fieldLengthSum = fdmList.get(i).getValue().getRight()[0];
            int docCount = fdmList.get(i).getValue().getRight()[1];
            int length = field.length;
            log.info("field name is " + new String(field) + ", fieldLength sum is " + fieldLengthSum +
                    ", total doc count of this field is " + docCount);
            DataOutput.writeInt(length, fc, pos);
            DataOutput.writeBytes(field, fc, pos);
            DataOutput.writeByte(type, fc, pos);
            DataOutput.writeInt(fieldLengthSum,fc,pos);
            DataOutput.writeInt(docCount, fc, pos);
        }
        log.info("end of writting fdm");
    }

    public void writeTIM(FileChannel fc, FieldTermPair fieldTermPair, WrapLong timPos, WrapLong frqPos){
        byte[] field = fieldTermPair.getField();
        byte[] term = fieldTermPair.getTerm();
        log.info("tim writing ==> filed name is " + new String(field) + ", term is " + new String(term) +
                ", frq offset is " + frqPos.getValue());
        DataOutput.writeInt(field.length, fc, timPos);
        DataOutput.writeBytes(field, fc, timPos);
        DataOutput.writeInt(term.length, fc, timPos);
        DataOutput.writeBytes(term, fc, timPos);
        DataOutput.writeVLong(frqPos, fc, timPos);
    }

    public void writeFRQ(FileChannel fc, int[][] posting, WrapLong frqPos, int docBase){
        int length = posting.length;
        log.info("frq writing ==> " + "posting length is " + length);
        DataOutput.writeVInt(length, fc, frqPos);
        for (int i = 0; i < length; i++) {
            log.info("frq writing ==> " + "doc id is " + posting[i][0] + ", frequency is " + posting[i][1] +
                    ", field value length is " + posting[i][2]);
            DataOutput.writeVInt(posting[i][0] + docBase, fc, frqPos);
            DataOutput.writeVInt(posting[i][1], fc, frqPos);
            DataOutput.writeVInt(posting[i][2], fc, frqPos);
        }
    }

    public void flushIndexed(Path timPath, Path frqPath, Path fdmPath, ArrayList<Map.Entry<FieldTermPair, int[][]>>
            ivtList, ArrayList<Map.Entry<ByteReference, Pair<byte[], int[]>>> fdmList, int docBase){
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
            log.info("start writing tim and frq");
            for (int i = 0; i < ivtList.size(); i++) { // write .tim and .frq
                FieldTermPair fieldTermPair = ivtList.get(i).getKey();
                int[][] posting = ivtList.get(i).getValue();
                writeTIM(timChannel, fieldTermPair, timPos, frqPos);
                writeFRQ(frqChannel, posting, frqPos, docBase);
            }
            log.info("end of writing tim and frq");
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

    public void sortIVTList(ArrayList<Map.Entry<FieldTermPair, int[][]>> ivtList){
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

    public void mergetest(int docBase){
        int segCount = directory.getSegmentInfo().getSegCount();
        if(segCount > 1) {
            log.info("merge start now," + " cur segment count is " + segCount + ", cur file number is " +
                    directory.getFiles().size() + ", cur maxDocID is " + directory.getSegmentInfo().getPreMaxID());
            IndexMerger indexMerger = new IndexMerger(directory, config, docIDAllocator, docBase);
            indexMerger.merge();
            log.info("merge end now," + "cur segment count is " + directory.getSegmentInfo().getSegCount() +
                    ", cur file number is " + directory.getFiles().size() + ", cur maxDocID is " +
                    directory.getSegmentInfo().getPreMaxID());
        }
    }

    public void flush(){
        log.info("start flushing");
        Path[] files = directory.generateSegFiles();
        int docBase = directory.getSegmentInfo().getPreMaxID();
        Path fdtPath = files[0];
        Path fdxPath = files[1];
        Path timPath = files[2];
        Path frqPath = files[3];
        Path fdmPath = files[4];
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
        ArrayList<Map.Entry<FieldTermPair, int[][]>> ivtList = new ArrayList<>(ivt.entrySet());
        sortIVTList(ivtList);
        //posting is already sorted
//        sortPosting(ivtList);
        flushStored(fdtPath, fdxPath, docBase);
        flushIndexed(timPath, frqPath, fdmPath, ivtList, fdmList, docBase);
        directory.updateSegInfo(docIDAllocator.get() + docBase, 1);
        mergetest(docBase);
    }

    public byte[][] processStoredFields(Document doc, WrapLong bytesCurDoc){
        List<Field> fields = doc.getFields();
        byte[][] bytePool = new byte[10][];
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if(field.isStored == Field.Stored.YES){
                byte[] fieldBytes = field.serialize();
                if(bytePool.length < i + 1){
                    bytePool = ArrayUtil.bytePoolGrow(bytePool);
                }
                bytePool[i] = fieldBytes;
                bytesCurDoc.setValue(bytesCurDoc.getValue() + fieldBytes.length);
            }
        }
        return bytePool;
    }

    public Pair processIndexedFields(Document doc, WrapLong bytesCurDoc){
        Pair<HashMap<ByteReference, Pair<byte[], Integer>>, HashMap<FieldTermPair, int[]>> ret = new Pair<>(new HashMap<>(),
                new HashMap<>());
        for (int i = 0; i < doc.getFields().size(); i++) {
            Field field = doc.getFields().get(i);
            if(field.isTokenized == Field.Tokenized.YES){
                processIndexedField(field, ret, bytesCurDoc);
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
                                     WrapLong bytesCurDoc, int fieldLength){
        FieldTermPair fieldTermPair = new FieldTermPair(filedName, filedValue);
        int[] preValue = fieldTermMap.putIfAbsent(fieldTermPair, new int[]{1, fieldLength});
        if(preValue != null){
            fieldTermMap.put(fieldTermPair, new int[]{preValue[0] + 1, fieldLength});
        }else{// 8 bytes from docID, frequency and fieldLength
            bytesCurDoc.setValue(bytesCurDoc.getValue() + filedName.length + filedValue.length + 8);
        }
    }

    public void processIndexedField(Field field, Pair pair,
                                    WrapLong bytesCurDoc){
        //key: filed name; value1:field type, value2: field value length
        HashMap<ByteReference, Pair<byte[], Integer>> fieldTypeMap = (HashMap) pair.getLeft();
        //key: field term pair; value: doc frequency, field value length
        HashMap<FieldTermPair, int[]> fieldTermMap = (HashMap) pair.getRight();
        byte termType = getFieldType(field);
        byte[] filedName = field.serializeName();
        int filedLength = 0;
        if (field instanceof StringField){
            // since analyzer returns position information, terms in same field
            //with same value may be identified as different terms as their positions differ
            HashSet<Term> termSet = config.getAnalyzer().anlyze(((StringField) field).getValue(),
                    ((StringField) field).getName());
            for (Term t : termSet) {
                byte[] filedValue = t.getValue().getBytes(StandardCharsets.UTF_8);
                filedLength = ((StringField) field).getValue().length();
                assembleFieldTermMap(fieldTermMap,filedName,filedValue,bytesCurDoc, filedLength);
            }
        } else if (field instanceof DoubleField) {
            double value = ((DoubleField) field).getValue();
            long sortableLong = NumberUtil.double2SortableLong(value);// double to sort
            String[] prefixString = NumberUtil.long2PrefixString(sortableLong,config.getPrecisionStep());
            filedLength = 1;
            for (int i = 0; i < prefixString.length; i++) {
                assembleFieldTermMap(fieldTermMap, filedName,prefixString[i].getBytes(StandardCharsets.UTF_8), bytesCurDoc, filedLength);
            }
        }
        assembleFieldTypeMap(fieldTypeMap, filedName, new byte[]{termType},filedLength, bytesCurDoc);
    }

    public static void main(String[] args) {
    }

}
