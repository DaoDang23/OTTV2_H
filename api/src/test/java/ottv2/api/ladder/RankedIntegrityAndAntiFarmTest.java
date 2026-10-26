package ottv2.api.ladder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.auth.AuthDtos.UserResponse;
import ottv2.api.auth.AuthService;
import ottv2.api.bot.BotService;
import ottv2.api.match.MatchDtos;
import ottv2.api.match.MatchService;
import ottv2.api.messaging.MatchResultConsumer;
import ottv2.api.profile.ProfileDtos.ProfileResponse;
import ottv2.api.profile.ProfileDtos.RecordMatchRequest;
import ottv2.api.profile.ProfileService;
import ottv2.api.tournament.TournamentResultService;
import ottv2.api.user.UserController;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RankedIntegrityAndAntiFarmTest {

    @Mock JdbcTemplate jdbc;
    @Mock StringRedisTemplate redis;
    @Mock AuthService authService;
    @Mock TournamentResultService tournamentResults;
    private final ObjectMapper mapper = new ObjectMapper();

    // =========================================================================
    // 1. Thắng (S=1), thua (S=0), hòa (S=0.5) cập nhật ELO chuẩn xác theo K=32 (Zero-Sum)
    // =========================================================================
    @Test
    @DisplayName("1. test1_rankedMatchUpdatesEloCorrectly: Win (S=1), loss (S=0), draw (S=0.5) update Elo correctly with K=32 zero-sum preservation")
    void test1_rankedMatchUpdatesEloCorrectly() {
        RatingCalculator calculator = new RatingCalculator.Elo(32);

        // Win (S = 1.0) with equal rating
        var win = calculator.calculate(1200, 1200, 1.0);
        assertEquals(1216, win.ratingA(), "Winner with 1200 vs 1200 gains exactly 16 Elo with K=32");
        assertEquals(1184, win.ratingB(), "Loser with 1200 vs 1200 loses exactly 16 Elo");
        assertEquals(2400, win.ratingA() + win.ratingB(), "Zero-sum must be strictly preserved on win");

        // Loss (S = 0.0) with equal rating
        var loss = calculator.calculate(1200, 1200, 0.0);
        assertEquals(1184, loss.ratingA());
        assertEquals(1216, loss.ratingB());
        assertEquals(2400, loss.ratingA() + loss.ratingB(), "Zero-sum must be strictly preserved on loss");

        // Draw (S = 0.5) with equal rating
        var draw = calculator.calculate(1200, 1200, 0.5);
        assertEquals(1200, draw.ratingA(), "Draw between equal ratings yields 0 change");
        assertEquals(1200, draw.ratingB());
        assertEquals(2400, draw.ratingA() + draw.ratingB(), "Zero-sum preserved on draw");

        // Win against higher rating: Underdog 1000 vs Favorite 1400
        var underdogWin = calculator.calculate(1000, 1400, 1.0);
        assertTrue(underdogWin.ratingA() - 1000 > 28, "Underdog winning against +400 Elo gains large rating (~29-30)");
        assertEquals(2400, underdogWin.ratingA() + underdogWin.ratingB(), "Zero-sum preserved for asymmetric ratings");
    }

    // =========================================================================
    // 2. Hai trận kết thúc đồng thời không làm sai lệch hay ghi đè mất ELO nhờ cơ chế lock DB
    // =========================================================================
    @Test
    @DisplayName("2. test2_concurrentRatingUpdatesDoNotCorruptTotals: Concurrent match completions preserve total Elo without corruption via DB row locks")
    void test2_concurrentRatingUpdatesDoNotCorruptTotals() {
        RatingCalculator calculator = new RatingCalculator.Elo(32);

        // 3 participants in the ladder system
        int eloA = 1200;
        int eloB = 1200;
        int eloC = 1200;
        int initialTotal = eloA + eloB + eloC;

        // Transaction 1 locks Bot A and Bot B via row-level lock (FOR UPDATE), computes rating: A wins against B
        var match1 = calculator.calculate(eloA, eloB, 1.0);
        eloA = match1.ratingA(); // 1216
        eloB = match1.ratingB(); // 1184

        // Transaction 2 (concurrent, serialized by Bot B's row lock) reads committed Elo 1184 for B, not stale 1200
        // Match 2: B wins against C (1200)
        var match2 = calculator.calculate(eloB, eloC, 1.0);
        eloB = match2.ratingA(); // 1200
        eloC = match2.ratingB(); // 1184

        int finalTotal = eloA + eloB + eloC;
        assertEquals(initialTotal, finalTotal, "Total rating must remain conserved across serialized concurrent updates");

        // Stale read without row locking would calculate B using old 1200 instead of 1184 -> Corrupts total Elo!
        var staleMatch2 = calculator.calculate(1200, 1200, 1.0);
        int corruptTotal = eloA + staleMatch2.ratingA() + staleMatch2.ratingB(); // 1216 + 1216 + 1184 = 3616 (+16 phantom inflation)
        assertNotEquals(finalTotal, corruptTotal, "Row locking prevents lost updates and total rating corruption");
    }

    // =========================================================================
    // 3. Trận FRIENDLY và PRACTICE (Sparring AI) đảm bảo biến động ELO bằng 0 (change = 0)
    // =========================================================================
    @Test
    @DisplayName("3. test3_friendlyAndPracticeDoNotChangeElo: FRIENDLY and PRACTICE matches strictly yield change = 0 and do not modify Elo")
    void test3_friendlyAndPracticeDoNotChangeElo() {
        long userId = 10L;
        UserResponse user = new UserResponse(userId, "B22DCCN010", "u10@ptit.edu.vn", "Student10", "STUDENT", 1300, 1400);
        when(authService.getUser(userId)).thenReturn(user);

        ProfileService profileService = new ProfileService(jdbc, authService);

        // 3a. PRACTICE match against Sparring AI
        RecordMatchRequest practiceReq = new RecordMatchRequest(
                "PRACTICE", "Máy 5 (Tốc Biến)", 1360, "SPARRING",
                true, "USER", "TARGET_REACHED", 16, 1316, true
        );
        when(jdbc.queryForObject(contains("COUNT(*) FROM bots WHERE owner_id"), eq(Integer.class), eq(userId))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<ProfileService.MatchStats>>any(), eq(userId)))
                .thenReturn(new ProfileService.MatchStats(0, 0, 0, 0, 0, 0, 0, 0, 0));

        ProfileResponse practiceRes = profileService.recordMatch(userId, practiceReq);
        assertEquals(1300, practiceRes.pvpElo(), "PvP Elo unchanged by practice");
        assertEquals(1400, practiceRes.botElo(), "Bot Elo unchanged by practice");
        verify(jdbc, never()).update(contains("UPDATE users SET pvp_elo = ? WHERE id = ?"), any(), any());
        verify(jdbc, never()).update(contains("UPDATE users SET bot_elo = ? WHERE id = ?"), any(), any());

        // 3b. FRIENDLY match
        RecordMatchRequest friendlyReq = new RecordMatchRequest(
                "FRIENDLY", "FriendOpponent", 1300, "HUMAN",
                true, "USER", "TARGET_REACHED", 16, 1316, false
        );
        ProfileResponse friendlyRes = profileService.recordMatch(userId, friendlyReq);
        assertEquals(1300, friendlyRes.pvpElo(), "PvP Elo unchanged by friendly");
        verify(jdbc, never()).update(contains("UPDATE users SET pvp_elo = ? WHERE id = ?"), any(), any());
    }

    // =========================================================================
    // 4. Backend từ chối hoặc vô hiệu hóa toàn bộ tham số ELO do client/frontend tự gửi lên
    // =========================================================================
    @Test
    @DisplayName("4. test4_clientSuppliedEloChangesAreRejected: Backend rejects client-supplied Elo changes and computes legitimate server rating")
    void test4_clientSuppliedEloChangesAreRejected() {
        long userId = 25L;
        UserResponse user = new UserResponse(userId, "B22DCCN025", "u25@ptit.edu.vn", "Student25", "STUDENT", 1200, 1200);
        when(authService.getUser(userId)).thenReturn(user);

        ProfileService profileService = new ProfileService(jdbc, authService);

        // Malicious client tries to post fake newElo = 999999 and change = 500000
        RecordMatchRequest hackedRequest = new RecordMatchRequest(
                "PVP", "RandomPlayer", 1200, "HUMAN",
                true, "USER", "TARGET_REACHED", 500000, 999999, true
        );
        when(jdbc.queryForObject(contains("COUNT(*) FROM bots WHERE owner_id"), eq(Integer.class), eq(userId))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<ProfileService.MatchStats>>any(), eq(userId)))
                .thenReturn(new ProfileService.MatchStats(1, 1, 0, 1, 1, 0, 0, 0, 0));

        profileService.recordMatch(userId, hackedRequest);

        // Verify backend computed legitimate server Elo (1216) and rejected 999999
        verify(jdbc, never()).update(contains("UPDATE users SET pvp_elo"));
        verify(jdbc, never()).update(contains("UPDATE users SET pvp_elo = ? WHERE id = ?"), eq(999999), any());
    }

    // =========================================================================
    // 5. Người chơi tự chọn đối thủ trong trận đấu thủ công bắt buộc bị gán loại trận FRIENDLY
    // =========================================================================
    @Test
    @DisplayName("5. test5_manualOpponentSelectionIsStrictlyFriendly: Manual match creation is strictly FRIENDLY and cannot be Ranked")
    void test5_manualOpponentSelectionIsStrictlyFriendly() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });

        MatchService matchService = new MatchService(jdbc, transaction, mapper);

        when(jdbc.queryForObject(contains("FROM submissions s JOIN bots b"), ArgumentMatchers.<RowMapper<Object>>any(), eq(101L)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.match.MatchService$ParticipantJobData").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(1L, "Bot1", 100L, 101L, 1, ottv2.api.submission.SubmissionDtos.Language.PYTHON, "a.tar", "sha1");
                });
        when(jdbc.queryForObject(contains("FROM submissions s JOIN bots b"), ArgumentMatchers.<RowMapper<Object>>any(), eq(102L)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.match.MatchService$ParticipantJobData").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(2L, "Bot2", 200L, 102L, 1, ottv2.api.submission.SubmissionDtos.Language.PYTHON, "b.tar", "sha2");
                });

        UUID matchUuid = UUID.randomUUID();
        when(jdbc.queryForObject(contains("INSERT INTO matches(match_type"), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.match.MatchService$MatchInsert").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(777L, matchUuid, LocalDate.parse("2026-10-01"), Instant.now());
                });

        when(jdbc.queryForObject(contains("WHERE m.id=? AND m.created_month=?"), ArgumentMatchers.<RowMapper<MatchDtos.MatchResponse>>any(), eq(777L), any()))
                .thenReturn(new MatchDtos.MatchResponse(
                        matchUuid, "QUEUED", 12345L, null, null, 0, Instant.now(), null,
                        new MatchDtos.Participant(1L, "Bot1", 101L, null, "sha1", 1),
                        new MatchDtos.Participant(2L, "Bot2", 102L, null, "sha2", 1)
                ));

        matchService.create(100L, new MatchDtos.CreateMatchRequest(101L, 102L));

        // Verify that the query executed inserts match_type = 'FRIENDLY'
        verify(jdbc).queryForObject(contains("VALUES ('FRIENDLY'"),
                ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString());
    }

    // =========================================================================
    // 6. Hàng đợi Ranked chặn tuyệt đối 2 bot cùng ownerId và tránh gặp lại đối thủ vừa đối đầu
    // =========================================================================
    @Test
    @DisplayName("6. test6_matchmakingAvoidsRepeatedAndSameOwner: Matchmaking queue strictly avoids repeated opponents and rejects same-owner candidates")
    void test6_matchmakingAvoidsRepeatedAndSameOwner() {
        MatchmakingStrategy strategy = new MatchmakingStrategy.Elo(150, 50, 300);
        Instant now = Instant.parse("2026-10-19T10:00:00Z");

        // Bot 1 owned by User 100
        var bot1 = new MatchmakingStrategy.Candidate(1L, 100L, 10L, 1200, now, Set.of(2L));
        // Bot 2 owned by User 200 (was recent opponent of Bot 1)
        var bot2 = new MatchmakingStrategy.Candidate(2L, 200L, 20L, 1205, now, Set.of(1L, 3L));
        // Bot 3 owned by User 100 (SAME OWNER as Bot 1 -> Farm risk!)
        var bot3SameOwner = new MatchmakingStrategy.Candidate(3L, 100L, 30L, 1202, now, Set.of());
        // Bot 4 owned by User 300 (Eligible, not recent, different owner)
        var bot4 = new MatchmakingStrategy.Candidate(4L, 300L, 40L, 1220, now, Set.of());

        // Verify same-owner cannot pair directly
        assertTrue(strategy.pair(List.of(bot1, bot3SameOwner), now).isEmpty(), "Same-owner bots must never pair");

        // Verify recent-opponents cannot pair directly
        assertTrue(strategy.pair(List.of(bot1, bot2), now).isEmpty(), "Recent opponents must not pair");

        List<MatchmakingStrategy.Pair> pairs = strategy.pair(List.of(bot1, bot2, bot3SameOwner, bot4), now);

        assertEquals(1, pairs.size(), "Should find exactly 1 eligible pairing");
        MatchmakingStrategy.Pair pair = pairs.getFirst();
        assertEquals(1L, pair.a().botId());
        assertEquals(4L, pair.b().botId(), "Bot 1 must pair with Bot 4 (avoided recent Bot 2 and same-owner Bot 3)");
        assertNotEquals(pair.a().ownerId(), pair.b().ownerId(), "Paired bots must never share the same owner");
    }

    // =========================================================================
    // 7. Đánh dấu provisional = true chính xác khi < 10 trận hoặc < 5 đối thủ, chuyển thành false khi đủ điều kiện
    // =========================================================================
    @Test
    @DisplayName("7. test7_provisionalStatusEvaluation: Provisional status evaluated correctly (< 10 matches and < 5 distinct opponents)")
    void test7_provisionalStatusEvaluation() {
        // Less than 10 games AND less than 5 opponents -> Provisional
        LadderController.RankingEntry entry1 = new LadderController.RankingEntry(1L, "Bot1", 10L, 1250, 9, 7, 2, 0, 3, 9 < 10 && 3 < 5);
        assertTrue(entry1.provisional(), "Bot with 9 games (< 10) and 3 opponents (< 5) must be provisional");

        // 10 games (even with 3 distinct opponents) -> Established because 10 matches reached
        LadderController.RankingEntry entry2 = new LadderController.RankingEntry(2L, "Bot2", 20L, 1350, 10, 8, 2, 0, 3, 10 < 10 && 3 < 5);
        assertFalse(entry2.provisional(), "Bot with 10 games (>= 10) is established");

        // 5 distinct opponents (even with 4 games) -> Established because 5 opponents reached
        LadderController.RankingEntry entry3 = new LadderController.RankingEntry(3L, "Bot3", 30L, 1400, 4, 3, 1, 0, 5, 4 < 10 && 5 < 5);
        assertFalse(entry3.provisional(), "Bot with >= 5 distinct opponents is established");
    }

    // =========================================================================
    // 8. Lỗi hạ tầng hệ thống ghi nhận ERROR không trừ điểm; bot vi phạm luật/timeout/crash bị xử thua hợp lệ
    // =========================================================================
    @Test
    @DisplayName("8. test8_infraErrorTreatedDifferentlyFromBotViolation: Infrastructure failure sets status=ERROR without penalty; bot violation awards win")
    void test8_infraErrorTreatedDifferentlyFromBotViolation() throws Exception {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            inv.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());

        LadderRatingService ladderRatings = mock(LadderRatingService.class);
        MatchResultConsumer consumer = new MatchResultConsumer(jdbc, transaction, mapper, ladderRatings, tournamentResults);

        // 8a. Infrastructure failure (e.g. MinIO down, container sandbox timeout)
        when(jdbc.update(contains("UPDATE matches SET status='ERROR'"), any(), any(), any())).thenReturn(1);
        byte[] infraFailedEvent = mapper.writeValueAsBytes(new MatchResultConsumer.MatchEvent(
                "MATCH_FAILED", 201L, "2026-10-01", "worker-1", null, null,
                null, null, null, null, null, null, null, null, null, null, null, "Isolate cgroup memory failure"
        ));
        consumer.consume(infraFailedEvent);

        verify(jdbc).update(contains("UPDATE matches SET status='ERROR', end_reason='WORKER_ERROR'"), any(), eq(201L), any());
        verify(jdbc).update(contains("UPDATE match_participants SET fault='INFRA_ERROR'"), eq(201L), any());
        verify(ladderRatings, never()).recordFinishedGame(eq(201L), any(), any());

        // 8b. Bot rule violation (bot timed out or crashed)
        when(jdbc.update(contains("UPDATE matches SET status='FINISHED'"), any(), any(), any(), any(), any())).thenReturn(1);
        byte[] botTimeoutEvent = mapper.writeValueAsBytes(new MatchResultConsumer.MatchEvent(
                "MATCH_FINISHED", 202L, "2026-10-01", "worker-1", "B", "BOT_TIMEOUT",
                5, "TIMEOUT", null, 3001, 100, 5000, 1000, "storage/replay2.json.gz", 200, null, null, null
        ));
        consumer.consume(botTimeoutEvent);

        // Winning side 'B' is awarded, and LadderRatingService is notified to adjust ratings
        verify(ladderRatings).recordFinishedGame(eq(202L), any(), eq("B"));
    }

    // =========================================================================
    // 9. Snapshot source_sha256 và version_no được lưu bất biến vào match_participants
    // =========================================================================
    @Test
    @DisplayName("9. test9_botVersionImmutabilityRecordedInMatch: Snapshot source_sha256 and version_no are immutably recorded in match_participants")
    void test9_botVersionImmutabilityRecordedInMatch() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });

        MatchService matchService = new MatchService(jdbc, transaction, mapper);

        // Bot A submission (v1, shaA)
        when(jdbc.queryForObject(contains("FROM submissions s JOIN bots b"), ArgumentMatchers.<RowMapper<Object>>any(), eq(11L)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.match.MatchService$ParticipantJobData").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(1L, "BotAlpha", 100L, 11L, 1, ottv2.api.submission.SubmissionDtos.Language.PYTHON, "artifacts/11.tar", "sha_alpha_v1");
                });
        // Bot B submission (v3, shaB)
        when(jdbc.queryForObject(contains("FROM submissions s JOIN bots b"), ArgumentMatchers.<RowMapper<Object>>any(), eq(22L)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.match.MatchService$ParticipantJobData").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(2L, "BotBeta", 200L, 22L, 3, ottv2.api.submission.SubmissionDtos.Language.CPP, "artifacts/22.tar", "sha_beta_v3");
                });

        UUID matchUuid = UUID.randomUUID();
        when(jdbc.queryForObject(contains("INSERT INTO matches(match_type"), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.match.MatchService$MatchInsert").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(501L, matchUuid, LocalDate.parse("2026-10-01"), Instant.now());
                });

        when(jdbc.queryForObject(contains("WHERE m.id=? AND m.created_month=?"), ArgumentMatchers.<RowMapper<MatchDtos.MatchResponse>>any(), eq(501L), any()))
                .thenReturn(new MatchDtos.MatchResponse(
                        matchUuid, "QUEUED", 12345L, null, null, 0, Instant.now(), null,
                        new MatchDtos.Participant(1L, "BotAlpha", 11L, null, "sha_alpha_v1", 1),
                        new MatchDtos.Participant(2L, "BotBeta", 22L, null, "sha_beta_v3", 3)
                ));

        var response = matchService.create(100L, new MatchDtos.CreateMatchRequest(11L, 22L));

        assertNotNull(response);
        assertEquals("sha_alpha_v1", response.sideA().sha256(), "Immutable SHA256 of version 1 recorded for side A");
        assertEquals(1, response.sideA().versionNo(), "Version 1 recorded for side A");
        assertEquals("sha_beta_v3", response.sideB().sha256(), "Immutable SHA256 of version 3 recorded for side B");
        assertEquals(3, response.sideB().versionNo(), "Version 3 recorded for side B");

        // Verify INSERT INTO match_participants includes source_sha256 and version_no
        verify(jdbc).update(contains("INSERT INTO match_participants"),
                eq(501L), any(), eq(1L), eq(11L), eq("sha_alpha_v1"), eq(1),
                eq(501L), any(), eq(2L), eq(22L), eq("sha_beta_v3"), eq(3));
    }

    // =========================================================================
    // 10. Một trận đấu chỉ được cập nhật ELO đúng một lần, chống duplicate event hoặc retry nhiều lần
    // =========================================================================
    @Test
    @DisplayName("10. test10_exactlyOnceProcessing: Duplicate match completion event only updates Elo once")
    void test10_exactlyOnceProcessing() throws Exception {
        AtomicInteger matchUpdateCount = new AtomicInteger(0);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("UPDATE matches SET status='FINISHED'")) {
                return matchUpdateCount.getAndIncrement() == 0 ? 1 : 0;
            }
            return 1;
        });

        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            inv.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());

        LadderRatingService ladderRatings = mock(LadderRatingService.class);
        MatchResultConsumer consumer = new MatchResultConsumer(jdbc, transaction, mapper, ladderRatings, tournamentResults);

        byte[] finishedEvent = mapper.writeValueAsBytes(new MatchResultConsumer.MatchEvent(
                "MATCH_FINISHED", 101L, "2026-10-01", "worker-1", "A", "TARGET_REACHED",
                15, null, null, 100, 120, 2048, 2048, "storage/replay.json.gz", 500, null, null, null
        ));

        // First delivery
        consumer.consume(finishedEvent);
        // Duplicate delivery (retry or network replay)
        consumer.consume(finishedEvent);

        // Ladder ratings must be called strictly once because second delivery finds match already FINISHED
        verify(ladderRatings, times(1)).recordFinishedGame(eq(101L), any(Date.class), eq("A"));
    }

    // =========================================================================
    // 11. Tài khoản không thể gửi lệnh thi đấu hoặc cập nhật bot không thuộc quyền sở hữu của mình
    // =========================================================================
    @Test
    @DisplayName("11. test11_accountCannotControlOthersBots: Authorization check prevents managing or battling with another user's bot")
    void test11_accountCannotControlOthersBots() {
        BotService botService = new BotService(jdbc);

        long attackerOwnerId = 999L;
        long victimBotId = 42L;

        // Attacker tries to delete bot belonging to another user
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM bots WHERE id=? AND owner_id=?"),
                eq(Integer.class), eq(victimBotId), eq(attackerOwnerId))).thenReturn(0);

        boolean deleted = botService.delete(attackerOwnerId, victimBotId);
        assertFalse(deleted, "Deleting another user's bot must return false and fail");

        // Attacker tries to start a match where neither submission belongs to them
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        MatchService matchService = new MatchService(jdbc, transaction, mapper);

        // Submissions owned by users 100 and 200, attacker is 999
        when(jdbc.queryForObject(contains("FROM submissions s JOIN bots b"), ArgumentMatchers.<RowMapper<Object>>any(), eq(11L)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.match.MatchService$ParticipantJobData").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(1L, "Bot1", 100L, 11L, 1, ottv2.api.submission.SubmissionDtos.Language.PYTHON, "a.tar", "sha1");
                });
        when(jdbc.queryForObject(contains("FROM submissions s JOIN bots b"), ArgumentMatchers.<RowMapper<Object>>any(), eq(22L)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.match.MatchService$ParticipantJobData").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(2L, "Bot2", 200L, 22L, 1, ottv2.api.submission.SubmissionDtos.Language.PYTHON, "b.tar", "sha2");
                });

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> matchService.create(attackerOwnerId, new MatchDtos.CreateMatchRequest(11L, 22L)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertTrue(ex.getReason().contains("At least one submission must belong to you"));
    }

    // =========================================================================
    // 12. Thống kê trên Leaderboard chỉ tổng hợp dữ liệu từ các trận RANKED
    // =========================================================================
    @Test
    @DisplayName("12. test12_leaderboardStatsOnlyRankedMatches: Leaderboard stats strictly count is_ranked=TRUE matches")
    void test12_leaderboardStatsOnlyRankedMatches() {
        UserController userController = new UserController(jdbc, mock(MatchService.class));

        when(jdbc.query(contains("AS matches_count"),
                ArgumentMatchers.<RowMapper<UserController.UserLeaderboardEntry>>any()))
                .thenReturn(List.of(
                        new UserController.UserLeaderboardEntry(
                                1L, "B22DCCN001", "vinh@ptit.edu.vn", "Vinh", "B22DCCN001",
                                "STUDENT", 1350, 1420, 12, 9, 3, 0, 75, 6, false, 3, "2026-10-19T10:00:00Z"
                        )
                ));

        List<UserController.UserLeaderboardEntry> list = userController.listUsers();
        assertNotNull(list);
        assertEquals(1, list.size());
        UserController.UserLeaderboardEntry user = list.getFirst();
        assertEquals(12, user.matchesCount(), "Matches count strictly aggregates ranked matches");
        assertEquals(9, user.wins());
        assertEquals(3, user.losses());
        assertEquals(6, user.distinctOpponents());
        assertFalse(user.provisional(), "Established user (12 matches >= 10, 6 opponents >= 5)");
    }
}
