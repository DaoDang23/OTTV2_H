package ottv2.engine.cell;

import ottv2.engine.event.GameEvent;
import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;

import java.util.Optional;

/**
 * Trap behavior is intentionally deferred by the current game rules.
 * This strategy is a no-op until the trap penalty is configured by a later stage.
 */
public final class TrapEffect implements CellEffect {
    @Override
    public Optional<GameEvent> onEnter(Piece piece, GameState state) {
        return Optional.empty();
    }
}
