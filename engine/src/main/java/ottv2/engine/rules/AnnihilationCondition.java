package ottv2.engine.rules;

import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Team;
import java.util.Optional;

public class AnnihilationCondition implements WinCondition {
    @Override
    public Optional<Team> check(GameState state) {
        boolean teamAAlive = false;
        boolean teamBAlive = false;

        for (Piece p : state.getPieces()) {
            if (p.isAlive()) {
                if (p.getTeam() == Team.A) teamAAlive = true;
                if (p.getTeam() == Team.B) teamBAlive = true;
            }
        }

        if (teamAAlive && !teamBAlive) return Optional.of(Team.A);
        if (teamBAlive && !teamAAlive) return Optional.of(Team.B);
        if (!teamAAlive && !teamBAlive) return Optional.empty(); // Draw? Handled by timeout or score? Actually we can just return empty and let score decide if both die.

        return Optional.empty();
    }
}
