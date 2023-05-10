package search;

import document.Document;
import field.DoubleField;
import field.Field;
import field.StringField;
import hawk.segment.core.Term;
import hawk.segment.core.anlyzer.Analyzer;
import lombok.extern.slf4j.Slf4j;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.Util;
import reader.DirectoryReader;
import util.DataInput;
import util.NumericTrie;
import util.WrapInt;
import writer.IndexConfig;
import writer.Pair;


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

    public ScoreDoc[] searchString(StringQuery query){
        Analyzer analyzer = indexConfig.getAnalyzer();
        HashSet<Term> terms = analyzer.anlyze(query.getValue(), query.getField());
        ScoreDoc[][] scoreDocs = new ScoreDoc[terms.size()][];
        int i = 0;
        BooleanQuery andQuery = new BooleanQuery(BooleanQuery.Operation.MUST);
        BooleanQuery orQuery = new BooleanQuery(BooleanQuery.Operation.SHOULD);
        for (Term term: terms) {
            String field = term.getFieldName();
            String termStr = term.getValue();
            TermQuery termQuery = new TermQuery(field, termStr);
            andQuery.addQuery(termQuery);
            orQuery.addQuery(termQuery);
        }
        ScoreDoc[] andSearchRet = booleanSearch(andQuery);
        if(andSearchRet!=null && andSearchRet.length != 0) return andSearchRet;
        ScoreDoc[] orSearchRet = booleanSearch(orQuery);
        return orSearchRet;
    }

    public ScoreDoc[] searchTerm(TermQuery query){
        String field = query.getField();
        String term = query.getTerm();
        HashMap<String, Pair<byte[], Float>> fdmMap = directoryReader.getFDMMap();
        float averageDocLength = fdmMap.get(field).getRight();
        FST<BytesRef> termFST = directoryReader.getTermFST();
        MappedByteBuffer frqMappedBuffer = directoryReader.getFRQBuffer();
        ByteBuffer frqBuffer = frqMappedBuffer.asReadOnlyBuffer();
        BytesRef value = null;
        try {
            value = Util.get(termFST, new BytesRef(field.concat(":").concat(term)));
        } catch (IOException e) {
            log.error("error searching fst");
            System.exit(1);
        }
        if(value == null) return null;
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
        MappedByteBuffer frqMappedBuffer = directoryReader.getFRQBuffer();
        ByteBuffer frqBuffer = frqMappedBuffer.asReadOnlyBuffer();
        NumericTrie trie = trieMap.get(field);
        List<NumericTrie.Node> nodes = trie.rangeSearch(lower,upper);
        HashSet<ScoreDoc> resultSet = new HashSet<>(); // same docID may be in different nodes' payload
        for (int i = 0; i < nodes.size(); i++) {
            NumericTrie.Node node = nodes.get(i);
            byte[][] offsets = node.getOffsets();
            for (int j = 0; j < offsets.length; j++) {
                byte[] offset = offsets[j];
                int frqOffset = (int) DataInput.readVlong(offset);
                WrapInt frqOffsetWrapper = new WrapInt(frqOffset);
                int length = DataInput.readVintAtIndex(frqBuffer, frqOffsetWrapper);
                for (int k = 0; k < length; k++) {
                    int docID = DataInput.readVintAtIndex(frqBuffer, frqOffsetWrapper);
                    int docFrequency = DataInput.readVintAtIndex(frqBuffer,frqOffsetWrapper);
                    int docFieldLength = DataInput.readVintAtIndex(frqBuffer,frqOffsetWrapper);
                    float score = 0;
                    ScoreDoc hit = new ScoreDoc(score, docID);
                    resultSet.add(hit);
                }//reset frqBuffer position after reading each frq record
                //[frq length<vint>, [docID<vint>, frq<vint>, fieldLength<vint>]]
                frqBuffer.rewind();
            }
        }
        ScoreDoc[] result = resultSet.toArray(new ScoreDoc[0]);
        return result;
    }


    public ScoreDoc[] booleanSearch(BooleanQuery query){
        Enum<BooleanQuery.Operation> operationEnum = query.getOperation();
        if(operationEnum == BooleanQuery.Operation.MUST) return andSearch(query);
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
        if(hitsList.size() == 0) return null;
        Collections.sort(hitsList, Comparator.comparingInt(a -> a.length));;
        List<ScoreDoc> result = new ArrayList<>();
        for (int i = 0; i < hitsList.get(0).length; i++) {
            result.add(hitsList.get(0)[i]);
        }
        ArrayList<ScoreDoc> needDelete = new ArrayList<>();
        // binary search shorter list in longer list to find intersection
        for (int i = 1; i < hitsList.size(); i++) { // start intersection
            if(result.size() == 0) return null; // if result list size becomes 0 during intersection, immediately retrun
            ScoreDoc[] hits = hitsList.get(i);
            for (int j = 0; j < result.size(); j++) { // binary search shorter list in the longer list
                ScoreDoc target = result.get(j);
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
        HashSet<ScoreDoc> retSet = new HashSet<>();
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

        for (int i = 0; i < hitsList.size(); i++) {
            for (int j = 0; j < hitsList.get(i).length; j++) {
                retSet.add(hitsList.get(i)[j]);
            }
        }

        return new ArrayList<>(retSet).toArray(new ScoreDoc[0]);
    }

    public ScoreDoc[] topN(ScoreDoc[] scoreDocs, int n){
        if(scoreDocs == null || scoreDocs.length == 0) return new ScoreDoc[0];
        Arrays.sort(scoreDocs, new Comparator<ScoreDoc>() {
            @Override
            public int compare(ScoreDoc o1, ScoreDoc o2) {
                if(o1.getScore() - o2.getScore() < 0){
                    return 1;
                } else if (o1.getScore() - o2.getScore() > 0) {
                    return -1;
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

    // return: left offset + right offset
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
        Document document = new Document(scoreDoc.getScore());
        TreeMap<Integer, byte[]> fdxMap = this.directoryReader.getFDXMap();
        MappedByteBuffer fdtMappedBuffer = this.directoryReader.getFDTBuffer();
        // create a duplicate of mappedBuffer, position, limit and mark are independent
        ByteBuffer fdtBuffer  = fdtMappedBuffer.asReadOnlyBuffer();
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
        // read compressed bloc into buffer
        byte[] fdtBloc = DataInput.readBytes(fdtBuffer, offsetLeft, blockLength);

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

    public ScoreDoc[] search(Query query, int topN){
        ScoreDoc[] result = null;
        if(query instanceof TermQuery){
            result = searchTerm((TermQuery) query);
        } else if (query instanceof BooleanQuery) {
            result = booleanSearch((BooleanQuery) query);
        } else if (query instanceof StringQuery) {
            result = searchString((StringQuery) query);
        } else if(query instanceof NumericRangeQuery){
            result = searchNumericRange((NumericRangeQuery) query);
            return result;
        }
        return topN(result, topN);
    }
}
