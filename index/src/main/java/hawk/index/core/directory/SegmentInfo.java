package hawk.index.core.directory;

import hawk.index.core.util.WrapLong;
import hawk.index.core.writer.DataOutput;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;
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

    public SegmentInfo(Path directoryPath, Set<String> dirFiles){
        String strPath = directoryPath.toString() + "/segment.info";
        Path path = Paths.get(strPath);
        if(dirFiles.contains("segment.info")){
            read(path);
        }else{ // create segment.info
            Directory.createFile(path);
            init(path);
            read(path);
        }
        this.segmentInfoPath = path;
    }

    public void read(Path path){
        try {
            FileChannel fc = FileChannel.open(path);
            ByteBuffer buffer = ByteBuffer.allocate(8);
            fc.read(buffer, 0);
            buffer.flip();
            this.timeStamp = new String(buffer.array(), StandardCharsets.UTF_8);

            fc.read(buffer, 8);
            buffer.flip();
            this.segCount = buffer.getInt();

            fc.read(buffer, 12);
            buffer.flip();
            this.preMaxID = buffer.getInt();
        } catch (IOException e) {
            log.error("sth wrong reading segment.info");
            System.exit(1);
        }
    }

    public void writeDate(FileChannel fc){
        Date date = new Date();
        Format formatter = new SimpleDateFormat("yyyyMMdd");
        String dateStr = formatter.format(date);
        byte[] dateBytes = dateStr.getBytes(StandardCharsets.UTF_8);
        ByteBuffer byteBuffer = ByteBuffer.wrap(dateBytes);
        try {
            fc.write(byteBuffer, 0);
        } catch (IOException e) {
            log.error("sth wrong with segment.info write date ");
            System.exit(1);
        }
    }

    public void writeSegCount(FileChannel fc, int count){
        DataOutput.writeInt(count, fc, new WrapLong(8));
    }

    public void writePreMaxID(FileChannel fc, int id){
        DataOutput.writeInt(id, fc, new WrapLong(12));
    }

    public void init(Path path){
        try {
            FileChannel fc = new RandomAccessFile(path.toString(), "rw").getChannel();
            writeDate(fc);
            writeSegCount(fc, 0);
            writePreMaxID(fc, 0);
            fc.force(false);
            fc.close();
        } catch (IOException e) {
            log.error("open segment.info failed");
            System.exit(1);
        }
    }

    public void update(){
        FileChannel fc = null;
        try {
            fc = new RandomAccessFile(segmentInfoPath.toString(),"rw").getChannel();
            writeDate(fc);
            writeSegCount(fc, this.segCount);
            writePreMaxID(fc, this.preMaxID);
            fc.force(false);
            fc.close();
        } catch (IOException e) {
            log.error("update .info failed");
            System.exit(1);
        }

    }

    public static void main(String[] args) {

    }
}
