package ottv2.api.ladder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ottv2.api.match.MatchJob;
import ottv2.api.ladder.MatchmakingStrategy.Candidate;
import ottv2.api.ladder.MatchmakingStrategy.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class LadderScheduler {
    private static final Logger log = LoggerFactory.getLogger(LadderScheduler.class);
    private static final long SCHEDULER_LOCK = 0x4f545456324cL;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final MatchJob.MapConfiguration MAP = new MatchJob.MapConfiguration(8, 0, 0, 0);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MatchmakingStrategy matchmaking;
    private final int presenceTtlSeconds;
    private final int maxSeriesPerMinute;
    private final SecureRandom random = new SecureRandom();
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public LadderScheduler(JdbcTemplate jdbc, TransactionTemplate transaction, StringRedisTemplate redis,
                           ObjectMapper mapper, MatchmakingStrategy matchmaking,
                           @Value("${ottv2.ladder.presence-ttl-seconds:120}") int presenceTtlSeconds,
                           @Value("${ottv2.ladder.max-matches-per-minute:1}") int maxSeriesPerMinute,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.redis = redis;
        this.mapper = mapper;
        this.matchmaking = matchmaking;
        this.presenceTtlSeconds = presenceTtlSeconds;
        this.maxSeriesPerMinute = maxSeriesPerMinute;
        this.messagingTemplate = messagingTemplate;
    }

    public LadderScheduler(JdbcTemplate jdbc, TransactionTemplate transaction, StringRedisTemplate redis,
                           ObjectMapper mapper, MatchmakingStrategy matchmaking,
                           int presenceTtlSeconds, int maxSeriesPerMinute) {
        this(jdbc, transaction, redis, mapper, matchmaking, presenceTtlSeconds, maxSeriesPerMinute, null);
    }

    @Scheduled(fixedDelayString = "${ottv2.ladder.poll-ms:5000}")
    public void schedule() {
        try {
            transaction.executeWithoutResult(status -> {
                Boolean acquired = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, SCHEDULER_LOCK);
                if (!Boolean.TRUE.equals(acquired)) return;
                long seasonId = activeSeason();
                if (seasonId == 0 || !insideWindow(seasonId, Instant.now())) return;
                List<Candidate> candidates = onlineCandidates(seasonId, Instant.now());
                for (Pair pair : matchmaking.pair(candidates, Instant.now())) {
                    enqueueSeries(seasonId, pair);
                }
            });
        } catch (Exception e) {
            log.warn("Ladder scheduling pass failed; it will retry on the next tick", e);
        }
    }

    private long activeSeason() {
        List<Long> ids = jdbc.query("""
                SELECT id FROM seasons WHERE status='ACTIVE' AND starts_at<=now() AND ends_at>now()
                ORDER BY starts_at LIMIT 1 FOR SHARE
                """, (rs, row) -> rs.getLong(1));
        return ids.isEmpty() ? 0 : ids.getFirst();
    }

    private boolean insideWindow(long seasonId, Instant instant) {
        var local = instant.atZone(DEFAULT_ZONE);
        int weekday = local.getDayOfWeek().getValue();
        LocalTime current = local.toLocalTime();
        List<Window> configured = jdbc.query("""
                SELECT day_of_week, start_time, end_time, timezone FROM ladder_windows WHERE season_id=?
                """, (rs, row) -> new Window(rs.getInt(1), rs.getTime(2).toLocalTime(),
                rs.getTime(3).toLocalTime(), ZoneId.of(rs.getString(4))), seasonId);
        if (configured.isEmpty()) {
            return weekday >= 1 && weekday <= 5 && !current.isBefore(LocalTime.of(19, 0))
                    && current.isBefore(LocalTime.of(22, 0));
        }
        return configured.stream().anyMatch(window -> {
            var zoned = instant.atZone(window.zone());
            int localDay = zoned.getDayOfWeek().getValue();
            LocalTime localTime = zoned.toLocalTime();
            return localDay == window.dayOfWeek() && !localTime.isBefore(window.start())
                    && localTime.isBefore(window.end());
        });
    }

    private List<Candidate> onlineCandidates(long seasonId, Instant now) {
        var online = redis != null ? redis.opsForZSet().rangeWithScores(LadderKeys.presence(seasonId), 0, -1) : null;
        List<Candidate> candidates = new ArrayList<>();

        if (online != null && !online.isEmpty()) {
            long staleBefore = now.toEpochMilli() - presenceTtlSeconds * 1000L;
            for (ZSetOperations.TypedTuple<String> tuple : online) {
                if (tuple.getValue() == null || tuple.getScore() == null) continue;
                long botId = Long.parseLong(tuple.getValue());
                long lastSeen = tuple.getScore().longValue();
                if (lastSeen < staleBefore) {
                    redis.opsForZSet().remove(LadderKeys.presence(seasonId), tuple.getValue());
                    redis.opsForHash().delete(LadderKeys.submissions(seasonId), tuple.getValue());
                    continue;
                }
                Object submissionValue = redis.opsForHash().get(LadderKeys.submissions(seasonId), tuple.getValue());
                if (submissionValue == null) continue;
                long submissionId = Long.parseLong(submissionValue.toString());
                CandidateData data = candidateData(seasonId, botId, submissionId);
                if (data == null || hasReachedThrottle(seasonId, botId)) continue;
                Set<Long> recent = jdbc.query("""
                        SELECT CASE WHEN bot_a_id=? THEN bot_b_id ELSE bot_a_id END
                        FROM ladder_series WHERE season_id=? AND (bot_a_id=? OR bot_b_id=?)
                        ORDER BY created_at DESC LIMIT 3
                        """, (rs, row) -> rs.getLong(1), botId, seasonId, botId, botId)
                        .stream().collect(java.util.stream.Collectors.toSet());
                candidates.add(new Candidate(botId, data.ownerId(), data.submissionId(), data.elo(), Instant.ofEpochMilli(lastSeen), recent));
                redis.opsForZSet().add(LadderKeys.ratings(seasonId), Long.toString(botId), data.elo());
            }
        } else {
            // Database scan fallback: match all bots with ladder_enabled=TRUE and active_submission_id IS NOT NULL
            List<CandidateDataWithBot> dbBots = jdbc.query("""
                    SELECT b.id AS bot_id, b.owner_id, b.active_submission_id AS submission_id, COALESCE(r.elo, 1200) AS elo
                    FROM bots b
                    JOIN submissions s ON s.id = b.active_submission_id
                    LEFT JOIN bot_ratings r ON r.bot_id = b.id AND r.season_id = ?
                    WHERE b.ladder_enabled = TRUE AND b.active_submission_id IS NOT NULL
                      AND (b.deleted IS FALSE OR b.deleted IS NULL)
                      AND s.status IN ('COMPILED', 'ACTIVE')
                      AND s.artifact_key IS NOT NULL
                    """, (rs, row) -> new CandidateDataWithBot(rs.getLong("bot_id"), rs.getLong("owner_id"),
                    rs.getLong("submission_id"), rs.getInt("elo")), seasonId);

            for (CandidateDataWithBot data : dbBots) {
                if (hasReachedThrottle(seasonId, data.botId())) continue;
                Set<Long> recent = jdbc.query("""
                        SELECT CASE WHEN bot_a_id=? THEN bot_b_id ELSE bot_a_id END
                        FROM ladder_series WHERE season_id=? AND (bot_a_id=? OR bot_b_id=?)
                        ORDER BY created_at DESC LIMIT 3
                        """, (rs, row) -> rs.getLong(1), data.botId(), seasonId, data.botId(), data.botId())
                        .stream().collect(java.util.stream.Collectors.toSet());
                candidates.add(new Candidate(data.botId(), data.ownerId(), data.submissionId(), data.elo(), now, recent));
                if (redis != null) {
                    try {
                        redis.opsForZSet().add(LadderKeys.ratings(seasonId), Long.toString(data.botId()), data.elo());
                    } catch (Exception ignored) {}
                }
            }
        }
        return candidates;
    }

    private CandidateData candidateData(long seasonId, long botId, long submissionId) {
        List<CandidateData> rows = jdbc.query("""
                SELECT s.id AS submission_id, COALESCE(r.elo, 1200) AS elo, b.owner_id
                FROM bots b JOIN submissions s ON s.bot_id=b.id
                LEFT JOIN bot_ratings r ON r.bot_id=b.id AND r.season_id=?
                WHERE b.id=? AND b.ladder_enabled=TRUE AND b.active_submission_id IS NOT NULL
                  AND (b.deleted IS FALSE OR b.deleted IS NULL)
                  AND s.id=? AND s.status IN ('COMPILED','ACTIVE')
                  AND s.artifact_key IS NOT NULL
                """, (rs, row) -> new CandidateData(rs.getLong(1), rs.getInt(2), rs.getLong(3)), seasonId, botId, submissionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record CandidateDataWithBot(long botId, long ownerId, long submissionId, int elo) { }

    private boolean hasReachedThrottle(long seasonId, long botId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ladder_series WHERE season_id=? AND (bot_a_id=? OR bot_b_id=?)
                  AND created_at >= now() - interval '1 minute'
                """, Integer.class, seasonId, botId, botId);
        return count != null && count * 2 >= maxSeriesPerMinute;
    }

    private void enqueueSeries(long seasonId, Pair pair) {
        long botA = pair.a().botId();
        long botB = pair.b().botId();
        int ratingA = pair.a().elo();
        int ratingB = pair.b().elo();
        jdbc.update("INSERT INTO bot_ratings(bot_id, season_id) VALUES (?, ?), (?, ?) ON CONFLICT DO NOTHING",
                botA, seasonId, botB, seasonId);
        long seed = random.nextLong();
        Long seriesId = jdbc.queryForObject("""
                INSERT INTO ladder_series(season_id, bot_a_id, bot_b_id, submission_a_id, submission_b_id, seed)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, seasonId, botA, botB, pair.a().submissionId(), pair.b().submissionId(), seed);
        Participant a = participant(botA, pair.a().submissionId());
        Participant b = participant(botB, pair.b().submissionId());
        createGame(seriesId, seasonId, 1, seed, a, b);
        createGame(seriesId, seasonId, 2, seed, b, a);
        log.debug("Queued ladder series {} for bots {} ({} Elo) and {} ({} Elo)", seriesId, botA, ratingA, botB, ratingB);
    }

    private Participant participant(long botId, long submissionId) {
        return jdbc.queryForObject("""
                SELECT b.id AS bot_id, b.name, b.owner_id, s.id AS submission_id, s.version_no, s.language, s.artifact_key, s.source_sha256
                FROM bots b JOIN submissions s ON s.bot_id=b.id
                WHERE b.id=? AND s.id=? AND s.status IN ('COMPILED','ACTIVE') AND s.artifact_key IS NOT NULL
                """, (rs, row) -> new Participant(rs.getLong("bot_id"), rs.getString("name"), rs.getLong("owner_id"),
                rs.getLong("submission_id"), rs.getInt("version_no"), rs.getString("language"), rs.getString("artifact_key"),
                rs.getString("source_sha256").trim()), botId, submissionId);
    }

    private void createGame(long seriesId, long seasonId, int gameNo, long seed, Participant a, Participant b) {
        Long matchId = jdbc.queryForObject("""
                INSERT INTO matches(season_id, match_type, seed, map_config, engine_ver, status, ladder_series_id, series_game_no)
                VALUES (?, 'LADDER', ?, ?::jsonb, '1', 'READY', ?, ?)
                RETURNING id
                """, Long.class, seasonId, seed, writeJson(MAP), seriesId, gameNo);
        MatchKey key = jdbc.queryForObject("SELECT public_id, created_month FROM matches WHERE id=?",
                (rs, row) -> new MatchKey(rs.getObject(1, java.util.UUID.class), rs.getDate(2).toLocalDate()), matchId);
        jdbc.update("""
                INSERT INTO match_participants(match_id, created_month, side, bot_id, submission_id, elo_before, source_sha256, version_no)
                VALUES (?, ?, 'A', ?, ?, (SELECT elo FROM bot_ratings WHERE bot_id=? AND season_id=?), ?, ?),
                       (?, ?, 'B', ?, ?, (SELECT elo FROM bot_ratings WHERE bot_id=? AND season_id=?), ?, ?)
                """, matchId, Date.valueOf(key.month()), a.botId(), a.submissionId(), a.botId(), seasonId, a.sha256(), a.versionNo(),
                matchId, Date.valueOf(key.month()), b.botId(), b.submissionId(), b.botId(), seasonId, b.sha256(), b.versionNo());
        MatchJob job = new MatchJob("MATCH_JOB", matchId, key.publicId().toString(), key.month().toString(), seed, MAP,
                a.toJob(), b.toJob());
        jdbc.update("INSERT INTO outbox_events(topic, payload) VALUES ('match.ladder.q', ?::jsonb)", writeJson(job));

        // Bắn sự kiện MATCH_FOUND qua STOMP cho cả 2 người chơi khi trận đấu được ghép thành công
        if (messagingTemplate != null && gameNo == 1) {
            broadcastMatchFound(seasonId, seriesId, matchId, key.publicId().toString(), a, b);
        }
    }

    private void broadcastMatchFound(long seasonId, long seriesId, long matchId, String publicId, Participant a, Participant b) {
        try {
            java.util.Map<String, Object> userA = fetchUserOwner(a.ownerId(), a.botId(), seasonId);
            java.util.Map<String, Object> userB = fetchUserOwner(b.ownerId(), b.botId(), seasonId);

            long startsAt = System.currentTimeMillis() + 3000L;
            java.util.Map<String, Object> event = new java.util.HashMap<>();
            event.put("type", "MATCH_FOUND");
            event.put("mode", "BOT");
            event.put("matchId", matchId);
            event.put("matchPublicId", publicId);
            event.put("seriesId", seriesId);
            event.put("startsAt", startsAt);
            event.put("countdownSeconds", 3);

            java.util.Map<String, Object> sideA = new java.util.HashMap<>();
            sideA.put("side", "A");
            sideA.put("userId", a.ownerId());
            sideA.put("username", userA.get("username"));
            sideA.put("displayName", userA.get("displayName"));
            sideA.put("botId", a.botId());
            sideA.put("botName", a.name());
            sideA.put("name", a.name() + " (" + userA.get("displayName") + ")");
            sideA.put("elo", userA.get("elo"));
            sideA.put("avatar", "https://api.dicebear.com/7.x/bottts/svg?seed=" + a.name());
            event.put("sideA", sideA);

            java.util.Map<String, Object> sideB = new java.util.HashMap<>();
            sideB.put("side", "B");
            sideB.put("userId", b.ownerId());
            sideB.put("username", userB.get("username"));
            sideB.put("displayName", userB.get("displayName"));
            sideB.put("botId", b.botId());
            sideB.put("botName", b.name());
            sideB.put("name", b.name() + " (" + userB.get("displayName") + ")");
            sideB.put("elo", userB.get("elo"));
            sideB.put("avatar", "https://api.dicebear.com/7.x/bottts/svg?seed=" + b.name());
            event.put("sideB", sideB);

            sendToUser(userA, event);
            sendToUser(userB, event);
            log.info("Ladder MATCH_FOUND broadcasted: Bot {} vs Bot {}, Series={}, MatchId={}", a.name(), b.name(), seriesId, matchId);
        } catch (Exception e) {
            log.warn("Lỗi khi bắn STOMP MATCH_FOUND từ LadderScheduler: {}", e.getMessage());
        }
    }

    private java.util.Map<String, Object> fetchUserOwner(long ownerId, long botId, long seasonId) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        try {
            jdbc.query("SELECT student_code, email, display_name FROM users WHERE id = ?", rs -> {
                String sc = rs.getString("student_code");
                String em = rs.getString("email");
                String dn = rs.getString("display_name");
                map.put("studentCode", sc);
                map.put("email", em);
                map.put("displayName", dn != null && !dn.isBlank() ? dn : (sc != null ? sc : "Người chơi"));
                map.put("username", sc != null && !sc.isBlank() ? sc : em);
            }, ownerId);
        } catch (Exception ignored) {}

        try {
            Integer elo = jdbc.queryForObject("SELECT elo FROM bot_ratings WHERE bot_id = ? AND season_id = ?",
                    Integer.class, botId, seasonId);
            map.put("elo", elo != null ? elo : 1200);
        } catch (Exception e) {
            map.put("elo", 1200);
        }
        if (!map.containsKey("username")) {
            map.put("username", "user_" + ownerId);
            map.put("displayName", "Người chơi " + ownerId);
        }
        return map;
    }

    private void sendToUser(java.util.Map<String, Object> user, java.util.Map<String, Object> event) {
        if (messagingTemplate == null || user == null) return;
        String username = (String) user.get("username");
        String studentCode = (String) user.get("studentCode");
        String email = (String) user.get("email");

        if (username != null && !username.isBlank()) {
            messagingTemplate.convertAndSend("/topic/user/" + username, (Object) event);
        }
        if (studentCode != null && !studentCode.equalsIgnoreCase(username)) {
            messagingTemplate.convertAndSend("/topic/user/" + studentCode, (Object) event);
        }
        if (email != null) {
            String prefix = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            if (!prefix.equalsIgnoreCase(username) && !prefix.equalsIgnoreCase(studentCode)) {
                messagingTemplate.convertAndSend("/topic/user/" + prefix, (Object) event);
            }
        }
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not encode ladder match job", e); }
    }

    private record Window(int dayOfWeek, LocalTime start, LocalTime end, ZoneId zone) { }
    private record CandidateData(long submissionId, int elo, long ownerId) { }
    private record MatchKey(java.util.UUID publicId, LocalDate month) { }
    private record Participant(long botId, String name, long ownerId, long submissionId, int versionNo,
                               String language, String artifactKey, String sha256) {
        MatchJob.ParticipantJob toJob() {
            return new MatchJob.ParticipantJob(botId, name, ownerId, submissionId, language, artifactKey, sha256);
        }
    }
}
