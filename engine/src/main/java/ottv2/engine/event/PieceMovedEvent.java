package ottv2.engine.event;

import ottv2.engine.model.Position;

public record PieceMovedEvent(int pieceId, Position from, Position to) implements GameEvent {
}
