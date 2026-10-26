package ottv2.engine.event;

public record GameEndedEvent(ottv2.engine.model.Team winner, String reason) implements GameEvent {
}
