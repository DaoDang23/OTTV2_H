package ottv2.engine.rules;

import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Position;
import ottv2.engine.model.Team;
import java.util.Optional;

public class ReachTargetCondition implements WinCondition {
    public static final Position TARGET_A = new Position(0, 8); // I1 (Đội Xanh / Phe dưới)
    public static final Position TARGET_B = new Position(8, 0); // A9 (Đội Đỏ / Phe trên)

    @Override
    public Optional<Team> check(GameState state) {
        for (Piece p : state.getPieces()) {
            if (p.isAlive()) {
                if (p.getTeam() == Team.A && p.getPosition().equals(TARGET_A)) {
                    return Optional.of(Team.A);
                }
                if (p.getTeam() == Team.B && p.getPosition().equals(TARGET_B)) {
                    return Optional.of(Team.B);
                }
            }
        }
        return Optional.empty();
    }
}
