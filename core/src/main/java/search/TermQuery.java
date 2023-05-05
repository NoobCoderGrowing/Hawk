package search;

import lombok.Data;

@Data
public class TermQuery extends Query{

    private String field;
    private String term;

    public TermQuery(String fieldName, String term) {
        this.field = fieldName;
        this.term = term;
    }
}
