package hawk.benchmark;

import directory.MMapDirectory;
import document.Document;
import hawk.indexer.writer.IndexWriter;
import hawk.indexer.writer.config.IndexConfig;

import java.nio.file.Path;
import java.util.List;

public class IndexSmokeTest {

    public static void main(String[] args) throws Exception {
        SecurityManager previous = System.getSecurityManager();
        System.setSecurityManager(new SecurityManager() {
            @Override
            public void checkExit(int status) {
                throw new RuntimeException("System.exit(" + status + ")", new Exception("exit stack"));
            }

            @Override
            public void checkPermission(java.security.Permission perm) {
            }
        });
        try {
            int docCount = args.length > 0 ? Integer.parseInt(args[0]) : 100;
            Path indexDir = BenchmarkSupport.indexDir().resolve("index-" + docCount);
            BenchmarkSupport.wipeDirectory(indexDir);
            List<Document> documents = CorpusLoader.loadDocuments(docCount);
            IndexConfig indexConfig = BenchmarkSupport.newBenchmarkIndexConfig();
            MMapDirectory directory = MMapDirectory.open(indexDir);
            IndexWriter indexWriter = new IndexWriter(indexConfig, directory, false);
            for (Document document : documents) {
                indexWriter.addDoc(document);
            }
            indexWriter.commit();
            System.out.println("indexed " + documents.size() + " documents");
        } catch (RuntimeException e) {
            e.printStackTrace();
            System.exit(2);
        } finally {
            System.setSecurityManager(previous);
        }
    }
}
