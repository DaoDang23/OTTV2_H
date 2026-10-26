package ottv2.engine.event;

public record PieceCapturedEvent(int attackerId, int victimId) implements GameEvent {
}
