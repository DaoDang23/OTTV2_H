package ottv2.engine.vision;

import ottv2.engine.model.Cell;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Position;
import java.util.List;

public record Observation(List<Piece> yourPieces, List<Piece> visibleEnemies, List<CellInfo> visibleCells) {
    public record CellInfo(Position pos, ottv2.engine.model.CellType type) {}
}
