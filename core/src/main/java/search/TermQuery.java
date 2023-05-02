package search;

import lombok.Data;

@Data
public class TermQuery extends Query{

    private String field;
    private String term;

    public TermQuery(String title, String term) {
        this.field = title;
        this.term = term;
    }
}
