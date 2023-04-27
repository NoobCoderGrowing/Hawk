package hawk.index.core.reader;

import hawk.index.core.directory.Directory;
import hawk.index.core.directory.memory.MMap;
import hawk.index.core.util.DataInput;
import hawk.index.core.util.NumberUtil;
import hawk.index.core.util.NumericTrie;
import hawk.index.core.writer.Pair;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.fst.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.TreeMap;

@Slf4j
public class MMapDirectoryReader extends DirectoryReader {

    private Directory directory;

    private TreeMap<Integer, byte[]> fdxMap;

    private MappedByteBuffer fdtBuffer;

    private HashMap<String, Pair<byte[], Float>> fdmMap;

    private MappedByteBuffer frqBuffer;

    private FST<BytesRef> termFST;

    private HashMap<String, NumericTrie> numericTries;

    public MMapDirectoryReader(Directory directory) {
        this.directory = directory;
        this.fdxMap = new TreeMap<>();
        this.fdmMap = new HashMap<>();
        this.numericTries = new HashMap<>();
        init();
    }

    public void init() {
        constructFdxMap();
        loadFdt();
        constructFdmMap();
        loadFrq();
        constructFSTNumericTrie();
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
                this.fdxMap.put(key, offset);
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
            FileChannel fc = new RandomAccessFile(fdtPath, "r").getChannel();
            long fcSize = fc.size();
            MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fcSize);// .fdt must not exceed 4GB
            buffer.load(); // force load buffer content into memory
            this.fdtBuffer = buffer;
            fc.close();
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
                fdmMap.put(fieldName, new Pair<>(new byte[]{fieldType}, avgFieldLength));
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
            FileChannel fc = new RandomAccessFile(frqPath, "r").getChannel();
            long fcSize = fc.size();
            MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fcSize);// .frq must not exceed 4GB
            buffer.load(); // force load buffer content into memory
            this.frqBuffer = buffer;
            fc.close();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void constructFSTNumericTrie(){
        ByteSequenceOutputs outputs = ByteSequenceOutputs.getSingleton();
        Builder<BytesRef> builder = new Builder<BytesRef>(FST.INPUT_TYPE.BYTE1, outputs);
        BytesRefBuilder scratchBytes = new BytesRefBuilder();
        IntsRefBuilder scratchInts = new IntsRefBuilder();

        String dirPath = directory.getPath().toAbsolutePath().toString();
        String timPath = dirPath + "/1.tim";
        log.info("start constructing FST and numericTrie");
        try {
            FileChannel fc = new RandomAccessFile(timPath, "rw").getChannel();
            int fcSize = (int) fc.size();
            ByteBuffer buffer = ByteBuffer.allocate(fcSize);
            fc.read(buffer, 0);
            buffer.flip();
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
                byte fieldType = fdmMap.get(fieldName).getLeft()[0];
                if((fieldType & 0b00001000) != 0){ // String term
                    log.info("FST building ==> " + "filed name is " + fieldName +", field value is " +
                            fieldValue + ", offset is " + DataInput.readLong(offset));
                    scratchBytes.copyChars(fieldName.concat(":").concat(fieldValue));
                    BytesRef bytesRef = new BytesRef(offset);
                    builder.add(Util.toIntsRef(scratchBytes.get(), scratchInts), bytesRef);
                } else if ((fieldType & 0b00000100)!= 0) { // double term
                    constructNumericTrieMap(fieldName, fieldValueBytes, offset, 64 , 4);
                }
            }
            this.termFST = builder.finish();
            fc.close();
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


        if(numericTries.containsKey(fieldName)){
            NumericTrie trie = numericTries.get(fieldName);
            trie.add(key, offset);
        }else {
            NumericTrie trie = new NumericTrie(length, precisionStep);
            trie.add(key, offset);
            numericTries.put(fieldName, trie);
        }
    }

    @Override
    public FST<BytesRef> getTermFST() {
        return this.termFST;
    }

    @Override
    public MappedByteBuffer getFRQBuffer() {
        return this.frqBuffer;
    }

    @Override
    public MappedByteBuffer getFDTBuffer() {
        return this.fdtBuffer;
    }

    @Override
    public HashMap<String, NumericTrie> getNumericTrieMap() {
        return this.numericTries;
    }

    @Override
    public HashMap<String, Pair<byte[], Float>> getFDMMap() {
        return this.fdmMap;
    }

    @Override
    public TreeMap<Integer, byte[]> getFDXMap() {
        return this.fdxMap;
    }

    @Override
    public int getTotalDoc() {
        return directory.getSegmentInfo().getPreMaxID();
    }

    @Override
    public void close() {
        MMap.unMMap(fdtBuffer);
        MMap.unMMap(frqBuffer);
    }

}
