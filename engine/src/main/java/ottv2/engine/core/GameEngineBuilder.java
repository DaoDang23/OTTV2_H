package ottv2.engine.core;

import ottv2.engine.model.*;
import ottv2.engine.event.GameEventPublisher;
import ottv2.engine.rules.WinCondition;
import ottv2.engine.vision.VisionStrategy;
import ottv2.engine.combat.CombatResolver;

public class GameEngineBuilder {
    private GameState state;
    private GameEventPublisher publisher = new GameEventPublisher();
    private WinCondition winCondition;
    private VisionStrategy visionStrategy;
    private CombatResolver combatResolver;

    public GameEngineBuilder withState(GameState state) {
        this.state = state;
        return this;
    }

    public GameEngineBuilder withPublisher(GameEventPublisher publisher) {
        this.publisher = publisher;
        return this;
    }

    public GameEngineBuilder withWinCondition(WinCondition winCondition) {
        this.winCondition = winCondition;
        return this;
    }

    public GameEngineBuilder withVisionStrategy(VisionStrategy visionStrategy) {
        this.visionStrategy = visionStrategy;
        return this;
    }

    public GameEngineBuilder withCombatResolver(CombatResolver combatResolver) {
        this.combatResolver = combatResolver;
        return this;
    }

    public GameEngine build() {
        return new GameEngine(state, publisher, winCondition, visionStrategy, combatResolver);
    }
}
