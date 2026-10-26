package ottv2.engine.vision;

import ottv2.engine.model.GameState;
import ottv2.engine.model.Team;

public interface VisionStrategy {
    // Actually should return Observation. Let's create an Observation record.
    Observation observe(Team team, GameState state);
}
