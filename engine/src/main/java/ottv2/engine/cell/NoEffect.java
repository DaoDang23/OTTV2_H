package ottv2.engine.cell;

import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;
import ottv2.engine.event.GameEvent;
import java.util.Optional;

public class NoEffect implements CellEffect {
    @Override
    public Optional<GameEvent> onEnter(Piece piece, GameState state) {
        return Optional.empty();
    }
}
