package hawk.index.core.search;

import hawk.index.core.document.Document;
import hawk.index.core.field.DoubleField;
import hawk.index.core.field.Field;
import hawk.index.core.field.StringField;
import hawk.index.core.reader.DataInput;
import hawk.index.core.reader.DirectoryReader;
import hawk.index.core.util.NumericTrie;
import hawk.index.core.util.WrapInt;
import hawk.index.core.writer.IndexConfig;
import hawk.index.core.writer.Pair;
import lombok.extern.slf4j.Slf4j;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.Util;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class Searcher {

    private DirectoryReader directoryReader;

    private IndexConfig indexConfig;

    public Searcher(DirectoryReader directoryReader, IndexConfig indexConfig) {
        this.directoryReader = directoryReader;
        this.indexConfig = indexConfig;
    }

    public ScoreDoc[] searchTerm(TermQuery query){
        String field = query.getField();
        String term = query.getTerm();
        HashMap<String, Pair<byte[], Float>> fdmMap = directoryReader.getFDMMap();
        float averageDocLength = fdmMap.get(field).getRight();
        FST<BytesRef> termFST = directoryReader.getTermFST();
        MappedByteBuffer frqBuffer = directoryReader.getFRQBuffer();
        BytesRef value = null;
        try {
            value = Util.get(termFST, new BytesRef(field.concat(":").concat(term)));
        } catch (IOException e) {
            log.error("error searching fst");
            System.exit(1);
        }
        byte[] frqOffsetBytes = value.bytes;
        long frqOffset = DataInput.readVlong(frqOffsetBytes);
        WrapInt frqOffsetWrapper = new WrapInt((int) frqOffset);
        int termFrequency = DataInput.readVintAtIndex(frqBuffer, frqOffsetWrapper);
        ScoreDoc[] hits = new ScoreDoc[termFrequency];
        for (int i = 0; i < hits.length; i++) {
            int docID = DataInput.readVintAtIndex(frqBuffer, frqOffsetWrapper);
            int docFrequency = DataInput.readVintAtIndex(frqBuffer,frqOffsetWrapper);
            int docFieldLength = DataInput.readVintAtIndex(frqBuffer,frqOffsetWrapper);
            float score = Similarity.BM25(directoryReader.getTotalDoc(),termFrequency, docFrequency,
                    docFieldLength, averageDocLength);
            ScoreDoc hit = new ScoreDoc(score, docID);
            hits[i] = hit;
        }

        return hits;
    }

    public ScoreDoc[] searchNumericRange(NumericRangeQuery query){
        String field = query.getField();
        double lower = query.getLower();
        double upper = query.getUpper();
        HashMap<String, NumericTrie> trieMap = directoryReader.getNumericTrieMap();
        MappedByteBuffer frqBuffer = directoryReader.getFRQBuffer();
        NumericTrie trie = trieMap.get(field);
        List<NumericTrie.Node> nodes = trie.rangeSearch(lower,upper);
        HashSet<ScoreDoc> resultSet = new HashSet<>(); // same docID may be in different nodes' payload
        for (int i = 0; i < nodes.size(); i++) {
            NumericTrie.Node node = nodes.get(i);
            byte[] offset = node.getOffset();
            int frqOffset = (int) DataInput.readVlong(offset);
            WrapInt frqOffsetWrapper = new WrapInt(frqOffset);
            int length = DataInput.readVintAtIndex(frqBuffer, frqOffsetWrapper);
            for (int j = 0; j < length; j++) {
                int docID = DataInput.readVintAtIndex(frqBuffer, frqOffsetWrapper);
                int docFrequency = DataInput.readVintAtIndex(frqBuffer,frqOffsetWrapper);
                int docFieldLength = DataInput.readVintAtIndex(frqBuffer,frqOffsetWrapper);
                float score = 0;
                ScoreDoc hit = new ScoreDoc(score, docID);
                resultSet.add(hit);
            }
        }
        ScoreDoc[] result = resultSet.toArray(new ScoreDoc[0]);
        return result;
    }


    public ScoreDoc[] booleanSearch(BooleanQuery query){
        Enum<BooleanQuery.Operation> operationEnum = query.getOperation();
        if(operationEnum == BooleanQuery.Operation.MUST){
            return andSearch(query);
        }
        return orSearch(query);
    }

    public ScoreDoc binarySearch(ScoreDoc[] list, ScoreDoc target){
        int first = 0;
        int last = list.length -1;

        int mid = (first + last) / 2;
        while (first <= last) {
            if (list[mid].docID < target.docID) {
                first = mid + 1;
            } else if (list[mid].docID == target.docID) {
                return list[mid];
            } else if (list[mid].docID > target.docID) {
                last = mid - 1;
            }
            mid = (first + last) / 2;
        }
        return null;
    }

    public ScoreDoc[] andSearch(BooleanQuery booleanQuery){
        List<Query> queries = booleanQuery.getQueries();
        List<ScoreDoc[]> hitsList = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) { // recall each term
            Query query = queries.get(i);
            ScoreDoc[] hits;
            if(query instanceof TermQuery){
                hits = searchTerm((TermQuery) query);
            }else if(query instanceof NumericRangeQuery){
                hits = searchNumericRange((NumericRangeQuery) query);
            } else {
                hits = booleanSearch((BooleanQuery) query);
            }
            if(hits == null) return null;
            hitsList.add(hits);
        }// sort recall result by their length
        Collections.sort(hitsList, Comparator.comparingInt(a -> a.length));
        List<ScoreDoc> result = Arrays.asList(hitsList.get(0));
        ArrayList<ScoreDoc> needDelete = new ArrayList<>();
        for (int i = 1; i < hitsList.size(); i++) { // start intersection
            if(result.size() == 0) return null; // if result list size becomes 0 during intersection, immediately retrun
            ScoreDoc[] hits = hitsList.get(i);

            for (int j = 0; j < result.size(); j++) { // binary search shorter list in the longer list
                ScoreDoc target = result.get(i);
                ScoreDoc match = binarySearch(hits, target);
                if(match != null){ // if match happens, add score
                    target.setScore(target.getScore() + match.score);
                }else{ //if mismatch, delete mismatched item after cur iteration
                    needDelete.add(target);
                }
            }
            result.removeAll(needDelete);
            needDelete.clear();
        }
        if(result.size() > 0) return result.toArray(new ScoreDoc[0]);
        return null;
    }

    public ScoreDoc[] orSearch(BooleanQuery booleanQuery){
        List<Query> queries = booleanQuery.getQueries();
        List<ScoreDoc[]> hitsList = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) { // recall each term
            Query query = queries.get(i);
            ScoreDoc[] hits;
            if(query instanceof TermQuery){
                hits = searchTerm((TermQuery) query);
            }else if(query instanceof NumericRangeQuery){
                hits = searchNumericRange((NumericRangeQuery) query);
            } else {
                hits = booleanSearch((BooleanQuery) query);
            }
            if(hits != null) hitsList.add(hits);
        }
        if(hitsList.size() == 0) return null;
        HashMap<Integer , ScoreDoc> result = new HashMap<>();
        for (int i = 0; i < hitsList.get(0).length; i++) {
            ScoreDoc hit = hitsList.get(0)[i];
            result.put(hit.getDocID(), hit);
        }

        for (int i = 1; i < hitsList.size(); i++) {
            for (int j = 0; j < hitsList.get(i).length; j++) {
                ScoreDoc hit = hitsList.get(i)[j];
                ScoreDoc origin = result.putIfAbsent(hit.getDocID(), hit);
                if(origin != null) origin.setScore(origin.getScore() + hit.getScore());
            }
        }
        ScoreDoc[] retArray = new ScoreDoc[result.size()];
        int i = 0;
        for (Map.Entry<Integer, ScoreDoc> entry: result.entrySet()) {
            retArray[i] = entry.getValue();
        }
        return retArray;
    }

    public ScoreDoc[] topN(ScoreDoc[] scoreDocs, int n){
        Arrays.sort(scoreDocs, new Comparator<ScoreDoc>() {
            @Override
            public int compare(ScoreDoc o1, ScoreDoc o2) {
                if(o1.getScore() - o2.getScore() < 0){
                    return -1;
                } else if (o1.getScore() - o2.getScore() > 0) {
                    return 1;
                }
                return 0;
            }
        });
        return Arrays.copyOfRange(scoreDocs, 0, Math.min(n, scoreDocs.length));
    }

    public Field createField(String fieldName, byte[] fieldValue){
        HashMap<String, Pair<byte[], Float>> fdmMap = this.directoryReader.getFDMMap();
        byte fieldType = fdmMap.get(fieldName).getLeft()[0];
        if((fieldType & 0b00001000) != 0){ // String field
            String value = new String(fieldValue,StandardCharsets.UTF_8);
            return new StringField(fieldName, value);
        } else if ((fieldType & 0b00000100)!= 0) { // double field
            long longVal = DataInput.readLong(fieldValue);
            double value = Double.longBitsToDouble(longVal);
            return new DoubleField(fieldName, value);
        }
        return null;
    }

    public byte[][] searchFDTOffset(int docID, TreeMap<Integer, byte[]> fdxMap){
        int leftKey = fdxMap.floorKey(docID);
        byte[] left = fdxMap.get(leftKey);
        Map.Entry<Integer, byte[]> rightEntry = fdxMap.higherEntry(leftKey);
        if(rightEntry != null){
            return new byte[][]{left, rightEntry.getValue()};
        }
        return new byte[][]{left, null};
    }

    public Document doc (ScoreDoc scoreDoc){
        if(scoreDoc == null) return null;
        int docID = scoreDoc.docID;
        Document document = new Document(docID,scoreDoc.getScore());
        TreeMap<Integer, byte[]> fdxMap = this.directoryReader.getFDXMap();
        MappedByteBuffer fdtBuffer = this.directoryReader.getFDTBuffer();
        // calculate fdt buffer offset
        byte[][] vlongOffsets = searchFDTOffset(docID, fdxMap);

        int offsetLeft = (int)DataInput.readVlong(vlongOffsets[0]);
        int offsetRight;
        if(vlongOffsets[1] != null){
            offsetRight = (int) DataInput.readVlong(vlongOffsets[1]);
        }else {
            offsetRight = fdtBuffer.limit();
        }
        int blockLength = offsetRight - offsetLeft;
        byte[] fdtBloc = new byte[blockLength];
        // read compressed bloc into buffer
        fdtBuffer.get(fdtBloc,offsetLeft,blockLength);
        fdtBuffer.rewind();
        byte[] unCompressedBloc = new byte[indexConfig.getBlocSize()];
        LZ4FastDecompressor decompressor = indexConfig.getDecompressor();
        // decompress buffer to unCompressedBloc
        decompressor.decompress(fdtBloc, unCompressedBloc);
        ByteBuffer buffer = ByteBuffer.wrap(unCompressedBloc);
        //read unCompressd block until the document is found
        while (buffer.position() < buffer.limit()){
            int curDocID = DataInput.readVint(buffer);
            int fieldCount = DataInput.readVint(buffer);
            for (int i = 0; i < fieldCount; i++) {
                int fieldLength = DataInput.readVint(buffer);
                byte[] fieldName = new byte[fieldLength];
                buffer.get(fieldName);
                int valueLength = DataInput.readVint(buffer);
                byte[] fieldValue = new byte[valueLength];
                buffer.get(fieldValue);
                if(docID == curDocID) {
                    Field field = createField(new String(fieldName,StandardCharsets.UTF_8), fieldValue);
                    document.add(field);
                }
            }
            if(docID == curDocID) return document;
        }
        return null;
    }

    public void close(){
        directoryReader.close();
    }

    public ScoreDoc[] search(Query query){
        if(query instanceof TermQuery) return searchTerm((TermQuery) query);
        if(query instanceof BooleanQuery) return booleanSearch((BooleanQuery) query);
        return searchNumericRange((NumericRangeQuery) query);
    }

    public static void main(String[] args) {
        TreeMap<Integer, Integer> treeMap = new TreeMap<>();
        treeMap.put(1, 6);

    }


}
