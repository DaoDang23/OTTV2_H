package ottv2.api.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ottv2.api.ladder.RatingCalculator;
import ottv2.api.match.MatchDtos.CreateMatchRequest;
import ottv2.api.match.MatchDtos.FinishPvpMatchRequest;
import ottv2.api.match.MatchDtos.MatchResponse;
import ottv2.api.match.MatchDtos.Participant;
import ottv2.api.match.MatchDtos.PlayableSubmission;
import ottv2.api.match.MatchDtos.PvpMatchFinishResponse;
import ottv2.api.submission.SubmissionDtos.Language;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MatchService {
    private static final MatchJob.MapConfiguration DEFAULT_MAP = new MatchJob.MapConfiguration(8, 0, 0, 0);
    private static final String MATCH_SELECT = """
            SELECT m.public_id, m.status, m.seed, m.winner_side, m.end_reason, m.total_turns,
                   m.queued_at, m.finished_at, m.created_month,
                   a.bot_id AS a_bot_id, ba.name AS a_bot_name, a.submission_id AS a_submission_id, a.fault AS a_fault,
                   a.source_sha256 AS a_sha, a.version_no AS a_ver, ba.owner_id AS a_owner_id,
                   a.elo_before AS a_elo_before, a.elo_after AS a_elo_after,
                   b.bot_id AS b_bot_id, bb.name AS b_bot_name, b.submission_id AS b_submission_id, b.fault AS b_fault,
                   b.source_sha256 AS b_sha, b.version_no AS b_ver, bb.owner_id AS b_owner_id,
                   b.elo_before AS b_elo_before, b.elo_after AS b_elo_after
            FROM matches m
            JOIN match_participants a ON a.match_id=m.id AND a.created_month=m.created_month AND a.side='A'
            JOIN bots ba ON ba.id=a.bot_id
            JOIN match_participants b ON b.match_id=m.id AND b.created_month=m.created_month AND b.side='B'
            JOIN bots bb ON bb.id=b.bot_id
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final PvpMatchValidator pvpMatchValidator;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public MatchService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper,
                        @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
                        @Autowired(required = false) PvpMatchValidator pvpMatchValidator) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
        this.messagingTemplate = messagingTemplate;
        this.pvpMatchValidator = pvpMatchValidator != null ? pvpMatchValidator : new PvpMatchValidator(mapper);
    }

    public MatchService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper) {
        this(jdbc, transaction, mapper, null, null);
    }

    public MatchResponse create(long ownerId, CreateMatchRequest request) {
        return transaction.execute(status -> {
            ParticipantJobData sideA = readySubmission(request.submissionAId());
            ParticipantJobData sideB = readySubmission(request.submissionBId());
            if (sideA.botId() == sideB.botId()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A bot cannot play against itself");
            }
            if (sideA.ownerId() != ownerId && sideB.ownerId() != ownerId) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "At least one submission must belong to you");
            }

            long seed = random.nextLong();
            String mapJson = writeJson(DEFAULT_MAP);
            MatchInsert inserted = jdbc.queryForObject("""
                    INSERT INTO matches(match_type, seed, map_config, engine_ver, status)
                    VALUES ('FRIENDLY', ?, ?::jsonb, '1', 'QUEUED')
                    RETURNING id, public_id, created_month, queued_at
                    """, (rs, row) -> new MatchInsert(rs.getLong("id"), rs.getObject("public_id", UUID.class),
                    rs.getDate("created_month").toLocalDate(), rs.getTimestamp("queued_at").toInstant()), seed, mapJson);

            jdbc.update("""
                    INSERT INTO match_participants(match_id, created_month, side, bot_id, submission_id, source_sha256, version_no)
                    VALUES (?, ?, 'A', ?, ?, ?, ?), (?, ?, 'B', ?, ?, ?, ?)
                    """, inserted.id(), Date.valueOf(inserted.createdMonth()), sideA.botId(), sideA.submissionId(), sideA.sourceSha256(), sideA.versionNo(),
                    inserted.id(), Date.valueOf(inserted.createdMonth()), sideB.botId(), sideB.submissionId(), sideB.sourceSha256(), sideB.versionNo());

            MatchJob job = new MatchJob("MATCH_JOB", inserted.id(), inserted.publicId().toString(),
                    inserted.createdMonth().toString(), seed, DEFAULT_MAP,
                    sideA.toJob(), sideB.toJob());
            jdbc.update("INSERT INTO outbox_events(topic, payload) VALUES ('match.ladder.q', ?::jsonb)", writeJson(job));
            return response(inserted.id(), inserted.createdMonth());
        });
    }

    public List<MatchResponse> list(long ownerId) {
        return jdbc.query(MATCH_SELECT + """
                WHERE EXISTS (SELECT 1 FROM match_participants mine JOIN bots owned ON owned.id=mine.bot_id
                              WHERE mine.match_id=m.id AND mine.created_month=m.created_month AND owned.owner_id=?)
                ORDER BY m.queued_at DESC LIMIT 100
                """, (rs, row) -> map(rs), ownerId);
    }

    public List<MatchResponse> listLadder(long ownerId) {
        return jdbc.query(MATCH_SELECT + """
                WHERE m.match_type='LADDER' AND EXISTS (
                  SELECT 1 FROM match_participants mine JOIN bots owned ON owned.id=mine.bot_id
                  WHERE mine.match_id=m.id AND mine.created_month=m.created_month AND owned.owner_id=?
                )
                ORDER BY m.queued_at DESC LIMIT 100
                """, (rs, row) -> map(rs), ownerId);
    }

    public List<PlayableSubmission> playableSubmissions() {
        return jdbc.query("""
                SELECT s.id, b.id AS bot_id, b.name AS bot_name, s.version_no, s.language, s.status
                FROM submissions s JOIN bots b ON b.id=s.bot_id
                WHERE s.status IN ('COMPILED','ACTIVE') AND s.artifact_key IS NOT NULL AND b.ladder_enabled=TRUE
                ORDER BY b.name, s.version_no DESC LIMIT 500
                """, (rs, row) -> new PlayableSubmission(rs.getLong("id"), rs.getLong("bot_id"),
                rs.getString("bot_name"), rs.getInt("version_no"), rs.getString("language"), rs.getString("status")));
    }

    public MatchResponse get(long ownerId, UUID publicId) {
        try {
            return jdbc.queryForObject(MATCH_SELECT + """
                    WHERE m.public_id=? AND EXISTS (
                      SELECT 1 FROM match_participants mine JOIN bots owned ON owned.id=mine.bot_id
                      WHERE mine.match_id=m.id AND mine.created_month=m.created_month AND owned.owner_id=?)
                    """, (rs, row) -> map(rs), publicId, ownerId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found");
        }
    }

    public record ReplayInfo(String storageKey, String replayJson) { }

    public ReplayInfo getReplayInfo(String matchIdentifier) {
        if (matchIdentifier == null || matchIdentifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dữ liệu Replay chưa sẵn sàng hoặc đã bị xóa");
        }
        String sql = """
                SELECT r.storage_key, r.replay_data::text AS replay_json
                FROM matches m
                LEFT JOIN replays r ON r.match_id = m.id AND r.created_month = m.created_month
                WHERE (m.public_id::text = ? OR m.id::text = ?)
                LIMIT 1
                """;
        List<ReplayInfo> list = jdbc.query(sql, (rs, rowNum) ->
                new ReplayInfo(rs.getString("storage_key"), rs.getString("replay_json")),
                matchIdentifier.trim(), matchIdentifier.trim());
        if (!list.isEmpty() && (list.get(0).storageKey() != null || list.get(0).replayJson() != null)) {
            return list.get(0);
        }

        // Fallback: Kiểm tra replay trong bảng user_matches theo replay_id hoặc id
        try {
            String pvpSql = """
                    SELECT replay_id, replay_data::text AS replay_json
                    FROM user_matches
                    WHERE (replay_id = ? OR id::text = ?) AND replay_data IS NOT NULL
                    ORDER BY id DESC LIMIT 1
                    """;
            List<ReplayInfo> pvpList = jdbc.query(pvpSql, (rs, rowNum) ->
                    new ReplayInfo(rs.getString("replay_id"), rs.getString("replay_json")),
                    matchIdentifier.trim(), matchIdentifier.trim());
            if (!pvpList.isEmpty() && pvpList.get(0).replayJson() != null) {
                return pvpList.get(0);
            }
        } catch (Exception ignored) { }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dữ liệu Replay chưa sẵn sàng hoặc đã bị xóa");
    }

    public String replayKey(long ownerId, UUID publicId) {
        return privateObjectKey(ownerId, publicId, null);
    }

    public String botLogKey(long ownerId, UUID publicId, String side) {
        if (!"A".equals(side) && !"B".equals(side)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Side must be A or B");
        }
        return privateObjectKey(ownerId, publicId, side);
    }

    private String privateObjectKey(long ownerId, UUID publicId, String side) {
        String sql = """
                SELECT r.storage_key, r.bot_a_log_key, r.bot_b_log_key,
                    EXISTS (SELECT 1 FROM match_participants p JOIN bots b ON b.id=p.bot_id
                            WHERE p.match_id=m.id AND p.created_month=m.created_month AND p.side='A' AND b.owner_id=?) AS owns_a,
                    EXISTS (SELECT 1 FROM match_participants p JOIN bots b ON b.id=p.bot_id
                            WHERE p.match_id=m.id AND p.created_month=m.created_month AND p.side='B' AND b.owner_id=?) AS owns_b
                FROM matches m JOIN replays r ON r.match_id=m.id AND r.created_month=m.created_month
                WHERE m.public_id=? AND EXISTS (
                  SELECT 1 FROM match_participants p JOIN bots b ON b.id=p.bot_id
                  WHERE p.match_id=m.id AND p.created_month=m.created_month AND b.owner_id=?
                )
                """;
        try {
            return jdbc.queryForObject(sql, (rs, row) -> {
                if (side == null) return rs.getString("storage_key");
                if (!rs.getBoolean("owns_" + side.toLowerCase())) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot log not found");
                }
                String key = rs.getString("bot_" + side.toLowerCase() + "_log_key");
                if (key == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot log not found");
                return key;
            }, ownerId, ownerId, publicId, ownerId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Replay or log not found");
        }
    }

    private ParticipantJobData readySubmission(long submissionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT b.id AS bot_id, b.name, b.owner_id, s.id AS submission_id, s.version_no,
                           s.language, s.artifact_key, s.source_sha256
                    FROM submissions s JOIN bots b ON b.id=s.bot_id
                    WHERE s.id=? AND s.status IN ('COMPILED','ACTIVE') AND s.artifact_key IS NOT NULL
                      AND b.ladder_enabled=TRUE
                    """, (rs, row) -> new ParticipantJobData(rs.getLong("bot_id"), rs.getString("name"),
                    rs.getLong("owner_id"), rs.getLong("submission_id"), rs.getInt("version_no"), Language.valueOf(rs.getString("language")),
                    rs.getString("artifact_key"), rs.getString("source_sha256").trim()), submissionId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both submissions must be compiled and playable");
        }
    }

    public PvpMatchFinishResponse finishPvpMatch(long currentUserId, String matchIdentifier, FinishPvpMatchRequest request) {
        return transaction.execute(status -> {
            // 1. Query match from matches table
            List<Map<String, Object>> matchRows = jdbc.queryForList("""
                    SELECT id, public_id, match_type, status, user_a_id, user_b_id, room_code, winner_side, end_reason
                    FROM matches
                    WHERE (public_id::text = ? OR id::text = ? OR room_code = ?)
                    ORDER BY id DESC
                    LIMIT 1
                    FOR UPDATE
                    """, matchIdentifier.trim(), matchIdentifier.trim(), matchIdentifier.trim());
            if (matchRows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy trận đấu: " + matchIdentifier);
            }
            Map<String, Object> match = matchRows.get(0);
            Long matchId = ((Number) match.get("id")).longValue();
            UUID publicId = (UUID) match.get("public_id");
            String matchStatus = (String) match.get("status");
            String matchType = (String) match.get("match_type");
            Long userAId = match.get("user_a_id") != null ? ((Number) match.get("user_a_id")).longValue() : null;
            Long userBId = match.get("user_b_id") != null ? ((Number) match.get("user_b_id")).longValue() : null;
            String roomCode = (String) match.get("room_code");

            // 2. Validate participants
            if (userAId == null || userBId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trận đấu không có thông tin người chơi hợp lệ!");
            }
            if (currentUserId != userAId && currentUserId != userBId) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không phải là người chơi tham gia trận đấu này!");
            }

            // 3. Idempotency: If already FINISHED, return existing result without re-calculating ELO
            if ("FINISHED".equalsIgnoreCase(matchStatus)) {
                List<Map<String, Object>> existingUserMatch = jdbc.queryForList("""
                        SELECT elo_change, new_elo, is_ranked, winner, reason
                        FROM user_matches
                        WHERE user_id = ? AND replay_id = ?
                        ORDER BY id DESC LIMIT 1
                        """, currentUserId, publicId.toString());
                int eloChange = 0;
                int newElo = 1200;
                boolean isRanked = false;
                if (!existingUserMatch.isEmpty()) {
                    eloChange = ((Number) existingUserMatch.get(0).get("elo_change")).intValue();
                    newElo = ((Number) existingUserMatch.get(0).get("new_elo")).intValue();
                    isRanked = Boolean.TRUE.equals(existingUserMatch.get(0).get("is_ranked"));
                } else {
                    Integer dbElo = jdbc.queryForObject("SELECT pvp_elo FROM users WHERE id = ?", Integer.class, currentUserId);
                    if (dbElo != null) newElo = dbElo;
                }
                int oldElo = newElo - eloChange;
                return new PvpMatchFinishResponse(publicId.toString(), "FINISHED",
                        (String) match.get("winner_side"), (String) match.get("end_reason"),
                        eloChange, newElo, isRanked, oldElo, null, 1200, 0, 1200);
            }

            // 4. Query current Elo and user details from users table with row-level locks
            Map<String, Object> userARow = jdbc.queryForMap("""
                    SELECT id, display_name, student_code, email, COALESCE(pvp_elo, 1200) AS pvp_elo
                    FROM users WHERE id = ? FOR UPDATE
                    """, userAId);
            Map<String, Object> userBRow = jdbc.queryForMap("""
                    SELECT id, display_name, student_code, email, COALESCE(pvp_elo, 1200) AS pvp_elo
                    FROM users WHERE id = ? FOR UPDATE
                    """, userBId);

            int eloA = ((Number) userARow.get("pvp_elo")).intValue();
            int eloB = ((Number) userBRow.get("pvp_elo")).intValue();
            String nameA = (String) userARow.get("display_name");
            if (nameA == null || nameA.isBlank()) nameA = (String) userARow.get("student_code");
            if (nameA == null || nameA.isBlank()) nameA = "Người chơi A";

            String nameB = (String) userBRow.get("display_name");
            if (nameB == null || nameB.isBlank()) nameB = (String) userBRow.get("student_code");
            if (nameB == null || nameB.isBlank()) nameB = "Người chơi B";

            // Authoritative PvP validation: Server determines winnerSide and verifies moves / resignation
            var validated = pvpMatchValidator.validate(
                    currentUserId,
                    userAId,
                    userBId,
                    request != null ? request.winnerSide() : null,
                    request != null ? request.reason() : null,
                    request != null ? request.replayData() : null
            );
            String winnerSide = validated.winnerSide();
            String reason = validated.reason();

            boolean isRanked = "PVP".equalsIgnoreCase(matchType);
            int newEloA = eloA;
            int newEloB = eloB;
            int changeA = 0;
            int changeB = 0;

            if (isRanked) {
                // Đếm số trận PvP xếp hạng hợp lệ đã hoàn thành trước trận này của từng người chơi để áp dụng K-Factor động
                Integer countAObj = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_matches WHERE user_id = ? AND is_ranked = TRUE AND UPPER(mode) = 'PVP'",
                        Integer.class, userAId);
                Integer countBObj = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_matches WHERE user_id = ? AND is_ranked = TRUE AND UPPER(mode) = 'PVP'",
                        Integer.class, userBId);
                int countA = countAObj != null ? countAObj : 0;
                int countB = countBObj != null ? countBObj : 0;

                int kA = determinePvpKFactor(countA);
                int kB = determinePvpKFactor(countB);

                double expectedA = 1.0 / (1.0 + Math.pow(10.0, (eloB - eloA) / 400.0));
                double expectedB = 1.0 - expectedA;

                if ("A".equals(winnerSide)) {
                    // Người thắng A nhận thưởng Elo chuẩn theo K_A
                    changeA = (int) Math.round(kA * (1.0 - expectedA));
                    newEloA = Math.max(1100, eloA + changeA);
                    changeA = newEloA - eloA;

                    // Người thua B chỉ chịu 75% mức phạt Elo chuẩn theo K_B
                    int rawLossB = - (int) Math.round(0.75 * kB * expectedB);
                    newEloB = Math.max(1100, eloB + rawLossB);
                    changeB = newEloB - eloB;
                } else if ("B".equals(winnerSide)) {
                    // Người thắng B nhận thưởng Elo chuẩn theo K_B
                    changeB = (int) Math.round(kB * (1.0 - expectedB));
                    newEloB = Math.max(1100, eloB + changeB);
                    changeB = newEloB - eloB;

                    // Người thua A chỉ chịu 75% mức phạt Elo chuẩn theo K_A
                    int rawLossA = - (int) Math.round(0.75 * kA * expectedA);
                    newEloA = Math.max(1100, eloA + rawLossA);
                    changeA = newEloA - eloA;
                } else {
                    // Hòa (DRAW)
                    int rawChangeA = (int) Math.round(kA * (0.5 - expectedA));
                    newEloA = Math.max(1100, eloA + rawChangeA);
                    changeA = newEloA - eloA;

                    int rawChangeB = (int) Math.round(kB * (0.5 - expectedB));
                    newEloB = Math.max(1100, eloB + rawChangeB);
                    changeB = newEloB - eloB;
                }

                // Update users table atomically
                jdbc.update("UPDATE users SET pvp_elo = ? WHERE id = ?", newEloA, userAId);
                jdbc.update("UPDATE users SET pvp_elo = ? WHERE id = ?", newEloB, userBId);
            }

            // 5. Update matches table
            jdbc.update("""
                    UPDATE matches
                    SET status = 'FINISHED', winner_side = ?, end_reason = ?, finished_at = now()
                    WHERE id = ?
                    """, winnerSide, reason, matchId);

            // Replay JSON serialization
            String replayJson = null;
            if (request != null && request.replayData() != null) {
                try {
                    if (request.replayData() instanceof String s) {
                        replayJson = s;
                    } else {
                        replayJson = mapper.writeValueAsString(request.replayData());
                    }
                } catch (Exception ignored) { }
            }

            String matchTypeLabel = isRanked ? "RANKED" : "FRIENDLY";

            // 6. Insert user_matches for User A
            insertUserMatch(userAId, "PVP", nameB, eloB, "HUMAN",
                    "A".equals(winnerSide), winnerSide, reason, changeA, newEloA, isRanked, matchTypeLabel,
                    publicId.toString(), replayJson);

            // 7. Insert user_matches for User B
            insertUserMatch(userBId, "PVP", nameA, eloA, "HUMAN",
                    "B".equals(winnerSide), winnerSide, reason, changeB, newEloB, isRanked, matchTypeLabel,
                    publicId.toString(), replayJson);

            // 8. Save replay to replays table if replayJson is provided
            if (replayJson != null) {
                try {
                    LocalDate month = jdbc.queryForObject("SELECT created_month FROM matches WHERE id = ?",
                            (rs, r) -> rs.getDate(1).toLocalDate(), matchId);
                    jdbc.update("""
                            INSERT INTO replays(match_id, created_month, storage_key, size_bytes, replay_data)
                            VALUES (?, ?, 'db://replay', 0, ?::jsonb)
                            ON CONFLICT (match_id, created_month) DO UPDATE SET replay_data = EXCLUDED.replay_data
                            """, matchId, Date.valueOf(month), replayJson);
                } catch (Exception ignored) { }
            }

            // 9. Broadcast STOMP MATCH_FINISHED event to room
            if (messagingTemplate != null && roomCode != null && !roomCode.isBlank()) {
                try {
                    Map<String, Object> wsEvent = new HashMap<>();
                    wsEvent.put("type", "MATCH_FINISHED");
                    wsEvent.put("matchId", matchId);
                    wsEvent.put("matchPublicId", publicId.toString());
                    wsEvent.put("roomCode", roomCode);
                    wsEvent.put("winnerSide", winnerSide);
                    wsEvent.put("winner", winnerSide);
                    wsEvent.put("endReason", reason);
                    wsEvent.put("winReason", reason);
                    wsEvent.put("isRanked", isRanked);

                    Map<String, Object> sideAData = new HashMap<>();
                    sideAData.put("eloBefore", eloA);
                    sideAData.put("eloAfter", newEloA);
                    sideAData.put("eloChange", changeA);

                    Map<String, Object> sideBData = new HashMap<>();
                    sideBData.put("eloBefore", eloB);
                    sideBData.put("eloAfter", newEloB);
                    sideBData.put("eloChange", changeB);

                    wsEvent.put("sideA", sideAData);
                    wsEvent.put("sideB", sideBData);

                    // Extract final move from replayData if available to guarantee board sync
                    if (request != null && request.replayData() != null) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode root;
                            if (request.replayData() instanceof String s) {
                                root = mapper.readTree(s);
                            } else {
                                root = mapper.valueToTree(request.replayData());
                            }
                            com.fasterxml.jackson.databind.JsonNode turns = root.path("turns");
                            if (!turns.isArray() || turns.isEmpty()) {
                                turns = root.path("moves");
                            }
                            if (turns.isArray() && !turns.isEmpty()) {
                                com.fasterxml.jackson.databind.JsonNode lastTurn = turns.get(turns.size() - 1);
                                Map<String, Object> finalMoveMap = new HashMap<>();
                                if (lastTurn.has("from")) {
                                    finalMoveMap.put("from", mapper.convertValue(lastTurn.get("from"), Object.class));
                                }
                                if (lastTurn.has("to")) {
                                    finalMoveMap.put("to", mapper.convertValue(lastTurn.get("to"), Object.class));
                                }
                                wsEvent.put("finalMove", finalMoveMap);
                                wsEvent.put("lastMove", finalMoveMap);
                            }
                        } catch (Exception ignored) { }
                    }

                    Map<String, Object> resultA = new HashMap<>();
                    resultA.put("userId", userAId);
                    resultA.put("eloBefore", eloA);
                    resultA.put("eloAfter", newEloA);
                    resultA.put("eloChange", changeA);
                    wsEvent.put("sideA", resultA);

                    Map<String, Object> resultB = new HashMap<>();
                    resultB.put("userId", userBId);
                    resultB.put("eloBefore", eloB);
                    resultB.put("eloAfter", newEloB);
                    resultB.put("eloChange", changeB);
                    wsEvent.put("sideB", resultB);

                    messagingTemplate.convertAndSend("/topic/room/" + roomCode, (Object) wsEvent);
                } catch (Exception ignored) { }
            }

            int myChange = (currentUserId == userAId) ? changeA : changeB;
            int myNewElo = (currentUserId == userAId) ? newEloA : newEloB;
            int myOldElo = (currentUserId == userAId) ? eloA : eloB;
            String oppName = (currentUserId == userAId) ? nameB : nameA;
            int oppElo = (currentUserId == userAId) ? eloB : eloA;
            int oppChange = (currentUserId == userAId) ? changeB : changeA;
            int oppNewElo = (currentUserId == userAId) ? newEloB : newEloA;

            return new PvpMatchFinishResponse(publicId.toString(), "FINISHED", winnerSide, reason,
                    myChange, myNewElo, isRanked, myOldElo, oppName, oppElo, oppChange, oppNewElo);
        });
    }

    public static int determinePvpKFactor(int completedRankedMatches) {
        if (completedRankedMatches < 20) {
            return 40;
        } else if (completedRankedMatches < 70) {
            return 30;
        } else {
            return 20;
        }
    }

    private void insertUserMatch(long userId, String mode, String opponent, int oppElo, String oppType,
                                 boolean won, String winner, String reason, int change, int newElo,
                                 boolean isRanked, String matchType, String replayId, String replayJson) {
        if (replayJson != null) {
            jdbc.update("""
                    INSERT INTO user_matches (user_id, mode, opponent, opponent_elo, opponent_type,
                                             user_won, winner, reason, elo_change, new_elo, is_ranked, match_type, replay_id, replay_data)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    userId, mode, opponent, oppElo, oppType,
                    won, winner, reason, change, newElo, isRanked, matchType, replayId, replayJson);
        } else {
            jdbc.update("""
                    INSERT INTO user_matches (user_id, mode, opponent, opponent_elo, opponent_type,
                                             user_won, winner, reason, elo_change, new_elo, is_ranked, match_type, replay_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    userId, mode, opponent, oppElo, oppType,
                    won, winner, reason, change, newElo, isRanked, matchType, replayId);
        }
    }

    private MatchResponse response(long id, java.time.LocalDate createdMonth) {
        return jdbc.queryForObject(MATCH_SELECT + " WHERE m.id=? AND m.created_month=?",
                (rs, row) -> map(rs), id, Date.valueOf(createdMonth));
    }

    private static MatchResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new MatchResponse(rs.getObject("public_id", UUID.class), rs.getString("status"), rs.getLong("seed"),
                rs.getString("winner_side"), rs.getString("end_reason"), (Integer) rs.getObject("total_turns"),
                rs.getTimestamp("queued_at").toInstant(), finished == null ? null : finished.toInstant(),
                new Participant(rs.getLong("a_bot_id"), rs.getString("a_bot_name"), rs.getLong("a_submission_id"), rs.getString("a_fault"),
                        rs.getString("a_sha"), (Integer) rs.getObject("a_ver"), rs.getLong("a_owner_id"),
                        (Integer) rs.getObject("a_elo_before"), (Integer) rs.getObject("a_elo_after")),
                new Participant(rs.getLong("b_bot_id"), rs.getString("b_bot_name"), rs.getLong("b_submission_id"), rs.getString("b_fault"),
                        rs.getString("b_sha"), (Integer) rs.getObject("b_ver"), rs.getLong("b_owner_id"),
                        (Integer) rs.getObject("b_elo_before"), (Integer) rs.getObject("b_elo_after")));
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not encode match job", e); }
    }

    private record MatchInsert(long id, UUID publicId, java.time.LocalDate createdMonth, Instant queuedAt) { }
    private record ParticipantJobData(long botId, String botName, long ownerId, long submissionId, int versionNo,
                                      Language language, String artifactKey, String sourceSha256) {
        MatchJob.ParticipantJob toJob() {
            return new MatchJob.ParticipantJob(botId, botName, ownerId, submissionId,
                    language.name(), artifactKey, sourceSha256);
        }
    }
}
