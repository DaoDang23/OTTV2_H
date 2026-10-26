package ottv2.engine.cell;

import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;
import ottv2.engine.event.GameEvent;
import ottv2.engine.event.TargetReachedEvent;
import java.util.Optional;

public class TargetEffect implements CellEffect {
    @Override
    public Optional<GameEvent> onEnter(Piece piece, GameState state) {
        return Optional.of(new TargetReachedEvent(piece.getId()));
    }
}
