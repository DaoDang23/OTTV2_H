package ottv2.engine.core;

import ottv2.engine.model.*;
import ottv2.engine.command.*;
import ottv2.engine.event.*;
import ottv2.engine.rules.WinCondition;
import ottv2.engine.vision.VisionStrategy;
import ottv2.engine.vision.Observation;
import java.util.List;
import java.util.Optional;

public class GameEngine {
    private final GameState state;
    private final GameEventPublisher publisher;
    private final WinCondition winCondition;
    private final VisionStrategy visionStrategy;
    private final ottv2.engine.combat.CombatResolver combatResolver;

    public GameEngine(GameState state, GameEventPublisher publisher, WinCondition winCondition, VisionStrategy visionStrategy, ottv2.engine.combat.CombatResolver combatResolver) {
        this.state = state;
        this.publisher = publisher;
        this.winCondition = winCondition;
        this.visionStrategy = visionStrategy;
        this.combatResolver = combatResolver;
    }

    public GameState getState() { return state; }
    public GameEventPublisher getPublisher() { return publisher; }

    public Observation getObservation(Team team) {
        return visionStrategy.observe(team, state);
    }

    public boolean step(GameCommand command) {
        if (state.getPhase() != ottv2.engine.state.GamePhase.ONGOING) return false;

        ValidationResult vr = command.validate(state);
        if (vr.isValid()) {
            List<GameEvent> events = command.execute(state);
            for (GameEvent e : events) {
                publisher.publish(e);
            }
        } else {
            // Invalid command, basically pass or penalize. We treat it as pass for now.
            // In a real match runner, bot would lose immediately for invalid move.
            // Let's publish an event or just do nothing (turn will still increment).
        }

        // Check win condition
        Optional<Team> winner = winCondition.check(state);
        if (winner.isPresent()) {
            state.setPhase(ottv2.engine.state.GamePhase.FINISHED);
            publisher.publish(new GameEndedEvent(winner.get(), "CONDITION_MET"));
            return false; // Game over
        } else if (state.getPhase() == ottv2.engine.state.GamePhase.FINISHED) {
             return false;
        }

        // Next turn
        state.setCurrentTeam(state.getCurrentTeam().opponent());
        if (state.getCurrentTeam() == Team.A) {
            state.incrementTurn();
        }

        return true;
    }

    public void start() {
        state.setPhase(ottv2.engine.state.GamePhase.ONGOING);
    }
}
