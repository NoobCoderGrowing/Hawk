package hawk.index.core.writer;

import hawk.index.core.directory.Directory;
import hawk.index.core.directory.memory.MMap;
import hawk.index.core.util.DataInput;
import hawk.index.core.util.DataOutput;
import hawk.index.core.util.WrapLong;
import lombok.extern.slf4j.Slf4j;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class IndexMerger {

    private Directory directory;

    private IndexConfig indexConfig;

    private AtomicInteger docIDAllocator;

    int docBase;


    public IndexMerger(Directory directory, IndexConfig indexConfig, AtomicInteger docIDAllocator, int docBase) {
        this.directory = directory;
        this.indexConfig = indexConfig;
        this.docIDAllocator = docIDAllocator;
        this.docBase = docBase;
    }

    public void deleteFiles(ArrayList<String> files){
        Iterator<String> it = files.iterator();
        while (it.hasNext()){
            String file = it.next();
            if(file.contains("2")){
                try {
                    Files.delete(Paths.get(file));
                    it.remove();
                } catch (IOException e) {
                    log.error("delete file errored after merge");
                    System.exit(1);
                }
            }
        }
    }

    public void mergeIndexed(ArrayList<String> files){

    }

    public void merge(){
        ArrayList<String> files = directory.getFiles();
        if(files.size() != 11) { // 1 segment contains 5 files, plus 1 segement.info, thus 2 * 5 + 1
            log.error("wrong file count " + files.size() + " detected during merge");
            System.exit(1);
        }
        mergeStored(files);
        mergeIndexed(files);
        deleteFiles(files);
        this.directory.updateSegInfo(docIDAllocator.get() + this.docBase, -1);
    }

    public void mergeFDX(ArrayList<int[]> seg2FDX, FileChannel seg1FdxFC){
        try {
            long limit = seg1FdxFC.size();
            for (int i = 0; i < seg2FDX.size(); i++) {
                int[] item = seg2FDX.get(i);
                int docID = item[0];
                long offset = item[1] + limit;
                WrapLong offsetWrapper = new WrapLong(offset);
                log.info("fdx merge writing ===> " + "docID is " + docID + ", fdt offset is " + offset);
                DataOutput.writeVInt(docID, seg1FdxFC, offsetWrapper);
                DataOutput.writeVLong(offset, seg1FdxFC, offsetWrapper);
            }
        } catch (IOException e) {
            log.error("something wrong during mergeFDX");
            System.exit(1);
        }
    }

    public void mergeFDT(FileChannel seg1FdtFC,  MappedByteBuffer seg2FDTBuffer,
                         ArrayList<int[]> seg2FDX){
        try {
            long writePos = seg1FdtFC.size();
            long limit = seg2FDTBuffer.limit();
            int left, right;
            for (int i = 0; i < seg2FDX.size(); i++) {
                // calculate original start and length
                left = seg2FDX.get(i)[1];
                if(i < seg2FDX.size() - 1){
                    right = seg2FDX.get(i+1)[1];
                }else{
                    right = (int) limit;
                }
                int length = right - left;
                byte[] block = new byte[length];
                //read original bloc
                seg2FDTBuffer.get(block, left, length);
                ByteBuffer buffer = ByteBuffer.wrap(block);
                //write original block to seg1 FDT
                writePos += left;
                seg1FdtFC.write(buffer, writePos);
            }
        } catch (IOException e) {
            log.error("something wrong during mergeFDT");
            System.exit(1);
        }
    }

    public void mergeStored(ArrayList<String> files){
        try {
            FileChannel seg1FdtFC = null, seg1FdxFC = null;
            MappedByteBuffer seg2FDTBuffer = null ,seg2FDXBuffer = null;
            for (int i = 0; i < files.size(); i++) {
                if(files.get(i).contains("1.fdx")){
                    seg1FdxFC = new RandomAccessFile(files.get(i), "rw").getChannel();
                } else if (files.get(i).contains("1.fdt")) {
                    seg1FdtFC = new RandomAccessFile(files.get(i), "rw").getChannel();
                } else if (files.get(i).contains("2.fdx")) {
                    seg2FDXBuffer = MMap.mmapFile(files.get(i));
                } else if (files.get(i).contains("2.fdt")) {
                    seg2FDTBuffer = MMap.mmapFile(files.get(i));
                }
            }
            ArrayList<int[]> seg2FDX = new ArrayList<>();
            while (seg2FDXBuffer.position() < seg2FDXBuffer.limit()){
                int seg2DocID = DataInput.readVint(seg2FDXBuffer);
                int seg2FDToffset = (int) DataInput.readVlong(seg2FDXBuffer);
                seg2FDX.add(new int[]{seg2DocID, seg2FDToffset});
            }
            mergeFDX(seg2FDX, seg1FdxFC);
            mergeFDT(seg1FdtFC, seg2FDTBuffer, seg2FDX);
            seg1FdxFC.close();
            seg1FdtFC.close();
            MMap.unMMap(seg2FDXBuffer);
            MMap.unMMap(seg2FDTBuffer);
        } catch (FileNotFoundException e) {
            log.error("file not found during mergeStored");
            System.exit(1);
        } catch (IOException e) {
            log.error("create mapping failed during mergeStored");
            System.exit(1);
        }



    }

    public static void main(String[] args) {
    }
}
