package hawk.segment.demo;

import com.alibaba.fastjson.JSON;
import lombok.Data;

@Data
public class DemoEdge {

    private String word;

    private DemoVertex start;

    private DemoVertex destination;

    private double cost;

    @Override
    public String toString(){
        return JSON.toJSONString(this);
    }
}

