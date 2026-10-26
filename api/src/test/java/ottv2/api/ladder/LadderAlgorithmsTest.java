package ottv2.api.ladder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LadderAlgorithmsTest {
    @Test
    void eloRatingsConvergeTowardRepeatedWinner() {
        RatingCalculator calculator = new RatingCalculator.Elo();
        int winner = 1200;
        int loser = 1200;
        for (int i = 0; i < 100; i++) {
            var change = calculator.calculate(winner, loser, 1.0);
            winner = change.ratingA();
            loser = change.ratingB();
        }
        assertTrue(winner > 1500);
        assertTrue(loser < 900);
        assertEquals(2400, winner + loser);
    }

    @Test
    void pairingUsesClosestRatingAndAvoidsRecentOpponent() {
        MatchmakingStrategy strategy = new MatchmakingStrategy.Elo(100, 50, 1000);
        Instant old = Instant.parse("2026-10-13T10:00:00Z");
        var a = new MatchmakingStrategy.Candidate(1, 11, 1200, old, Set.of(2L));
        var b = new MatchmakingStrategy.Candidate(2, 22, 1210, old.plusSeconds(5), Set.of(1L));
        var c = new MatchmakingStrategy.Candidate(3, 33, 1240, old.plusSeconds(10), Set.of());
        List<MatchmakingStrategy.Pair> pairs = strategy.pair(List.of(a, b, c), old.plusSeconds(20));
        assertEquals(1, pairs.size());
        assertEquals(1L, pairs.getFirst().a().botId());
        assertEquals(3L, pairs.getFirst().b().botId());
    }

    @Test
    void expandingWindowEventuallyFindsDistantOpponent() {
        MatchmakingStrategy strategy = new MatchmakingStrategy.Elo(100, 50, 1000);
        Instant online = Instant.parse("2026-10-13T10:00:00Z");
        var a = new MatchmakingStrategy.Candidate(1, 11, 1000, online, Set.of());
        var b = new MatchmakingStrategy.Candidate(2, 22, 500, online, Set.of());
        assertTrue(strategy.pair(List.of(a, b), online).isEmpty());
        assertEquals(1, strategy.pair(List.of(a, b), online.plusSeconds(600)).size());
    }
}
