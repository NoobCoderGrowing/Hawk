package hawk.index.core.reader;

import hawk.index.core.directory.Directory;
import hawk.index.core.util.AVLTree;
import hawk.index.core.util.NumericTrie;
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

@Slf4j
public class MMapDirectoryReader extends DirectoryReader {

    private Directory directory;

    private AVLTree<byte[]> fdxTree;

    private MappedByteBuffer fdxBuffer;

    private HashMap<String, byte[]> fdmMap;

    private MappedByteBuffer frqBuffer;

    private FST<BytesRef> termFST;

    private HashMap<String, NumericTrie> numericTries;

    public MMapDirectoryReader(Directory directory) {
        this.directory = directory;
        this.fdxTree = new AVLTree<>();
        this.fdmMap = new HashMap<>();
        this.numericTries = new HashMap<>();
        init();
    }

    public void init() {
        constructFdxTree();
        loadFdt();
        constructFdmMap();
        loadFrq();
        constructFSTNumericTrie();
    }

    public void constructFdxTree() {
        String dirPath = directory.getPath().toAbsolutePath().toString();
        String fdxPath = dirPath + "/1.fdx";
        try {
            FileChannel fc = new RandomAccessFile(fdxPath, "rw").getChannel();
            int fcSize = (int) fc.size(); // .fdx must not exceed 4GB
            ByteBuffer buffer = ByteBuffer.allocate(fcSize);
            fc.read(buffer, 0);
            while (buffer.position() < buffer.limit()) {
                int key = DataInput.readVint(buffer);
                byte[] value = DataInput.readVlongBytes(buffer);
                fdxTree.insert(key, value);
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
            FileChannel fc = new RandomAccessFile(fdtPath, "rw").getChannel();
            long fcSize = fc.size();
            MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fcSize);// .fdt must not exceed 4GB
            buffer.load(); // force load buffer content into memory
            this.fdxBuffer = buffer;
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
            int length = 0;
            byte[] bytes;
            byte fieldType;
            String fieldName;
            while (buffer.position() < buffer.limit()){
                length = buffer.getInt();
                bytes = new byte[length];
                buffer.get(bytes);
                fieldName = new String(bytes, StandardCharsets.UTF_8);
                fieldType = buffer.get();
                fdmMap.put(fieldName, new byte[]{fieldType});
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
            FileChannel fc = new RandomAccessFile(frqPath, "rw").getChannel();
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
        try {
            FileChannel fc = new RandomAccessFile(timPath, "rw").getChannel();
            long fcSize = fc.size();
            MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fcSize);
            int fieldLength, termLength;
            byte[] bytes;
            String fieldName;
            byte fieldType;
            BytesRef bytesRef;
            while (buffer.position() < buffer.limit()){
                fieldLength = buffer.getInt();
                bytes = new byte[fieldLength];
                buffer.get(bytes);
                fieldName = new String(bytes, StandardCharsets.UTF_8);
                termLength = buffer.getInt();
                bytes = new byte[termLength];
                buffer.get(bytes);
                bytes = DataInput.readVlongBytes(buffer);
                fieldType = fdmMap.get(fieldName)[0];
                if((fieldType & 0b00001000) != 0){
                    scratchBytes.copyChars(fieldName);
                    bytesRef = new BytesRef(bytes);
                    builder.add(Util.toIntsRef(scratchBytes.get(), scratchInts), bytesRef);
                } else if ((fieldType & 0b00000100)!= 0) {
                    constructNumericTrie(fieldName, bytes);
                }
            }
            this.termFST = builder.finish();
            fc.close();
        } catch (FileNotFoundException e) {
            log.error("tim file does not exist");
            System.exit(1);
        } catch (IOException e) {
            log.error("errored reading tim file");
            System.exit(1);
        }
    }

    public void  constructNumericTrie(String fieldName, byte[] bytes){

    }

}
