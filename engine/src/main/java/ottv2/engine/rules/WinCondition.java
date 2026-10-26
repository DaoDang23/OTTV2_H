package ottv2.engine.rules;

import ottv2.engine.model.GameState;
import ottv2.engine.model.Team;
import java.util.Optional;

public interface WinCondition {
    Optional<Team> check(GameState state);
}
