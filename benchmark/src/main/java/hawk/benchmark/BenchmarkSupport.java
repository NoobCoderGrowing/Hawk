package hawk.benchmark;

import directory.MMapDirectory;
import document.Document;
import hawk.indexer.writer.IndexWriter;
import hawk.indexer.writer.config.IndexConfig;
import hawk.recall.config.SearchConfig;
import hawk.recall.reader.DirectoryReader;
import hawk.recall.search.Searcher;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class BenchmarkSupport {

    public static final String INDEX_DIR_PROPERTY = "hawk.benchmark.index.dir";

    private static final Path DEFAULT_INDEX_DIR = Paths.get("/tmp/hawk-jmh-index");

    private BenchmarkSupport() {
    }

    public static Path indexDir() {
        String configured = System.getProperty(INDEX_DIR_PROPERTY);
        return configured == null ? DEFAULT_INDEX_DIR : Paths.get(configured);
    }

    public static Analyzer newAnalyzer() {
        return new NShortestPathAnalyzer(1);
    }

    public static IndexConfig newIndexConfig() {
        return new IndexConfig(newAnalyzer());
    }

    public static IndexConfig newBenchmarkIndexConfig() {
        IndexConfig indexConfig = new IndexConfig(newAnalyzer());
        indexConfig.setEnableMerge(false);
        return indexConfig;
    }

    public static void wipeDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        Files.createDirectories(dir);
    }

    public static void buildIndex(Path indexDir, List<Document> documents, IndexConfig indexConfig) throws Exception {
        wipeDirectory(indexDir);
        MMapDirectory directory = MMapDirectory.open(indexDir);
        IndexWriter indexWriter = new IndexWriter(indexConfig, directory, false);
        for (Document document : documents) {
            indexWriter.addDoc(document);
        }
        indexWriter.commit();
    }

    public static Searcher openSearcher(Path indexDir, IndexConfig indexConfig) {
        MMapDirectory directory = MMapDirectory.open(indexDir);
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        return new Searcher(directoryReader, new SearchConfig(indexConfig.getAnalyzer()), indexConfig);
    }
}
