package hawk.segment.core.anlyzer;
import com.mysql.jdbc.StringUtils;
import hawk.segment.core.*;
import hawk.segment.core.graph.Edge;
import hawk.segment.core.graph.Vertex;
import hawk.segment.core.Term;
import hawk.segment.demo.DemoEdge;
import hawk.segment.demo.DemoVertex;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.*;


@Slf4j
@Data
public class NShortestPathAnalyzer implements Analyzer {

    private StringTools stringTools = new StringTools();

    //number of shortest paths
    private int n;

    private final static int PHRASE_MAX_LEN = 20;

    private HashSet<String> NPathSet = new HashSet<String>(621950);

    public NShortestPathAnalyzer() {
        loadNPathDic();
    }

    public NShortestPathAnalyzer( int n) {
        this.n = n;
        loadNPathDic();
    }

    public void loadNPathDic(){
        try {
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("data/npathword.data");
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
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
        if(value==null || value.length() == 0){ // check for empty str
            return result;
        }
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

    public HashSet<Term> anlyze(String value, String fieldName, int n){
        this.n = n;
        HashSet<Term> result = new HashSet<>();
        if(value==null || value.length() == 0){ // check for empty str
            return result;
        }
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
            ArrayList<Triple<Double, Integer, Integer>>  nPathsTable = current.getNPathsTable();
            for (int j = 0; j < i; j++) {
                Vertex preNode = graph.get(j);
                if(current.getInEdges().containsKey(preNode.getId())){
                    Edge curEdge = current.getInEdges().get(preNode.getId());
                    ArrayList<Triple<Double, Integer, Integer>> preNodePathsTable = preNode.getNPathsTable();
                    if(preNodePathsTable.size()==0){// 和原点相连的词
                        nPathsTable.add(new Triple<>(1.0, preNode.getId(), 0));
                    }
                    for (int k = 0; k < preNodePathsTable.size(); k++) {
                        Double length = preNodePathsTable.get(k).getLeft();
                        length += curEdge.getCost();
                        Collections.sort(nPathsTable);
                        if(nPathsTable.size()< n){
                            nPathsTable.add(new Triple<>(length, preNode.getId(), k));
                        }else if(length < nPathsTable.get(nPathsTable.size()-1).getLeft()){
                            nPathsTable.remove(nPathsTable.size()-1);
                            nPathsTable.add(new Triple<>(length, preNode.getId(), k));
                        }
                    }
                }
            }

        }
    }


    public void retriveNShortestPath(List<Vertex> graph, int pos,
                                               HashSet<Term> termSet, String fieldName){
        Vertex curNode = graph.get(graph.size()-1);
        getEdge(curNode, graph, termSet, pos, fieldName);
    }

    // 回溯时间复杂度为nN^2, n为字符数，N为最短路径数
    public void getEdge(Vertex curNode, List<Vertex> graph, HashSet<Term> termSet, int pos,
                        String fieldName){
        if(curNode == graph.get(0)){
            return;
        }
        ArrayList<Triple<Double, Integer, Integer>>  nPathsTable = curNode.getNPathsTable();
        while(nPathsTable.size()!=0){
            Triple<Double, Integer, Integer> entry = nPathsTable.get(0);
            Integer prevNodeID = entry.getMid();
            Integer prevEntry = entry.getRight();
            Edge edge = curNode.getInEdges().get(prevNodeID);
            //计算token的起始位置
            int startPos = prevNodeID;
            startPos += pos;
            Term term = new Term();
            term.setValue(edge.getWord());
            term.setPos(startPos);
            term.setFieldName(fieldName);
            termSet.add(term);
            nPathsTable.remove(0);
            helper(graph, prevNodeID, prevEntry, pos, fieldName, termSet);
        }
    }

    public void helper(List<Vertex> graph, Integer curNodeID, Integer curEntry, int pos, String fieldName,
                       HashSet<Term> termSet){
        if(curNodeID == 0){
            return;
        }
        Vertex curNode = graph.get(curNodeID);
        ArrayList<Triple<Double, Integer, Integer>>  nPathsTable = curNode.getNPathsTable();
        Triple<Double, Integer, Integer> entry = nPathsTable.get(curEntry);
        Integer prevNodeID = entry.getMid();
        Integer prevEntry = entry.getRight();
        Edge edge = curNode.getInEdges().get(prevNodeID);
        int startPos = prevNodeID;
        startPos += pos;

        Term term = new Term();
        term.setValue(edge.getWord());
        term.setPos(startPos);
        term.setFieldName(fieldName);
        termSet.add(term);
        helper(graph, prevNodeID, prevEntry, pos, fieldName, termSet);
    }

//    public static void main(String[] args) throws InterruptedException {
//        Analyzer analyzer = new NShortestPathAnalyzer(1);
//        HashSet<Term> terms = analyzer.anlyze("适用于丰田皇冠锐志发动机机脚胶机脚支架脚垫机脚胶垫", "title");
//        System.out.println(terms);
//    }

}
