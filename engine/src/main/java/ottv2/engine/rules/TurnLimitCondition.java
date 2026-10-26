package ottv2.engine.rules;

import ottv2.engine.model.GameState;
import ottv2.engine.model.Team;
import java.util.Optional;

public class TurnLimitCondition implements WinCondition {
    public static final int DEFAULT_LIMIT = 200;
    public static final int QUICK_TEST_LIMIT = 100;
    public static final int RANKED_LIMIT = 200;
    public static final int TOURNAMENT_LIMIT = 300;
    public static final int SANDBOX_LIMIT = 500;

    private final int limit;

    public TurnLimitCondition() {
        this(DEFAULT_LIMIT);
    }

    public TurnLimitCondition(int limit) {
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }

    @Override
    public Optional<Team> check(GameState state) {
        if (state.getTurn() > limit) {
            // Determine winner by score
            // Score = number of alive pieces
            long countA = state.getPieces().stream().filter(p -> p.isAlive() && p.getTeam() == Team.A).count();
            long countB = state.getPieces().stream().filter(p -> p.isAlive() && p.getTeam() == Team.B).count();
            
            if (countA > countB) {
                state.setWinnerScore((int)countA);
                return Optional.of(Team.A);
            } else if (countB > countA) {
                state.setWinnerScore((int)countB);
                return Optional.of(Team.B);
            } else {
                // If pieces are equal, check distance to each team's own target
                int minA = Integer.MAX_VALUE;
                int minB = Integer.MAX_VALUE;
                for (var p : state.getPieces()) {
                    if (p.isAlive()) {
                        if (p.getTeam() == Team.A) {
                            minA = Math.min(minA, p.getPosition().chebyshevDistance(ReachTargetCondition.TARGET_A));
                        } else if (p.getTeam() == Team.B) {
                            minB = Math.min(minB, p.getPosition().chebyshevDistance(ReachTargetCondition.TARGET_B));
                        }
                    }
                }
                if (minA < minB) return Optional.of(Team.A);
                if (minB < minA) return Optional.of(Team.B);
                return Optional.of(Team.A); // Arbitrary tie breaker if still tied
            }
        }
        return Optional.empty();
    }
}
