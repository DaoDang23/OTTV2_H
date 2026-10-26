package ottv2.engine.core;

import ottv2.engine.bot.BotController;
import ottv2.engine.bot.BotTimeoutException;
import ottv2.engine.model.Move;
import ottv2.engine.model.Team;
import ottv2.engine.command.MoveCommand;
import ottv2.engine.command.PassCommand;
import ottv2.engine.event.GameEndedEvent;
import ottv2.engine.combat.RpsCombatResolver;
import ottv2.engine.event.ReplayRecorder;
import java.time.Duration;

public class MatchRunner {
    public static final long INITIAL_GLOBAL_TIME_MS = 180_000L;
    public static final double MAX_TURN_TIME_SECONDS = 3.0;

    public void run(GameEngine engine, BotController botA, BotController botB) {
        run(engine, botA, botB, null);
    }

    public void run(GameEngine engine, BotController botA, BotController botB, ReplayRecorder replay) {
        try {
            botA.init("Team A");
            botB.init("Team B");
            engine.start();

            long timeLeftA = INITIAL_GLOBAL_TIME_MS;
            long timeLeftB = INITIAL_GLOBAL_TIME_MS;

            while (engine.getState().getPhase() == ottv2.engine.state.GamePhase.ONGOING) {
                Team currentTeam = engine.getState().getCurrentTeam();
                BotController currentBot = (currentTeam == Team.A) ? botA : botB;
                long currentTimeLeft = (currentTeam == Team.A) ? timeLeftA : timeLeftB;

                if (currentTimeLeft <= 0) {
                    engine.getState().setPhase(ottv2.engine.state.GamePhase.FINISHED);
                    engine.getPublisher().publish(new GameEndedEvent(currentTeam.opponent(), "TIMEOUT"));
                    break;
                }

                double turnTimeLimitSec = Math.min(MAX_TURN_TIME_SECONDS, currentTimeLeft / 1000.0);
                long turnLimitMs = Math.max(1L, (long) (turnTimeLimitSec * 1000.0));
                Duration turnLimit = Duration.ofMillis(turnLimitMs);

                var obs = engine.getObservation(currentTeam);
                Move move;
                long startNs = System.nanoTime();
                try {
                    move = currentBot.requestMove(obs, turnLimit, Duration.ofMillis(currentTimeLeft));
                } catch (BotTimeoutException e) {
                    engine.getState().setPhase(ottv2.engine.state.GamePhase.FINISHED);
                    engine.getPublisher().publish(new GameEndedEvent(currentTeam.opponent(), "TIMEOUT"));
                    break;
                } catch (Exception e) {
                    engine.getState().setPhase(ottv2.engine.state.GamePhase.FINISHED);
                    engine.getPublisher().publish(new GameEndedEvent(currentTeam.opponent(), "BOT_CRASHED"));
                    break;
                }

                long elapsedNs = System.nanoTime() - startNs;
                long turnUsedMs = Math.max(1L, elapsedNs / 1_000_000L);

                Double metaSec = currentBot.getLastCpuTimeSeconds();
                if (metaSec != null && metaSec > 0) {
                    turnUsedMs = (long) (metaSec * 1000.0);
                    if (metaSec > MAX_TURN_TIME_SECONDS) {
                        engine.getState().setPhase(ottv2.engine.state.GamePhase.FINISHED);
                        engine.getPublisher().publish(new GameEndedEvent(currentTeam.opponent(), "TIMEOUT"));
                        break;
                    }
                }

                if (currentTeam == Team.A) {
                    timeLeftA -= turnUsedMs;
                    if (timeLeftA <= 0) {
                        engine.getState().setPhase(ottv2.engine.state.GamePhase.FINISHED);
                        engine.getPublisher().publish(new GameEndedEvent(currentTeam.opponent(), "TIMEOUT"));
                        break;
                    }
                } else {
                    timeLeftB -= turnUsedMs;
                    if (timeLeftB <= 0) {
                        engine.getState().setPhase(ottv2.engine.state.GamePhase.FINISHED);
                        engine.getPublisher().publish(new GameEndedEvent(currentTeam.opponent(), "TIMEOUT"));
                        break;
                    }
                }

                ottv2.engine.command.GameCommand cmd;
                if (move == null) {
                    cmd = new PassCommand();
                } else {
                    cmd = new MoveCommand(move.pieceId(), move.to(), new RpsCombatResolver());
                }

            // Engine steps and handles validation. If validation fails, in strict mode, current team loses.
            // For now, if validate fails, engine ignores move. We can enforce rule here:
                if (!cmd.validate(engine.getState()).isValid()) {
                    engine.getState().setPhase(ottv2.engine.state.GamePhase.FINISHED);
                    engine.getPublisher().publish(new GameEndedEvent(currentTeam.opponent(), "INVALID_MOVE"));
                    break;
                }

                if (replay != null) replay.recordTurnStart(engine.getState().getTurn(), currentTeam, move);
                engine.step(cmd);
                if (replay != null) replay.recordTurnEnd();
            }
        } finally {
            botA.shutdown();
            botB.shutdown();
        }
    }
}
