package hawk.index.core.directory;

import hawk.index.core.writer.DataOutput;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
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
        if(dirFiles.contains("/segment.info")){
            read(path);
        }else{ // create segment.info
            try {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxrwxrwx");
                Files.createFile(path, PosixFilePermissions.asFileAttribute(perms));
                dirFiles.add("/segment.info");
            } catch (IOException e) {
                log.error("create segment.info file failed");
                System.exit(1);
            }
            init(path);
            read(path);
        }
        this.segmentInfoPath = path;
    }

    public void read(Path path){
        try {
            FileChannel fc = FileChannel.open(path);
            ByteBuffer timeStamp = ByteBuffer.allocate(8);
            ByteBuffer segCount = ByteBuffer.allocate(4);
            ByteBuffer preMaxID = ByteBuffer.allocate(4);
            fc.read(timeStamp, 0);
            fc.read(segCount, 8);
            fc.read(preMaxID, 12);
            this.timeStamp = new String(timeStamp.array(), StandardCharsets.UTF_8);
            this.segCount = segCount.getInt();
            this.preMaxID = preMaxID.getInt();
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
        DataOutput.writeInt(count, fc, 8L);
    }

    public void writePreMaxID(FileChannel fc, int id){
        DataOutput.writeInt(id, fc, 12L);
    }

    public void init(Path path){
        try {
            FileChannel fc =FileChannel.open(path);
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
            fc = FileChannel.open(segmentInfoPath);
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
