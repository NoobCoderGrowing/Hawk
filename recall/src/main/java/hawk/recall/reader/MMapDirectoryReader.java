package hawk.recall.reader;

import directory.Directory;
import directory.LiveDocsStore;
import directory.PkMapStore;
import directory.memory.MMap;
import io.github.noobcodergrowing.JFST.FST;
import io.github.noobcodergrowing.JFST.fstPair;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import util.DataInput;
import util.NumberUtil;
import util.NumericTrie;
import common.Pair;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
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

    private HashMap<String, NumericTrie> numericTrieMap;

    private Map<Long, Integer> pkMap;

    private BitSet liveDocs;

    public MMapDirectoryReader(Directory directory) {
        this.directory = directory;
        this.fDXMap = new TreeMap<>();
        this.fDMMap = new HashMap<>();
        this.numericTrieMap = new HashMap<>();
        init();
    }

    public void init() {
        loadDeleteMetadata();
        constructFdxMap();
        loadFdt();
        constructFdmMap();
        loadFrq();
        constructFSTNumericTrie();
    }

    private void loadDeleteMetadata() {
        try {
            this.pkMap = PkMapStore.load(directory.getPath());
            int preMaxID = directory.getSegmentInfo().getPreMaxID();
            this.liveDocs = LiveDocsStore.load(directory.getPath(), preMaxID);
        } catch (IOException e) {
            log.error("failed to load pk.map or live.docs");
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

    public void constructFSTNumericTrie(){
        ArrayList<fstPair<String, Long>> stringTerms = new ArrayList<>();

        String dirPath = directory.getPath().toAbsolutePath().toString();
        String timPath = dirPath + "/1.tim";
        log.info("start constructing FST and numericTrie");
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
                if((fieldType & 0b00001000) != 0){ // String term
                    long frqOffset = DataInput.readVlong(offset);
                    log.info("FST building ==> " + "filed name is " + fieldName +", field value is " +
                            fieldValue + ", offset is " + frqOffset);
                    stringTerms.add(new fstPair<>(
                            TermFstUtil.termKey(fieldName, fieldValue), frqOffset));
                } else if ((fieldType & 0b00000100)!= 0) { // double term
                    constructNumericTrieMap(fieldName, fieldValueBytes, offset, 64 , 4);
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
            log.info("end of constructing FST and numericTrie");
        } catch (FileNotFoundException e) {
            log.error("tim file does not exist");
            System.exit(1);
        } catch (IOException e) {
            log.error("errored reading tim file");
            System.exit(1);
        }
    }

    public void  constructNumericTrieMap(String fieldName, byte[] fieldValueBytes, byte[] offset, int length,
                                      int precisionStep){
        String key = new String(fieldValueBytes);

//        debug info
        long sortableLong = DataInput.read7bitBytes2Long(fieldValueBytes, 1);
        double doubelValue = NumberUtil.sortableLong2Double(sortableLong);
        int shift = fieldValueBytes[0] & 0xff;
        log.info("NumericTrie Construction ===> " + "field name is " + fieldName + ", shift is " + shift + ", value is "
                + doubelValue);
        if(numericTrieMap.containsKey(fieldName)){
            NumericTrie trie = numericTrieMap.get(fieldName);
            trie.add(key, offset);
        }else {
            NumericTrie trie = new NumericTrie(length, precisionStep);
            trie.add(key, offset);
            numericTrieMap.put(fieldName, trie);
        }
    }

    @Override
    public int getTotalDoc() {
        return directory.getSegmentInfo().getPreMaxID();
    }

    @Override
    public boolean isLive(int docID) {
        return docID > 0 && liveDocs.get(docID);
    }

    @Override
    public int numDocs() {
        return liveDocs.cardinality();
    }

    @Override
    public void close() {
        MMap.unMMap(fDTBuffer);
        MMap.unMMap(fRQBuffer);
    }

}
