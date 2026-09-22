package demo;

import directory.MMapDirectory;
import document.Document;
import field.DoubleField;
import field.Field;
import field.PrimaryKeyField;
import field.StringField;
import hawk.indexer.writer.IndexWriter;
import hawk.indexer.writer.config.IndexConfig;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * 建倒排索引的命令行入口。
 *
 * <pre>
 *   java -cp ... demo.BuildIndex goods.csv index-data/goods
 * </pre>
 *
 * 语料格式为 `唯一ID \t 标题 \t 价格`（即 goods.csv）。文档形状与
 * {@code hawk.benchmark.CorpusLoader} 完全一致 —— 两边建出来的索引必须能互相替换，
 * 否则 web 演示的全文 tab 和基准测试看到的就不是同一份数据。
 *
 * <p>注意 {@code uniqueID} 用的是**行号计数器**（0-based），不读 csv 第一列。
 * goods.csv 里第一列恰好等于行号，所以两者相同；换一份不连续 id 的语料时这里要改。
 *
 * <p>目标目录会被**清空重建**——追加是另一条路径（`loadExistingPkMap=true`），
 * 而且那个路径下未合并的段对搜索不可见，不适合演示。
 */
public class BuildIndex {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: demo.BuildIndex <corpus.csv> <index-dir>");
            System.err.println("例如: demo.BuildIndex goods.csv index-data/goods");
            System.exit(2);
        }
        Path corpus = Paths.get(args[0]);
        Path indexDir = Paths.get(args[1]);

        if (!Files.exists(corpus)) {
            System.err.println("找不到语料文件: " + corpus.toAbsolutePath());
            System.exit(1);
        }

        wipe(indexDir);
        Files.createDirectories(indexDir);

        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexWriter writer = new IndexWriter(new IndexConfig(analyzer), MMapDirectory.open(indexDir), false);

        long count = 0;
        long t0 = System.currentTimeMillis();
        try (BufferedReader reader = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
            String line;
            long uniqueID = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                writer.addDoc(parseLine(line, uniqueID++));
                count++;
                if (count % 20000 == 0) {
                    System.out.printf("  %d 篇…%n", count);
                }
            }
        }
        writer.commit();

        System.out.printf("建索引完成: %d 篇 -> %s  用时 %.1fs%n",
                count, indexDir.toAbsolutePath(), (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** 与 {@code CorpusLoader.parseLine} 保持一致，见类注释。 */
    private static Document parseLine(String line, long uniqueID) {
        String[] parts = line.split("\t", 3);
        String title = parts[1];
        double price = Double.parseDouble(parts[2]);

        Document document = new Document();
        document.add(new PrimaryKeyField(uniqueID));
        document.add(new StringField("title", title, Field.Tokenized.YES, Field.Stored.YES));
        document.add(new DoubleField("price", price, Field.Tokenized.YES, Field.Stored.YES));
        document.add(new StringField("descript", title, Field.Tokenized.NO, Field.Stored.YES));
        document.add(new DoubleField("digt", price, Field.Tokenized.NO, Field.Stored.YES));
        return document;
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static final class UncheckedIOException extends RuntimeException {
        UncheckedIOException(IOException cause) {
            super(cause);
        }
    }
}
