package directory;

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

    /**
     * 索引的磁盘格式版本，取值见 {@link IndexFormatVersion}。
     *
     * <p>整个索引只有这**一个**版本号（不是"BKD 一个、fdm 一个"）。读路径上有守卫，见 {@link #read}。
     */
    private int formatVersion;

    public SegmentInfo(){
        this.timeStamp = DateUtil.getDateStr();
        this.segCount = 0;
        this.preMaxID = 0;
        this.formatVersion = IndexFormatVersion.CURRENT;
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
                this.formatVersion = IndexFormatVersion.LEGACY_PREFIX;
            }
            fc.close();
        } catch (IOException e) {
            log.error("sth wrong reading segment.info");
            System.exit(1);
        }

        // ★ 版本守卫。**放在读路径上**，一处覆盖"打开检索 / 追加 / 段合并"三条路。
        //
        // 为什么必须在这里、而不是在某个读文件的方法里：update() 与 init() 每次都把 segment.info
        // 刷成 CURRENT，所以往一个旧索引上**追加**会先把版本号写成新的，而磁盘上的 1.fdm / 1.bkd
        // 还是旧格式 —— 等某个读文件的方法再判就晚了，版本号已经说了谎。在**读**的时候拦，
        // 才不会出现"版本号说 v3、内容却是 v2"的状态。
        //
        // 放在 try 之外，免得抛异常时漏掉 FileChannel。抛 IllegalStateException 而不是 IOException：
        // 这是**格式不兼容**，调用方该做的是重建索引，不是重试。
        if (this.formatVersion < IndexFormatVersion.CURRENT) {
            throw new IllegalStateException("索引的格式版本是 " + this.formatVersion
                    + "，当前需要 " + IndexFormatVersion.CURRENT
                    + "（旧版本没有 fdm 的 tokenizer 记录，或数值编码跨零不单调）。请重建索引："
                    + path.toAbsolutePath());
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
            writeFormatVersion(fc, IndexFormatVersion.CURRENT);
            this.formatVersion = IndexFormatVersion.CURRENT;
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
            writeFormatVersion(fc, IndexFormatVersion.CURRENT);
            this.timeStamp = writeDate(fc);
            this.segCount = newSegCount;
            this.preMaxID = lastDocID;
            this.formatVersion = IndexFormatVersion.CURRENT;
            fc.force(false);
            fc.close();
        } catch (IOException e) {
            log.error("update .info failed");
            System.exit(1);
        }

    }
}
