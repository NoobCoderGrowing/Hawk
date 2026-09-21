package hawk.recall.reader;

import directory.Directory;
import directory.DeletedIdsStore;
import directory.PkMapStore;
import directory.memory.MMap;
import io.github.noobcodergrowing.JFST.FST;
import io.github.noobcodergrowing.JFST.fstPair;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import util.DataInput;
import util.WrapLong;
import util.bkd.BkdFileReader;
import util.bkd.BkdFormatVersion;
import util.bkd.BkdReader;
import common.Pair;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Slf4j
@Data
public class MMapDirectoryReader extends DirectoryReader {

    private Directory directory;

    /** 本 reader 所有映射的生命周期所有者；close() 时统一解除映射。
     *  必须 ofShared：Searcher 会被多线程共享访问。 */
    private final Arena arena = Arena.ofShared();

    /** 读索引格式里的 Int 字段。两个坑：
     *
     *  1) 字节序：格式是 big-endian（与 DataOutput / ByteBuffer.putInt 一致），
     *     而 ValueLayout.JAVA_INT 用的是 ByteOrder.nativeOrder()（x86 上是小端）。
     *     直接用它读会把 `00 00 00 08` 读成 0x08000000 —— 小数值不报错，**静默损坏**。
     *
     *  2) 对齐：JAVA_INT 默认要求 4 字节对齐，而格式是紧凑排布的，
     *     例如 .fdm 的 `Byte fieldType` 之后紧跟 `Int fieldLengthSum`，落在偏移 13。
     *     必须用 withByteAlignment(1) 关掉对齐约束，否则抛 IllegalArgumentException。
     *
     *  JAVA_BYTE 既无字节序也无对齐问题，不受影响。 */
    private static final ValueLayout.OfInt JAVA_INT_BE =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);

    private TreeMap<Integer, byte[]> fDXMap;

    private MemorySegment fDTBuffer;

    private HashMap<String, Pair<byte[], Float>> fDMMap;

    private MemorySegment fRQBuffer;

    private FST termFST;

    private HashMap<String, BkdReader> bkdReaders;

    private Map<Long, Integer> pkMap;

    private Map<Integer, Long> docIdToUniqueId;

    private HashSet<Long> deletedUniqueIds;

    public MMapDirectoryReader(Directory directory) {
        this.directory = directory;
        this.fDXMap = new TreeMap<>();
        this.fDMMap = new HashMap<>();
        this.bkdReaders = new HashMap<>();
        init();
    }

    public void init() {
        loadDeleteMetadata();
        constructFdxMap();
        loadFdt();
        constructFdmMap();
        loadFrq();
        loadBkd();
        constructTermFST();
    }

    private void loadBkd() {
        int formatVersion = directory.getSegmentInfo().getFormatVersion();
        if (formatVersion < BkdFormatVersion.BKD) {
            throw new IllegalStateException("index formatVersion " + formatVersion
                    + " does not support BKD; rebuild the index");
        }
        String dirPath = directory.getPath().toAbsolutePath().toString();
        String bkdPath = dirPath + "/1.bkd";
        try {
            this.bkdReaders = new HashMap<>(BkdFileReader.open(java.nio.file.Paths.get(bkdPath), arena));
        } catch (IOException e) {
            log.error("failed to load .bkd file");
            throw new RuntimeException(e);
        }
    }

    private void loadDeleteMetadata() {
        try {
            this.pkMap = PkMapStore.load(directory.getPath());
            this.docIdToUniqueId = new HashMap<>();
            for (Map.Entry<Long, Integer> entry : pkMap.entrySet()) {
                docIdToUniqueId.put(entry.getValue(), entry.getKey());
            }
            this.deletedUniqueIds = DeletedIdsStore.load(directory.getPath());
        } catch (IOException e) {
            log.error("failed to load pk.map or deleted.ids");
            throw new RuntimeException(e);
        }
    }

    public void constructFdxMap() {
        String dirPath = directory.getPath().toAbsolutePath().toString();
        String fdxPath = dirPath + "/1.fdx";
        try {
            MemorySegment buffer = MMap.mmapFile(fdxPath, arena);
            long size = buffer.byteSize();
            WrapLong pos = new WrapLong(0);
            while (pos.getValue() < size) {
                int key = DataInput.readVintAt(buffer, pos);
                byte[] offset = DataInput.readVlongBytesAt(buffer, pos);
                this.fDXMap.put(key, offset);
            }
        } catch (FileNotFoundException e) {
            log.error("fdx file does not exist");
            System.exit(1);
        } catch (IOException e) {
            log.error("errored reading fdx file");
            System.exit(1);
        }
    }

    private void loadFdt() {
        String dirPath = directory.getPath().toAbsolutePath().toString();
        String fdtPath = dirPath + "/1.fdt";
        try {
            MemorySegment buffer = MMap.mmapFile(fdtPath, arena);
            buffer.load(); // force load buffer content into memory
            this.fDTBuffer = buffer;
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void constructFdmMap(){
        String dirPath = directory.getPath().toAbsolutePath().toString();
        String fdmPath = dirPath + "/1.fdm";
        try {
            MemorySegment buffer = MMap.mmapFile(fdmPath, arena);
            long size = buffer.byteSize();
            long pos = 0;
            while (pos < size){
                int length = buffer.get(JAVA_INT_BE, pos);
                pos += 4;
                byte[] bytes = DataInput.readBytes(buffer, pos, length);
                pos += length;
                String fieldName = new String(bytes, StandardCharsets.UTF_8);
                byte fieldType = buffer.get(ValueLayout.JAVA_BYTE, pos);
                pos += 1;
                int fieldLengthSum = buffer.get(JAVA_INT_BE, pos);
                pos += 4;
                int docCount = buffer.get(JAVA_INT_BE, pos);
                pos += 4;
                float avgFieldLength = fieldLengthSum/docCount;
                fDMMap.put(fieldName, new Pair<>(new byte[]{fieldType}, avgFieldLength));
            }
        } catch (FileNotFoundException e) {
            log.error("fdm file does not exist");
            System.exit(1);
        } catch (IOException e) {
            log.error("errored reading fdm file");
            System.exit(1);
        }
    }

    public void loadFrq(){
        String dirPath = directory.getPath().toAbsolutePath().toString();
        String frqPath = dirPath + "/1.frq";
        try {
            MemorySegment buffer = MMap.mmapFile(frqPath, arena);
            buffer.load(); // force load buffer content into memory
            this.fRQBuffer = buffer;
        } catch (FileNotFoundException e) {
            log.error("frq file not found during load frq");
            System.exit(1);
        } catch (IOException e) {
            log.error("frq file IOException during load frq");
            System.exit(1);
        }
    }

    public void constructTermFST(){
        ArrayList<fstPair<String, Long>> stringTerms = new ArrayList<>();

        String dirPath = directory.getPath().toAbsolutePath().toString();
        String timPath = dirPath + "/1.tim";
        try {
            MemorySegment buffer = MMap.mmapFile(timPath, arena);
            long size = buffer.byteSize();
            WrapLong pos = new WrapLong(0);
            while (pos.getValue() < size){
                int fieldLength = buffer.get(JAVA_INT_BE, pos.getValue());
                pos.setValue(pos.getValue() + 4);
                byte[] filedNameBytes = DataInput.readBytes(buffer, pos.getValue(), fieldLength);
                pos.setValue(pos.getValue() + fieldLength);
                String fieldName = new String(filedNameBytes, StandardCharsets.UTF_8);
                int termLength = buffer.get(JAVA_INT_BE, pos.getValue());
                pos.setValue(pos.getValue() + 4);
                byte[]  fieldValueBytes = DataInput.readBytes(buffer, pos.getValue(), termLength);
                pos.setValue(pos.getValue() + termLength);
                String fieldValue = new String(fieldValueBytes,StandardCharsets.UTF_8);
                byte[] offset = DataInput.readVlongBytesAt(buffer, pos);
                byte fieldType = fDMMap.get(fieldName).getLeft()[0];
                if((fieldType & 0b00001000) != 0){
                    long frqOffset = DataInput.readVlong(offset);
                    stringTerms.add(new fstPair<>(
                            TermFstUtil.termKey(fieldName, fieldValue), frqOffset));
                } else if ((fieldType & 0b00000100)!= 0) {
                    // numeric fields are indexed in .bkd
                }
            }
            stringTerms.sort(Comparator.comparing(fstPair::getKey));
            ArrayList<fstPair<String[], Long>> fstInput = new ArrayList<>(stringTerms.size());
            for (fstPair<String, Long> entry : stringTerms) {
                fstInput.add(new fstPair<>(TermFstUtil.toCharArray(entry.getKey()), entry.getValue()));
            }
            this.termFST = new FST();
            this.termFST.build(fstInput);
        } catch (FileNotFoundException e) {
            log.error("tim file does not exist");
            System.exit(1);
        } catch (IOException e) {
            log.error("errored reading tim file");
            System.exit(1);
        }
    }

    @Override
    public HashMap<String, BkdReader> getBkdReaders() {
        return bkdReaders;
    }

    @Override
    public int getTotalDoc() {
        return directory.getSegmentInfo().getPreMaxID();
    }

    @Override
    public boolean isLive(int docID) {
        if (docID <= 0) {
            return false;
        }
        Long uniqueID = docIdToUniqueId.get(docID);
        if (uniqueID == null) {
            return false;
        }
        return !deletedUniqueIds.contains(uniqueID);
    }

    @Override
    public boolean hasDeletions() {
        return !deletedUniqueIds.isEmpty();
    }

    @Override
    public int numDocs() {
        return pkMap.size() - deletedUniqueIds.size();
    }

    @Override
    public void close() {
        // Arena 统一解除本 reader 的全部映射（.fdt/.frq/.fdm/.fdx/.tim/.bkd）
        arena.close();
    }

}
