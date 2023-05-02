package search;

import hawk.segment.core.anlyzer.Analyzer;
import lombok.Data;

@Data
public class SearchConfig {

    private Analyzer analyzer;

    public SearchConfig(Analyzer analyzer) {
        this.analyzer = analyzer;
    }
}
