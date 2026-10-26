package ottv2.api.ladder;

/** Computes a zero-sum Elo update from the ratings before a series. */
public interface RatingCalculator {
    RatingChange calculate(int ratingA, int ratingB, double scoreA);

    record RatingChange(int ratingA, int ratingB) { }

    final class Elo implements RatingCalculator {
        private final int kFactor;

        public Elo() { this(32); }

        public Elo(int kFactor) {
            if (kFactor <= 0) throw new IllegalArgumentException("K-factor must be positive");
            this.kFactor = kFactor;
        }

        @Override
        public RatingChange calculate(int ratingA, int ratingB, double scoreA) {
            if (scoreA < 0 || scoreA > 1) throw new IllegalArgumentException("Score must be between 0 and 1");
            double expectedA = 1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / 400.0));
            int nextA = (int) Math.round(ratingA + kFactor * (scoreA - expectedA));
            return new RatingChange(nextA, ratingA + ratingB - nextA);
        }
    }
}
