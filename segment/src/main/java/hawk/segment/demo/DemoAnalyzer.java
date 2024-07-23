package hawk.segment.demo;
import hawk.segment.core.Triple;

import java.util.*;

public class DemoAnalyzer {

    private HashSet<String> NPathSet = new HashSet<String>();


    public DemoAnalyzer() {
    }

    public Map<String, Boolean> customizeDict(String[] strings){
        NPathSet.removeAll(NPathSet);
        for (String string: strings) {
            NPathSet.add(string);
        }
        HashMap<String, Boolean> response = new HashMap<>();
        response.put("success", true);
        return response;
    }

    //不重复版本，例如说，3-最短路径，那么最终路径数不会超过3条，同样长度的路径会占用名额
    //n:共多少个字； N: N-最短路径； k：每个字的入度
    //时间复杂度：n*N*k
    public void computeNShortestPath(List<DemoVertex> graph, int n){
        for (int i = 1; i < graph.size(); i++) {
            DemoVertex current = graph.get(i);
            ArrayList<Triple<Double, Integer, Integer>>  nPathsTable = current.getNPathsTable();
            for (int j = 0; j < i; j++) {
                DemoVertex preNode = graph.get(j);
                if(current.getInEdges().containsKey(preNode.getId())){
                    DemoEdge curEdge = current.getInEdges().get(preNode.getId());
                    ArrayList<Triple<Double, Integer, Integer>>  preNodePathsTable = preNode.getNPathsTable();
                    if(preNodePathsTable.size()==0){// 和原点相连的词
                        nPathsTable.add(new Triple<>(1.0, preNode.getId(), 0));
                    }
                    for (int k = 0; k < preNodePathsTable.size(); k++) {
                        Double length = preNodePathsTable.get(k).getLeft();
                        length += curEdge.getCost();
                        Collections.sort(nPathsTable);
                        if(nPathsTable.size()<n){
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

    public List<DemoVertex> createGraph(String chineseStr, int n){
        ArrayList<DemoVertex> graph = new ArrayList<DemoVertex>(chineseStr.length()+1);
        // create string.length + 1 vertex
        for (int i = 0; i < chineseStr.length()+1 ; i++) {
            DemoVertex vertex = new DemoVertex(i, n);
            graph.add(vertex);
        }
        // create string.length edge between every vertex
        for (int i = 0; i < chineseStr.length(); i++) {
            DemoEdge edge = new DemoEdge();
            edge.setWord(String.valueOf(chineseStr.charAt(i)));
            edge.setCost(1);
            DemoVertex from = graph.get(i);
            DemoVertex to = graph.get(i+1);
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
                    DemoEdge edge = new DemoEdge();
                    edge.setWord(str);
                    edge.setCost(1);
                    DemoVertex from = graph.get(i);
                    DemoVertex to = graph.get(j);
                    edge.setStart(from);
                    edge.setDestination(to);
                    from.getOutEdges().putIfAbsent(j, edge);
                    to.getInEdges().putIfAbsent(i, edge);
                }
            }
        }
        return graph;
    }

    public void getEdges(DemoVertex curNode, List<DemoVertex> graph,
                         ArrayList<Map.Entry<Double, TreeMap<Integer, String>>> ret){
        if(curNode == graph.get(0)){
            return;
        }
        ArrayList<Triple<Double, Integer, Integer>>  nPathsTable = curNode.getNPathsTable();
        while(nPathsTable.size()!=0){
            Triple<Double, Integer, Integer> entry = nPathsTable.get(0);
            Double cost = entry.getLeft();
            Integer prevNodeID = entry.getMid();
            Integer prevEntry = entry.getRight();
            // 从最后一个节点开始，就新建一条path
            TreeMap<Integer, String> newPath = new TreeMap<>();
            Map.Entry<Double, TreeMap<Integer, String>> newPathMapEntry = new AbstractMap.SimpleEntry<>(cost, newPath);
            ret.add(newPathMapEntry);
            // 获取当前的Path
            TreeMap<Integer, String> curPath = ret.get(ret.size()-1).getValue();
            DemoEdge edge = curNode.getInEdges().get(prevNodeID);
            //计算token的起始位置
            int startPos = prevNodeID;
            String word = edge.getWord();
            curPath.put(startPos, word);
            nPathsTable.remove(0);
            helper(curPath, prevNodeID, prevEntry, graph);
        }
    }

    public void helper(TreeMap<Integer, String> curPath, Integer curNodeID, Integer curEntry, List<DemoVertex> graph){
        if(curNodeID == 0){
            return;
        }
        DemoVertex curNode = graph.get(curNodeID);
        ArrayList<Triple<Double, Integer, Integer>>  nPathsTable = curNode.getNPathsTable();
        Triple<Double, Integer, Integer> entry = nPathsTable.get(curEntry);
        Integer prevNodeID = entry.getMid();
        Integer prevEntry = entry.getRight();
        DemoEdge edge = curNode.getInEdges().get(prevNodeID);
        int startPos = prevNodeID;
        String word = edge.getWord();
        curPath.put(startPos, word);
        helper(curPath, prevNodeID, prevEntry, graph);
    }

    public ArrayList<Map.Entry<Double,TreeMap<Integer, String>>> retriveNShortestPath(List<DemoVertex> graph){
        ArrayList<Map.Entry<Double, TreeMap<Integer, String>>> ret = new ArrayList<>();
        DemoVertex curNode = graph.get(graph.size()-1);
        getEdges(curNode, graph, ret);
        return ret;
    }


    public ArrayList analyze(String chineseStr, int n){
        List<DemoVertex> graph = createGraph(chineseStr, n);
        computeNShortestPath(graph, n);
        ArrayList<Map.Entry<Double,TreeMap<Integer, String>>> paths = retriveNShortestPath(graph);
        ArrayList ret = new ArrayList();
        ret.add(paths);
        ret.add(graph);
        return ret;
    }
}
