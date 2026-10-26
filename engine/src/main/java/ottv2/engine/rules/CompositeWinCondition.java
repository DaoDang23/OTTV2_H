package ottv2.engine.rules;

import ottv2.engine.model.GameState;
import ottv2.engine.model.Team;
import java.util.List;
import java.util.Optional;

public class CompositeWinCondition implements WinCondition {
    private final List<WinCondition> conditions;

    public CompositeWinCondition(List<WinCondition> conditions) {
        this.conditions = conditions;
    }

    @Override
    public Optional<Team> check(GameState state) {
        for (WinCondition condition : conditions) {
            Optional<Team> winner = condition.check(state);
            if (winner.isPresent()) {
                return winner;
            }
        }
        return Optional.empty();
    }
}
