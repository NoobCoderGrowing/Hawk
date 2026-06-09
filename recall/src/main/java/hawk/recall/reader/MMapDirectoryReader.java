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
import util.bkd.BkdFileReader;
import util.bkd.BkdFormatVersion;
import util.bkd.BkdReader;
import common.Pair;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
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

    private TreeMap<Integer, byte[]> fDXMap;

    private MappedByteBuffer fDTBuffer;

    private HashMap<String, Pair<byte[], Float>> fDMMap;

    private MappedByteBuffer fRQBuffer;

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
            this.bkdReaders = new HashMap<>(BkdFileReader.open(java.nio.file.Paths.get(bkdPath)));
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
            FileChannel fc = new RandomAccessFile(fdxPath, "rw").getChannel();
            int fcSize = (int) fc.size(); // .fdx must not exceed 4GB
            ByteBuffer buffer = ByteBuffer.allocate(fcSize);
            fc.read(buffer, 0);
            buffer.flip();
            while (buffer.position() < buffer.limit()) {
                int key = DataInput.readVint(buffer);
                byte[] offset = DataInput.readVlongBytes(buffer);
                this.fDXMap.put(key, offset);
            }
            fc.close();
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
            MappedByteBuffer buffer = MMap.mmapFile(fdtPath);// .fdt must not exceed 4GB
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
            FileChannel fc = new RandomAccessFile(fdmPath, "rw").getChannel();
            int fcSize = (int) fc.size(); // .fdx must not exceed 4GB
            ByteBuffer buffer = ByteBuffer.allocate(fcSize);
            fc.read(buffer, 0);
            buffer.flip();
            while (buffer.position() < buffer.limit()){
                int length = buffer.getInt();
                byte[] bytes = new byte[length];
                buffer.get(bytes);
                String fieldName = new String(bytes, StandardCharsets.UTF_8);
                byte fieldType =  buffer.get();
                int fieldLengthSum = buffer.getInt();
                int docCount = buffer.getInt();
                float avgFieldLength = fieldLengthSum/docCount;
                fDMMap.put(fieldName, new Pair<>(new byte[]{fieldType}, avgFieldLength));
            }
            fc.close();
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
            MappedByteBuffer buffer = MMap.mmapFile(frqPath);// .frq must not exceed 4GB
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
            MappedByteBuffer buffer = MMap.mmapFile(timPath);
            while (buffer.position() < buffer.limit()){
                int fieldLength =  buffer.getInt();
                byte[] filedNameBytes = new byte[fieldLength];
                buffer.get(filedNameBytes);
                String fieldName = new String(filedNameBytes, StandardCharsets.UTF_8);
                int termLength = buffer.getInt();
                byte[]  fieldValueBytes = new byte[termLength];
                buffer.get(fieldValueBytes);
                String fieldValue = new String(fieldValueBytes,StandardCharsets.UTF_8);
                byte[] offset = DataInput.readVlongBytes(buffer);
                byte fieldType = fDMMap.get(fieldName).getLeft()[0];
                if((fieldType & 0b00001000) != 0){
                    long frqOffset = DataInput.readVlong(offset);
                    stringTerms.add(new fstPair<>(
                            TermFstUtil.termKey(fieldName, fieldValue), frqOffset));
                } else if ((fieldType & 0b00000100)!= 0) {
                    // numeric fields are indexed in .bkd
                }
            }
            MMap.unMMap(buffer);
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
    public int numDocs() {
        return pkMap.size() - deletedUniqueIds.size();
    }

    @Override
    public void close() {
        MMap.unMMap(fDTBuffer);
        MMap.unMMap(fRQBuffer);
    }

}
