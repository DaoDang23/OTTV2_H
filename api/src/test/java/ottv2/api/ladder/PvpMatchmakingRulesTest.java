package ottv2.api.ladder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ottv2.api.room.RoomService;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PvpMatchmakingRulesTest {

    @Mock JdbcTemplate jdbc;
    @Mock TransactionTemplate transaction;
    @Mock SimpMessagingTemplate messagingTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private MatchmakingQueueService queueService;

    @BeforeEach
    void setUp() {
        RoomService roomService = new RoomService();
        queueService = new MatchmakingQueueService(
                jdbc, transaction, null, messagingTemplate, roomService, mapper
        );

        when(jdbc.queryForObject(contains("INSERT INTO matches"), eq(Long.class), any(), any(), any(), any(), any()))
                .thenReturn(1001L);
        when(jdbc.queryForObject(contains("SELECT public_id FROM matches"), eq(UUID.class), eq(1001L)))
                .thenReturn(UUID.randomUUID());
    }

    private void mockRecentOpponents(long userId, List<Long> recentOpponentIds) {
        when(jdbc.query(contains("SELECT CASE WHEN user_a_id"), any(RowMapper.class), eq(userId), eq(userId), anyInt()))
                .thenReturn(recentOpponentIds);
    }

    private MatchmakingQueueService.PvpQueueEntry createEntry(long userId, int elo, Instant enqueuedAt) {
        return new MatchmakingQueueService.PvpQueueEntry(
                userId, "user" + userId, "sc" + userId, "user" + userId + "@test.com", "User " + userId,
                elo, "avatar", enqueuedAt, "PVP", "blitz_3_2", Instant.now()
        );
    }

    @Test
    @DisplayName("TC-01: Chặn ghép khi chưa đủ 3 giây chờ")
    void testUnder3SecondsCannotMatch() {
        Instant now = Instant.now();
        // User 1 chờ 2s, User 2 chờ 2s (< 3s)
        MatchmakingQueueService.PvpQueueEntry u1 = createEntry(1L, 1200, now.minusSeconds(2));
        MatchmakingQueueService.PvpQueueEntry u2 = createEntry(2L, 1200, now.minusSeconds(2));

        queueService.getPvpQueue().put(1L, u1);
        queueService.getPvpQueue().put(2L, u2);

        List<MatchmakingQueueService.PvpMatchResult> results = queueService.matchPvpQueue(now);
        assertTrue(results.isEmpty(), "Cả 2 người chờ < 3s thì tuyệt đối không được ghép!");

        // Một người chờ 10s, người kia chờ 1s -> người chờ 1s chưa đủ 3s cũng không được ghép
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(10)));
        queueService.getPvpQueue().put(2L, createEntry(2L, 1200, now.minusSeconds(1)));

        List<MatchmakingQueueService.PvpMatchResult> results2 = queueService.matchPvpQueue(now);
        assertTrue(results2.isEmpty(), "Nếu có một người chờ < 3s thì không được phép ghép!");
    }

    @Test
    @DisplayName("TC-02: Ngưỡng 3s - 5s: Tối đa 50 ELO và cấm 4 đối thủ gần nhất")
    void testWait3To5SecondsRules() {
        Instant now = Instant.now();
        // A chờ 4s, B chờ 4s (3s <= maxWait < 5s)
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(4)));
        queueService.getPvpQueue().put(2L, createEntry(2L, 1240, now.minusSeconds(4))); // diff 40 <= 50

        mockRecentOpponents(1L, List.of());
        mockRecentOpponents(2L, List.of());

        List<MatchmakingQueueService.PvpMatchResult> res = queueService.matchPvpQueue(now);
        assertEquals(1, res.size(), "Lệch 40 ELO <= 50 ELO và không có đối thủ cũ thì phải ghép thành công");

        // Trường hợp lệch 60 ELO (> 50)
        queueService.clearPvpQueue();
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(4)));
        queueService.getPvpQueue().put(3L, createEntry(3L, 1260, now.minusSeconds(4))); // diff 60 > 50

        mockRecentOpponents(1L, List.of());
        mockRecentOpponents(3L, List.of());

        List<MatchmakingQueueService.PvpMatchResult> resTooFar = queueService.matchPvpQueue(now);
        assertTrue(resTooFar.isEmpty(), "Lệch 60 ELO > 50 ELO thì không được ghép");

        // Trường hợp lệch 30 ELO nhưng đối thủ nằm trong 4 trận gần nhất
        queueService.clearPvpQueue();
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(4)));
        queueService.getPvpQueue().put(4L, createEntry(4L, 1230, now.minusSeconds(4)));

        mockRecentOpponents(1L, List.of(99L, 98L, 97L, 4L)); // 4L là trận thứ 4 gần đây của 1L
        mockRecentOpponents(4L, List.of());

        List<MatchmakingQueueService.PvpMatchResult> resForbidden = queueService.matchPvpQueue(now);
        assertTrue(resForbidden.isEmpty(), "Đối thủ nằm trong 4 trận gần nhất thì không được ghép ở ngưỡng 3-5s");
    }

    @Test
    @DisplayName("TC-03: Ngưỡng 5s - 15s: Tối đa 100 ELO và cấm 3 đối thủ gần nhất")
    void testWait5To15SecondsRules() {
        Instant now = Instant.now();
        // A chờ 8s, B chờ 4s -> maxWait = 8s (5s <= maxWait < 15s)
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(8)));
        queueService.getPvpQueue().put(2L, createEntry(2L, 1290, now.minusSeconds(4))); // diff 90 <= 100

        // Đối thủ 2L là trận thứ 4 gần đây của 1L -> ở ngưỡng 5-15s chỉ cấm 3 trận gần nhất, nên trận thứ 4 được phép!
        mockRecentOpponents(1L, List.of(99L, 98L, 97L, 2L));
        mockRecentOpponents(2L, List.of());

        List<MatchmakingQueueService.PvpMatchResult> res = queueService.matchPvpQueue(now);
        assertEquals(1, res.size(), "Lệch 90 <= 100 ELO và đối thủ là trận thứ 4 thì được phép ghép ở ngưỡng 5-15s");

        // Nếu lệch 110 ELO > 100
        queueService.clearPvpQueue();
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(8)));
        queueService.getPvpQueue().put(3L, createEntry(3L, 1310, now.minusSeconds(4))); // diff 110 > 100

        List<MatchmakingQueueService.PvpMatchResult> resOver = queueService.matchPvpQueue(now);
        assertTrue(resOver.isEmpty(), "Lệch 110 ELO > 100 ELO không được ghép");
    }

    @Test
    @DisplayName("TC-04: Ngưỡng 15s - 30s: Tối đa 200 ELO và cấm 2 đối thủ gần nhất")
    void testWait15To30SecondsRules() {
        Instant now = Instant.now();
        // A chờ 20s, B chờ 5s -> maxWait = 20s (15s <= maxWait < 30s)
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(20)));
        queueService.getPvpQueue().put(2L, createEntry(2L, 1380, now.minusSeconds(5))); // diff 180 <= 200

        // Đối thủ 2L là trận thứ 3 gần đây -> ở ngưỡng 15-30s chỉ cấm 2 trận gần nhất -> được phép!
        mockRecentOpponents(1L, List.of(99L, 98L, 2L));
        mockRecentOpponents(2L, List.of());

        List<MatchmakingQueueService.PvpMatchResult> res = queueService.matchPvpQueue(now);
        assertEquals(1, res.size(), "Lệch 180 <= 200 ELO và đối thủ là trận thứ 3 được phép ghép");

        // Nếu đối thủ là trận gần nhất (index 0) -> bị cấm
        queueService.clearPvpQueue();
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(20)));
        queueService.getPvpQueue().put(2L, createEntry(2L, 1380, now.minusSeconds(5)));
        mockRecentOpponents(1L, List.of(2L)); // 2L là trận vừa đấu xong

        List<MatchmakingQueueService.PvpMatchResult> resRecent = queueService.matchPvpQueue(now);
        assertTrue(resRecent.isEmpty(), "Đối thủ là trận gần nhất bị cấm dưới 30s");
    }

    @Test
    @DisplayName("TC-05: Ngưỡng >= 30s: Ưu tiên đối thủ mới, chỉ tái đấu khi không còn ai khác")
    void testWaitOver30SecondsRematchRules() {
        Instant now = Instant.now();
        // A chờ 35s. Có 2 đối thủ:
        // B (chờ 10s, diff 40 ELO, nhưng là đối thủ trận gần nhất)
        // C (chờ 5s, diff 150 ELO <= 300, là người mới chưa từng đấu)
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(35)));
        queueService.getPvpQueue().put(2L, createEntry(2L, 1240, now.minusSeconds(10)));
        queueService.getPvpQueue().put(3L, createEntry(3L, 1350, now.minusSeconds(5)));

        mockRecentOpponents(1L, List.of(2L)); // 2L là trận vừa đấu
        mockRecentOpponents(2L, List.of(1L));
        mockRecentOpponents(3L, List.of());

        List<MatchmakingQueueService.PvpMatchResult> res = queueService.matchPvpQueue(now);
        assertEquals(1, res.size());
        MatchmakingQueueService.PvpMatchResult match = res.get(0);
        long matchedOpponentId = ((Number) match.sideB().get("userId")).longValue();

        assertEquals(3L, matchedOpponentId, "Bắt buộc ưu tiên ghép với đối thủ mới (User 3), không được tái đấu khi còn lựa chọn khác!");

        // Trường hợp 2: Hàng đợi CHỈ CÒN User 1 và User 2 (đối thủ vừa đấu), maxWait >= 30s
        queueService.clearPvpQueue();
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(35)));
        queueService.getPvpQueue().put(2L, createEntry(2L, 1240, now.minusSeconds(10)));

        mockRecentOpponents(1L, List.of(2L));
        mockRecentOpponents(2L, List.of(1L));

        List<MatchmakingQueueService.PvpMatchResult> rematchRes = queueService.matchPvpQueue(now);
        assertEquals(1, rematchRes.size(), "Khi >= 30s và không còn ai khác thì cho phép tái đấu");
        long rematchId = ((Number) rematchRes.get(0).sideB().get("userId")).longValue();
        assertEquals(2L, rematchId);
    }

    @Test
    @DisplayName("TC-06: Hard Cap tuyệt đối 300 ELO không có ngoại lệ")
    void testHardCap300Elo() {
        Instant now = Instant.now();
        // Cả 2 chờ 150 giây (gần hết 180s timeout), nhưng lệch 301 ELO
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(150)));
        queueService.getPvpQueue().put(2L, createEntry(2L, 1501, now.minusSeconds(150))); // diff 301 > 300

        mockRecentOpponents(1L, List.of());
        mockRecentOpponents(2L, List.of());

        List<MatchmakingQueueService.PvpMatchResult> res = queueService.matchPvpQueue(now);
        assertTrue(res.isEmpty(), "Chênh lệch 301 ELO vượt quá Hard Cap 300 ELO thì tuyệt đối không được ghép!");
    }

    @Test
    @DisplayName("TC-07: Đánh giá toàn bộ hàng đợi, không ghép trùng và không bỏ sót")
    void testFairQueueMultipleUsers() {
        Instant now = Instant.now();
        // 4 người chơi:
        // User 1: chờ 40s, ELO 1200
        // User 2: chờ 35s, ELO 1210
        // User 3: chờ 10s, ELO 1220
        // User 4: chờ 10s, ELO 1230
        queueService.getPvpQueue().put(1L, createEntry(1L, 1200, now.minusSeconds(40)));
        queueService.getPvpQueue().put(2L, createEntry(2L, 1210, now.minusSeconds(35)));
        queueService.getPvpQueue().put(3L, createEntry(3L, 1220, now.minusSeconds(10)));
        queueService.getPvpQueue().put(4L, createEntry(4L, 1230, now.minusSeconds(10)));

        mockRecentOpponents(1L, List.of());
        mockRecentOpponents(2L, List.of());
        mockRecentOpponents(3L, List.of());
        mockRecentOpponents(4L, List.of());

        List<MatchmakingQueueService.PvpMatchResult> res = queueService.matchPvpQueue(now);
        assertEquals(2, res.size(), "Cả 4 người đều phải được ghép thành 2 trận, không ai bị bỏ sót");

        Set<Long> matchedUsers = new HashSet<>();
        for (MatchmakingQueueService.PvpMatchResult m : res) {
            long a = ((Number) m.sideA().get("userId")).longValue();
            long b = ((Number) m.sideB().get("userId")).longValue();
            assertFalse(matchedUsers.contains(a), "Mỗi user chỉ được tham gia tối đa 1 trận");
            assertFalse(matchedUsers.contains(b), "Mỗi user chỉ được tham gia tối đa 1 trận");
            matchedUsers.add(a);
            matchedUsers.add(b);
        }
        assertEquals(4, matchedUsers.size());
    }
}
