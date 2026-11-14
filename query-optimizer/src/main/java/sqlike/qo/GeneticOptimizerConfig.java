package sqlike.qo;

/** QO5-local GA configuration; no change to the supergroup optimizer/shared API. */
public record GeneticOptimizerConfig(int populationSize, int generations, double mutationRate, long seed) {
    public GeneticOptimizerConfig {
        if (populationSize < 2) throw new IllegalArgumentException("populationSize must be at least 2");
        if (generations < 0) throw new IllegalArgumentException("generations must not be negative");
        if (!Double.isFinite(mutationRate) || mutationRate < 0 || mutationRate > 1)
            throw new IllegalArgumentException("mutationRate must be finite and between 0 and 1");
    }

    public static GeneticOptimizerConfig defaults() { return new GeneticOptimizerConfig(32, 20, 0.15, 3140); }
}
