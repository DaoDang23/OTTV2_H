package ottv2.engine.piece;

import ottv2.engine.model.Piece;
import ottv2.engine.model.PieceType;
import ottv2.engine.model.Position;
import ottv2.engine.model.Team;

public interface PieceFactory {
    Piece create(int id, PieceType type, Team team, Position position);
}
