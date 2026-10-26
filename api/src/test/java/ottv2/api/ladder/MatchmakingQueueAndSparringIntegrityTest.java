package ottv2.api.ladder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import ottv2.api.auth.AuthDtos.UserResponse;
import ottv2.api.auth.AuthService;
import ottv2.api.profile.ProfileDtos.ProfileResponse;
import ottv2.api.profile.ProfileDtos.RecordMatchRequest;
import ottv2.api.profile.ProfileService;
import ottv2.api.room.MatchmakingController;
import ottv2.api.room.RoomService;

import java.sql.Date;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatchmakingQueueAndSparringIntegrityTest {

    @Mock JdbcTemplate jdbc;
    @Mock AuthService authService;
    @Mock StringRedisTemplate redis;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock TransactionTemplate transaction;

    private final ObjectMapper mapper = new ObjectMapper();

    // =========================================================================
    // 1. Tuyệt đối không cho phép cộng ELO PvP khi đánh với máy (Máy 1-12)
    // =========================================================================
    @Test
    @DisplayName("Fix Lỗi: Bất kỳ trận nào có sự tham gia của SPARRING_MACHINE (Máy 1-12) đều bị ép is_ranked=false và change=0")
    void testSparringMachineCannotIncreasePvPElo() {
        ProfileService service = new ProfileService(jdbc, authService, new RatingCalculator.Elo(32));

        UserResponse user = new UserResponse(100L, "test_student", "test@domain.com", "Test User", "STUDENT", 1200, 1200);
        when(authService.getUser(100L)).thenReturn(user);

        // Giả lập frontend gửi yêu cầu hoàn thành trận đấu với Máy 12 kèm isRanked = true
        RecordMatchRequest request = new RecordMatchRequest(
                "PVP",
                "Máy 12 (Huyền Thoại)",
                2150,
                "SPARRING_MACHINE",
                true, // User thắng
                "A",
                "TARGET_REACHED",
                30,
                1230,
                true // Cố ý gửi isRanked = true
        );

        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM bots"), eq(Integer.class), eq(100L))).thenReturn(1);
        when(jdbc.queryForObject(contains("SELECT"), any(RowMapper.class), eq(100L)))
                .thenReturn(new ProfileService.MatchStats(0, 0, 0, 0, 0, 0, 0, 0, 0));
        when(jdbc.query(contains("SELECT id, opponent"), any(RowMapper.class), eq(100L))).thenReturn(List.of());

        // Thực thi recordMatch
        ProfileResponse response = service.recordMatch(100L, request);

        // VERIFY:
        // 1. UPDATE users SET pvp_elo TUYỆT ĐỐI KHÔNG ĐƯỢC GỌI
        verify(jdbc, never()).update(startsWith("UPDATE users SET pvp_elo"), any(), any());

        // 2. user_matches được chèn với is_ranked = false, elo_change = 0, new_elo = 1200, match_type = 'PRACTICE'
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(
                contains("INSERT INTO user_matches"),
                captor.capture(), captor.capture(), captor.capture(), captor.capture(),
                captor.capture(), captor.capture(), captor.capture(), captor.capture(),
                captor.capture(), captor.capture(), captor.capture(), captor.capture(),
                captor.capture()
        );
        List<Object> capturedArgs = captor.getAllValues();

        // elo_change phải là 0
        assertEquals(0, capturedArgs.get(8), "elo_change phải bằng 0 khi đánh với Sparring Machine");
        // new_elo phải là 1200 (không tăng)
        assertEquals(1200, capturedArgs.get(9), "new_elo phải giữ nguyên 1200");
        // is_ranked phải là false
        assertEquals(false, capturedArgs.get(10), "is_ranked phải bị ép về false");
        // match_type phải là PRACTICE
        assertEquals("PRACTICE", capturedArgs.get(11), "match_type phải là PRACTICE");
    }

    // =========================================================================
    // 2. Hàng đợi PvP ghép với PvP và phát sự kiện MATCH_FOUND
    // =========================================================================
    @Test
    @DisplayName("Matchmaking Queue: PvP chỉ ghép với PvP và gửi sự kiện MATCH_FOUND với thông tin 2 bên và countdown")
    void testPvpQueueMatchmakingAndMatchFoundEvent() {
        RoomService roomService = new RoomService();
        MatchmakingQueueService queueService = new MatchmakingQueueService(
                jdbc, transaction, redis, messagingTemplate, roomService, mapper
        );

        // Mock DB query cho User 1
        Map<String, Object> user1Map = Map.of(
                "student_code", "student_a",
                "email", "a@school.edu.vn",
                "display_name", "Nguyễn Văn A",
                "pvp_elo", 1250
        );
        when(jdbc.queryForMap(contains("SELECT student_code"), eq(1L))).thenReturn(user1Map);

        // Mock DB query cho User 2
        Map<String, Object> user2Map = Map.of(
                "student_code", "student_b",
                "email", "b@school.edu.vn",
                "display_name", "Trần Thị B",
                "pvp_elo", 1280
        );
        when(jdbc.queryForMap(contains("SELECT student_code"), eq(2L))).thenReturn(user2Map);

        // Mock tạo bản ghi match
        when(jdbc.queryForObject(contains("INSERT INTO matches"), eq(Long.class), any(), any(), any(), any(), any()))
                .thenReturn(999L);
        when(jdbc.queryForObject(contains("SELECT public_id FROM matches"), eq(UUID.class), eq(999L)))
                .thenReturn(UUID.randomUUID());

        // User 1 tham gia hàng đợi PvP
        Map<String, Object> join1 = queueService.join(1L, "student_a", "Nguyễn Văn A", "PVP", null);
        assertEquals("QUEUED", join1.get("status"), "User 1 tham gia hàng đợi đầu tiên phải ở trạng thái QUEUED");

        // User 2 tham gia hàng đợi PvP -> Ghép trận thành công!
        Map<String, Object> join2 = queueService.join(2L, "student_b", "Trần Thị B", "PVP", null);
        assertEquals("QUEUED", join2.get("status"), "User 2 vừa vào hàng đợi chưa đủ 3s phải ở trạng thái QUEUED");

        // Sau 4 giây, scheduler quét hàng đợi và ghép trận thành công
        Instant after4s = Instant.now().plusSeconds(4);
        List<MatchmakingQueueService.PvpMatchResult> matched = queueService.matchPvpQueue(after4s);
        assertEquals(1, matched.size(), "Sau khi cả 2 chờ >= 3s, phải ghép thành công 1 trận");
        MatchmakingQueueService.PvpMatchResult join2Match = matched.get(0);
        assertNotNull(join2Match.roomCode(), "Phải có roomCode");
        assertEquals(6, join2Match.roomCode().length(), "roomCode phải đúng 6 chữ số");

        // VERIFY: Bắn sự kiện MATCH_FOUND qua STOMP tới topic cá nhân của cả 2 user
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

        verify(messagingTemplate, atLeast(2)).convertAndSend(topicCaptor.capture(), eventCaptor.capture());
        assertTrue(topicCaptor.getAllValues().contains("/topic/user/student_a"));
        assertTrue(topicCaptor.getAllValues().contains("/topic/user/student_b"));

        // Kiểm tra payload MATCH_FOUND
        Map<String, Object> event = (Map<String, Object>) eventCaptor.getAllValues().get(0);
        assertEquals("MATCH_FOUND", event.get("type"));
        assertEquals("PVP", event.get("mode"));
        assertNotNull(event.get("sideA"));
        assertNotNull(event.get("sideB"));
        assertNotNull(event.get("startsAt"));
    }

    // =========================================================================
    // 3. Cô lập Hàng Đợi: PvP không bao giờ ghép với Bot
    // =========================================================================
    @Test
    @DisplayName("Cô lập Hàng Đợi: Hàng đợi PvP chỉ ghép với PvP, không bao giờ ghép chéo với Bot")
    void testQueueIsolationPvpDoesNotMatchBot() {
        RoomService roomService = new RoomService();
        MatchmakingQueueService queueService = new MatchmakingQueueService(
                jdbc, transaction, redis, messagingTemplate, roomService, mapper
        );

        // User 1 vào hàng đợi PvP
        Map<String, Object> user1Map = Map.of(
                "student_code", "student_a",
                "email", "a@school.edu.vn",
                "display_name", "Nguyễn Văn A",
                "pvp_elo", 1200
        );
        when(jdbc.queryForMap(contains("SELECT student_code"), eq(1L))).thenReturn(user1Map);

        Map<String, Object> pvpJoin = queueService.join(1L, "student_a", "Nguyễn Văn A", "PVP", null);
        assertEquals("QUEUED", pvpJoin.get("status"));

        // User 2 vào hàng đợi BOT
        when(jdbc.query(contains("SELECT id FROM seasons WHERE status='ACTIVE'"), any(RowMapper.class)))
                .thenReturn(List.of(1L));

        doReturn(List.of(new MatchmakingQueueService.BotQueueEntry(
                10L, 2L, "student_b", "student_b", "b@edu.vn", "Trần B",
                "MyBot", 100L, 1, "PYTHON", "artifacts/key.zip", "sha256", 1200, "avatar", Instant.now()
        ))).when(jdbc).query(anyString(), any(RowMapper.class), eq(1L), eq(2L), eq(10L));

        Map<String, Object> botJoin = queueService.join(2L, "student_b", "Trần B", "BOT", 10L);
        assertEquals("QUEUED", botJoin.get("status"), "Bot của User 2 không được phép ghép với PvP của User 1!");

        // Không có trận đấu nào được tạo
        verify(jdbc, never()).queryForObject(contains("INSERT INTO matches"), eq(Long.class), any(), any(), any(), any(), any());
    }

    // =========================================================================
    // 4. Hàng đợi PvP Nhanh (PVP_QUICK - Giao Hữu): Tự động sinh roomCode 6 số và MATCH_FOUND
    // =========================================================================
    @Test
    @DisplayName("PVP_QUICK Matchmaking: Ghép 2 người chơi online, sinh roomCode 6 số và broadcast MATCH_FOUND với đối thủ")
    void testPvpQuickMatchmakingFlow() {
        RoomService roomService = new RoomService();
        MatchmakingQueueService queueService = new MatchmakingQueueService(
                jdbc, transaction, redis, messagingTemplate, roomService, mapper
        );

        // Mock DB query cho User 1
        when(jdbc.queryForMap(contains("SELECT student_code"), eq(10L))).thenReturn(Map.of(
                "student_code", "student_quick_1",
                "email", "q1@school.edu.vn",
                "display_name", "Game Thủ 1",
                "pvp_elo", 1210
        ));

        // Mock DB query cho User 2
        when(jdbc.queryForMap(contains("SELECT student_code"), eq(20L))).thenReturn(Map.of(
                "student_code", "student_quick_2",
                "email", "q2@school.edu.vn",
                "display_name", "Game Thủ 2",
                "pvp_elo", 1230
        ));

        when(jdbc.queryForObject(contains("INSERT INTO matches"), eq(Long.class), any(), any(), any(), any(), any()))
                .thenReturn(888L);
        when(jdbc.queryForObject(contains("SELECT public_id FROM matches"), eq(UUID.class), eq(888L)))
                .thenReturn(UUID.randomUUID());

        // User 1 tham gia hàng đợi PVP_QUICK
        Map<String, Object> join1 = queueService.join(10L, "student_quick_1", "Game Thủ 1", "PVP_QUICK", null);
        assertEquals("QUEUED", join1.get("status"));
        assertEquals("PVP_QUICK", join1.get("mode"));

        // User 2 tham gia hàng đợi PVP_QUICK -> Ghép thành công ngay lập tức!
        Map<String, Object> join2 = queueService.join(20L, "student_quick_2", "Game Thủ 2", "PVP_QUICK", null);
        assertEquals("QUEUED", join2.get("status"));
        assertEquals("PVP_QUICK", join2.get("mode"));
        // Sau 4s, scheduler quét và ghép trận thành công
        Instant after4s = Instant.now().plusSeconds(4);
        List<MatchmakingQueueService.PvpMatchResult> matched = queueService.matchPvpQueue(after4s);
        assertEquals(1, matched.size());
        MatchmakingQueueService.PvpMatchResult mRes = matched.get(0);
        assertNotNull(mRes.roomCode(), "Phải có roomCode 6 số");
        assertEquals(6, mRes.roomCode().length(), "roomCode phải đúng 6 ký tự số");




        // Verify broadcast qua WebSocket tới kênh cá nhân
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

        verify(messagingTemplate, atLeast(2)).convertAndSend(topicCaptor.capture(), eventCaptor.capture());
        assertTrue(topicCaptor.getAllValues().contains("/topic/user/student_quick_1"));
        assertTrue(topicCaptor.getAllValues().contains("/topic/user/student_quick_2"));

        // Kiểm tra payload
        Map<String, Object> event = (Map<String, Object>) eventCaptor.getAllValues().get(0);
        assertEquals("MATCH_FOUND", event.get("type"));
        assertEquals("PVP_QUICK", event.get("mode"));
        assertEquals(false, event.get("isRanked"), "PVP_QUICK phải là trận giao hữu không xếp hạng");
        assertNotNull(event.get("roomCode"));
        assertNotNull(event.get("startsAt"));
        assertNotNull(event.get("sideA"));
        assertNotNull(event.get("sideB"));
    }

    // =========================================================================
    // 5. Cách ly thể thức thời gian: Người chọn 3m KHÔNG ĐƯỢC ghép với người chọn 5m hay 1m
    // =========================================================================
    @Test
    @DisplayName("Cách ly thể thức Fischer Clock: 3m không ghép với 5m; Auto ghép được với thể thức cụ thể")
    void testStrictTimeControlIsolationAndAuto() {
        RoomService roomService = new RoomService();
        MatchmakingQueueService queueService = new MatchmakingQueueService(
                jdbc, transaction, redis, messagingTemplate, roomService, mapper
        );

        when(jdbc.queryForMap(contains("SELECT student_code"), eq(101L))).thenReturn(Map.of(
                "student_code", "user_3m", "email", "3m@test.com", "display_name", "Player 3m", "pvp_elo", 1200
        ));
        when(jdbc.queryForMap(contains("SELECT student_code"), eq(102L))).thenReturn(Map.of(
                "student_code", "user_5m", "email", "5m@test.com", "display_name", "Player 5m", "pvp_elo", 1200
        ));
        when(jdbc.queryForMap(contains("SELECT student_code"), eq(103L))).thenReturn(Map.of(
                "student_code", "user_auto", "email", "auto@test.com", "display_name", "Player Auto", "pvp_elo", 1200
        ));

        when(jdbc.queryForObject(contains("INSERT INTO matches"), eq(Long.class), any(), any(), any(), any(), any()))
                .thenReturn(777L);
        when(jdbc.queryForObject(contains("SELECT public_id FROM matches"), eq(UUID.class), eq(777L)))
                .thenReturn(UUID.randomUUID());

        // 1. User 101 vào hàng đợi với thể thức 3m
        Map<String, Object> res1 = queueService.join(101L, "user_3m", "Player 3m", "PVP", null, "blitz_3_2");
        assertEquals("QUEUED", res1.get("status"));

        // 2. User 102 vào hàng đợi với thể thức 5m -> KHÔNG ĐƯỢC GHÉP VỚI 101!
        Map<String, Object> res2 = queueService.join(102L, "user_5m", "Player 5m", "PVP", null, "rapid_5_3");
        assertEquals("QUEUED", res2.get("status"), "Người chọn 5m tuyệt đối không được ghép với người chọn 3m");

        // 3. User 103 vào hàng đợi với thể thức AUTO -> Ghép được với người đang chờ (ở đây gặp 101 là người đầu tiên thỏa mãn)
        Map<String, Object> res3 = queueService.join(103L, "user_auto", "Player Auto", "PVP", null, "auto");
        assertEquals("QUEUED", res3.get("status"), "Vừa vào hàng đợi chưa đủ 3s phải ở trạng thái QUEUED");

        // Sau 4 giây, scheduler quét hàng đợi và ghép 103 (Auto) với 101 (3+2) thành công
        Instant after4s = Instant.now().plusSeconds(4);
        List<MatchmakingQueueService.PvpMatchResult> matched = queueService.matchPvpQueue(after4s);
        assertEquals(1, matched.size(), "Người chọn Auto phải ghép được với người đang chờ (101)");
        MatchmakingQueueService.PvpMatchResult mRes = matched.get(0);
        assertNotNull(mRes.roomCode(), "Phải có roomCode");


        @SuppressWarnings("unchecked")
        Map<String, Object> matchedTc = mRes.timeControl();
        assertNotNull(matchedTc);
        assertEquals("3+2", matchedTc.get("id"), "Khi ghép Auto với người chọn 3+2, thể thức trận đấu phải là 3+2");
    }

    // =========================================================================
    // 6. Chống "Bóng ma" trong hàng đợi PvP (Heartbeat & Queue TTL 180s)
    // =========================================================================
    @Test
    @DisplayName("Heartbeat & Queue TTL: Tự động loại bỏ bóng ma mất mạng (>10s) và timeout hàng đợi (>=180s)")
    void testGhostEvictionAndQueueTtl() throws Exception {
        RoomService roomService = new RoomService();
        MatchmakingQueueService queueService = new MatchmakingQueueService(
                jdbc, transaction, redis, messagingTemplate, roomService, mapper
        );

        when(jdbc.queryForMap(contains("SELECT student_code"), eq(201L))).thenReturn(Map.of(
                "student_code", "ghost_user", "email", "ghost@test.com", "display_name", "Ghost", "pvp_elo", 1200
        ));
        when(jdbc.queryForMap(contains("SELECT student_code"), eq(202L))).thenReturn(Map.of(
                "student_code", "timeout_user", "email", "timeout@test.com", "display_name", "Timeout", "pvp_elo", 1200
        ));
        when(jdbc.queryForMap(contains("SELECT student_code"), eq(203L))).thenReturn(Map.of(
                "student_code", "active_user", "email", "active@test.com", "display_name", "Active", "pvp_elo", 1200
        ));

        // 1. Cả 3 người tham gia hàng đợi
        queueService.join(201L, "ghost_user", "Ghost", "PVP", null);
        queueService.join(202L, "timeout_user", "Timeout", "PVP", null, "rapid_5_3"); // khác TC để không tự ghép
        queueService.join(203L, "active_user", "Active", "PVP", null, "bullet_1_1");

        assertTrue((Boolean) queueService.getStatus(201L).get("inPvpQueue"));
        assertTrue((Boolean) queueService.getStatus(202L).get("inPvpQueue"));
        assertTrue((Boolean) queueService.getStatus(203L).get("inPvpQueue"));

        // 2. Kiểm tra heartbeat update
        boolean hbResult = queueService.heartbeat(203L);
        assertTrue(hbResult, "Heartbeat cho user đang ở trong hàng đợi phải trả về true");

        // 3. Giả lập: User 201 bị mất mạng (lastSeenAt cách đây 15 giây > 10s)
        java.lang.reflect.Field pvpQueueField = MatchmakingQueueService.class.getDeclaredField("pvpQueue");
        pvpQueueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Long, MatchmakingQueueService.PvpQueueEntry> pvpQueue = (Map<Long, MatchmakingQueueService.PvpQueueEntry>) pvpQueueField.get(queueService);

        pvpQueue.computeIfPresent(201L, (id, e) -> new MatchmakingQueueService.PvpQueueEntry(
                e.userId(), e.username(), e.studentCode(), e.email(), e.displayName(),
                e.elo(), e.avatar(), e.enqueuedAt(), e.queueMode(), e.timeControlKey(),
                Instant.now().minusSeconds(15)
        ));

        // 4. Giả lập: User 202 bị timeout chờ quá lâu (enqueuedAt cách đây 200 giây >= 180s)
        pvpQueue.computeIfPresent(202L, (id, e) -> new MatchmakingQueueService.PvpQueueEntry(
                e.userId(), e.username(), e.studentCode(), e.email(), e.displayName(),
                e.elo(), e.avatar(), Instant.now().minusSeconds(200), e.queueMode(), e.timeControlKey(),
                Instant.now()
        ));

        // 5. Chạy hàm dọn dẹp stale entries
        queueService.cleanupStalePvpEntries();

        // 6. Kiểm tra kết quả:
        // - Ghost user 201 phải bị xóa khỏi RAM
        assertFalse((Boolean) queueService.getStatus(201L).get("inPvpQueue"), "User mất mạng >10s phải bị xóa khỏi pvpQueue");
        // - Timeout user 202 phải bị xóa khỏi RAM
        assertFalse((Boolean) queueService.getStatus(202L).get("inPvpQueue"), "User chờ quá 180s phải bị xóa do timeout");
        // - Active user 203 vẫn phải còn trong hàng đợi
        assertTrue((Boolean) queueService.getStatus(203L).get("inPvpQueue"), "User có heartbeat bình thường phải được giữ lại");
    }
}
