package hawk.index.distribute.config;

import directory.MMapDirectory;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import writer.IndexConfig;
import writer.IndexWriter;

@Configuration
@Order(2)
public class IndexerConfig {

    @Autowired
    MMapDirectory mMapDirectory;

    @Bean
    public IndexWriter initIndexWriter(){
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(indexConfig, mMapDirectory);
        return indexWriter;
    }
}
