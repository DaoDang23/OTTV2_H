package ottv2.api.ladder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LadderConfiguration {
    @Bean
    MatchmakingStrategy matchmakingStrategy(
            @Value("${ottv2.ladder.matchmaking.initial-window:150}") int initialWindow,
            @Value("${ottv2.ladder.matchmaking.expansion-per-minute:50}") int expansionPerMinute,
            @Value("${ottv2.ladder.matchmaking.max-window:300}") int maxWindow) {
        return new MatchmakingStrategy.Elo(initialWindow, expansionPerMinute, maxWindow);
    }

    @Bean
    RatingCalculator ratingCalculator(
            @Value("${ottv2.ladder.k-factor:${ladder.rating.k-factor:32}}") int kFactor) {
        return new RatingCalculator.Elo(kFactor);
    }
}

