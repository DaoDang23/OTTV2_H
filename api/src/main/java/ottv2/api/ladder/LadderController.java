package ottv2.api.ladder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import ottv2.api.security.ApiPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.match.MatchDtos.MatchResponse;
import ottv2.api.match.MatchService;

@RestController
@RequestMapping("/api/ladder")
public class LadderController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final MatchService matches;

    public LadderController(JdbcTemplate jdbc, StringRedisTemplate redis, MatchService matches) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.matches = matches;
    }

    @PostMapping("/online")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void online(@AuthenticationPrincipal ApiPrincipal principal, @Valid @RequestBody OnlineRequest request) {
        long seasonId = activeSeason();
        int playable = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bots b JOIN submissions s ON s.bot_id=b.id
                WHERE b.id=? AND b.owner_id=? AND b.ladder_enabled=TRUE AND s.id=?
                  AND s.status IN ('COMPILED','ACTIVE') AND s.artifact_key IS NOT NULL
                """, Integer.class, request.botId(), principal.id(), request.submissionId());
        if (playable != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a compiled submission owned by your ladder-enabled bot");
        jdbc.update("INSERT INTO bot_ratings(bot_id, season_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                request.botId(), seasonId);
        Integer rating = jdbc.queryForObject("SELECT elo FROM bot_ratings WHERE bot_id=? AND season_id=?",
                Integer.class, request.botId(), seasonId);
        String key = LadderKeys.presence(seasonId);
        redis.opsForZSet().add(key, Long.toString(request.botId()), System.currentTimeMillis());
        redis.opsForHash().put(LadderKeys.submissions(seasonId), Long.toString(request.botId()),
                Long.toString(request.submissionId()));
        redis.opsForZSet().add(LadderKeys.ratings(seasonId), Long.toString(request.botId()), rating);
    }

    @DeleteMapping("/online/{botId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void offline(@AuthenticationPrincipal ApiPrincipal principal, @PathVariable long botId) {
        int owned = jdbc.queryForObject("SELECT COUNT(*) FROM bots WHERE id=? AND owner_id=?", Integer.class,
                botId, principal.id());
        if (owned == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found");
        jdbc.query("SELECT id FROM seasons WHERE status='ACTIVE'", rs -> {
            long seasonId = rs.getLong(1);
            redis.opsForZSet().remove(LadderKeys.presence(seasonId), Long.toString(botId));
            redis.opsForHash().delete(LadderKeys.submissions(seasonId), Long.toString(botId));
        });
    }

    @GetMapping({"", "/rankings", "/leaderboard"})
    public List<RankingEntry> rankings(@RequestParam(required = false) Long seasonId,
                                       @RequestParam(defaultValue = "100") @Min(1) int limit) {
        long season = seasonId == null ? activeSeason() : seasonId;
        return jdbc.query("""
                SELECT r.bot_id, b.name AS bot_name, b.owner_id,
                       u.student_code AS owner_username,
                       COALESCE(u.display_name, u.student_code) AS owner_display_name,
                       s.language,
                       r.elo, r.games, r.wins, r.losses, r.draws,
                       COALESCE(
                           NULLIF((SELECT COUNT(DISTINCT opp.bot_id)
                            FROM match_participants self
                            JOIN match_participants opp ON opp.match_id = self.match_id AND opp.side <> self.side
                            WHERE self.bot_id = r.bot_id), 0),
                           (SELECT COUNT(DISTINCT CASE WHEN ls.bot_a_id = r.bot_id THEN ls.bot_b_id ELSE ls.bot_a_id END)
                            FROM ladder_series ls
                            WHERE (ls.bot_a_id = r.bot_id OR ls.bot_b_id = r.bot_id)
                              AND ls.finished_at IS NOT NULL),
                           0
                       ) AS distinct_opponents
                FROM bot_ratings r
                JOIN bots b ON b.id = r.bot_id
                JOIN users u ON u.id = b.owner_id
                LEFT JOIN submissions s ON s.id = b.active_submission_id
                WHERE r.season_id = ? AND (b.deleted IS FALSE OR b.deleted IS NULL)
                ORDER BY r.elo DESC, r.wins DESC, r.games DESC
                LIMIT ?
                """, (rs, row) -> {
            int games = rs.getInt("games");
            int distinctOpponents = rs.getInt("distinct_opponents");
            int wins = rs.getInt("wins");
            int losses = rs.getInt("losses");
            int draws = rs.getInt("draws");
            int elo = rs.getInt("elo");
            double winRate = games > 0 ? Math.round(((double) wins / games) * 1000.0) / 10.0 : 0.0;
            boolean provisional = games < 10 && distinctOpponents < 5;
            String language = rs.getString("language");
            if (language == null) language = "PYTHON";

            return new RankingEntry(
                    rs.getLong("bot_id"),
                    rs.getString("bot_name"),
                    rs.getLong("owner_id"),
                    rs.getString("owner_username"),
                    rs.getString("owner_display_name"),
                    elo,
                    elo,
                    games,
                    games,
                    wins,
                    losses,
                    draws,
                    winRate,
                    distinctOpponents,
                    provisional,
                    provisional,
                    language
            );
        }, season, Math.min(limit, 500));
    }

    @GetMapping("/history")
    public List<MatchResponse> history(@AuthenticationPrincipal ApiPrincipal principal) {
        return matches.listLadder(principal.id());
    }

    private long activeSeason() {
        try {
            return jdbc.queryForObject("SELECT id FROM seasons WHERE status='ACTIVE' AND starts_at<=now() AND ends_at>now() ORDER BY starts_at LIMIT 1",
                    Long.class);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            try {
                return jdbc.queryForObject("SELECT id FROM seasons WHERE status='ACTIVE' ORDER BY starts_at DESC LIMIT 1", Long.class);
            } catch (org.springframework.dao.EmptyResultDataAccessException e2) {
                try {
                    return jdbc.queryForObject("SELECT id FROM seasons ORDER BY id DESC LIMIT 1", Long.class);
                } catch (org.springframework.dao.EmptyResultDataAccessException e3) {
                    return 1L;
                }
            }
        }
    }

    public record OnlineRequest(@NotNull @Min(1) Long botId, @NotNull @Min(1) Long submissionId) { }
    public record RankingEntry(
            long botId,
            String botName,
            long ownerId,
            String ownerUsername,
            String ownerDisplayName,
            int botElo,
            int elo,
            int matchesCount,
            int games,
            int wins,
            int losses,
            int draws,
            double winRate,
            int distinctOpponents,
            boolean isProvisional,
            boolean provisional,
            String language
    ) {
        public RankingEntry(long botId, String botName, long ownerId, int elo, int games) {
            this(botId, botName, ownerId, "user_" + ownerId, "User #" + ownerId,
                    elo, elo, games, games, 0, 0, 0, 0.0, 0, games < 10, games < 10, "PYTHON");
        }

        public RankingEntry(long botId, String botName, long ownerId, int elo, int games,
                            int wins, int losses, int draws, int distinctOpponents, boolean provisional) {
            this(botId, botName, ownerId, "user_" + ownerId, "User #" + ownerId,
                    elo, elo, games, games, wins, losses, draws,
                    games > 0 ? Math.round(((double) wins / games) * 1000.0) / 10.0 : 0.0,
                    distinctOpponents, provisional, provisional, "PYTHON");
        }
    }
}
