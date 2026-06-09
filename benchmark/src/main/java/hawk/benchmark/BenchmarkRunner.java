package hawk.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include("hawk\\.benchmark\\..*")
                .shouldDoGC(true)
                .build())
                .run();
    }
}
