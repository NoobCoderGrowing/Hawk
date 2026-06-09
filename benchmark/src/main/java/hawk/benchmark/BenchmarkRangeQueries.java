package hawk.benchmark;

import java.util.Random;

public final class BenchmarkRangeQueries {

    private static final double MIN_PRICE = 1.0d;

    private static final double MAX_PRICE = 100.0d;

    private static final double RANGE_WIDTH = 20.0d;

    private static final long SEED = 42L;

    private BenchmarkRangeQueries() {
    }

    public static double[][] buildRandomRanges(int count) {
        Random random = new Random(SEED);
        double[][] ranges = new double[count][2];
        for (int i = 0; i < count; i++) {
            ranges[i] = nextRandomRange(random);
        }
        return ranges;
    }

    static double[] nextRandomRange(Random random) {
        double maxLower = MAX_PRICE - RANGE_WIDTH;
        double lower = MIN_PRICE + random.nextDouble() * (maxLower - MIN_PRICE);
        double upper = lower + RANGE_WIDTH;
        return new double[] {lower, upper};
    }
}
