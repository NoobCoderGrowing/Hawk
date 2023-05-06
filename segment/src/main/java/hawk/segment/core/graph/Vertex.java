package hawk.segment.core.graph;

import com.alibaba.fastjson.JSON;
import lombok.Data;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

@Data
public class Vertex {

    private int id;

    private HashMap<Integer, Edge> inEdges;

    private HashMap<Integer, Edge> outEdges;

    private PriorityQueue<Map.Entry<Double,Vertex>> NPathsTable;


    public Vertex(int id){
        this.id = id;
        inEdges = new HashMap<Integer, Edge>();
        outEdges = new HashMap<Integer, Edge>();
    }


    public Vertex(int id, int n){
        this.id = id;
        inEdges = new HashMap<Integer, Edge>();
        outEdges = new HashMap<Integer, Edge>();
        NPathsTable = new PriorityQueue<Map.Entry<Double,Vertex>>(n, new Comparator<Map.Entry<Double,
                Vertex>>() {
            @Override
            public int compare(Map.Entry<Double, Vertex> o1, Map.Entry<Double, Vertex> o2) {
                return -o1.getKey().compareTo(o2.getKey());
            }
        });
    }


    @Override
    public String toString() {
        return JSON.toJSONString(this);
    }
}
