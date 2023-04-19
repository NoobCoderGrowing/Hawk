package hawk.index.core.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BooleanQuery extends Query{

    private List<Query> queries;

    private Enum<Operation> operation;

    public BooleanQuery(Enum<Operation> operation) {
        this.queries = new ArrayList<>();
        this.operation = operation;
    }

    public enum Operation {
        MUST,
        SHOULD
    }
}
