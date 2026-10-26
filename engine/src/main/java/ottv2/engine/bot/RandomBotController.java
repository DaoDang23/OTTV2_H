package ottv2.engine.bot;

import ottv2.engine.model.Move;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Position;
import ottv2.engine.vision.Observation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RandomBotController implements BotController {
    private Random rng;

    public RandomBotController(long seed) {
        this.rng = new Random(seed);
    }

    @Override
    public void init(String matchInfo) {}

    @Override
    public Move requestMove(Observation obs, Duration timeLimit) {
        if (obs.yourPieces().isEmpty()) return null;
        
        List<Move> possibleMoves = new ArrayList<>();
        for (Piece p : obs.yourPieces()) {
            for (Position n : p.getPosition().neighbors()) {
                if (n.row() >= 0 && n.row() < 9 && n.col() >= 0 && n.col() < 9) {
                    possibleMoves.add(new Move(p.getId(), n));
                }
            }
        }
        
        if (possibleMoves.isEmpty()) return null;
        return possibleMoves.get(rng.nextInt(possibleMoves.size()));
    }

    @Override
    public void shutdown() {}
}
