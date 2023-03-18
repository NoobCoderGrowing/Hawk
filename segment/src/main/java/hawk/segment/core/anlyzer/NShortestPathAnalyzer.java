package hawk.segment.core.anlyzer;
import com.mysql.cj.util.StringUtils;
import hawk.segment.core.*;
import hawk.segment.core.graph.Edge;
import hawk.segment.core.graph.Vertex;
import hawk.segment.core.Term;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.net.URL;
import java.util.*;

@Slf4j
@Data
public class NShortestPathAnalyzer implements Analyzer {

    private StringTools stringTools = new StringTools();

    //number of shortest paths
    private int n=3;

    private final static int PHRASE_MAX_LEN = 20;

    private HashSet<String> NPathSet = new HashSet<String>(621949);

    public NShortestPathAnalyzer() {
        loadNPathDic();
    }

    public void loadNPathDic(){
        try {
            URL fileUrl = getClass().getClassLoader().getResource("data/npathword.data");
            BufferedReader reader = new BufferedReader(new FileReader(fileUrl.getPath()));
            String line = reader.readLine();
            while(line != null){
                line = line.trim();
                NPathSet.add(line);
                line = reader.readLine();
            }
            reader.close();
        } catch (FileNotFoundException e) {
            log.error("npathword.data file not found");
            System.exit(-1);
        } catch (Exception e) {
            log.error("met error while reading npathword.data");
            System.exit(-1);
        }
    }

    public HashSet<Term> anlyze(String value, String fieldName){
        HashSet<Term> result = new HashSet<>();
        value = stringTools.normalizeString(value);
        String[] strArr = value.split(" ");
        HanPinDigSeg hanPinDigSeg = new HanPinDigSeg();
        int pos = 0;
        for (int i = 0; i < strArr.length; i++) {
            if(i > 0) {
                pos ++;
            }
            //分割汉字、数字、英语字母成三个list
            strArr[i] = stringTools.splitChineseDigitEnglishByComma(strArr[i]);
            pos = stringTools.collectHanPinDigit(strArr[i], hanPinDigSeg, pos);
        }
        tokenize(hanPinDigSeg, result, fieldName);
        return result;
    }

    public void tokenize( HanPinDigSeg hanPinDigSeg,
                                   HashSet<Term> termSet, String fieldName){
        ArrayList<Phrase> hanziList = hanPinDigSeg.getHanZiList();
        //汉字分词形成token
        for (int i = 0; i < hanziList.size(); i++) {
            nShortestPath(hanziList.get(i), termSet, fieldName);
        }
        //数字形成token，粗分
        ArrayList<Phrase> digitList = hanPinDigSeg.getDigitList();
        for (int i = 0; i < digitList.size(); i++) {
            Term digitTerm = new Term();
            digitTerm.setValue(digitList.get(i).getValue());
            digitTerm.setPos(digitList.get(i).getPos());
            digitTerm.setFieldName(fieldName);
            termSet.add(digitTerm);
        }
        ArrayList<Phrase> pinYinList = hanPinDigSeg.getPinYinList();
        //数字形成token，粗分
        for (int i = 0; i < pinYinList.size(); i++) {
            Term pinYinTerm = new Term();
            pinYinTerm.setValue(pinYinList.get(i).getValue());
            pinYinTerm.setPos(pinYinList.get(i).getPos());
            pinYinTerm.setFieldName(fieldName);
            termSet.add(pinYinTerm);
        }
    }

    // n 最短路径分词
    public void nShortestPath(Phrase phrase, HashSet<Term> termSet, String fieldName){
        String chineseStr = phrase.getValue();
        if(StringUtils.isNullOrEmpty(chineseStr)){
            return;
        }

        if(chineseStr.length()==1){
            Term term = new Term();
            term.setValue(chineseStr);
            term.setPos(phrase.getPos());
            term.setFieldName(fieldName);
            termSet.add(term);
            return;
        }

        if(chineseStr.length() > PHRASE_MAX_LEN){
            chineseStr = chineseStr.substring(0, PHRASE_MAX_LEN);
        }
        List<Vertex> graph = createGraph(chineseStr);
        computeNShortestPath(graph);
        retriveNShortestPath(graph, phrase.getPos(), termSet, fieldName);
    }


    public List<Vertex> createGraph(String chineseStr){
        ArrayList<Vertex> graph = new ArrayList<Vertex>(chineseStr.length()+1);
        // create string.length + 1 vertex
        for (int i = 0; i < chineseStr.length()+1 ; i++) {
            Vertex vertex = new Vertex(i, n);
            graph.add(vertex);
        }
        // create string.length edge between every vertex
        for (int i = 0; i < chineseStr.length(); i++) {
            Edge edge = new Edge();
            edge.setWord(String.valueOf(chineseStr.charAt(i)));
            edge.setCost(1);
            Vertex from = graph.get(i);
            Vertex to = graph.get(i+1);
            edge.setStart(from);
            edge.setDestination(to);
            from.getOutEdges().putIfAbsent(i+1, edge);
            to.getInEdges().putIfAbsent(i, edge);
        }

        //如果字典中包该词组，创建对应edge
        for (int i = 0; i < chineseStr.length(); i++) {
            for (int j = chineseStr.length(); j >= i + 2 ; j--) {
                String str = chineseStr.substring(i, j);
                if(NPathSet.contains(str)){
                    Edge edge = new Edge();
                    edge.setWord(str);
                    edge.setCost(1);
                    Vertex from = graph.get(i);
                    Vertex to = graph.get(j);
                    edge.setStart(from);
                    edge.setDestination(to);
                    from.getOutEdges().putIfAbsent(j, edge);
                    to.getInEdges().putIfAbsent(i, edge);
                }
            }
        }
        return graph;
    }

    //不重复版本，例如说，3-最短路径，那么最终路径数不会超过3条，同样长度的路径会占用名额
    //n:共多少个字； N: N-最短路径； k：每个字的入度
    //时间复杂度：n*N*k
    public void computeNShortestPath(List<Vertex> graph){
        for (int i = 1; i < graph.size(); i++) {
            Vertex current = graph.get(i);
            PriorityQueue<Map.Entry<Double,Vertex>> nPathsTable = current.getNPathsTable();
            if(i==1){
                Vertex first = graph.get(0);
                nPathsTable.add(new AbstractMap.SimpleEntry<Double, Vertex>(1.0, first));
                continue;
            }else{
                Double maxlength = Double.MAX_VALUE;
                for (int j = 0; j < i; j++) {
                    Vertex preNode = graph.get(j);
                    if(current.getInEdges().containsKey(preNode.getId())){
                        Edge curEdge = current.getInEdges().get(preNode.getId());
                        PriorityQueue<Map.Entry<Double,Vertex>> preNodePathsTable = preNode.getNPathsTable();
                        if(preNodePathsTable.size()==0){// 和原点相连的词
                            nPathsTable.add(new AbstractMap.SimpleEntry<>(1.0, preNode));
                        }
                        for (Map.Entry<Double, Vertex> entry : preNodePathsTable) {
                            Double length = entry.getKey();
                            length += curEdge.getCost();
                            if(nPathsTable.size()<n){
                                nPathsTable.add(new AbstractMap.SimpleEntry<>(length,preNode));
                                maxlength = nPathsTable.peek().getKey();
                            }else if(length < maxlength){
                                nPathsTable.poll();
                                nPathsTable.add(new AbstractMap.SimpleEntry<>(length,preNode));
                                maxlength = nPathsTable.peek().getKey();
                            }
                        }
                    }
                }
            }
        }
    }


    public void retriveNShortestPath(List<Vertex> graph, int pos,
                                               HashSet<Term> termSet, String fieldName){
        Vertex curNode = graph.get(graph.size()-1);
        getTerm(curNode, graph, termSet, pos, fieldName);
    }

    // 回溯时间复杂度为nN^2, n为字符数，N为最短路径数
    public void getTerm(Vertex curNode, List<Vertex> graph, HashSet<Term> termSet, int pos,
                        String fieldName){
        if(curNode == graph.get(0)){
            return;
        }
        PriorityQueue<Map.Entry<Double, Vertex>> nPathsTable = curNode.getNPathsTable();
        Iterator<Map.Entry<Double, Vertex>> it = nPathsTable.iterator();
        while(it.hasNext()){
            Map.Entry<Double, Vertex> entry = it.next();
            Vertex prevNode = entry.getValue();
            Edge edge = curNode.getInEdges().get(prevNode.getId());
            //计算token的起始位置
            int startPos = prevNode.getId();
            startPos += pos;
            Term term = new Term();
            term.setValue(edge.getWord());
            term.setPos(startPos);
            term.setFieldName(fieldName);
            termSet.add(term);
            it.remove();
            getTerm(prevNode, graph, termSet, pos,fieldName);
        }
    }

    public static void main(String[] args) {
        NShortestPathAnalyzer nShortestPathAnalyzer = new NShortestPathAnalyzer();
        System.out.println(nShortestPathAnalyzer.anlyze("他说的很有道理","title"));
    }

}
