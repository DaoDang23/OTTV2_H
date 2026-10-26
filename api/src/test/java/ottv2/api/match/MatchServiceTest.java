package ottv2.api.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ottv2.api.match.MatchDtos.CreateMatchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchServiceTest {
    @Test
    void snapshotsPlayableSubmissionsAndWritesQueueJobInTheMatchTransaction() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        Instant now = Instant.now();
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        UUID publicId = UUID.randomUUID();
        doAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)))
                .when(transaction).execute(any(TransactionCallback.class));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            ResultSet row = mock(ResultSet.class);
            if (sql.contains("FROM submissions s JOIN bots b")) {
                long submissionId = ((Number) invocation.getArgument(2)).longValue();
                when(row.getLong("bot_id")).thenReturn(submissionId == 11 ? 101L : 202L);
                when(row.getString("name")).thenReturn(submissionId == 11 ? "Alpha" : "Beta");
                when(row.getLong("owner_id")).thenReturn(submissionId == 11 ? 50L : 60L);
                when(row.getLong("submission_id")).thenReturn(submissionId);
                when(row.getString("language")).thenReturn("PYTHON");
                when(row.getString("artifact_key")).thenReturn("artifacts/sha/python");
                when(row.getString("source_sha256")).thenReturn("a".repeat(64));
            } else if (sql.contains("INSERT INTO matches")) {
                when(row.getLong("id")).thenReturn(900L);
                when(row.getObject("public_id", UUID.class)).thenReturn(publicId);
                when(row.getDate("created_month")).thenReturn(Date.valueOf(month));
                when(row.getTimestamp("queued_at")).thenReturn(Timestamp.from(now));
            } else {
                when(row.getObject("public_id", UUID.class)).thenReturn(publicId);
                when(row.getString("status")).thenReturn("QUEUED");
                when(row.getString("winner_side")).thenReturn(null);
                when(row.getString("end_reason")).thenReturn(null);
                when(row.getString("a_bot_name")).thenReturn("Alpha");
                when(row.getString("b_bot_name")).thenReturn("Beta");
                when(row.getString("a_fault")).thenReturn(null);
                when(row.getString("b_fault")).thenReturn(null);
                when(row.getLong("a_bot_id")).thenReturn(101L);
                when(row.getLong("b_bot_id")).thenReturn(202L);
                when(row.getLong("a_submission_id")).thenReturn(11L);
                when(row.getLong("b_submission_id")).thenReturn(12L);
                when(row.getLong("seed")).thenReturn(7L);
                when(row.getObject("total_turns")).thenReturn(null);
                when(row.getTimestamp("queued_at")).thenReturn(Timestamp.from(now));
                when(row.getTimestamp("finished_at")).thenReturn(null);
            }
            @SuppressWarnings("unchecked") RowMapper<Object> mapper = invocation.getArgument(1);
            return mapper.mapRow(row, 0);
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        MatchService service = new MatchService(jdbc, transaction, new ObjectMapper());
        var created = service.create(50, new CreateMatchRequest(11L, 12L));

        assertEquals(publicId, created.id());
        assertEquals("QUEUED", created.status());
        assertEquals(101L, created.sideA().botId());
        assertEquals(202L, created.sideB().botId());
        verify(jdbc).update(contains("INSERT INTO match_participants"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO outbox_events(topic, payload)"), any(Object[].class));
    }

    @Test
    void finishPvpMatchCalculatesAuthoritativeEloAndUpdatesBothUsers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        UUID publicId = UUID.randomUUID();
        doAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)))
                .when(transaction).execute(any(TransactionCallback.class));

        // 1. Mock query match from matches table
        Map<String, Object> matchRow = new java.util.HashMap<>();
        matchRow.put("id", 555L);
        matchRow.put("public_id", publicId);
        matchRow.put("match_type", "PVP");
        matchRow.put("status", "READY");
        matchRow.put("user_a_id", 100L);
        matchRow.put("user_b_id", 200L);
        matchRow.put("room_code", "123456");
        matchRow.put("winner_side", null);
        matchRow.put("end_reason", null);

        when(jdbc.queryForList(contains("SELECT id, public_id, match_type"), any(Object[].class)))
                .thenReturn(List.of(matchRow));

        // 2. Mock query user A and user B current elo with FOR UPDATE
        Map<String, Object> userARow = Map.of(
                "id", 100L,
                "display_name", "Player Blue",
                "student_code", "B22DCCN100",
                "email", "blue@ptit.edu.vn",
                "pvp_elo", 1200
        );
        Map<String, Object> userBRow = Map.of(
                "id", 200L,
                "display_name", "Player Red",
                "student_code", "B22DCCN200",
                "email", "red@ptit.edu.vn",
                "pvp_elo", 1200
        );

        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(100L))).thenReturn(userARow);
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(200L))).thenReturn(userBRow);
        when(jdbc.queryForObject(contains("SELECT created_month FROM matches"), any(RowMapper.class), eq(555L)))
                .thenReturn(LocalDate.now());

        MatchService service = new MatchService(jdbc, transaction, new ObjectMapper());

        // Valid replay showing Team A reaching enemy base [0, 8]
        Map<String, Object> validWinningReplay = Map.of(
                "moves", List.of(
                        Map.of(
                                "team", "A",
                                "from", List.of(1, 8),
                                "to", List.of(0, 8)
                        )
                )
        );

        // Player A finishes match declaring winner Side A with legitimate moves
        var response = service.finishPvpMatch(100L, "123456",
                new MatchDtos.FinishPvpMatchRequest("A", "TARGET_REACHED", validWinningReplay));

        assertEquals(publicId.toString(), response.matchPublicId());
        assertEquals("FINISHED", response.status());
        assertEquals("A", response.winnerSide());
        assertEquals(20, response.eloChange(), "Winner A gains +20 Elo with K=40");
        assertEquals(1220, response.newElo(), "Winner A new Elo is 1220");
        assertEquals(true, response.isRanked());

        // Verify users table updated atomically with new Elo (Winner A +20, Loser B -15)
        verify(jdbc).update("UPDATE users SET pvp_elo = ? WHERE id = ?", 1220, 100L);
        verify(jdbc).update("UPDATE users SET pvp_elo = ? WHERE id = ?", 1185, 200L);

        // Verify match status set to FINISHED
        verify(jdbc).update(contains("UPDATE matches"), eq("A"), eq("TARGET_REACHED"), eq(555L));

        // 3. Test security: third-party user cannot finalize other users' match
        assertThrows(
                ResponseStatusException.class,
                () -> service.finishPvpMatch(999L, "123456", new MatchDtos.FinishPvpMatchRequest("A", "TARGET_REACHED", validWinningReplay))
        );
    }

    @Test
    void finishPvpMatchRejectsCheatingClaimWhenReplayMismatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        UUID publicId = UUID.randomUUID();
        doAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)))
                .when(transaction).execute(any(TransactionCallback.class));

        Map<String, Object> matchRow = new java.util.HashMap<>();
        matchRow.put("id", 555L);
        matchRow.put("public_id", publicId);
        matchRow.put("match_type", "PVP");
        matchRow.put("status", "READY");
        matchRow.put("user_a_id", 100L);
        matchRow.put("user_b_id", 200L);
        matchRow.put("room_code", "123456");

        when(jdbc.queryForList(contains("SELECT id, public_id, match_type"), any(Object[].class)))
                .thenReturn(List.of(matchRow));
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(100L)))
                .thenReturn(Map.of("id", 100L, "pvp_elo", 1200));
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(200L)))
                .thenReturn(Map.of("id", 200L, "pvp_elo", 1200));

        MatchService service = new MatchService(jdbc, transaction, new ObjectMapper());

        // Replay clearly shows Team B won by reaching base [8, 0]
        Map<String, Object> replayShowingBWin = Map.of(
                "moves", List.of(
                        Map.of(
                                "team", "B",
                                "from", List.of(7, 0),
                                "to", List.of(8, 0)
                        )
                )
        );

        // Cheating attempt: Player A claims winnerSide is "A", but replay proves B won
        assertThrows(
                ResponseStatusException.class,
                () -> service.finishPvpMatch(100L, "123456",
                        new MatchDtos.FinishPvpMatchRequest("A", "TARGET_REACHED", replayShowingBWin))
        );
    }

    @Test
    void finishPvpMatchAuthoritativelyAppliesResignation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        UUID publicId = UUID.randomUUID();
        doAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)))
                .when(transaction).execute(any(TransactionCallback.class));

        Map<String, Object> matchRow = new java.util.HashMap<>();
        matchRow.put("id", 555L);
        matchRow.put("public_id", publicId);
        matchRow.put("match_type", "PVP");
        matchRow.put("status", "READY");
        matchRow.put("user_a_id", 100L);
        matchRow.put("user_b_id", 200L);
        matchRow.put("room_code", "123456");

        when(jdbc.queryForList(contains("SELECT id, public_id, match_type"), any(Object[].class)))
                .thenReturn(List.of(matchRow));
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(100L)))
                .thenReturn(Map.of("id", 100L, "pvp_elo", 1200));
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(200L)))
                .thenReturn(Map.of("id", 200L, "pvp_elo", 1200));
        when(jdbc.queryForObject(contains("SELECT created_month FROM matches"), any(RowMapper.class), eq(555L)))
                .thenReturn(LocalDate.now());

        MatchService service = new MatchService(jdbc, transaction, new ObjectMapper());

        // Player A resigns -> Server MUST assign victory to Player B
        var response = service.finishPvpMatch(100L, "123456",
                new MatchDtos.FinishPvpMatchRequest("A", "RESIGN", null));

        assertEquals("B", response.winnerSide(), "Server-Authoritative: Resigning player A cannot win, B wins");
        assertEquals(-15, response.eloChange(), "Resigning player A loses -15 Elo with K=40");
        assertEquals(1185, response.newElo(), "Resigning player A new Elo is 1185");
    }

    @Test
    void finishPvpMatchRejectsWinClaimWithoutProof() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        UUID publicId = UUID.randomUUID();
        doAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)))
                .when(transaction).execute(any(TransactionCallback.class));

        Map<String, Object> matchRow = new java.util.HashMap<>();
        matchRow.put("id", 555L);
        matchRow.put("public_id", publicId);
        matchRow.put("match_type", "PVP");
        matchRow.put("status", "READY");
        matchRow.put("user_a_id", 100L);
        matchRow.put("user_b_id", 200L);
        matchRow.put("room_code", "123456");

        when(jdbc.queryForList(contains("SELECT id, public_id, match_type"), any(Object[].class)))
                .thenReturn(List.of(matchRow));
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(100L)))
                .thenReturn(Map.of("id", 100L, "pvp_elo", 1200));
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(200L)))
                .thenReturn(Map.of("id", 200L, "pvp_elo", 1200));

        MatchService service = new MatchService(jdbc, transaction, new ObjectMapper());

        // Malicious client claims winner A with reason FINISHED and no moves
        assertThrows(
                ResponseStatusException.class,
                () -> service.finishPvpMatch(100L, "123456",
                        new MatchDtos.FinishPvpMatchRequest("A", "FINISHED", null))
        );
    }

    @Test
    void finishPvpMatchAuthoritativelyAppliesOpponentLeft() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        UUID publicId = UUID.randomUUID();
        doAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)))
                .when(transaction).execute(any(TransactionCallback.class));

        Map<String, Object> matchRow = new java.util.HashMap<>();
        matchRow.put("id", 555L);
        matchRow.put("public_id", publicId);
        matchRow.put("match_type", "PVP");
        matchRow.put("status", "READY");
        matchRow.put("user_a_id", 100L);
        matchRow.put("user_b_id", 200L);
        matchRow.put("room_code", "123456");

        when(jdbc.queryForList(contains("SELECT id, public_id, match_type"), any(Object[].class)))
                .thenReturn(List.of(matchRow));
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(100L)))
                .thenReturn(Map.of("id", 100L, "pvp_elo", 1200));
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(200L)))
                .thenReturn(Map.of("id", 200L, "pvp_elo", 1200));
        when(jdbc.queryForObject(contains("SELECT created_month FROM matches"), any(RowMapper.class), eq(555L)))
                .thenReturn(LocalDate.now());

        MatchService service = new MatchService(jdbc, transaction, new ObjectMapper());

        // Player A claims win because opponent B left
        var response = service.finishPvpMatch(100L, "123456",
                new MatchDtos.FinishPvpMatchRequest("A", "OPPONENT_LEFT", null));

        assertEquals("A", response.winnerSide(), "Player A wins due to opponent leaving");
        assertEquals(20, response.eloChange(), "Winner A gains +20 Elo with K=40");
        assertEquals(1220, response.newElo(), "Winner A new Elo is 1220");
    }

    @Test
    void determinePvpKFactorFollowsMatchCountProgression() {
        assertEquals(40, MatchService.determinePvpKFactor(0));
        assertEquals(40, MatchService.determinePvpKFactor(19));
        assertEquals(30, MatchService.determinePvpKFactor(20));
        assertEquals(30, MatchService.determinePvpKFactor(69));
        assertEquals(20, MatchService.determinePvpKFactor(70));
        assertEquals(20, MatchService.determinePvpKFactor(150));
    }

    @Test
    void finishPvpMatchEnforces1100EloFloor() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        UUID publicId = UUID.randomUUID();
        doAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0)
                .doInTransaction(mock(TransactionStatus.class)))
                .when(transaction).execute(any(TransactionCallback.class));

        Map<String, Object> matchRow = new java.util.HashMap<>();
        matchRow.put("id", 777L);
        matchRow.put("public_id", publicId);
        matchRow.put("match_type", "PVP");
        matchRow.put("status", "READY");
        matchRow.put("user_a_id", 100L);
        matchRow.put("user_b_id", 200L);
        matchRow.put("room_code", "123456");

        when(jdbc.queryForList(contains("SELECT id, public_id, match_type"), any(Object[].class)))
                .thenReturn(List.of(matchRow));
        // User A has 1105 Elo (near floor 1100), User B has 1200 Elo
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(100L)))
                .thenReturn(Map.of("id", 100L, "pvp_elo", 1105));
        when(jdbc.queryForMap(contains("FROM users WHERE id = ? FOR UPDATE"), eq(200L)))
                .thenReturn(Map.of("id", 200L, "pvp_elo", 1200));
        when(jdbc.queryForObject(contains("SELECT created_month FROM matches"), any(RowMapper.class), eq(777L)))
                .thenReturn(LocalDate.now());

        MatchService service = new MatchService(jdbc, transaction, new ObjectMapper());

        // Player A resigns -> B wins, A should be clamped at 1100 (delta = 1100 - 1105 = -5)
        var response = service.finishPvpMatch(100L, "123456",
                new MatchDtos.FinishPvpMatchRequest("A", "RESIGN", null));

        assertEquals("B", response.winnerSide());
        assertEquals(1100, response.newElo(), "Elo floor of 1100 must be enforced");
        assertEquals(-5, response.eloChange(), "Actual delta must be clamped to -5");
        verify(jdbc).update("UPDATE users SET pvp_elo = ? WHERE id = ?", 1100, 100L);
    }
}
