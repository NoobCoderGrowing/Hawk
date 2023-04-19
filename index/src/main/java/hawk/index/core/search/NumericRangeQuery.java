package hawk.index.core.search;

import lombok.Data;

@Data
public class NumericRangeQuery extends Query{

    private String field;

    private double lower;

    private double upper;

    public NumericRangeQuery(String field, Number lower, Number upper) {
        this.field = field;
        this.lower = (double) lower;
        this.upper = (double) upper;
    }
}
