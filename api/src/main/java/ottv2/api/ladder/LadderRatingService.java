package ottv2.api.ladder;

import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ottv2.api.ladder.RatingCalculator.RatingChange;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies rating updates after a ladder match or series of matches have finished.
 * Supports both single-match matchmaking series (games_completed=1) and 2-game background series (games_completed=2).
 */
@Component
public class LadderRatingService {
    private static final Logger log = LoggerFactory.getLogger(LadderRatingService.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final RatingCalculator calculator;

    public LadderRatingService(JdbcTemplate jdbc, StringRedisTemplate redis, RatingCalculator calculator) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.calculator = calculator;
    }

    @Transactional
    public void recordFinishedGame(long matchId, Date month, String winnerSide) {
        List<SeriesGame> games = jdbc.query("""
                SELECT ls.id, ls.season_id, ls.bot_a_id, ls.bot_b_id,
                       CASE WHEN winner.bot_id = ls.bot_a_id THEN 1.0
                            WHEN winner.bot_id = ls.bot_b_id THEN 0.0
                            WHEN ? = 'DRAW' OR m.winner_side = 'DRAW' 
                                 OR m.end_reason IN ('DRAW', 'REPETITION', 'AGREED_DRAW') THEN 0.5
                            ELSE -1.0 END AS score_a
                FROM matches m JOIN ladder_series ls ON ls.id=m.ladder_series_id
                LEFT JOIN match_participants winner ON winner.match_id=m.id AND winner.created_month=m.created_month
                    AND winner.side=?
                WHERE m.id=? AND m.created_month=? AND m.match_type='LADDER'
                  AND m.status = 'FINISHED'
                  AND (m.end_reason IS NULL OR m.end_reason NOT IN ('WORKER_ERROR', 'INFRA_ERROR', 'CANCELLED', 'ERROR', 'ABORTED'))
                """, (rs, row) -> new SeriesGame(rs.getLong("id"), rs.getLong("season_id"),
                rs.getLong("bot_a_id"), rs.getLong("bot_b_id"), rs.getDouble("score_a")),
                winnerSide, winnerSide, matchId, month);
        if (games.isEmpty()) return;
        SeriesGame game = games.getFirst();
        if (game.scoreA() < 0.0) {
            log.warn("Match #{} ended without a valid winner or genuine draw; skipping rating update and draws count.", matchId);
            return;
        }

        List<String> seasonStatus = jdbc.query("SELECT status FROM seasons WHERE id=? FOR SHARE",
                (rs, row) -> rs.getString(1), game.seasonId());
        if (seasonStatus.isEmpty() || !"ACTIVE".equals(seasonStatus.getFirst())) return;

        int totalMatches = 1;
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM matches WHERE ladder_series_id=?", Integer.class, game.seriesId());
            if (count != null && count > 0) {
                totalMatches = count;
            }
        } catch (Exception ignored) {}

        List<Progress> progress = jdbc.query("""
                UPDATE ladder_series SET score_a=score_a+?, games_completed=games_completed+1
                WHERE id=? AND finished_at IS NULL AND games_completed < ?
                RETURNING score_a, games_completed
                """, (rs, row) -> new Progress(rs.getDouble(1), rs.getInt(2)),
                game.scoreA(), game.seriesId(), totalMatches);

        if (progress.isEmpty() || progress.getFirst().gamesCompleted() < totalMatches) return;

        jdbc.update("UPDATE ladder_series SET finished_at=now() WHERE id=? AND finished_at IS NULL", game.seriesId());

        // Ensure bot_ratings exist for both bots
        jdbc.update("INSERT INTO bot_ratings(bot_id, season_id, elo, games, wins, losses, draws) VALUES (?, ?, 1200, 0, 0, 0, 0) ON CONFLICT (bot_id, season_id) DO NOTHING",
                game.botA(), game.seasonId());
        jdbc.update("INSERT INTO bot_ratings(bot_id, season_id, elo, games, wins, losses, draws) VALUES (?, ?, 1200, 0, 0, 0, 0) ON CONFLICT (bot_id, season_id) DO NOTHING",
                game.botB(), game.seasonId());

        List<RatingRow> ratings = jdbc.query("""
                SELECT bot_id, elo FROM bot_ratings WHERE season_id=? AND bot_id IN (?, ?)
                ORDER BY bot_id FOR UPDATE
                """, (rs, row) -> new RatingRow(rs.getLong(1), rs.getInt(2)),
                game.seasonId(), game.botA(), game.botB());
        if (ratings.size() != 2) throw new IllegalStateException("Ladder series is missing a bot rating");
        RatingRow a = ratings.get(0).botId() == game.botA() ? ratings.get(0) : ratings.get(1);
        RatingRow b = ratings.get(0).botId() == game.botB() ? ratings.get(0) : ratings.get(1);

        double scoreA = progress.getFirst().scoreA() / (double) totalMatches;
        RatingChange change = calculator.calculate(a.elo(), b.elo(), scoreA);
        int outcomeA = Double.compare(scoreA, 0.5);

        applyRating(a, game.seasonId(), change.ratingA(), outcomeA);
        applyRating(b, game.seasonId(), change.ratingB(), -outcomeA);
        updateParticipantRatings(game.seriesId(), a, b, change);

        UUID publicId = null;
        try {
            publicId = jdbc.queryForObject("SELECT public_id FROM matches WHERE id=?", UUID.class, matchId);
        } catch (Exception ignored) {}
        String replayId = publicId != null ? publicId.toString() : String.valueOf(matchId);

        updateUserStatsAndHistory(game.botA(), game.botB(), a.elo(), b.elo(), change.ratingA(), outcomeA, replayId);
        updateUserStatsAndHistory(game.botB(), game.botA(), b.elo(), a.elo(), change.ratingB(), -outcomeA, replayId);

        log.info("Ranked Bot Match #{} completed: Bot A (ID={}) ELO {} -> {}, Bot B (ID={}) ELO {} -> {}",
                matchId, game.botA(), a.elo(), change.ratingA(), game.botB(), b.elo(), change.ratingB());

        if (redis != null) {
            try {
                redis.opsForZSet().add(LadderKeys.ratings(game.seasonId()), Long.toString(game.botA()), change.ratingA());
                redis.opsForZSet().add(LadderKeys.ratings(game.seasonId()), Long.toString(game.botB()), change.ratingB());
            } catch (Exception ignored) {}
        }
    }

    private void updateUserStatsAndHistory(long botId, long opponentBotId, int eloBefore, int opponentElo, int eloAfter, int outcome, String replayId) {
        try {
            List<BotOwner> myInfo = jdbc.query("SELECT b.owner_id, b.name FROM bots b WHERE b.id=?",
                    (rs, row) -> new BotOwner(rs.getLong(1), rs.getString(2)), botId);
            List<BotOwner> oppInfo = jdbc.query("SELECT b.owner_id, b.name FROM bots b WHERE b.id=?",
                    (rs, row) -> new BotOwner(rs.getLong(1), rs.getString(2)), opponentBotId);
            if (myInfo.isEmpty() || oppInfo.isEmpty()) return;

            long ownerId = myInfo.getFirst().ownerId();
            String oppBotName = oppInfo.getFirst().name();
            boolean userWon = outcome > 0;
            String winner = outcome > 0 ? "USER" : (outcome < 0 ? "OPPONENT" : "DRAW");
            int change = eloAfter - eloBefore;

            jdbc.update("""
                    INSERT INTO user_matches (user_id, mode, opponent, opponent_elo, opponent_type,
                                             user_won, winner, reason, elo_change, new_elo, is_ranked, match_type, replay_id)
                    VALUES (?, 'LADDER', ?, ?, 'BOT', ?, ?, 'LADDER_SERIES', ?, ?, TRUE, 'RANKED', ?)
                    """,
                    ownerId, oppBotName, opponentElo, userWon, winner, change, eloAfter, replayId);

            // Update user bot_wins/bot_losses
            if (outcome > 0) {
                jdbc.update("UPDATE users SET bot_wins = bot_wins + 1 WHERE id = ?", ownerId);
            } else if (outcome < 0) {
                jdbc.update("UPDATE users SET bot_losses = bot_losses + 1 WHERE id = ?", ownerId);
            }

            // Sync users.bot_elo with highest bot elo
            jdbc.update("""
                    UPDATE users SET bot_elo = COALESCE(
                        (SELECT MAX(r.elo) FROM bot_ratings r
                         JOIN bots b ON b.id = r.bot_id
                         WHERE b.owner_id = ? AND (b.deleted IS FALSE OR b.deleted IS NULL)),
                        ?
                    ) WHERE id = ?
                    """, ownerId, eloAfter, ownerId);
        } catch (Exception e) {
            log.error("Failed to update user stats and history for bot {}: {}", botId, e.getMessage(), e);
        }
    }

    private void applyRating(RatingRow rating, long seasonId, int newElo, int outcome) {
        jdbc.update("""
                UPDATE bot_ratings SET elo=?, games=games+1,
                    wins=wins+?, losses=losses+?, draws=draws+?, version=version+1, updated_at=now()
                WHERE bot_id=? AND season_id=?
                """, newElo, outcome > 0 ? 1 : 0, outcome < 0 ? 1 : 0, outcome == 0 ? 1 : 0,
                rating.botId(), seasonId);
    }

    private void updateParticipantRatings(long seriesId, RatingRow a, RatingRow b, RatingChange change) {
        jdbc.update("""
                UPDATE match_participants p SET elo_before=?, elo_after=?
                FROM matches m WHERE m.id=p.match_id AND m.created_month=p.created_month
                  AND m.ladder_series_id=? AND p.bot_id=?
                """, a.elo(), change.ratingA(), seriesId, a.botId());
        jdbc.update("""
                UPDATE match_participants p SET elo_before=?, elo_after=?
                FROM matches m WHERE m.id=p.match_id AND m.created_month=p.created_month
                  AND m.ladder_series_id=? AND p.bot_id=?
                """, b.elo(), change.ratingB(), seriesId, b.botId());
    }

    record SeriesGame(long seriesId, long seasonId, long botA, long botB, double scoreA) { }
    private record Progress(double scoreA, int gamesCompleted) { }
    private record RatingRow(long botId, int elo) { }
    private record BotOwner(long ownerId, String name) { }
}
