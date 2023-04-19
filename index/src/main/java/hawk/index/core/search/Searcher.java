package hawk.index.core.search;

import hawk.index.core.document.Document;
import hawk.index.core.reader.DataInput;
import hawk.index.core.reader.DirectoryReader;
import hawk.index.core.util.ArrayUtil;
import hawk.index.core.util.NumberUtil;
import hawk.index.core.util.NumericTrie;
import hawk.index.core.util.WrapInt;
import hawk.index.core.writer.DataOutput;
import hawk.index.core.writer.Pair;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.Util;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.HashMap;
import java.util.List;

@Slf4j
public class Searcher {

    private DirectoryReader directoryReader;

    public Searcher(DirectoryReader directoryReader) {
        this.directoryReader = directoryReader;
    }

    public ScoreDoc[] searchTerm(TermQuery query){
        String field = query.getField();
        String term = query.getTerm();
        FST<BytesRef> termFST = directoryReader.getTermFST();
        MappedByteBuffer frqBuffer = directoryReader.getFRQBuffer();
        BytesRef value = null;
        try {
            value = Util.get(termFST, new BytesRef(field.concat(term)));
        } catch (IOException e) {
            log.error("error searching fst");
            System.exit(1);
        }
        byte[] frqOffsetBytes = value.bytes;
        long frqOffset = DataInput.readVlong(frqOffsetBytes);
        WrapInt frqOffsetWrapper = new WrapInt((int) frqOffset);
        int postingLength = DataInput.readVintAtIndex(frqBuffer, frqOffsetWrapper);
        int[][] postingList = new int[postingLength][2];
        for (int i = 0; i < postingList.length; i++) {
            int docID = DataInput.readVintAtIndex(frqBuffer, frqOffsetWrapper);
            int frequence = DataInput.readVintAtIndex(frqBuffer,frqOffsetWrapper);
            postingList[i][0] = docID;
            postingList[i][1] = frequence;
        }
        return null;
    }

    public ScoreDoc[] searchNumericRange(NumericRangeQuery query){
        String field = query.getField();
        double lower = query.getLower();
        double upper = query.getUpper();
        HashMap<String, NumericTrie> trieMap = directoryReader.getNumericTrieMap();
        MappedByteBuffer frqBuffer = directoryReader.getFRQBuffer();
        NumericTrie trie = trieMap.get(field);
        List<NumericTrie.Node> result = trie.rangeSearch(lower,upper);
        return null;
    }


    public ScoreDoc[] search(Query query){
        if(query instanceof TermQuery) return searchTerm((TermQuery) query);
        return searchNumericRange((NumericRangeQuery) query);
    }


}
