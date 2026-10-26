package ottv2.engine.mapgen;

import ottv2.engine.model.Board;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Team;
import java.util.List;

public interface MapGenerator {
    Board generate(long seed, MapConfig config);
    List<Piece> generatePieces(long seed);
}
