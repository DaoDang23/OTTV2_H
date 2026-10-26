package ottv2.api.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ottv2.api.match.MatchJob;
import ottv2.api.ladder.LadderRatingService;
import ottv2.api.tournament.TournamentResultService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MatchResultConsumer {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final LadderRatingService ladderRatings;
    private final TournamentResultService tournamentResults;
    private final SimpMessagingTemplate messagingTemplate;

    public MatchResultConsumer(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper,
                               LadderRatingService ladderRatings, TournamentResultService tournamentResults) {
        this(jdbc, transaction, mapper, ladderRatings, tournamentResults, null);
    }

    @Autowired
    public MatchResultConsumer(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper,
                               LadderRatingService ladderRatings, TournamentResultService tournamentResults,
                               @Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
        this.ladderRatings = ladderRatings;
        this.tournamentResults = tournamentResults;
        this.messagingTemplate = messagingTemplate;
    }

    public void consume(byte[] body) throws Exception {
        MatchEvent event = mapper.readValue(body, MatchEvent.class);
        if (!"MATCH_STARTED".equals(event.type()) && !"MATCH_FINISHED".equals(event.type())
                && !"MATCH_FAILED".equals(event.type())) {
            throw new IllegalArgumentException("Unsupported match event type: " + event.type());
        }
        Date month = Date.valueOf(event.createdMonth());
        if ("MATCH_STARTED".equals(event.type())) {
            jdbc.update("""
                    UPDATE matches SET status='RUNNING', started_at=COALESCE(started_at, now()), worker_id=?
                    WHERE id=? AND created_month=? AND status IN ('QUEUED','READY','STARTING','RUNNING')
                    """, event.workerId(), event.matchId(), month);
            broadcastMatchStarted(event);
            return;
        }
        if (event.type().equals("MATCH_FAILED")) {
            transaction.executeWithoutResult(status -> {
                int changed = jdbc.update("""
                        UPDATE matches SET status='ERROR', end_reason='WORKER_ERROR', failure_detail=?, finished_at=now()
                        WHERE id=? AND created_month=? AND status IN ('QUEUED','READY','STARTING','RUNNING')
                        """, safeText(event.error(), 4096), event.matchId(), month);
                if (changed == 0) return;
                jdbc.update("UPDATE match_participants SET fault='INFRA_ERROR' WHERE match_id=? AND created_month=?",
                        event.matchId(), month);
                tournamentResults.recordFailedGame(event.matchId(), month, event.error());
            });
            return;
        }
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE matches SET status='FINISHED', winner_side=?, end_reason=?, total_turns=?, finished_at=now()
                    WHERE id=? AND created_month=? AND status IN ('QUEUED','READY','STARTING','RUNNING')
                    """, event.winnerSide(), safeText(event.endReason(), 24), event.totalTurns(), event.matchId(), month);
            if (changed == 0) return; // duplicate delivery or late result after the match was finalized
            jdbc.update("""
                    UPDATE match_participants SET fault=CASE side WHEN 'A' THEN ? ELSE ? END,
                        cpu_time_ms=CASE side WHEN 'A' THEN ?::int ELSE ?::int END,
                        peak_mem_kb=CASE side WHEN 'A' THEN ?::int ELSE ?::int END
                    WHERE match_id=? AND created_month=?
                    """, event.faultA(), event.faultB(), event.cpuTimeMsA(), event.cpuTimeMsB(),
                    event.peakMemKbA(), event.peakMemKbB(), event.matchId(), month);
            if (event.replayKey() != null) {
                jdbc.update("""
                        INSERT INTO replays(match_id, created_month, storage_key, size_bytes, bot_a_log_key, bot_b_log_key)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (match_id, created_month) DO UPDATE SET storage_key=EXCLUDED.storage_key,
                            size_bytes=EXCLUDED.size_bytes, bot_a_log_key=EXCLUDED.bot_a_log_key,
                            bot_b_log_key=EXCLUDED.bot_b_log_key
                        """, event.matchId(), month, event.replayKey(), event.replaySizeBytes(),
                        event.botALogKey(), event.botBLogKey());
            }
            if (event.replayData() != null) {
                try {
                    jdbc.update("""
                            INSERT INTO replays(match_id, created_month, storage_key, size_bytes, replay_data)
                            VALUES (?, ?, 'db://replay', 0, ?::jsonb)
                            ON CONFLICT (match_id, created_month) DO UPDATE SET replay_data=EXCLUDED.replay_data
                            """, event.matchId(), month, event.replayData());
                } catch (Exception ignored) {}
            }

            String matchType = "LADDER";
            try {
                String fetched = jdbc.queryForObject("SELECT match_type FROM matches WHERE id=?", String.class, event.matchId());
                if (fetched != null && !fetched.isBlank()) {
                    matchType = fetched.toUpperCase().trim();
                }
            } catch (Exception ignored) {}

            boolean isRanked = "LADDER".equalsIgnoreCase(matchType);
            boolean isInfraError = "WORKER_ERROR".equalsIgnoreCase(event.endReason())
                    || "INFRA_ERROR".equalsIgnoreCase(event.endReason())
                    || "CANCELLED".equalsIgnoreCase(event.endReason());
            if (isRanked && !isInfraError) {
                ladderRatings.recordFinishedGame(event.matchId(), month, event.winnerSide());
            }
            if ("TOURNAMENT".equalsIgnoreCase(matchType)) {
                tournamentResults.recordFinishedGame(event.matchId(), month);
            }
            broadcastMatchFinished(event, month, matchType, isRanked);
        });
    }

    private void broadcastMatchStarted(MatchEvent event) {
        if (messagingTemplate == null) return;
        try {
            UUID publicId = null;
            try {
                publicId = jdbc.queryForObject("SELECT public_id FROM matches WHERE id=?", UUID.class, event.matchId());
            } catch (Exception ignored) {}

            Map<String, Object> startedPayload = new HashMap<>();
            startedPayload.put("type", "MATCH_STARTED");
            startedPayload.put("mode", "BOT");
            startedPayload.put("matchId", event.matchId());
            startedPayload.put("matchPublicId", publicId != null ? publicId.toString() : null);

            if (publicId != null) {
                messagingTemplate.convertAndSend("/topic/match/" + publicId, (Object) startedPayload);
            }
        } catch (Exception ignored) {}
    }

    private void broadcastMatchFinished(MatchEvent event, Date month, String matchType, boolean isRanked) {
        if (messagingTemplate == null) return;
        try {
            UUID publicId = null;
            try {
                publicId = jdbc.queryForObject("SELECT public_id FROM matches WHERE id=?", UUID.class, event.matchId());
            } catch (Exception ignored) {}

            List<Map<String, Object>> participants = jdbc.query("""
                    SELECT p.side, p.bot_id, b.name AS bot_name, b.owner_id,
                           u.student_code, u.email, u.display_name,
                           p.elo_before, COALESCE(br.elo, p.elo_before) AS elo_after
                    FROM match_participants p
                    JOIN bots b ON b.id = p.bot_id
                    JOIN users u ON u.id = b.owner_id
                    LEFT JOIN bot_ratings br ON br.bot_id = p.bot_id AND br.season_id = (SELECT season_id FROM matches WHERE id = p.match_id)
                    WHERE p.match_id = ?
                    """, (rs, rowNum) -> {
                Map<String, Object> map = new HashMap<>();
                map.put("side", rs.getString("side"));
                map.put("botId", rs.getLong("bot_id"));
                map.put("botName", rs.getString("bot_name"));
                map.put("ownerId", rs.getLong("owner_id"));
                String sc = rs.getString("student_code");
                String em = rs.getString("email");
                map.put("studentCode", sc);
                map.put("email", em);
                map.put("username", sc != null && !sc.isBlank() ? sc : em);
                map.put("displayName", rs.getString("display_name"));
                int before = rs.getObject("elo_before") != null ? rs.getInt("elo_before")
                        : (rs.getObject("elo_after") != null ? rs.getInt("elo_after") : 1200);
                int after = isRanked ? (rs.getObject("elo_after") != null ? rs.getInt("elo_after") : before) : before;
                map.put("eloBefore", before);
                map.put("eloAfter", after);
                map.put("eloChange", isRanked ? (after - before) : 0);
                return map;
            }, event.matchId());

            Map<String, Object> sideA = null;
            Map<String, Object> sideB = null;
            for (Map<String, Object> p : participants) {
                if ("A".equals(p.get("side"))) sideA = p;
                else if ("B".equals(p.get("side"))) sideB = p;
            }

            Map<String, Object> finishedPayload = new HashMap<>();
            finishedPayload.put("type", "MATCH_FINISHED");
            finishedPayload.put("mode", "TOURNAMENT".equalsIgnoreCase(matchType) ? "TOURNAMENT" : "BOT");
            finishedPayload.put("matchType", matchType != null ? matchType : "LADDER");
            finishedPayload.put("isRanked", isRanked);
            finishedPayload.put("matchId", event.matchId());
            finishedPayload.put("matchPublicId", publicId != null ? publicId.toString() : null);
            finishedPayload.put("winnerSide", event.winnerSide());
            finishedPayload.put("endReason", event.endReason());
            finishedPayload.put("totalTurns", event.totalTurns());
            finishedPayload.put("replayKey", event.replayKey());
            finishedPayload.put("sideA", sideA);
            finishedPayload.put("sideB", sideB);

            for (Map<String, Object> p : participants) {
                sendToUser(p, finishedPayload);
            }

            if (publicId != null) {
                messagingTemplate.convertAndSend("/topic/match/" + publicId, (Object) finishedPayload);
            }
        } catch (Exception ignored) {}
    }

    private void sendToUser(Map<String, Object> user, Map<String, Object> event) {
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

    private static String safeText(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    public record MatchEvent(String type, long matchId, String createdMonth, String workerId,
                             String winnerSide, String endReason, Integer totalTurns,
                             String faultA, String faultB, Integer cpuTimeMsA, Integer cpuTimeMsB,
                             Integer peakMemKbA, Integer peakMemKbB, String replayKey, Integer replaySizeBytes,
                             String botALogKey, String botBLogKey, String error, String replayData) {
        public MatchEvent(String type, long matchId, String createdMonth, String workerId,
                          String winnerSide, String endReason, Integer totalTurns,
                          String faultA, String faultB, Integer cpuTimeMsA, Integer cpuTimeMsB,
                          Integer peakMemKbA, Integer peakMemKbB, String replayKey, Integer replaySizeBytes,
                          String botALogKey, String botBLogKey, String error) {
            this(type, matchId, createdMonth, workerId, winnerSide, endReason, totalTurns,
                 faultA, faultB, cpuTimeMsA, cpuTimeMsB, peakMemKbA, peakMemKbB,
                 replayKey, replaySizeBytes, botALogKey, botBLogKey, error, null);
        }
    }
}
