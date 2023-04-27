package hawk.index.core.writer;

import hawk.index.core.directory.Directory;
import hawk.index.core.directory.memory.MMap;
import hawk.index.core.util.DataInput;
import hawk.index.core.util.DataOutput;
import hawk.index.core.util.WrapLong;
import lombok.extern.slf4j.Slf4j;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class IndexMerger {

    private Directory directory;

    private IndexConfig indexConfig;

    private AtomicInteger docIDAllocator;

    int docBase;


    public IndexMerger(Directory directory, IndexConfig indexConfig, AtomicInteger docIDAllocator, int docBase) {
        this.directory = directory;
        this.indexConfig = indexConfig;
        this.docIDAllocator = docIDAllocator;
        this.docBase = docBase;
    }

    public void deleteFiles(ArrayList<String> files){
        Iterator<String> it = files.iterator();
        while (it.hasNext()){
            String file = it.next();
            if(file.contains("2")){
                try {
                    Files.delete(Paths.get(file));
                    it.remove();
                } catch (IOException e) {
                    log.error("delete file errored after merge");
                    System.exit(1);
                }
            } else if (file.contains("3.tim")) {
                int lastIndex = file.lastIndexOf("/");
                String pathStr = file.substring(0, lastIndex + 1).concat("1.tim");
                try {
                    Files.delete(Paths.get(pathStr));
                    Files.move(Paths.get(file), Paths.get(pathStr));
                    it.remove();
                } catch (IOException e) {
                    log.error("override 1.tim errored after merge");
                    System.exit(1);
                }
            } else if (file.contains("3.frq")) {
                int lastIndex = file.lastIndexOf("/");
                String pathStr = file.substring(0, lastIndex + 1).concat("1.frq");
                try {
                    Files.delete(Paths.get(pathStr));
                    Files.move(Paths.get(file), Paths.get(pathStr));
                    it.remove();
                } catch (IOException e) {
                    log.error("override 1.frq errored after merge");
                    System.exit(1);
                }
            }else if (file.contains("3.fdm")) {
                int lastIndex = file.lastIndexOf("/");
                String pathStr = file.substring(0, lastIndex + 1).concat("1.fdm");
                try {
                    Files.delete(Paths.get(pathStr));
                    Files.move(Paths.get(file), Paths.get(pathStr));
                    it.remove();
                } catch (IOException e) {
                    log.error("override 1.frq errored after merge");
                    System.exit(1);
                }
            }
        }
    }

    public void mergeFrq(MappedByteBuffer frqBuffer1, MappedByteBuffer frqBuffer2, FileChannel seg3Frq){
        int frqLength1 = DataInput.readVint(frqBuffer1);
        int frqLength2 = DataInput.readVint(frqBuffer2);
        int frqLength = frqLength1 + frqLength2;
        DataOutput.writeVInt(frqLength,seg3Frq);
        for (int i = 0; i < frqLength1; i++) {
            int docID = DataInput.readVint(frqBuffer1);
            int frequency = DataInput.readVint(frqBuffer1);
            int fieldLength = DataInput.readVint(frqBuffer1);
            DataOutput.writeVInt(docID, seg3Frq);
            DataOutput.writeVInt(frequency, seg3Frq);
            DataOutput.writeVInt(fieldLength, seg3Frq);
        }
        for (int i = 0; i < frqLength2; i++) {
            int docID = DataInput.readVint(frqBuffer2);
            int frequency = DataInput.readVint(frqBuffer2);
            int fieldLength = DataInput.readVint(frqBuffer2);
            DataOutput.writeVInt(docID, seg3Frq);
            DataOutput.writeVInt(frequency, seg3Frq);
            DataOutput.writeVInt(fieldLength, seg3Frq);
        }
    }


    public void mergeFrq(MappedByteBuffer frqBuffer, FileChannel seg3Frq){
        int frqLength = DataInput.readVint(frqBuffer);
        DataOutput.writeVInt(frqLength,seg3Frq);
        for (int i = 0; i < frqLength; i++) {
            int docID = DataInput.readVint(frqBuffer);
            int frequency = DataInput.readVint(frqBuffer);
            int fieldLength = DataInput.readVint(frqBuffer);
            DataOutput.writeVInt(docID, seg3Frq);
            DataOutput.writeVInt(frequency, seg3Frq);
            DataOutput.writeVInt(fieldLength, seg3Frq);
        }
    }

    public void writeFieldTermPair(FieldTermPair fieldTermPair, FileChannel fc){
        byte[] field = fieldTermPair.getField();
        byte[] term = fieldTermPair.getTerm();
        int fieldLength = field.length;
        int termLength = term.length;
        DataOutput.writeInt(fieldLength, fc);
        DataOutput.writeBytes(field, fc);
        DataOutput.writeInt(termLength, fc);
        DataOutput.writeBytes(term, fc);
    }

    public FieldTermPair readFieldTermPair(MappedByteBuffer buffer){
        if(buffer.position() >= buffer.limit()){
            return null;
        }
        int fieldLength = buffer.getInt();
        byte[] fieldBytes = DataInput.readBytes(buffer, fieldLength);
        int termLength = buffer.getInt();
        byte[] termBytes = DataInput.readBytes(buffer, termLength);
        long offset = DataInput.readVlong(buffer);
        FieldTermPair fieldTermPair = new FieldTermPair(fieldBytes, termBytes);
        return fieldTermPair;
    }

    // assume 2 tim are not empty
    public void mergeTim(MappedByteBuffer seg1TimBuffer, MappedByteBuffer seg1FrqBuffer, MappedByteBuffer seg2TimBuffer,
                         MappedByteBuffer seg2FrqBuffer, FileChannel seg3Tim, FileChannel seg3Frq) throws IOException {
        FieldTermPair seg1Pair = readFieldTermPair(seg1TimBuffer);
        FieldTermPair seg2Pair = readFieldTermPair(seg2TimBuffer);
        while(seg1Pair != null && seg2Pair != null){
            if(seg1Pair.compareTo(seg2Pair) < 0){
                // write fieldTerm to new tim
                writeFieldTermPair(seg1Pair, seg3Tim);
                // write frq offset to tim
                DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
                //write to new frq
                mergeFrq(seg1FrqBuffer, seg3Frq);
                // read next fieldTerm
                seg1Pair = readFieldTermPair(seg1TimBuffer);
            } else if (seg1Pair.compareTo(seg2Pair) > 0) {
                writeFieldTermPair(seg2Pair, seg3Tim);
                DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
                mergeFrq(seg2FrqBuffer, seg3Frq);
                seg2Pair = readFieldTermPair(seg2TimBuffer);
            } else {
                writeFieldTermPair(seg1Pair, seg3Tim);
                DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
                //concatenate 2 old frq to new frq
                mergeFrq(seg1FrqBuffer,seg2FrqBuffer, seg3Frq);
                // read next fieldTerm
                seg1Pair = readFieldTermPair(seg1TimBuffer);
                seg2Pair = readFieldTermPair(seg2TimBuffer);
            }
        }
        while(seg1Pair != null){
            writeFieldTermPair(seg1Pair, seg3Tim);
            // write frq offset to tim
            DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
            //write to new frq
            mergeFrq(seg1FrqBuffer, seg3Frq);
            // read next fieldTerm
            seg1Pair = readFieldTermPair(seg1TimBuffer);
        }

        while(seg2Pair != null){
            writeFieldTermPair(seg2Pair, seg3Tim);
            DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
            mergeFrq(seg2FrqBuffer, seg3Frq);
            seg2Pair = readFieldTermPair(seg2TimBuffer);
        }
    }

    public void writeFdmRecord(FdmRecord record1, FdmRecord record2, FileChannel fc){
        byte[] field = record1.getField();
        int length = field.length;
        byte type = record1.getType();
        int fieldLengthSum = record1.getFieldLengthSum() + record2.getFieldLengthSum();
        int docCount = record1.getDocCount() + record2.getDocCount();
        DataOutput.writeInt(length, fc);
        DataOutput.writeBytes(field, fc);
        DataOutput.writeByte(type, fc);
        DataOutput.writeInt(fieldLengthSum, fc);
        DataOutput.writeInt(docCount, fc);
    }


    public void writeFdmRecord(FdmRecord record, FileChannel fc){
        byte[] field = record.getField();
        int length = field.length;
        byte type = record.getType();
        int fieldLengthSum = record.getFieldLengthSum();
        int docCount = record.getDocCount();
        DataOutput.writeInt(length, fc);
        DataOutput.writeBytes(field, fc);
        DataOutput.writeByte(type, fc);
        DataOutput.writeInt(fieldLengthSum, fc);
        DataOutput.writeInt(docCount, fc);
    }

    public FdmRecord readFdmRecord(MappedByteBuffer buffer){
        if(buffer.position() >= buffer.limit()){
            return null;
        }
        int fieldLength = buffer.getInt();
        byte[] field = DataInput.readBytes(buffer, fieldLength);
        byte fieldType = buffer.get();
        int fieldLengthSum = buffer.getInt();
        int docCount = buffer.getInt();
        FdmRecord fdmRecord = new FdmRecord(field, fieldType, fieldLengthSum, docCount);
        return fdmRecord;
    }

    public void mergeFdm(MappedByteBuffer seg1FdmBuffer, MappedByteBuffer seg2FdmBuffer, FileChannel seg3Fdm){
        FdmRecord seg1Record = readFdmRecord(seg1FdmBuffer);
        FdmRecord seg2Record = readFdmRecord(seg2FdmBuffer);
        while(seg1Record != null && seg2Record != null){
            if(seg1Record.compareTo(seg2Record) < 0){
                writeFdmRecord(seg1Record, seg3Fdm);
                // read next fieldTerm
                seg1Record = readFdmRecord(seg1FdmBuffer);
            } else if (seg1Record.compareTo(seg2Record) > 0) {
                writeFdmRecord(seg2Record, seg3Fdm);
                seg2Record = readFdmRecord(seg2FdmBuffer);
            } else {
                writeFdmRecord(seg1Record, seg2Record, seg3Fdm);
                seg1Record = readFdmRecord(seg1FdmBuffer);
                seg2Record = readFdmRecord(seg2FdmBuffer);
            }
        }
        while (seg1Record != null){
            writeFdmRecord(seg1Record, seg3Fdm);
            // read next fieldTerm
            seg1Record = readFdmRecord(seg1FdmBuffer);
        }
        while (seg2Record != null){
            writeFdmRecord(seg2Record, seg3Fdm);
            seg2Record = readFdmRecord(seg2FdmBuffer);
        }
    }


    //比较fieldterm, 小的写入seg3, 相等拼接posting写入seg3。每次移动小的，或者同时移动
    public void mergeIndexed(ArrayList<String> files){
        MappedByteBuffer seg1TimBuffer = null, seg1FrqBuffer = null, seg1FdmBuffer = null, seg2TimBuffer = null,
                seg2FrqBuffer = null, seg2FdmBuffer = null;
        FileChannel seg3Tim = null, seg3Frq = null, seg3Fdm;
        String seg3TimPath = directory.generateSegFile("3.tim");
        String seg3FrqPath = directory.generateSegFile("3.frq");
        String seg3FdmPath = directory.generateSegFile("3.fdm");
        try {
            seg3Tim = new RandomAccessFile(seg3TimPath, "rw").getChannel();
            seg3Frq = new RandomAccessFile(seg3FrqPath, "rw").getChannel();
            seg3Fdm = new RandomAccessFile(seg3FdmPath, "rw").getChannel();
            for (int i = 0; i < files.size(); i++) {
                if(files.get(i).contains("1.tim")){
                    seg1TimBuffer = MMap.mmapFile(files.get(i));
                } else if (files.get(i).contains("2.tim")) {
                    seg2TimBuffer = MMap.mmapFile(files.get(i));
                }else if (files.get(i).contains("1.frq")) {
                    seg1FrqBuffer = MMap.mmapFile(files.get(i));
                }else if (files.get(i).contains("2.frq")) {
                    seg2FrqBuffer = MMap.mmapFile(files.get(i));
                }else if (files.get(i).contains("1.fdm")) {
                    seg1FdmBuffer = MMap.mmapFile(files.get(i));
                }else if (files.get(i).contains("2.fdm")) {
                    seg2FdmBuffer = MMap.mmapFile(files.get(i));
                }
            }
            mergeFdm(seg1FdmBuffer, seg2FdmBuffer, seg3Fdm);
            MMap.unMMap(seg1FdmBuffer);
            MMap.unMMap(seg2FdmBuffer);
            seg3Fdm.close();
            mergeTim(seg1TimBuffer,seg1FrqBuffer, seg2TimBuffer, seg2FrqBuffer, seg3Tim, seg3Frq);
            MMap.unMMap(seg1TimBuffer);
            MMap.unMMap(seg1FrqBuffer);
            MMap.unMMap(seg2TimBuffer);
            MMap.unMMap(seg2FrqBuffer);
            seg3Tim.close();
            seg3Frq.close();
        } catch (IOException e) {
            log.error("file not found during mergeIndexed");
            System.exit(1);
        }

    }

    public void merge(){
        ArrayList<String> files = directory.getFiles();
        if(files.size() != 11) { // 1 segment contains 5 files, plus 1 segement.info, thus 2 * 5 + 1
            log.error("wrong file count " + files.size() + " detected during merge");
            System.exit(1);
        }
        mergeStored(files);
        mergeIndexed(files);
        deleteFiles(files);
        this.directory.updateSegInfo(docIDAllocator.get() + this.docBase, -1);
    }

    public void mergeFDX(ArrayList<int[]> seg2FDX, FileChannel seg1FdxFC){
        try {
            long limit = seg1FdxFC.size();
            for (int i = 0; i < seg2FDX.size(); i++) {
                int[] item = seg2FDX.get(i);
                int docID = item[0];
                long offset = item[1] + limit;
                WrapLong offsetWrapper = new WrapLong(offset);
                log.info("fdx merge writing ===> " + "docID is " + docID + ", fdt offset is " + offset);
                DataOutput.writeVInt(docID, seg1FdxFC, offsetWrapper);
                DataOutput.writeVLong(offset, seg1FdxFC, offsetWrapper);
            }
        } catch (IOException e) {
            log.error("something wrong during mergeFDX");
            System.exit(1);
        }
    }

    public void mergeFDT(FileChannel seg1FdtFC,  MappedByteBuffer seg2FDTBuffer,
                         ArrayList<int[]> seg2FDX){
        try {
            long writePos = seg1FdtFC.size();
            long limit = seg2FDTBuffer.limit();
            int left, right;
            for (int i = 0; i < seg2FDX.size(); i++) {
                // calculate original start and length
                left = seg2FDX.get(i)[1];
                if(i < seg2FDX.size() - 1){
                    right = seg2FDX.get(i+1)[1];
                }else{
                    right = (int) limit;
                }
                int length = right - left;
                byte[] block = new byte[length];
                //read original bloc
                seg2FDTBuffer.get(block, left, length);
                ByteBuffer buffer = ByteBuffer.wrap(block);
                //write original block to seg1 FDT
                writePos += left;
                seg1FdtFC.write(buffer, writePos);
            }
        } catch (IOException e) {
            log.error("something wrong during mergeFDT");
            System.exit(1);
        }
    }

    public void mergeStored(ArrayList<String> files){
        try {
            FileChannel seg1FdtFC = null, seg1FdxFC = null;
            MappedByteBuffer seg2FDTBuffer = null ,seg2FDXBuffer = null;
            for (int i = 0; i < files.size(); i++) {
                if(files.get(i).contains("1.fdx")){
                    seg1FdxFC = new RandomAccessFile(files.get(i), "rw").getChannel();
                } else if (files.get(i).contains("1.fdt")) {
                    seg1FdtFC = new RandomAccessFile(files.get(i), "rw").getChannel();
                } else if (files.get(i).contains("2.fdx")) {
                    seg2FDXBuffer = MMap.mmapFile(files.get(i));
                } else if (files.get(i).contains("2.fdt")) {
                    seg2FDTBuffer = MMap.mmapFile(files.get(i));
                }
            }
            ArrayList<int[]> seg2FDX = new ArrayList<>();
            while (seg2FDXBuffer.position() < seg2FDXBuffer.limit()){
                int seg2DocID = DataInput.readVint(seg2FDXBuffer);
                int seg2FDToffset = (int) DataInput.readVlong(seg2FDXBuffer);
                seg2FDX.add(new int[]{seg2DocID, seg2FDToffset});
            }
            mergeFDX(seg2FDX, seg1FdxFC);
            mergeFDT(seg1FdtFC, seg2FDTBuffer, seg2FDX);
            seg1FdxFC.close();
            seg1FdtFC.close();
            MMap.unMMap(seg2FDXBuffer);
            MMap.unMMap(seg2FDTBuffer);
        } catch (FileNotFoundException e) {
            log.error("file not found during mergeStored");
            System.exit(1);
        } catch (IOException e) {
            log.error("create mapping failed during mergeStored");
            System.exit(1);
        }



    }

    public static void main(String[] args) {
    }
}
