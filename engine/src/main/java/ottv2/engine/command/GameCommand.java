package ottv2.engine.command;

import ottv2.engine.model.GameState;
import ottv2.engine.event.GameEvent;
import java.util.List;

public interface GameCommand {
    ValidationResult validate(GameState state);
    List<GameEvent> execute(GameState state);
}
