package ottv2.api.ladder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Chooses the closest eligible Elo opponent while gradually widening the wait window. */
public interface MatchmakingStrategy {
    List<Pair> pair(List<Candidate> candidates, Instant now);

    record Candidate(long botId, long ownerId, long submissionId, int elo, Instant onlineSince, Set<Long> recentOpponents) {
        public Candidate {
            recentOpponents = recentOpponents == null ? Set.of() : Set.copyOf(recentOpponents);
        }

        public Candidate(long botId, long submissionId, int elo, Instant onlineSince, Set<Long> recentOpponents) {
            this(botId, botId, submissionId, elo, onlineSince, recentOpponents);
        }
    }

    record Pair(Candidate a, Candidate b) { }

    final class Elo implements MatchmakingStrategy {
        private final int initialWindow;
        private final int expansionPerMinute;
        private final int maximumWindow;

        public Elo() { this(150, 50, 300); }

        public Elo(int initialWindow, int expansionPerMinute, int maximumWindow) {
            if (initialWindow < 0 || expansionPerMinute < 0 || maximumWindow < initialWindow) {
                throw new IllegalArgumentException("Invalid Elo matchmaking window");
            }
            this.initialWindow = initialWindow;
            this.expansionPerMinute = expansionPerMinute;
            this.maximumWindow = maximumWindow;
        }

        @Override
        public List<Pair> pair(List<Candidate> candidates, Instant now) {
            List<Candidate> waiting = new ArrayList<>(candidates);
            waiting.sort(Comparator.comparing(Candidate::onlineSince).thenComparingLong(Candidate::botId));
            Set<Long> paired = new HashSet<>();
            List<Pair> result = new ArrayList<>();
            for (Candidate first : waiting) {
                if (paired.contains(first.botId())) continue;
                long waitedMinutes = Math.max(0, Duration.between(first.onlineSince(), now).toMinutes());
                long window = Math.min(maximumWindow, (long) initialWindow + waitedMinutes * expansionPerMinute);
                Candidate opponent = waiting.stream()
                        .filter(candidate -> candidate.botId() != first.botId())
                        .filter(candidate -> candidate.ownerId() != first.ownerId()) // Chống tự đấu/farm điểm cùng chủ sở hữu
                        .filter(candidate -> !paired.contains(candidate.botId()))
                        .filter(candidate -> !first.recentOpponents().contains(candidate.botId()))
                        .filter(candidate -> !candidate.recentOpponents().contains(first.botId()))
                        .filter(candidate -> Math.abs((long) first.elo() - candidate.elo()) <= window)
                        .min(Comparator.comparingLong((Candidate candidate) -> Math.abs((long) first.elo() - candidate.elo()))
                                .thenComparing(Candidate::onlineSince).thenComparingLong(Candidate::botId))
                        .orElse(null);
                if (opponent != null) {
                    paired.add(first.botId());
                    paired.add(opponent.botId());
                    result.add(new Pair(first, opponent));
                }
            }
            return List.copyOf(result);
        }
    }
}
