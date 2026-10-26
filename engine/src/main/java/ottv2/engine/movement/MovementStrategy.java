package ottv2.engine.movement;

import ottv2.engine.model.Board;
import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Position;
import java.util.List;

public interface MovementStrategy {
    List<Position> legalMoves(Piece piece, Board board, GameState state);
}
