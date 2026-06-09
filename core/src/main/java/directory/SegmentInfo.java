package directory;

import util.bkd.BkdFormatVersion;
import util.DateUtil;
import util.WrapLong;
import util.DataOutput;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

@Slf4j
@Data
public class SegmentInfo {

    private Path segmentInfoPath;
    // files in the same dir with segment.info

    private Set<String> dirFiles;

    private String timeStamp;

    private int segCount;

    private int preMaxID;

    /** 0 = legacy prefix-term numeric index; 1 = BKD numeric index */
    private int formatVersion;

    public SegmentInfo(){
        this.timeStamp = DateUtil.getDateStr();
        this.segCount = 0;
        this.preMaxID = 0;
        this.formatVersion = BkdFormatVersion.CURRENT;
    }

    public SegmentInfo(Path segmentInfoPath){
        this.segmentInfoPath = segmentInfoPath;
        read(segmentInfoPath);
    }

    public void read(Path path){
        try {
            FileChannel fc = FileChannel.open(path);
            ByteBuffer dateBuffer = ByteBuffer.allocate(8);
            ByteBuffer segCountBuffer = ByteBuffer.allocate(4);
            ByteBuffer preMaxIDBuffer = ByteBuffer.allocate(4);
            fc.read(dateBuffer, 0);
            this.timeStamp = new String(dateBuffer.array(), StandardCharsets.UTF_8);

            fc.read(segCountBuffer, 8);
            segCountBuffer.flip();
            this.segCount = segCountBuffer.getInt();

            fc.read(preMaxIDBuffer, 12);
            preMaxIDBuffer.flip();
            this.preMaxID = preMaxIDBuffer.getInt();
            if (fc.size() >= 20) {
                ByteBuffer formatVersionBuffer = ByteBuffer.allocate(4);
                fc.read(formatVersionBuffer, 16);
                formatVersionBuffer.flip();
                this.formatVersion = formatVersionBuffer.getInt();
            } else {
                this.formatVersion = BkdFormatVersion.LEGACY_PREFIX;
            }
            fc.close();
        } catch (IOException e) {
            log.error("sth wrong reading segment.info");
            System.exit(1);
        }
    }

    public String writeDate(FileChannel fc){
        String dateStr = DateUtil.getDateStr();
        byte[] dateBytes = dateStr.getBytes(StandardCharsets.UTF_8);
        ByteBuffer byteBuffer = ByteBuffer.wrap(dateBytes);
        try {
            fc.write(byteBuffer, 0);
            return new String(dateBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("sth wrong with segment.info write date ");
            System.exit(1);
        }
        return null;
    }

    public void writeSegCount(FileChannel fc, int count){
        DataOutput.writeInt(count, fc, new WrapLong(8));
    }

    public void writePreMaxID(FileChannel fc, int id){
        DataOutput.writeInt(id, fc, new WrapLong(12));
    }

    public void writeFormatVersion(FileChannel fc, int version) {
        DataOutput.writeInt(version, fc, new WrapLong(16));
    }

    public void init(Path path){
        try {
            FileChannel fc = new RandomAccessFile(path.toString(), "rw").getChannel();
            writeDate(fc);
            writeSegCount(fc, 0);
            writePreMaxID(fc, 0);
            writeFormatVersion(fc, BkdFormatVersion.CURRENT);
            this.formatVersion = BkdFormatVersion.CURRENT;
            fc.force(false);
            fc.close();
        } catch (IOException e) {
            log.error("open segment.info failed");
            System.exit(1);
        }
    }

    public void update(int lastDocID, int segCountInc){
        FileChannel fc = null;
        try {
            fc = new RandomAccessFile(segmentInfoPath.toString(),"rw").getChannel();
            int newSegCount = this.segCount + segCountInc;
            writeSegCount(fc, newSegCount);
            writePreMaxID(fc, lastDocID);
            writeFormatVersion(fc, BkdFormatVersion.CURRENT);
            this.timeStamp = writeDate(fc);
            this.segCount = newSegCount;
            this.preMaxID = lastDocID;
            this.formatVersion = BkdFormatVersion.CURRENT;
            fc.force(false);
            fc.close();
        } catch (IOException e) {
            log.error("update .info failed");
            System.exit(1);
        }

    }
}
