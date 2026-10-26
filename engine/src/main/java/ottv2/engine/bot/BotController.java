package ottv2.engine.bot;

import ottv2.engine.model.Move;
import ottv2.engine.vision.Observation;
import java.time.Duration;

public interface BotController {
    void init(String matchInfo);
    Move requestMove(Observation observation, Duration timeLimit);

    default Move requestMove(Observation observation, Duration timeLimit, Duration globalTimeLeft) {
        return requestMove(observation, timeLimit);
    }

    default Double getLastCpuTimeSeconds() {
        return null;
    }

    void shutdown();
}
