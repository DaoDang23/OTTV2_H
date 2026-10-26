package ottv2.engine.core;

import org.junit.jupiter.api.Test;
import ottv2.engine.bot.BotController;
import ottv2.engine.bot.RandomBotController;
import ottv2.engine.event.ReplayRecorder;
import ottv2.engine.mapgen.MapConfig;
import ottv2.engine.mapgen.SymmetricMapGenerator;
import ottv2.engine.model.Board;
import ottv2.engine.model.GameState;
import ottv2.engine.model.Piece;
import ottv2.engine.rules.TurnLimitCondition;
import ottv2.engine.vision.Observation;
import ottv2.engine.combat.RpsCombatResolver;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterminismTest {

    @Test
    void testSameSeedSameResult() {
        String json1 = runMatch(42L);
        String json2 = runMatch(42L);
        
        assertEquals(json1, json2, "Matches with same seed should produce identical replays");
    }

    private String runMatch(long seed) {
        MapConfig config = new MapConfig(10, 4, 4, 4);
        SymmetricMapGenerator mapGen = new SymmetricMapGenerator();
        Board board = mapGen.generate(seed, config);
        List<Piece> pieces = mapGen.generatePieces(seed);
        
        GameState state = new GameState(board, pieces, seed);
        
        ReplayRecorder recorder = new ReplayRecorder("match-1", seed, config, "botA", "botB");
        
        GameEngineBuilder builder = new GameEngineBuilder()
            .withState(state)
            .withWinCondition(new TurnLimitCondition(20))
            // RadiusFogVision is not implemented yet. Use a temporary full-visibility
            // observation so this determinism test remains executable without fog rules.
            .withVisionStrategy((team, currentState) -> new Observation(
                currentState.getPieces().stream()
                    .filter(piece -> piece.isAlive() && piece.getTeam() == team)
                    .toList(),
                currentState.getPieces().stream()
                    .filter(piece -> piece.isAlive() && piece.getTeam() != team)
                    .toList(),
                List.of()))
            .withCombatResolver(new RpsCombatResolver());
            
        GameEngine engine = builder.build();
        engine.getPublisher().subscribe(recorder);
        
        // Add a simple listener to trigger recordTurnEnd
        engine.getPublisher().subscribe(event -> {
            if (event instanceof ottv2.engine.event.GameEndedEvent) {
                recorder.recordTurnEnd();
            }
        });
        
        BotController botA = new RandomBotController(seed);
        BotController botB = new RandomBotController(seed + 1);
        
        MatchRunner runner = new MatchRunner();
        
        // A hack for replay recording in tests, since match runner loop doesn't natively call recordTurnStart/End
        // We'll just wrap the bot controllers to record turn start/end.
        BotController wrapperA = new BotController() {
            public void init(String info) { botA.init(info); }
            public void shutdown() { botA.shutdown(); }
            public ottv2.engine.model.Move requestMove(ottv2.engine.vision.Observation obs, java.time.Duration time) {
                ottv2.engine.model.Move move = botA.requestMove(obs, time);
                recorder.recordTurnStart(engine.getState().getTurn(), ottv2.engine.model.Team.A, move);
                return move;
            }
        };
        
        BotController wrapperB = new BotController() {
            public void init(String info) { botB.init(info); }
            public void shutdown() { botB.shutdown(); }
            public ottv2.engine.model.Move requestMove(ottv2.engine.vision.Observation obs, java.time.Duration time) {
                recorder.recordTurnEnd(); // end previous turn
                ottv2.engine.model.Move move = botB.requestMove(obs, time);
                recorder.recordTurnStart(engine.getState().getTurn(), ottv2.engine.model.Team.B, move);
                return move;
            }
        };

        runner.run(engine, wrapperA, wrapperB);
        return recorder.getJson();
    }
}
