package ottv2.engine.movement;

import ottv2.engine.model.Board;
import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Position;
import ottv2.engine.model.CellType;
import java.util.ArrayList;
import java.util.List;

public class OrthogonalOneStep implements MovementStrategy {
    @Override
    public List<Position> legalMoves(Piece piece, Board board, GameState state) {
        List<Position> validMoves = new ArrayList<>();
        Position curr = piece.getPosition();
        for (Position p : curr.neighbors()) {
            if (board.inBounds(p) && board.get(p).getType() != CellType.OBSTACLE) {
                // Cannot move if occupied by own team
                if (board.get(p).getOccupant() != null && board.get(p).getOccupant().getTeam() == piece.getTeam()) {
                    continue;
                }
                // Cannot move if occupied by enemy of SAME type (RPS draw)
                if (board.get(p).getOccupant() != null && board.get(p).getOccupant().getTeam() != piece.getTeam()) {
                    if (board.get(p).getOccupant().getType() == piece.getType()) {
                        continue; // Invalid move according to design decision #7
                    }
                }
                validMoves.add(p);
            }
        }
        return validMoves;
    }
}
