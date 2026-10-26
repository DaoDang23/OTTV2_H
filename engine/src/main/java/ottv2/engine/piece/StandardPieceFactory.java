package ottv2.engine.piece;

import ottv2.engine.model.Piece;
import ottv2.engine.model.PieceType;
import ottv2.engine.model.Position;
import ottv2.engine.model.Team;
import ottv2.engine.movement.MovementStrategy;
import ottv2.engine.movement.OrthogonalOneStep;

public class StandardPieceFactory implements PieceFactory {
    private final MovementStrategy defaultMovement = new OrthogonalOneStep();

    @Override
    public Piece create(int id, PieceType type, Team team, Position position) {
        return new Piece(id, team, position, type, defaultMovement);
    }
}
