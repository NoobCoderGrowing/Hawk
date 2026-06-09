package hawk.benchmark;

import directory.Constants;
import hawk.indexer.writer.config.IndexConfig;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MergeScheduler;

public final class BenchmarkThreadInfo {

    private BenchmarkThreadInfo() {
    }

    public static void logHawkIndexThreads(IndexConfig config) {
        System.out.println(formatHawkIndexThreads(config));
    }

    public static String formatHawkIndexThreads(IndexConfig config) {
        return String.format(
                "# Benchmark index threads [hawk]: indexerThreadNum=%d, maxRamUsageBytes=%d, mergeEnabled=%s, "
                        + "availableProcessors=%d, constantsProcessorNum=%d",
                config.getIndexerThreadNum(),
                config.getMaxRamUsage(),
                config.isEnableMerge(),
                Runtime.getRuntime().availableProcessors(),
                Constants.PROCESSOR_NUM);
    }

    public static void logLuceneIndexThreads(IndexWriterConfig config) {
        System.out.println(formatLuceneIndexThreads(config));
    }

    public static String formatLuceneIndexThreads(IndexWriterConfig config) {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        StringBuilder builder = new StringBuilder();
        builder.append("# Benchmark index threads [lucene]: addDocumentThreads=1, availableProcessors=")
                .append(availableProcessors);
        MergeScheduler scheduler = config.getMergeScheduler();
        if (scheduler instanceof ConcurrentMergeScheduler) {
            ConcurrentMergeScheduler mergeScheduler = (ConcurrentMergeScheduler) scheduler;
            int mergeMaxThreads = mergeScheduler.getMaxThreadCount();
            int mergeMaxCount = mergeScheduler.getMaxMergeCount();
            builder.append(", mergeScheduler=ConcurrentMergeScheduler");
            builder.append(", mergeMaxThreads=").append(formatLuceneMergeLimit(mergeMaxThreads,
                    Math.max(1, Math.min(3, availableProcessors / 2))));
            builder.append(", mergeMaxCount=").append(formatLuceneMergeLimit(mergeMaxCount,
                    Math.max(1, Math.min(8, availableProcessors / 8))));
        } else {
            builder.append(", mergeScheduler=").append(scheduler.getClass().getSimpleName());
        }
        builder.append(", ramBufferSizeMB=").append(config.getRAMBufferSizeMB());
        return builder.toString();
    }

    private static String formatLuceneMergeLimit(int configured, int autoDetected) {
        if (configured == ConcurrentMergeScheduler.AUTO_DETECT_MERGES_AND_THREADS) {
            return "auto(" + autoDetected + ")";
        }
        return Integer.toString(configured);
    }
}
