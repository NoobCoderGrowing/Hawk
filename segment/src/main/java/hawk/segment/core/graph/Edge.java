package hawk.segment.core.graph;

import lombok.Data;

@Data
public class Edge {

    private String word;

    private Vertex start;

    private Vertex destination;

    private double cost;

    @Override
    public String toString(){
        return "word: " + word + ", cost: " + cost + ", start: " +
                (start != null? start.getId() : null) + ", end: " +
                (destination != null? destination.getId() : null);
    }
}
