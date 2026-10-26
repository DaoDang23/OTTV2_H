package ottv2.engine.command;

import ottv2.engine.model.GameState;
import ottv2.engine.event.GameEvent;
import java.util.Collections;
import java.util.List;

public class PassCommand implements GameCommand {
    @Override
    public ValidationResult validate(GameState state) {
        return ValidationResult.valid();
    }

    @Override
    public List<GameEvent> execute(GameState state) {
        // Does nothing except passing the turn, which is handled outside.
        // We could return a PassEvent here if we want to log it.
        return Collections.emptyList();
    }
}
