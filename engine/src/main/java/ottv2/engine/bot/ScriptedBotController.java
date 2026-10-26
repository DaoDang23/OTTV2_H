package ottv2.engine.bot;

import ottv2.engine.model.Move;
import ottv2.engine.vision.Observation;
import java.time.Duration;
import java.util.List;

public class ScriptedBotController implements BotController {
    private final List<Move> moves;
    private int current = 0;

    public ScriptedBotController(List<Move> moves) {
        this.moves = moves;
    }

    @Override
    public void init(String matchInfo) {}

    @Override
    public Move requestMove(Observation obs, Duration timeLimit) {
        if (current < moves.size()) {
            return moves.get(current++);
        }
        return null;
    }

    @Override
    public void shutdown() {}
}
