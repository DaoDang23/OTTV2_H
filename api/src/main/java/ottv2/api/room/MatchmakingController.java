package ottv2.api.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ottv2.api.security.ApiPrincipal;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class MatchmakingController {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingController.class);

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;
    private final JdbcTemplate jdbc;
    private final ottv2.api.ladder.MatchmakingQueueService queueService;

    public record SparringMachine(int level, String name, int elo, String algorithm, String description, String avatarSeed) {}

    public static final List<SparringMachine> SPARRING_MACHINES = List.of(
            new SparringMachine(1, "Máy 1 (Tân Binh)", 1050, "Ngẫu Nhiên", "Chọn ngẫu nhiên một nước đi hợp lệ bất kỳ", "Spar1"),
            new SparringMachine(2, "Máy 2 (Tập Sự)", 1120, "Tiến Thẳng", "Luôn ưu tiên đẩy quân về phía trước", "Spar2"),
            new SparringMachine(3, "Máy 3 (Cảm Tử)", 1200, "Hổ Báo", "Phát hiện quân địch trong tầm là lao vào ăn", "Spar3"),
            new SparringMachine(4, "Máy 4 (Hộ Vệ)", 1280, "Phòng Thủ", "Bố trí quân chặn cửa ô đích I1", "Spar4"),
            new SparringMachine(5, "Máy 5 (Tốc Biến)", 1360, "Đua Đích", "Dồn toàn lực đưa quân gần ô đích nhất về đích", "Spar5"),
            new SparringMachine(6, "Máy 6 (Cẩn Trọng)", 1450, "Né Thiên Địch", "Tuyệt đối không bước vào ô có quân khắc chế", "Spar6"),
            new SparringMachine(7, "Máy 7 (Bầy Đàn)", 1550, "Phalanx", "Di chuyển quân Kéo - Búa - Bao thành cụm tam giác", "Spar7"),
            new SparringMachine(8, "Máy 8 (Chiến Thuật)", 1650, "Đánh Giá Đa Tầng", "Cân bằng giữa khoảng cách đích, ăn quân và an toàn", "Spar8"),
            new SparringMachine(9, "Máy 9 (Bậc Thầy)", 1760, "Đánh Giá Đa Tầng v2", "Kiểm soát trung tâm, đổi quân có lợi", "Spar9"),
            new SparringMachine(10, "Máy 10 (Kiện Tướng)", 1880, "Minimax Cận Chiến", "Tính toán dự đoán nước đi của đối phương", "Spar10"),
            new SparringMachine(11, "Máy 11 (Đại Kiện Tướng)", 2010, "Minimax Đa Tầng", "Triệt tiêu quân chủ lực của bạn", "Spar11"),
            new SparringMachine(12, "Máy 12 (Huyền Thoại)", 2150, "Grandmaster Heuristic", "Tối ưu hóa toàn diện: Đua đích + Đồ sát", "Spar12")
    );

    @Autowired
    public MatchmakingController(RoomService roomService,
                                 @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
                                 @Autowired(required = false) JdbcTemplate jdbc,
                                 @Autowired(required = false) ottv2.api.ladder.MatchmakingQueueService queueService) {
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
        this.jdbc = jdbc;
        this.queueService = queueService;
    }

    public MatchmakingController(RoomService roomService,
                                 SimpMessagingTemplate messagingTemplate,
                                 JdbcTemplate jdbc) {
        this(roomService, messagingTemplate, jdbc, null);
    }

    public MatchmakingController(RoomService roomService) {
        this(roomService, null, null, null);
    }

    /**
     * Endpoint gửi lời mời thách đấu thời gian thực qua WebSocket
     * POST /api/challenge/send
     * Payload: { targetUsername, timeControl, fromUser? }
     */
    @PostMapping("/api/challenge/send")
    public ResponseEntity<?> sendChallenge(
            @AuthenticationPrincipal ApiPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        String fromUser = principal != null ? principal.username() : null;
        if (fromUser == null && body != null && body.get("fromUser") != null) {
            fromUser = String.valueOf(body.get("fromUser"));
        }
        if (fromUser == null || fromUser.isBlank()) {
            fromUser = "Người chơi";
        }

        String targetUsername = body != null && body.get("targetUsername") != null
                ? String.valueOf(body.get("targetUsername")).trim()
                : null;
        if (targetUsername == null || targetUsername.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "TARGET_REQUIRED",
                    "message", "Thiếu targetUsername!"
            ));
        }

        Object timeControl = body != null ? body.get("timeControl") : null;

        // 1. Tạo phòng 6 chữ số
        RoomSession room = roomService.createRoom(fromUser, timeControl);
        String roomCode = room.getCode();

        // 2. Gửi thông điệp CHALLENGE_INVITE tới topic cá nhân của đối thủ: /topic/user/{targetUsername}
        if (messagingTemplate != null) {
            Map<String, Object> invite = new HashMap<>();
            invite.put("type", "CHALLENGE_INVITE");
            invite.put("fromUser", fromUser);
            invite.put("sender", fromUser);
            invite.put("roomId", roomCode);
            invite.put("roomCode", roomCode);
            invite.put("timeControl", timeControl);
            invite.put("timestamp", System.currentTimeMillis());

            try {
                messagingTemplate.convertAndSend("/topic/user/" + targetUsername, (Object) invite);
                log.info("Gửi lời mời thách đấu từ {} tới /topic/user/{}, phòng: {}", fromUser, targetUsername, roomCode);
            } catch (Exception e) {
                log.error("Lỗi khi gửi WebSocket challenge invite tới {}: {}", targetUsername, e.getMessage());
            }

            // Nếu trong DB đối thủ có cả student_code hoặc email khác với targetUsername, gửi thêm để chắc chắn nhận được
            if (jdbc != null) {
                try {
                    List<Map<String, Object>> targetRows = jdbc.queryForList(
                            "SELECT student_code, email FROM users WHERE student_code = ? OR email = ?",
                            targetUsername, targetUsername
                    );
                    for (Map<String, Object> row : targetRows) {
                        String sc = (String) row.get("student_code");
                        String em = (String) row.get("email");
                        if (sc != null && !sc.equalsIgnoreCase(targetUsername)) {
                            messagingTemplate.convertAndSend("/topic/user/" + sc, (Object) invite);
                        }
                        if (em != null) {
                            String prefix = em.contains("@") ? em.substring(0, em.indexOf('@')) : em;
                            if (!prefix.equalsIgnoreCase(targetUsername) && !em.equalsIgnoreCase(targetUsername)) {
                                messagingTemplate.convertAndSend("/topic/user/" + prefix, (Object) invite);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("roomCode", roomCode);
        res.put("code", roomCode);
        res.put("isHost", true);
        res.put("hostName", fromUser);
        res.put("playerToken", room.getHostPlayerToken());
        res.put("targetUsername", targetUsername);
        res.put("timeControl", timeControl);
        return ResponseEntity.ok(res);
    }

    /**
     * Endpoint Ghép trận ngẫu nhiên (chênh lệch ELO <= 300)
     * POST /api/matchmaking/random
     * Payload: { pvpElo?, username? }
     */
    @PostMapping("/api/matchmaking/random")
    public ResponseEntity<?> randomMatchmaking(
            @AuthenticationPrincipal ApiPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {

        int userElo = 1200;
        long currentUserId = -1;
        String currentUserName = "Người chơi";

        if (principal != null) {
            currentUserId = principal.id();
            currentUserName = principal.username();
        }

        if (body != null && body.get("pvpElo") != null) {
            try {
                userElo = Integer.parseInt(String.valueOf(body.get("pvpElo")));
            } catch (NumberFormatException ignored) {}
        } else if (principal != null && jdbc != null) {
            try {
                Integer dbElo = jdbc.queryForObject("SELECT pvp_elo FROM users WHERE id = ?", Integer.class, principal.id());
                if (dbElo != null) userElo = dbElo;
            } catch (Exception ignored) {}
        }

        if (body != null && body.get("username") != null && principal == null) {
            currentUserName = String.valueOf(body.get("username"));
        }

        List<Map<String, Object>> candidates = new ArrayList<>();

        // 1. Tìm người chơi thật trong Database có chênh lệch ELO <= 300
        if (jdbc != null) {
            try {
                List<Map<String, Object>> dbUsers = jdbc.query(
                        "SELECT id, student_code, email, display_name, COALESCE(pvp_elo, 1200) AS pvp_elo " +
                        "FROM users WHERE id <> ? AND ABS(COALESCE(pvp_elo, 1200) - ?) <= 300",
                        (rs, rowNum) -> {
                            Map<String, Object> map = new HashMap<>();
                            map.put("id", rs.getLong("id"));
                            String dName = rs.getString("display_name");
                            String sCode = rs.getString("student_code");
                            map.put("name", dName != null && !dName.isBlank() ? dName : (sCode != null ? sCode : "Người chơi"));
                            map.put("displayName", dName);
                            map.put("studentCode", sCode);
                            map.put("email", rs.getString("email"));
                            map.put("username", sCode != null && !sCode.isBlank() ? sCode : rs.getString("email"));
                            int elo = rs.getInt("pvp_elo");
                            map.put("elo", elo);
                            map.put("pvpElo", elo);
                            map.put("isSparring", false);
                            map.put("isMachine", false);
                            return map;
                        },
                        currentUserId, userElo
                );
                candidates.addAll(dbUsers);
            } catch (Exception e) {
                log.warn("Database query for random matchmaking failed: {}", e.getMessage());
            }
        }

        boolean isRankedMatch = true;
        // 2. Nếu chưa có người chơi thật online, hỗ trợ Máy Luyện Tập nhưng TUYỆT ĐỐI KHÔNG tính Ranked (isRanked = false, Mục 3.3)
        if (candidates.isEmpty()) {
            isRankedMatch = false;
            for (SparringMachine m : SPARRING_MACHINES) {
                if (Math.abs(m.elo() - userElo) <= 300) {
                    Map<String, Object> machineOpponent = new HashMap<>();
                    machineOpponent.put("id", "sys_machine_" + m.level());
                    machineOpponent.put("name", m.name());
                    machineOpponent.put("displayName", m.name());
                    machineOpponent.put("elo", m.elo());
                    machineOpponent.put("pvpElo", m.elo());
                    machineOpponent.put("level", m.level());
                    machineOpponent.put("aiLevel", m.level());
                    machineOpponent.put("isSparring", true);
                    machineOpponent.put("isMachine", true);
                    machineOpponent.put("isRanked", false);
                    machineOpponent.put("avatarSeed", m.avatarSeed());
                    machineOpponent.put("algorithm", m.algorithm());
                    machineOpponent.put("description", m.description());
                    candidates.add(machineOpponent);
                }
            }
        }

        if (candidates.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "NO_MATCH_FOUND",
                    "message", "Hiện không có đối thủ nào phù hợp!"
            ));
        }

        // Chọn ngẫu nhiên 1 đối thủ trong danh sách hợp lệ
        Map<String, Object> chosenOpponent = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));

        Map<String, Object> defaultTc = new HashMap<>();
        defaultTc.put("id", "3+2");
        defaultTc.put("name", "⚡ Chớp 3+2");
        defaultTc.put("initialSeconds", 180);
        defaultTc.put("incrementSeconds", 2);
        defaultTc.put("baseMs", 180000);
        defaultTc.put("incMs", 2000);
        defaultTc.put("label", "3+2");

        RoomSession room = roomService.createRoom(currentUserName, defaultTc);

        // Nếu đối thủ được chọn là người chơi thật, bắn lời mời thách đấu tới topic của họ
        if (!Boolean.TRUE.equals(chosenOpponent.get("isMachine")) && messagingTemplate != null) {
            String targetUsername = String.valueOf(chosenOpponent.get("username"));
            Map<String, Object> invite = new HashMap<>();
            invite.put("type", "CHALLENGE_INVITE");
            invite.put("fromUser", currentUserName);
            invite.put("sender", currentUserName);
            invite.put("roomId", room.getCode());
            invite.put("roomCode", room.getCode());
            invite.put("timeControl", defaultTc);
            invite.put("timestamp", System.currentTimeMillis());

            try {
                messagingTemplate.convertAndSend("/topic/user/" + targetUsername, (Object) invite);
            } catch (Exception e) {
                log.warn("Lỗi gửi lời mời ghép trận tới {}: {}", targetUsername, e.getMessage());
            }
        }

        boolean isMachine = Boolean.TRUE.equals(chosenOpponent.get("isMachine")) || Boolean.TRUE.equals(chosenOpponent.get("isSparring"));
        if (isMachine) {
            isRankedMatch = false;
            chosenOpponent.put("isRanked", false);
            chosenOpponent.put("opponentType", "SPARRING_MACHINE");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("roomCode", room.getCode());
        response.put("code", room.getCode());
        response.put("isHost", true);
        response.put("playerToken", room.getHostPlayerToken());
        response.put("opponent", chosenOpponent);
        response.put("timeControl", defaultTc);
        response.put("isRanked", isRankedMatch && !isMachine);
        response.put("change", 0);
        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint tham gia hàng đợi ghép trận (Matchmaking Queue)
     * POST /api/matchmaking/join
     * Payload: { "mode": "PVP" | "BOT", "botId": <Long|null> }
     */
    @PostMapping("/api/matchmaking/join")
    public ResponseEntity<?> joinQueue(
            @AuthenticationPrincipal ApiPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        long userId = principal != null ? principal.id() : -1L;
        String username = principal != null ? principal.username() : null;
        String displayName = principal != null ? principal.username() : null;

        if (userId <= 0 && body != null && body.get("userId") != null) {
            try {
                userId = Long.parseLong(String.valueOf(body.get("userId")));
            } catch (Exception ignored) {}
        }
        if (username == null && body != null) {
            if (body.get("username") != null) {
                username = String.valueOf(body.get("username"));
            } else if (body.get("studentCode") != null) {
                username = String.valueOf(body.get("studentCode"));
            } else if (body.get("email") != null) {
                username = String.valueOf(body.get("email"));
            }
        }
        if (displayName == null && body != null && body.get("displayName") != null) {
            displayName = String.valueOf(body.get("displayName"));
        }
        if (userId <= 0 && username != null && !username.isBlank() && jdbc != null) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT id, student_code, email, display_name FROM users WHERE student_code = ? OR email = ? OR display_name = ? LIMIT 1",
                        username, username, username
                );
                if (!rows.isEmpty()) {
                    userId = ((Number) rows.getFirst().get("id")).longValue();
                    if (displayName == null) {
                        displayName = (String) rows.getFirst().get("display_name");
                    }
                    if (username == null) {
                        username = (String) rows.getFirst().get("student_code");
                    }
                }
            } catch (Exception ignored) {}
        }
        if (userId > 0 && (username == null || displayName == null) && jdbc != null) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT id, student_code, email, display_name FROM users WHERE id = ? LIMIT 1",
                        userId
                );
                if (!rows.isEmpty()) {
                    if (username == null) {
                        username = (String) rows.getFirst().get("student_code");
                    }
                    if (displayName == null) {
                        displayName = (String) rows.getFirst().get("display_name");
                    }
                }
            } catch (Exception ignored) {}
        }
        if (userId <= 0) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "UNAUTHORIZED",
                    "message", "Vui lòng đăng nhập để tham gia hàng đợi ghép trận!"
            ));
        }

        String mode = body != null && body.get("mode") != null ? String.valueOf(body.get("mode")) : "PVP";
        Long botId = null;
        if (body != null && body.get("botId") != null) {
            try {
                botId = Long.parseLong(String.valueOf(body.get("botId")));
            } catch (Exception ignored) {}
        }
        String timeControlKey = null;
        if (body != null) {
            if (body.get("timeControlKey") != null) {
                timeControlKey = String.valueOf(body.get("timeControlKey"));
            } else if (body.get("timeControl") != null) {
                timeControlKey = String.valueOf(body.get("timeControl"));
            }
        }

        if (queueService != null) {
            Map<String, Object> result = queueService.join(userId, username, displayName, mode, botId, timeControlKey);
            return ResponseEntity.ok(result);
        }

        return ResponseEntity.ok(Map.of("status", "QUEUED", "mode", mode));
    }

    /**
     * Endpoint rời khỏi hàng đợi ghép trận
     * POST /api/matchmaking/leave
     * Payload: { "mode": "PVP" | "BOT" }
     */
    @PostMapping("/api/matchmaking/leave")
    public ResponseEntity<?> leaveQueue(
            @AuthenticationPrincipal ApiPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        long userId = principal != null ? principal.id() : -1L;
        if (userId <= 0 && body != null && body.get("userId") != null) {
            try {
                userId = Long.parseLong(String.valueOf(body.get("userId")));
            } catch (Exception ignored) {}
        }
        if (userId <= 0) {
            return ResponseEntity.ok(Map.of("status", "LEFT"));
        }

        String mode = body != null && body.get("mode") != null ? String.valueOf(body.get("mode")) : null;
        if (queueService != null) {
            return ResponseEntity.ok(queueService.leave(userId, mode));
        }
        return ResponseEntity.ok(Map.of("status", "LEFT"));
    }

    /**
     * Endpoint kiểm tra trạng thái hàng đợi
     * GET /api/matchmaking/status
     */
    @org.springframework.web.bind.annotation.GetMapping("/api/matchmaking/status")
    public ResponseEntity<?> getQueueStatus(@AuthenticationPrincipal ApiPrincipal principal) {
        long userId = principal != null ? principal.id() : -1L;
        if (userId <= 0 || queueService == null) {
            return ResponseEntity.ok(Map.of("inQueue", false));
        }
        return ResponseEntity.ok(queueService.getStatus(userId));
    }

    /**
     * Endpoint heartbeat / ping định kỳ khi đang tìm trận để chống "bóng ma" hàng đợi.
     * Tuyệt đối không log console tại đây để tránh spam.
     * POST /api/matchmaking/heartbeat
     */
    @PostMapping("/api/matchmaking/heartbeat")
    public ResponseEntity<?> heartbeat(
            @AuthenticationPrincipal ApiPrincipal principal,
            @RequestBody(required = false) Map<String, Object> body) {
        long userId = principal != null ? principal.id() : -1L;
        if (userId <= 0 && body != null && body.get("userId") != null) {
            try {
                userId = Long.parseLong(String.valueOf(body.get("userId")));
            } catch (Exception ignored) {}
        }
        if (userId <= 0) {
            return ResponseEntity.ok(Map.of("inQueue", false, "heartbeat", false));
        }

        if (queueService != null) {
            boolean alive = queueService.heartbeat(userId);
            Map<String, Object> status = new HashMap<>(queueService.getStatus(userId));
            status.put("heartbeat", alive);
            return ResponseEntity.ok(status);
        }
        return ResponseEntity.ok(Map.of("inQueue", false, "heartbeat", false));
    }
}
