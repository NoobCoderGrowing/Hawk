package search;

import lombok.Data;

@Data
public class StringQuery extends Query{

    private String field;
    private String value;


    public StringQuery(String fieldName ,String value) {
        this.field = fieldName;
        this.value = value;
    }
}
