package hawk.indexer.writer;

import directory.Directory;
import directory.memory.MMap;
import hawk.indexer.writer.config.IndexConfig;
import util.bkd.BkdConfig;
import util.bkd.BkdFileReader;
import util.bkd.BkdFileWriter;
import util.bkd.BkdPoint;
import util.bkd.FieldBkdWriter;
import util.DataInput;
import util.DataOutput;
import util.SegmentCursor;
import util.WrapLong;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Data
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

    public void deleteFiles(HashMap<String, Path> files) throws IOException {
        Iterator<Map.Entry<String, Path>> it = files.entrySet().iterator();
        while (it.hasNext()){
            Map.Entry<String, Path> entry = it.next();
            if(entry.getKey().contains("2")){
                Files.delete(entry.getValue());
                it.remove();
            } else if (entry.getKey().contains("3.frq") || entry.getKey().contains("3.tim") ||
                    entry.getKey().contains("3.fdm") || entry.getKey().contains("3.bkd")) {
                String fileName = entry.getKey();
                Path filePath = entry.getValue();
                String deleteFileName = "1.".concat(fileName.split("\\.")[1]);
                Path deleteFilePath = files.get(deleteFileName);
                Files.delete(deleteFilePath);
                Files.move(filePath,deleteFilePath);
                it.remove();
            }
        }
    }

    public void mergeFrq(SegmentCursor frqCursor1, SegmentCursor frqCursor2, FileChannel seg3Frq){
        int frqLength1 = frqCursor1.readVint();
        int frqLength2 = frqCursor2.readVint();
        int frqLength = frqLength1 + frqLength2;
        DataOutput.writeVInt(frqLength,seg3Frq);
        for (int i = 0; i < frqLength1; i++) {
            int docID = frqCursor1.readVint();
            int frequency = frqCursor1.readVint();
            int fieldLength = frqCursor1.readVint();
            DataOutput.writeVInt(docID, seg3Frq);
            DataOutput.writeVInt(frequency, seg3Frq);
            DataOutput.writeVInt(fieldLength, seg3Frq);
        }
        for (int i = 0; i < frqLength2; i++) {
            int docID = frqCursor2.readVint();
            int frequency = frqCursor2.readVint();
            int fieldLength = frqCursor2.readVint();
            DataOutput.writeVInt(docID, seg3Frq);
            DataOutput.writeVInt(frequency, seg3Frq);
            DataOutput.writeVInt(fieldLength, seg3Frq);
        }
    }

    public void mergeFrq(SegmentCursor frqCursor, FileChannel seg3Frq){
        int frqLength = frqCursor.readVint();
        DataOutput.writeVInt(frqLength,seg3Frq);
        for (int i = 0; i < frqLength; i++) {
            int docID = frqCursor.readVint();
            int frequency = frqCursor.readVint();
            int fieldLength = frqCursor.readVint();
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

    public FieldTermPair readFieldTermPair(SegmentCursor cursor){
        if(!cursor.hasRemaining()){
            return null;
        }
        int fieldLength = cursor.getInt();
        byte[] fieldBytes = cursor.readBytes(fieldLength);
        int termLength = cursor.getInt();
        byte[] termBytes = cursor.readBytes(termLength);
        cursor.readVlong(); // 旧段里的 frq 偏移，合并时用不到（新偏移由写出顺序决定）
        FieldTermPair fieldTermPair = new FieldTermPair(fieldBytes, termBytes);
        return fieldTermPair;
    }

    // assume 2 tim are not empty
    public void mergeTim(SegmentCursor seg1TimCursor, SegmentCursor seg1FrqCursor, SegmentCursor seg2TimCursor,
                         SegmentCursor seg2FrqCursor, FileChannel seg3Tim, FileChannel seg3Frq) throws IOException {
        FieldTermPair seg1Pair = readFieldTermPair(seg1TimCursor);
        FieldTermPair seg2Pair = readFieldTermPair(seg2TimCursor);
        while(seg1Pair != null && seg2Pair != null){
            if(seg1Pair.compareTo(seg2Pair) < 0){
                // write fieldTerm to new tim
                writeFieldTermPair(seg1Pair, seg3Tim);
                // write frq offset to tim
                DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
                //write to new frq
                mergeFrq(seg1FrqCursor, seg3Frq);
                // read next fieldTerm
                seg1Pair = readFieldTermPair(seg1TimCursor);
            } else if (seg1Pair.compareTo(seg2Pair) > 0) {
                writeFieldTermPair(seg2Pair, seg3Tim);
                DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
                mergeFrq(seg2FrqCursor, seg3Frq);
                seg2Pair = readFieldTermPair(seg2TimCursor);
            } else {
                writeFieldTermPair(seg1Pair, seg3Tim);
                DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
                //concatenate 2 old frq to new frq
                mergeFrq(seg1FrqCursor,seg2FrqCursor, seg3Frq);
                // read next fieldTerm
                seg1Pair = readFieldTermPair(seg1TimCursor);
                seg2Pair = readFieldTermPair(seg2TimCursor);
            }
        }
        while(seg1Pair != null){
            writeFieldTermPair(seg1Pair, seg3Tim);
            // write frq offset to tim
            DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
            //write to new frq
            mergeFrq(seg1FrqCursor, seg3Frq);
            // read next fieldTerm
            seg1Pair = readFieldTermPair(seg1TimCursor);
        }

        while(seg2Pair != null){
            writeFieldTermPair(seg2Pair, seg3Tim);
            DataOutput.writeVLong(seg3Frq.position(), seg3Tim);
            mergeFrq(seg2FrqCursor, seg3Frq);
            seg2Pair = readFieldTermPair(seg2TimCursor);
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

    public FdmRecord readFdmRecord(SegmentCursor cursor){
        if(!cursor.hasRemaining()){
            return null;
        }
        int fieldLength = cursor.getInt();
        byte[] field = cursor.readBytes(fieldLength);
        byte fieldType = cursor.get();
        int fieldLengthSum = cursor.getInt();
        int docCount = cursor.getInt();
        FdmRecord fdmRecord = new FdmRecord(field, fieldType, fieldLengthSum, docCount);
        return fdmRecord;
    }

    public void mergeFdm(SegmentCursor seg1FdmCursor, SegmentCursor seg2FdmCursor, FileChannel seg3Fdm){
        FdmRecord seg1Record = readFdmRecord(seg1FdmCursor);
        FdmRecord seg2Record = readFdmRecord(seg2FdmCursor);
        while(seg1Record != null && seg2Record != null){
            if(seg1Record.compareTo(seg2Record) < 0){
                writeFdmRecord(seg1Record, seg3Fdm);
                // read next fieldTerm
                seg1Record = readFdmRecord(seg1FdmCursor);
            } else if (seg1Record.compareTo(seg2Record) > 0) {
                writeFdmRecord(seg2Record, seg3Fdm);
                seg2Record = readFdmRecord(seg2FdmCursor);
            } else {
                writeFdmRecord(seg1Record, seg2Record, seg3Fdm);
                seg1Record = readFdmRecord(seg1FdmCursor);
                seg2Record = readFdmRecord(seg2FdmCursor);
            }
        }
        while (seg1Record != null){
            writeFdmRecord(seg1Record, seg3Fdm);
            // read next fieldTerm
            seg1Record = readFdmRecord(seg1FdmCursor);
        }
        while (seg2Record != null){
            writeFdmRecord(seg2Record, seg3Fdm);
            seg2Record = readFdmRecord(seg2FdmCursor);
        }
    }

    //比较fieldterm, 小的写入seg3, 相等拼接posting写入seg3。每次移动小的，或者同时移动
    public void mergeIndexed(HashMap<String, Path> files){
        String seg3TimPath = directory.generateSegFile("3.tim");
        String seg3FrqPath = directory.generateSegFile("3.frq");
        String seg3FdmPath = directory.generateSegFile("3.fdm");
        // 合并是单线程的，用局部 Arena 管住这批映射，退出即解除
        try (Arena arena = Arena.ofConfined()) {
            FileChannel seg3Tim  = new RandomAccessFile(seg3TimPath, "rw").getChannel();
            FileChannel seg3Frq = new RandomAccessFile(seg3FrqPath, "rw").getChannel();
            FileChannel seg3Fdm = new RandomAccessFile(seg3FdmPath, "rw").getChannel();
            SegmentCursor seg1TimCursor = new SegmentCursor(MMap.mmapFile(files.get("1.tim").toString(), arena));
            SegmentCursor seg2TimCursor = new SegmentCursor(MMap.mmapFile(files.get("2.tim").toString(), arena));
            SegmentCursor seg1FrqCursor = new SegmentCursor(MMap.mmapFile(files.get("1.frq").toString(), arena));
            SegmentCursor seg2FrqCursor = new SegmentCursor(MMap.mmapFile(files.get("2.frq").toString(), arena));
            SegmentCursor seg1FdmCursor = new SegmentCursor(MMap.mmapFile(files.get("1.fdm").toString(), arena));
            SegmentCursor seg2FdmCursor = new SegmentCursor(MMap.mmapFile(files.get("2.fdm").toString(), arena));
            mergeFdm(seg1FdmCursor, seg2FdmCursor, seg3Fdm);
            seg3Fdm.close();
            mergeTim(seg1TimCursor,seg1FrqCursor, seg2TimCursor, seg2FrqCursor, seg3Tim, seg3Frq);
            seg3Tim.close();
            seg3Frq.close();
        } catch (IOException e) {
            log.error("file not found during mergeIndexed");
            System.exit(1);
        }

    }

    public void merge(){
        HashMap<String, Path> files = directory.getFiles();
        int segCount = directory.getSegmentInfo().getSegCount();
        int expected = segCount * 6 + 1;
        long segmentFileCount = files.keySet().stream().filter(this::isSegmentFile).count();
        if (segmentFileCount != expected) {
            throw new RuntimeException("wrong segment file count " + segmentFileCount
                    + " detected during merge, expected " + expected);
        }
        mergeStored(files);
        mergeIndexed(files);
        mergeBkd(files);
        try {
            deleteFiles(files);
        } catch (IOException e) {
            throw new RuntimeException("delete file errored during merge", e);
        }
        this.directory.updateSegInfo(docIDAllocator.get() + this.docBase, -1);
    }

    private boolean isSegmentFile(String fileName) {
        return "segment.info".equals(fileName) || fileName.matches("\\d+\\.(fdt|fdx|tim|frq|fdm|bkd)");
    }

    public void mergeBkd(HashMap<String, Path> files) {
        String seg3BkdPath = directory.generateSegFile("3.bkd");
        try {
            Map<String, List<BkdPoint>> seg1Points = readBkdPoints(files.get("1.bkd"));
            Map<String, List<BkdPoint>> seg2Points = readBkdPoints(files.get("2.bkd"));
            Map<String, List<BkdPoint>> mergedPoints = new HashMap<>();
            HashSet<String> fieldNames = new HashSet<>();
            fieldNames.addAll(seg1Points.keySet());
            fieldNames.addAll(seg2Points.keySet());
            for (String fieldName : fieldNames) {
                List<BkdPoint> left = seg1Points.getOrDefault(fieldName, new ArrayList<>());
                List<BkdPoint> right = seg2Points.getOrDefault(fieldName, new ArrayList<>());
                mergedPoints.put(fieldName, FieldBkdWriter.mergePoints(left, right, 0));
            }
            BkdFileWriter.write(Paths.get(seg3BkdPath), mergedPoints, new BkdConfig(indexConfig));
        } catch (IOException e) {
            log.error("file not found during mergeBkd");
            System.exit(1);
        }
    }

    private Map<String, List<BkdPoint>> readBkdPoints(Path bkdPath) throws IOException {
        if (bkdPath == null || !Files.exists(bkdPath) || Files.size(bkdPath) == 0) {
            return new HashMap<>();
        }
        return BkdFileReader.readAllFieldPoints(bkdPath);
    }

    public void mergeFDX(ArrayList<long[]> seg2FDX, FileChannel seg1FdxFC, FileChannel seg1FdtFC){
        try {
            long limit = seg1FdtFC.size();
            WrapLong fdxPos = new WrapLong(seg1FdxFC.size());
            for (int i = 0; i < seg2FDX.size(); i++) {
                long[] item = seg2FDX.get(i);
                int docID = (int) item[0];
                long offset = item[1] + limit;
                DataOutput.writeVInt(docID, seg1FdxFC, fdxPos);
                DataOutput.writeVLong(offset, seg1FdxFC, fdxPos);
            }
        } catch (IOException e) {
            log.error("something wrong during mergeFDX");
            System.exit(1);
        }
    }

    public void mergeFDT(FileChannel seg1FdtFC,  MemorySegment seg2FDT,
                         ArrayList<long[]> seg2FDX){
        try {
            long base = seg1FdtFC.size();
            long limit = seg2FDT.byteSize();
            // 偏移全程 long：被合并的 .fdt 可能超过 2 GiB（旧实现这里 (int) 窄化后必错）
            long left, right;
            for (int i = 0; i < seg2FDX.size(); i++) {
                // calculate original start and length
                left = seg2FDX.get(i)[1];
                if(i < seg2FDX.size() - 1){
                    right = seg2FDX.get(i+1)[1];
                }else{
                    right = limit;
                }
                int length = (int) (right - left);
                byte[] block = DataInput.readBytes(seg2FDT, left, length);
                ByteBuffer buffer = ByteBuffer.wrap(block);
                seg1FdtFC.write(buffer, base + left);
            }
        } catch (IOException e) {
            log.error("something wrong during mergeFDT");
            System.exit(1);
        }
    }

    public void mergeStored(HashMap<String, Path> files){
        try (Arena arena = Arena.ofConfined()) {
            FileChannel seg1FdtFC = null, seg1FdxFC = null;
            seg1FdxFC = new RandomAccessFile(files.get("1.fdx").toString(), "rw").getChannel();
            seg1FdtFC = new RandomAccessFile(files.get("1.fdt").toString(), "rw").getChannel();
            SegmentCursor seg2FdxCursor = new SegmentCursor(MMap.mmapFile(files.get("2.fdx").toString(), arena));
            MemorySegment seg2FdtSegment = MMap.mmapFile(files.get("2.fdt").toString(), arena);
            ArrayList<long[]> seg2FDX = new ArrayList<>();
            while (seg2FdxCursor.hasRemaining()){
                int seg2DocID = seg2FdxCursor.readVint();
                // 块偏移必须是 long：.fdt 可超过 2 GiB
                long seg2FDToffset = seg2FdxCursor.readVlong();
                seg2FDX.add(new long[]{seg2DocID, seg2FDToffset});
            }
            mergeFDX(seg2FDX, seg1FdxFC, seg1FdtFC);
            mergeFDT(seg1FdtFC, seg2FdtSegment, seg2FDX);
            seg1FdxFC.close();
            seg1FdtFC.close();
        } catch (FileNotFoundException e) {
            log.error("file not found during mergeStored");
            System.exit(1);
        } catch (IOException e) {
            log.error("create mapping failed during mergeStored");
            System.exit(1);
        }
    }

}
