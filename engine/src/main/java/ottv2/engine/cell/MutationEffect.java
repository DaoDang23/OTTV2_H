package ottv2.engine.cell;

import ottv2.engine.event.GameEvent;
import ottv2.engine.event.PieceMutatedEvent;
import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;
import ottv2.engine.model.PieceType;

import java.util.Optional;

/** Advances the piece type in the game's RPS cycle when the piece enters the cell. */
public final class MutationEffect implements CellEffect {
    @Override
    public Optional<GameEvent> onEnter(Piece piece, GameState state) {
        PieceType before = piece.getType();
        piece.mutate();
        return Optional.of(new PieceMutatedEvent(piece.getId(), before, piece.getType()));
    }
}
