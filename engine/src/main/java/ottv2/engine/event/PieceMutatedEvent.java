package ottv2.engine.event;

import ottv2.engine.model.PieceType;

public record PieceMutatedEvent(int pieceId, PieceType from, PieceType to) implements GameEvent {
}
