package ottv2.api.room;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ottv2.api.security.ApiPrincipal;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

@RestController
public class RoomController {

    private static final Logger log = LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public RoomController(RoomService roomService,
                          @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
                          @Autowired(required = false) ObjectMapper objectMapper) {
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public RoomController(RoomService roomService) {
        this(roomService, null, null);
    }

    public ResponseEntity<?> createRoom(Map<String, ?> body) {
        return createRoom(body != null ? new HashMap<>(body) : null, null);
    }

    @PostMapping("/api/rooms/create")
    public ResponseEntity<?> createRoom(@RequestBody(required = false) Map<String, Object> body,
                                        @AuthenticationPrincipal ApiPrincipal principal) {
        String hostName = body != null && body.get("hostName") != null ? String.valueOf(body.get("hostName")) : "Chủ Phòng";
        Object timeControl = body != null ? body.get("timeControl") : null;

        RoomSession room = roomService.createRoom(hostName, timeControl);
        if (principal != null) {
            room.setHostUserId(principal.id());
            room.setHostUsername(principal.username());
            if ("Chủ Phòng".equals(hostName) && principal.username() != null) {
                room.setHostName(principal.username());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("code", room.getCode());
        response.put("roomCode", room.getCode());
        response.put("status", room.getStatus().name());
        response.put("isHost", true);
        response.put("hostName", room.getHostName());
        response.put("playerToken", room.getHostPlayerToken());
        response.put("createdAt", room.getCreatedAt().toString());
        if (room.getTimeControl() != null) {
            response.put("timeControl", room.getTimeControl());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    public ResponseEntity<?> joinRoom(String code, Map<String, ?> body) {
        return joinRoom(code, body != null ? new HashMap<>(body) : null, null);
    }

    @PostMapping("/api/rooms/join/{code}")
    public ResponseEntity<?> joinRoom(@PathVariable String code,
                                      @RequestBody(required = false) Map<String, Object> body,
                                      @AuthenticationPrincipal ApiPrincipal principal) {
        String guestName = body != null && body.get("guestName") != null ? String.valueOf(body.get("guestName")) : "Khách";
        try {
            RoomSession room = roomService.joinRoom(code, guestName);
            if (principal != null) {
                room.setGuestUserId(principal.id());
                room.setGuestUsername(principal.username());
                if ("Khách".equals(guestName) && principal.username() != null) {
                    room.setGuestName(principal.username());
                }
            }

            // Bắn (broadcast) sự kiện MATCH_START vào topic /topic/room/{code}
            // để Chủ phòng đang chờ tự động nhận diện và chuyển sang màn hình bàn cờ cùng lúc với Khách
            if (messagingTemplate != null) {
                Map<String, Object> wsEvent = new HashMap<>();
                wsEvent.put("type", "MATCH_START");
                wsEvent.put("roomCode", room.getCode());
                wsEvent.put("status", "PLAYING");
                wsEvent.put("hostName", room.getHostName());
                wsEvent.put("guestName", room.getGuestName());
                wsEvent.put("sender", "SERVER");
                wsEvent.put("ready", true);
                if (room.getTimeControl() != null) {
                    wsEvent.put("timeControl", room.getTimeControl());
                }
                messagingTemplate.convertAndSend("/topic/room/" + code, (Object) wsEvent);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("code", room.getCode());
            response.put("roomCode", room.getCode());
            response.put("status", room.getStatus().name());
            response.put("isHost", false);
            response.put("hostName", room.getHostName());
            response.put("guestName", room.getGuestName());
            response.put("playerToken", room.getGuestPlayerToken());
            if (room.getTimeControl() != null) {
                response.put("timeControl", room.getTimeControl());
            }
            return ResponseEntity.ok(response);
        } catch (RoomNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "ROOM_NOT_FOUND",
                    "message", "Mã phòng không tồn tại hoặc đã hết hạn!"
            ));
        } catch (RoomFullException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "ROOM_FULL",
                    "message", "Phòng đã đủ 2 người chơi!"
            ));
        }
    }

    public ResponseEntity<?> getRoom(String code) {
        return getRoom(code, null, null, null);
    }

    @GetMapping("/api/rooms/{code}")
    public ResponseEntity<?> getRoom(@PathVariable String code,
                                    @AuthenticationPrincipal ApiPrincipal principal,
                                    @RequestParam(required = false) Long userId,
                                    @RequestParam(required = false) String username) {
        RoomSession room = roomService.getRoom(code);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "ROOM_NOT_FOUND",
                    "message", "Mã phòng không tồn tại!"
            ));
        }
        Map<String, Object> response = new HashMap<>();
        response.put("code", room.getCode());
        response.put("roomCode", room.getCode());
        response.put("status", room.getStatus().name());
        response.put("hostName", room.getHostName());
        response.put("guestName", room.getGuestName() != null ? room.getGuestName() : "");
        if (room.getTimeControl() != null) {
            response.put("timeControl", room.getTimeControl());
        }

        // Kiểm tra quyền hạn của người gọi (qua Principal hoặc userId/username) để trả về playerToken tương ứng
        boolean isA = false;
        boolean isB = false;

        if (principal != null) {
            String pName = principal.username();
            Long pId = principal.id();
            if ((pId != null && pId.equals(room.getHostUserId())) ||
                (pName != null && (pName.equalsIgnoreCase(room.getHostUsername()) || pName.equalsIgnoreCase(room.getHostName())))) {
                isA = true;
            } else if ((pId != null && pId.equals(room.getGuestUserId())) ||
                       (pName != null && (pName.equalsIgnoreCase(room.getGuestUsername()) || pName.equalsIgnoreCase(room.getGuestName())))) {
                isB = true;
            }
        }

        if (!isA && !isB) {
            if (userId != null && userId.equals(room.getHostUserId())) {
                isA = true;
            } else if (userId != null && userId.equals(room.getGuestUserId())) {
                isB = true;
            } else if (username != null && !username.isBlank()) {
                String u = username.trim();
                if (u.equalsIgnoreCase(room.getHostUsername()) || u.equalsIgnoreCase(room.getHostName())) {
                    isA = true;
                } else if (u.equalsIgnoreCase(room.getGuestUsername()) || u.equalsIgnoreCase(room.getGuestName())) {
                    isB = true;
                }
            }
        }

        if (isA) {
            response.put("isHost", true);
            response.put("playerToken", room.getHostPlayerToken());
        } else if (isB) {
            response.put("isHost", false);
            response.put("playerToken", room.getGuestPlayerToken());
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/rooms/{code}/leave")
    public ResponseEntity<?> leaveRoomHttp(@PathVariable String code,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           @AuthenticationPrincipal ApiPrincipal principal) {
        RoomSession room = roomService.getRoom(code);
        if (room != null) {
            String sender = body != null && body.get("sender") != null ? String.valueOf(body.get("sender")).trim().toUpperCase() : "A";
            room.setPlayerLeft(sender);
            if (messagingTemplate != null) {
                try {
                    Map<String, Object> event = new HashMap<>();
                    event.put("type", "PLAYER_LEFT");
                    event.put("roomCode", code);
                    event.put("sender", sender);
                    event.put("reason", "OPPONENT_LEFT");
                    messagingTemplate.convertAndSend((String) ("/topic/room/" + code), (Object) event);
                } catch (Exception e) {
                    log.warn("Lỗi broadcast PLAYER_LEFT via HTTP leave: {}", e.getMessage());
                }
            }
        }
        return ResponseEntity.ok(Map.of("status", "LEFT"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(Object rawPayload) {
        if (rawPayload == null) {
            return new HashMap<>();
        }
        if (rawPayload instanceof Map) {
            return (Map<String, Object>) rawPayload;
        }
        try {
            String str;
            if (rawPayload instanceof byte[] bytes) {
                str = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            } else if (rawPayload instanceof String s) {
                str = s.trim();
            } else {
                return objectMapper.convertValue(rawPayload, new TypeReference<Map<String, Object>>() {});
            }

            if (str.startsWith("{") && str.endsWith("}")) {
                return objectMapper.readValue(str, new TypeReference<Map<String, Object>>() {});
            }

            // If str is a quoted string: e.g. "\"{\\\"type\\\":...}\"" or "\"eyJ...\""
            if (str.startsWith("\"") && str.endsWith("\"")) {
                String inner = objectMapper.readValue(str, String.class);
                if (inner.startsWith("{") && inner.endsWith("}")) {
                    return objectMapper.readValue(inner, new TypeReference<Map<String, Object>>() {});
                }
                // Try Base64 decode
                try {
                    byte[] decoded = java.util.Base64.getDecoder().decode(inner);
                    String decodedStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (decodedStr.startsWith("{") && decodedStr.endsWith("}")) {
                        return objectMapper.readValue(decodedStr, new TypeReference<Map<String, Object>>() {});
                    }
                } catch (Exception ignored) {
                }
            }

            return objectMapper.readValue(str, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse STOMP payload as Map: {}", e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("raw", rawPayload.toString());
            return fallback;
        }
    }

    // STOMP Message Handler: Lắng nghe ở @MessageMapping("/room/{code}/move") và broadcast lại nước đi cho cả 2 người qua @SendTo("/topic/room/{code}")
    @MessageMapping("/room/{code}/move")
    @SendTo("/topic/room/{code}")
    public Map<String, Object> handleMove(@DestinationVariable String code,
                                          @Payload(required = false) Object rawPayload,
                                          Principal principal) {
        try {
            RoomSession room = roomService.getRoom(code);
            if (room == null) {
                log.warn("[STOMP MOVE SECURITY] Bị từ chối: Phòng '{}' không tồn tại!", code);
                return null;
            }
            if (room.getStatus() == RoomSession.Status.FINISHED) {
                log.warn("[STOMP MOVE SECURITY] Bị từ chối: Phòng '{}' đã kết thúc!", code);
                return null;
            }

            Map<String, Object> moveData = parsePayload(rawPayload);
            String sender = moveData.get("sender") != null ? String.valueOf(moveData.get("sender")).trim().toUpperCase() : "";
            if (!"A".equals(sender) && !"B".equals(sender)) {
                log.warn("[STOMP MOVE SECURITY] Bị từ chối: Phòng '{}' sender không hợp lệ '{}'", code, sender);
                return null;
            }

            String playerToken = moveData.get("playerToken") != null ? String.valueOf(moveData.get("playerToken")).trim() : null;
            if (!room.isSenderAuthorized(sender, playerToken, principal)) {
                log.warn("[STOMP MOVE SECURITY] Bị từ chối: Sender '{}' không có quyền đi cờ trong phòng '{}' (sai playerToken/principal)", sender, code);
                return null;
            }

            room.updateLastActive();
            log.info("[STOMP MOVE] Room {}: sender={}, pieceId={}, from={}, to={}", code, sender, moveData.get("pieceId"), moveData.get("from"), moveData.get("to"));
            moveData.remove("playerToken");
            return moveData;
        } catch (Exception e) {
            log.error("Lỗi khi xử lý STOMP move phòng {}: {}", code, e.getMessage(), e);
            return null;
        }
    }

    public Map<String, Object> handleMove(String code, Object rawPayload) {
        return handleMove(code, rawPayload, null);
    }

    // STOMP Message Handler: Lắng nghe các action khác (ready, clock_sync, restart, chat, leave)
    @MessageMapping("/room/{code}/action")
    @SendTo("/topic/room/{code}")
    public Map<String, Object> handleAction(@DestinationVariable String code,
                                            @Payload(required = false) Object rawPayload,
                                            Principal principal) {
        try {
            RoomSession room = roomService.getRoom(code);
            if (room == null) {
                log.warn("[STOMP ACTION SECURITY] Bị từ chối: Phòng '{}' không tồn tại!", code);
                return null;
            }

            Map<String, Object> actionData = parsePayload(rawPayload);
            String actionType = actionData.get("type") != null ? String.valueOf(actionData.get("type")).trim() : "";
            String sender = actionData.get("sender") != null ? String.valueOf(actionData.get("sender")).trim().toUpperCase() : "";

            if (!"A".equals(sender) && !"B".equals(sender) && !"SERVER".equals(sender)) {
                log.warn("[STOMP ACTION SECURITY] Bị từ chối: Phòng '{}' sender không hợp lệ '{}'", code, sender);
                return null;
            }

            String playerToken = actionData.get("playerToken") != null ? String.valueOf(actionData.get("playerToken")).trim() : null;
            if (!"SERVER".equals(sender) && !room.isSenderAuthorized(sender, playerToken, principal)) {
                log.warn("[STOMP ACTION SECURITY] Bị từ chối: Sender '{}' không có quyền gửi action '{}' trong phòng '{}' (sai playerToken/principal)", sender, actionType, code);
                return null;
            }

            room.updateLastActive();
            log.info("[STOMP ACTION] Room {}: type={}, sender={}", code, actionType, sender);

            if ("PLAYER_LEFT".equalsIgnoreCase(actionType)) {
                room.setPlayerLeft(sender);
            }
            actionData.remove("playerToken");
            actionData.put("roomCode", code);
            if (messagingTemplate != null) {
                try {
                    messagingTemplate.convertAndSend((String) ("/topic/room/" + code), (Object) actionData);
                } catch (Exception e) {
                    log.warn("Lỗi messagingTemplate khi gửi action: {}", e.getMessage());
                }
            }
            return actionData;
        } catch (Exception e) {
            log.error("Lỗi khi xử lý STOMP action phòng {}: {}", code, e.getMessage(), e);
            return null;
        }
    }

    public Map<String, Object> handleAction(String code, Object rawPayload) {
        return handleAction(code, rawPayload, null);
    }

    // Bắt và xử lý tập trung mọi ngoại lệ STOMP trong controller để không bị ngắt kết nối WebSocket session
    @MessageExceptionHandler
    public void handleMessageException(Exception e) {
        log.error("Unhandled WebSocket message error: {}", e.getMessage(), e);
    }
}
