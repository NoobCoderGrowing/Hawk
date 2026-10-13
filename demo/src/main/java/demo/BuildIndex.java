package demo;

import directory.MMapDirectory;
import document.Document;
import field.Field;
import field.NumberField;
import field.PrimaryKeyField;
import field.StringField;
import hawk.indexer.writer.IndexWriter;
import hawk.indexer.writer.config.IndexConfig;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 建索引示例：读 goods.csv，写出一份可检索的索引。
 *
 * <p>输入输出都写死（{@code goods.csv} → {@code index-data/goods}），
 * 工作目录是仓库根；目标目录每次**清空重建**。
 *
 * <p>检索那边怎么用看 {@link RecallDemo}。
 */
public class BuildIndex {

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of("goods.csv");
        Path indexDir = Path.of("index-data/goods");

        if (Files.exists(indexDir)) {
            try (var walk = Files.walk(indexDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(indexDir);

        var analyzer = new NShortestPathAnalyzer(1);
        // 第三个参数是主键字段名，默认 "uniqueID"，与下面 PrimaryKeyField 的名字一致
        IndexWriter writer = new IndexWriter(new IndexConfig(analyzer), MMapDirectory.open(indexDir), false);

        long count = 0;
        long t0 = System.currentTimeMillis();
        // 语料格式：唯一ID \t 标题 \t 价格，首行表头。uniqueID 用行号计数器，不读第一列。
        try (BufferedReader reader = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("uniqueID\t")) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                String title = parts[1];
                double price = Double.parseDouble(parts[2]);

                Document doc = new Document();
                doc.add(new PrimaryKeyField(count));                                          // 业务主键
                // Tokenized.YES = 建倒排/BKD，可检索；Stored.YES = 存原文，取回文档时能拿到
                doc.add(new StringField("title", title, Field.Tokenized.YES, Field.Stored.YES));
                doc.add(new NumberField("price", price, Field.Tokenized.YES, Field.Stored.YES));
                // 下面两个只存原文、不建索引（Tokenized.NO）—— 两个都传 NO 的字段会被整个丢弃，别踩
                doc.add(new StringField("descript", title, Field.Tokenized.NO, Field.Stored.YES));
                doc.add(new NumberField("digt", price, Field.Tokenized.NO, Field.Stored.YES));

                writer.addDoc(doc);
                count++;
            }
        }
        writer.commit();
        writer.getThreadPoolExecutor().shutdown();

        System.out.printf("建索引完成: %,d 篇 -> %s  用时 %.1fs%n",
                count, indexDir.toAbsolutePath(), (System.currentTimeMillis() - t0) / 1000.0);
    }
}
