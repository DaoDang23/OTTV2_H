package ottv2.engine.cell;

import ottv2.engine.event.GameEvent;
import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;

import java.util.Optional;

/**
 * Speed movement is resolved by MoveCommand, which knows the direction of travel
 * and can validate and resolve a possible capture in the next cell.
 */
public final class SpeedEffect implements CellEffect {
    @Override
    public Optional<GameEvent> onEnter(Piece piece, GameState state) {
        return Optional.empty();
    }
}
