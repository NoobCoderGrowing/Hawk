package hawk.segment.demo;



import com.alibaba.fastjson.JSON;
import hawk.segment.core.Triple;
import lombok.Data;

import java.util.*;

@Data
public class DemoVertex {

    private int id;

    private HashMap<Integer, DemoEdge> inEdges;

    private HashMap<Integer, DemoEdge> outEdges;

    private ArrayList<Triple<Double, Integer, Integer>> NPathsTable;

    public DemoVertex(int id){
        this.id = id;
        this.inEdges = new HashMap<Integer, DemoEdge>();
        this. outEdges = new HashMap<Integer, DemoEdge>();
    }


    public DemoVertex(int id, int n){
        this.id = id;
        this.inEdges = new HashMap<Integer, DemoEdge>();
        this.outEdges = new HashMap<Integer, DemoEdge>();
        this.NPathsTable = new ArrayList<>();

    }

    @Override
    public String toString() {
        return JSON.toJSONString(this);
    }
}
