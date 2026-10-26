package ottv2.engine.core;

import org.junit.jupiter.api.Test;
import ottv2.engine.bot.BotController;
import ottv2.engine.bot.BotTimeoutException;
import ottv2.engine.combat.RpsCombatResolver;
import ottv2.engine.mapgen.MapConfig;
import ottv2.engine.mapgen.SymmetricMapGenerator;
import ottv2.engine.model.Board;
import ottv2.engine.model.Move;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Position;
import ottv2.engine.model.Team;
import ottv2.engine.vision.Observation;
import ottv2.engine.rules.AnnihilationCondition;
import ottv2.engine.rules.CompositeWinCondition;
import ottv2.engine.rules.ReachTargetCondition;
import ottv2.engine.rules.TurnLimitCondition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchRunnerTimePoolTest {

    @Test
    void testInitialGlobalTimeAndPerTurnLimit() {
        SymmetricMapGenerator gen = new SymmetricMapGenerator();
        Board board = gen.generate(42L, new MapConfig(8));
        List<Piece> pieces = gen.generatePieces(42L);
        pieces.forEach(p -> board.get(p.getPosition()).setOccupant(p));
        var state = new ottv2.engine.model.GameState(board, pieces, 42L);
        var publisher = new ottv2.engine.event.GameEventPublisher();
        var wins = new CompositeWinCondition(List.of(new ReachTargetCondition(), new AnnihilationCondition(), new TurnLimitCondition(5)));

        GameEngine engine = new GameEngineBuilder()
                .withState(state)
                .withPublisher(publisher)
                .withWinCondition(wins)
                .withCombatResolver(new RpsCombatResolver())
                .withVisionStrategy((team, g) -> new Observation(List.of(), List.of(), List.of()))
                .build();

        AtomicLong initialGlobalTime = new AtomicLong(-1);
        AtomicLong observedTurnLimit = new AtomicLong(-1);
        AtomicLong lastGlobalTime = new AtomicLong(-1);

        BotController botA = new BotController() {
            @Override
            public void init(String matchInfo) {}

            @Override
            public Move requestMove(Observation observation, Duration timeLimit, Duration globalTimeLeft) {
                initialGlobalTime.compareAndSet(-1, globalTimeLeft.toMillis());
                observedTurnLimit.compareAndSet(-1, timeLimit.toMillis());
                lastGlobalTime.set(globalTimeLeft.toMillis());
                // Pass move
                return null;
            }

            @Override
            public Move requestMove(Observation observation, Duration timeLimit) {
                return null;
            }

            @Override
            public void shutdown() {}
        };

        BotController botB = new BotController() {
            @Override
            public void init(String matchInfo) {}

            @Override
            public Move requestMove(Observation observation, Duration timeLimit) {
                return null;
            }

            @Override
            public void shutdown() {}
        };

        MatchRunner runner = new MatchRunner();
        runner.run(engine, botA, botB);

        assertEquals(180_000L, initialGlobalTime.get());
        assertTrue(observedTurnLimit.get() <= 3000L);
        assertTrue(lastGlobalTime.get() < 180_000L);
    }
}
