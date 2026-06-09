package hawk.indexer.writer;
import common.ByteReference;
import common.Pair;
import directory.Directory;
import document.Document;
import field.DoubleField;
import field.Field;
import field.PrimaryKeyField;
import field.StringField;
import util.*;
import hawk.indexer.writer.config.IndexConfig;
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
        // FDT: field data table, 正排索引数据
        byte[][]  docFDT = processStoredFields(doc, bytesCurDoc);

        // 左侧FDM: field metadata table, 存储字段元数据, 只记录文档中有哪些字段，都是什么类型，值有多长
        // 右侧IVT: inverted index table, 存储倒排索引数据, 存储字段名和词频，词频和字段值长度
        Pair docFDMIVT =  processIndexedFields(doc, bytesCurDoc);
        HashMap<ByteReference, Pair<byte[], Integer>> docFDM = (HashMap<ByteReference, Pair<byte[], Integer>>) docFDMIVT.getLeft();
        docFDM.putAll(processStoredOnlyFieldsForFDM(doc, bytesCurDoc));
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
        log.info("current bytes used： " + bytesUsed.get());
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
                oldVal[oldVal.length-1] = IDFreqLength;
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
            if(fdt.size()>0){
                int blockStartID = fdt.get(0).getLeft() + docBase;
                for (int i = 0; i < fdt.size(); i++) {
                    int docID = fdt.get(i).getLeft() + docBase;
                    byte[][] data = (byte[][]) fdt.get(i).getRight();
                    if(!insertBlock(docID, data, buffer, bufferPos)){ // if buffer is full, write to disk
                        writeFDX(fdxChannel, blockStartID, fdtPos, fdxPos);
                        writeCompressedBloc(buffer, compressedBuffer, maxCompressedLength, fdtChannel, fdtPos,
                                bufferPos);

                        insertBlock(docID, data, buffer, bufferPos);
                        blockStartID = docID;
                    }
                }
                // last write
                if(bufferPos.getValue() > 0){
                    writeFDX(fdxChannel, blockStartID, fdtPos, fdxPos);
                    writeCompressedBloc(buffer, compressedBuffer, maxCompressedLength, fdtChannel, fdtPos,
                            bufferPos);
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
        flushStored(fdtPath, fdxPath, docBase);
        flushIndexed(timPath, frqPath, fdmPath, ivtList, fdmList, docBase);
        directory.updateSegInfo(docIDAllocator.get() + docBase, 1);
        mergetest(docBase);
    }

    /**
     * 将文档中所有标记为 {@link Field.Stored#YES} 的字段序列化，构建该文档在 FDT（Field Data Table）中的
     * 原始字节池，供后续 {@link #assembleFDT} 写入内存索引，并在 flush 时经 {@link #insertBlock} 落盘到 .fdt 文件。
     *
     * <p>返回的 {@code bytePool} 下标与 {@code fieldMap} 的遍历顺序一一对应：仅对 stored 字段写入序列化结果，
     * 非 stored 字段对应槽位保持 {@code null}。{@link #insertBlock} 从下标 0 起连续读取非空槽位，
     * 因此 stored 字段在 fieldMap 中应位于非 stored 字段之前，才能保证落盘时字段顺序正确。
     *
     * @param doc         待索引的文档，字段定义见 {@link Document#getFieldMap()}
     * @param bytesCurDoc 当前文档的内存占用估算（字节），调用方用于判断是否触发 RAM flush；
     *                    本方法会将每个 stored 字段序列化后的长度累加到此计数器
     * @return 该文档的 stored 字段字节池，元素为各字段 {@link Field#customSerialize()} 的结果
     */
    public byte[][] processStoredFields(Document doc, WrapLong bytesCurDoc) {
        // 预分配容量为 10 的二维数组，按需通过 bytePoolGrow 扩容
        byte[][] bytePool = new byte[10][];
        HashMap<String, Field> fieldMap = doc.getFieldMap();
        // i 为 fieldMap 遍历序号，同时作为 bytePool 的写入下标
        int i = 0;
        for (Map.Entry<String, Field> entry : fieldMap.entrySet()) {
            Field field = entry.getValue();
            if (field.isStored() == Field.Stored.YES) {
                // 按字段类型（StringField / DoubleField / PrimaryKeyField 等）序列化为字节数组
                byte[] fieldBytes = field.customSerialize();
                if (bytePool.length < i + 1) {
                    bytePool = ArrayUtil.bytePoolGrow(bytePool);
                }
                bytePool[i] = fieldBytes;
                // 累加本字段占用的 RAM，供 run() 中与 maxRamUsage 比较以决定是否 flush
                bytesCurDoc.setValue(bytesCurDoc.getValue() + fieldBytes.length);
            }
            i++;
        }
        return bytePool;
    }

    /**
     * 处理文档中所有需要建立倒排索引的字段（{@link Field.Tokenized#YES}），生成单文档级别的
     * FDM 元数据与 IVT 倒排条目，供后续 {@link #assembleFDM} 和 {@link #assembleIVT} 合并到全局内存索引。
     *
     * <p>返回值是一个 {@link Pair}，左右两部分分别对应：
     * <ul>
     *   <li><b>Left — docFDM</b>：字段元数据表。key 为字段名（{@link ByteReference}），
     *       value 为 {@code Pair<字段类型字节, 字段值长度>}，用于 flush 时写入 .fdm 文件，
     *       并在检索阶段通过 {@code doc()} 反序列化字段。</li>
     *   <li><b>Right — docIVT</b>：倒排索引条目。key 为 {@link FieldTermPair}（字段名 + term 值），
     *       value 为 {@code int[]{词频, 字段值长度}}，后续会附加 docID 形成 posting list，
     *       flush 时写入 .tim / .frq 文件。</li>
     * </ul>
     *
     * <p>仅处理 {@code Tokenized.YES} 的字段；仅 stored、不 tokenize 的字段由
     * {@link #processStoredOnlyFieldsForFDM} 单独处理，调用方在 {@link #run()} 中通过
     * {@code putAll} 合并到 docFDM。
     *
     * @param doc         待索引的文档
     * @param bytesCurDoc 当前文档的内存占用估算，{@link #processIndexedField} 在新增 FDM/IVT 条目时累加
     * @return Pair 左为 docFDM，右为 docIVT
     */
    public Pair processIndexedFields(Document doc, WrapLong bytesCurDoc){
        // 初始化单文档的 FDM 与 IVT 容器，逐字段填充后返回
        Pair<HashMap<ByteReference, Pair<byte[], Integer>>, HashMap<FieldTermPair, int[]>> ret = new Pair<>(new HashMap<>(),
                new HashMap<>());
        HashMap<String, Field> fieldMap = doc.getFieldMap();
        for (Map.Entry<String, Field> entry : fieldMap.entrySet()) {
            Field field = entry.getValue();
            // 仅对需要分词/建索引的字段做 analyze、term 提取，并写入 ret 的左右两个 Map
            if(field.isTokenized() == Field.Tokenized.YES){
                processIndexedField(field, ret, bytesCurDoc);
            }
        }
        return ret;
    }

    public byte getFieldType(Field field){
        byte termType = 0b00000000;
        if(field.isStored() == Field.Stored.YES){
            termType |= 0b00000001;
        }
        if(field.isTokenized() == Field.Tokenized.YES){
            termType |= 0b00000010;
        }
        if(field instanceof DoubleField){
            termType |= 0b00000100;
        } else if (field instanceof StringField) {
            termType |= 0b00001000;
        } else if (field instanceof PrimaryKeyField) {
            termType |= 0b00010000;
        }
        return termType;
    }

    // Stored-only fields need fdm metadata for doc() deserialization
    public HashMap<ByteReference, Pair<byte[], Integer>> processStoredOnlyFieldsForFDM(Document doc, WrapLong bytesCurDoc) {
        HashMap<ByteReference, Pair<byte[], Integer>> docFDM = new HashMap<>();
        for (Field field : doc.getFieldMap().values()) {
            if (field.isStored() != Field.Stored.YES || field.isTokenized() != Field.Tokenized.NO) {
                continue;
            }
            byte[] fieldName = field.serializeName();
            assembleFieldTypeMap(docFDM, fieldName, new byte[]{getFieldType(field)},
                    storedOnlyFieldLength(field), bytesCurDoc);
        }
        return docFDM;
    }

    private int storedOnlyFieldLength(Field field) {
        if (field instanceof PrimaryKeyField) {
            return 8;
        } else if (field instanceof StringField) {
            return ((StringField) field).getValue().length();
        } else if (field instanceof DoubleField) {
            return 1;
        }
        return 0;
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

    /**
     * 对单个可索引字段完成「分词 / 数值前缀化 → 写入 IVT → 登记 FDM」的完整处理流程。
     * 由 {@link #processIndexedFields} 按字段逐个调用。
     *
     * <p>处理结果写入 {@code pair} 的两个 Map：
     * <ul>
     *   <li><b>Left — fieldTypeMap（docFDM）</b>：key 为字段名，value 为
     *       {@code Pair<类型标志位, 字段值长度>}，最终通过 {@link #assembleFieldTypeMap} 登记。</li>
     *   <li><b>Right — fieldTermMap（docIVT）</b>：key 为 {@link FieldTermPair}（字段名 + term），
     *       value 为 {@code int[]{词频, 字段值长度}}，通过 {@link #assembleFieldTermMap} 写入。</li>
     * </ul>
     *
     * <p>按字段类型分两条路径：
     * <ul>
     *   <li>{@link StringField}：经 {@link IndexConfig#getAnalyzer()} 分词，每个 term 作为倒排条目；
     *       同一字段内相同 term 出现多次时词频累加。</li>
     *   <li>{@link DoubleField}：将 double 转为可排序 long（{@link NumberUtil#double2SortableLong}），
     *       再按 {@code precisionStep} 生成多级前缀 term（{@link NumberUtil#long2PrefixString}），
     *       以支持范围查询。</li>
     * </ul>
     *
     * @param field       待处理的单个字段（调用方已保证 {@link Field.Tokenized#YES}）
     * @param pair        {@link #processIndexedFields} 返回的容器，Left 为 FDM，Right 为 IVT
     * @param bytesCurDoc 当前文档内存占用估算，新增 IVT/FDM 条目时由 assemble 方法累加
     */
    public void processIndexedField(Field field, Pair pair,
                                    WrapLong bytesCurDoc){
        HashMap<ByteReference, Pair<byte[], Integer>> fieldTypeMap = (HashMap) pair.getLeft();
        HashMap<FieldTermPair, int[]> fieldTermMap = (HashMap) pair.getRight();
        // 编码 stored/tokenized/字段类型 等属性，供 .fdm 落盘与 doc() 反序列化
        byte termType = getFieldType(field);
        byte[] filedName = field.serializeName();
        int filedLength = 0;
        if (field instanceof StringField){
            // 分词得到 term 集合；Term 含位置信息，同值不同位置的 term 视为不同条目
            HashSet<Term> termSet = config.getAnalyzer().anlyze(((StringField) field).getValue(),
                    ((StringField) field).getName());
            for (Term t : termSet) {
                byte[] filedValue = t.getValue().getBytes(StandardCharsets.UTF_8);
                // StringField 的 fieldLength 为原始字符串字符长度，用于 doc() 还原
                filedLength = ((StringField) field).getValue().length();
                assembleFieldTermMap(fieldTermMap, filedName, filedValue, bytesCurDoc, filedLength);
            }
        } else if (field instanceof DoubleField) {
            double value = ((DoubleField) field).getValue();
            // 转为按数值大小可排序的 long 比特表示
            long sortableLong = NumberUtil.double2SortableLong(value);
            // 按 precisionStep 切分，生成多级前缀 term 以支持范围检索
            String[] prefixString = NumberUtil.long2PrefixString(sortableLong, config.getPrecisionStep());
            // DoubleField 在 FDM 中固定占 1 字节
            filedLength = 1;
            for (int i = 0; i < prefixString.length; i++) {
                assembleFieldTermMap(fieldTermMap, filedName, prefixString[i].getBytes(StandardCharsets.UTF_8),
                        bytesCurDoc, filedLength);
            }
        }
        // 无论 String 还是 Double，最终都在 FDM 中登记该字段的类型与值长度
        assembleFieldTypeMap(fieldTypeMap, filedName, new byte[]{termType}, filedLength, bytesCurDoc);
    }

}
