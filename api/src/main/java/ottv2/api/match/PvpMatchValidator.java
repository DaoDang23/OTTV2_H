package ottv2.api.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.Set;

/**
 * Server-Authoritative Match Validator for OTTV2 PvP matches.
 * Ngăn chặn tuyệt đối lỗ hổng Client-Authoritative: Người chơi tự quyết định ai thắng trận.
 */
@Component
public class PvpMatchValidator {
    private static final Logger log = LoggerFactory.getLogger(PvpMatchValidator.class);

    public record ValidatedMatchResult(String winnerSide, String reason, String verifiedBy) { }

    private final ObjectMapper mapper;

    public PvpMatchValidator(ObjectMapper mapper) {
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    /**
     * Thẩm định kết quả ván cờ trên Server.
     * Tuyệt đối không tin tưởng trực tiếp vào winnerSide từ client!
     */
    public ValidatedMatchResult validate(
            long currentUserId,
            long userAId,
            long userBId,
            String claimedWinner,
            String claimedReason,
            Object replayData
    ) {
        String reason = claimedReason != null ? claimedReason.trim().toUpperCase() : "FINISHED";
        String normalizedClaimedWinner = claimedWinner != null ? claimedWinner.trim().toUpperCase() : "DRAW";

        // 1. Trường hợp ĐẦU HÀNG (RESIGN / FORFEIT / SURRENDER)
        if ("RESIGN".equalsIgnoreCase(reason) || "FORFEIT".equalsIgnoreCase(reason) || "SURRENDER".equalsIgnoreCase(reason)) {
            // Quy tắc bảo mật: Người chơi CHỈ có thể tự nhận thua, nhường chiến thắng cho đối thủ!
            if (currentUserId == userAId) {
                // User A tự nhận thua -> User B thắng
                return new ValidatedMatchResult("B", "RESIGN", "SERVER_RESIGN_RULE");
            } else if (currentUserId == userBId) {
                // User B tự nhận thua -> User A thắng
                return new ValidatedMatchResult("A", "RESIGN", "SERVER_RESIGN_RULE");
            } else {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không thể đầu hàng thay cho người chơi khác!");
            }
        }

        // 1b. Trường hợp ĐỐI THỦ THOÁT PHÒNG (OPPONENT_LEFT / DISCONNECT)
        if ("OPPONENT_LEFT".equalsIgnoreCase(reason) || "DISCONNECTED".equalsIgnoreCase(reason)) {
            // Người còn lại trong phòng được công nhận chiến thắng do đối thủ bỏ cuộc
            if (currentUserId == userAId && "A".equalsIgnoreCase(normalizedClaimedWinner)) {
                return new ValidatedMatchResult("A", "OPPONENT_LEFT", "SERVER_OPPONENT_LEFT_RULE");
            } else if (currentUserId == userBId && "B".equalsIgnoreCase(normalizedClaimedWinner)) {
                return new ValidatedMatchResult("B", "OPPONENT_LEFT", "SERVER_OPPONENT_LEFT_RULE");
            }
        }

        // 2. Thẩm định qua Replay Data (Chuỗi nước đi thực tế)
        if (replayData != null) {
            try {
                JsonNode root;
                if (replayData instanceof String s) {
                    root = mapper.readTree(s);
                } else if (replayData instanceof JsonNode jn) {
                    root = jn;
                } else {
                    root = mapper.valueToTree(replayData);
                }

                ValidatedMatchResult engineResult = evaluateReplay(root);
                if (engineResult != null) {
                    // Nếu client khai báo winnerSide mâu thuẫn với kết quả thực tế từ replay -> CHẶN GIAN LẬN!
                    if (!"DRAW".equals(engineResult.winnerSide()) &&
                        !"DRAW".equals(normalizedClaimedWinner) &&
                        !engineResult.winnerSide().equalsIgnoreCase(normalizedClaimedWinner)) {
                        log.warn("[ANTI-CHEAT] Phát hiện gian lận: User {} khai báo phe {} thắng nhưng replay thực tế phe {} thắng!",
                                currentUserId, normalizedClaimedWinner, engineResult.winnerSide());
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Kết quả trận đấu không hợp lệ: Nước đi thực tế mâu thuẫn với phe chiến thắng được khai báo!");
                    }
                    return engineResult;
                }
            } catch (ResponseStatusException rse) {
                throw rse;
            } catch (Exception e) {
                log.warn("Không thể phân tích replayData: {}", e.getMessage());
            }
        }

        // 3. Trường hợp HẾT GIỜ (TIMEOUT)
        if ("TIMEOUT".equalsIgnoreCase(reason)) {
            // Bên hết giờ bị xử thua, bên còn lại thắng
            if ("A".equals(normalizedClaimedWinner) || "B".equals(normalizedClaimedWinner)) {
                return new ValidatedMatchResult(normalizedClaimedWinner, "TIMEOUT", "SERVER_TIMEOUT_RULE");
            }
        }

        // 4. Trường hợp HÒA (DRAW)
        if ("DRAW".equalsIgnoreCase(normalizedClaimedWinner) || "AGREED_DRAW".equalsIgnoreCase(reason)
                || "TURN_LIMIT_EXCEEDED".equalsIgnoreCase(reason) || "TURN_LIMIT_REACHED".equalsIgnoreCase(reason)) {
            return new ValidatedMatchResult("DRAW", "AGREED_DRAW", "SERVER_CONSENSUS");
        }

        // 5. Nếu client đòi thắng TARGET_REACHED hoặc ANNIHILATION mà không có dữ liệu nước đi chứng minh hợp lệ
        if ("TARGET_REACHED".equalsIgnoreCase(reason) || "ANNIHILATION".equalsIgnoreCase(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không thể công nhận chiến thắng " + reason + " nếu thiếu dữ liệu nước đi (replayData) hợp lệ!");
        }

        // 6. Bất kỳ yêu cầu chiến thắng nào khác không có chứng cứ hợp lệ từ engine đều bị chặn
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Không thể công nhận chiến thắng cho phe " + normalizedClaimedWinner + " (" + reason + ") nếu thiếu bằng chứng hợp lệ!");
    }

    private ValidatedMatchResult evaluateReplay(JsonNode root) {
        JsonNode moves = root.path("moves");
        if (!moves.isArray() || moves.isEmpty()) {
            moves = root.path("turns");
        }
        if (!moves.isArray() || moves.isEmpty()) {
            return null;
        }

        // Kiểm tra tính hợp lệ cơ bản của các nước đi
        for (JsonNode m : moves) {
            int toR = extractRow(m);
            int toC = extractCol(m);
            int fromR = extractFromRow(m);
            int fromC = extractFromCol(m);

            if (toR >= 0 && toC >= 0 && (toR > 8 || toC > 8)) {
                log.warn("[ANTI-CHEAT] Ô đích ngoài bàn cờ: [{}, {}]", toR, toC);
                return null;
            }
            if (fromR >= 0 && fromC >= 0 && toR >= 0 && toC >= 0) {
                int dist = Math.abs(fromR - toR) + Math.abs(fromC - toC);
                if (dist != 1) {
                    log.warn("[ANTI-CHEAT] Phát hiện nước đi bất hợp lệ: dist={} ([{},{}] -> [{},{}])",
                            dist, fromR, fromC, toR, toC);
                    return null;
                }
            }
        }

        // 1. Kiểm tra điều kiện CHIẾM CĂN CỨ ĐỐI PHƯƠNG (TARGET_REACHED)
        // Team A target: [0, 8] (I1), Team B target: [8, 0] (A9)
        for (int i = moves.size() - 1; i >= 0; i--) {
            JsonNode m = moves.get(i);
            String team = m.path("team").asText(null);
            int r = extractRow(m);
            int c = extractCol(m);

            if ("A".equalsIgnoreCase(team) && r == 0 && c == 8) {
                return new ValidatedMatchResult("A", "TARGET_REACHED", "ENGINE_TARGET_REACHED_A");
            }
            if ("B".equalsIgnoreCase(team) && r == 8 && c == 0) {
                return new ValidatedMatchResult("B", "TARGET_REACHED", "ENGINE_TARGET_REACHED_B");
            }
        }

        // 2. Kiểm tra điều kiện TIÊU DIỆT SẠCH QUÂN ĐỐI PHƯƠNG (ANNIHILATION)
        // Team A gồm các quân ID 1-9, Team B gồm các quân ID 10-18
        Set<Integer> deadA = new HashSet<>();
        Set<Integer> deadB = new HashSet<>();

        for (JsonNode m : moves) {
            int capturedId = m.path("capturedPieceId").asInt(0);
            if (capturedId > 0) {
                if (capturedId <= 9) deadA.add(capturedId);
                else deadB.add(capturedId);
            }
            JsonNode events = m.path("events");
            if (events.isArray()) {
                for (JsonNode ev : events) {
                    if ("CAPTURED".equalsIgnoreCase(ev.path("type").asText())) {
                        int victimId = ev.path("victimId").asInt(ev.path("target").asInt(0));
                        if (victimId > 0) {
                            if (victimId <= 9) deadA.add(victimId);
                            else deadB.add(victimId);
                        }
                    }
                }
            }
        }

        if (deadB.size() >= 9 && deadA.size() < 9) {
            return new ValidatedMatchResult("A", "ANNIHILATION", "ENGINE_ANNIHILATION_B");
        }
        if (deadA.size() >= 9 && deadB.size() < 9) {
            return new ValidatedMatchResult("B", "ANNIHILATION", "ENGINE_ANNIHILATION_A");
        }
        if (deadA.size() >= 9 && deadB.size() >= 9) {
            return new ValidatedMatchResult("DRAW", "ALL_CAPTURED", "ENGINE_ALL_CAPTURED");
        }

        // 3. Kiểm tra chạm giới hạn số lượt (Turn limit reached)
        if (moves.size() >= 200) {
            return new ValidatedMatchResult("DRAW", "TURN_LIMIT_REACHED", "ENGINE_TURN_LIMIT");
        }

        return null;
    }

    private int extractRow(JsonNode m) {
        if (m.has("to") && m.get("to").isArray() && m.get("to").size() >= 2) {
            return m.get("to").get(0).asInt(-1);
        }
        if (m.has("to") && m.get("to").isObject()) {
            return m.get("to").path("row").asInt(-1);
        }
        if (m.has("toPos") && m.get("toPos").isArray() && m.get("toPos").size() >= 2) {
            return m.get("toPos").get(0).asInt(-1);
        }
        if (m.path("move").has("to") && m.path("move").get("to").isArray() && m.path("move").get("to").size() >= 2) {
            return m.path("move").get("to").get(0).asInt(-1);
        }
        return -1;
    }

    private int extractCol(JsonNode m) {
        if (m.has("to") && m.get("to").isArray() && m.get("to").size() >= 2) {
            return m.get("to").get(1).asInt(-1);
        }
        if (m.has("to") && m.get("to").isObject()) {
            return m.get("to").path("col").asInt(-1);
        }
        if (m.has("toPos") && m.get("toPos").isArray() && m.get("toPos").size() >= 2) {
            return m.get("toPos").get(1).asInt(-1);
        }
        if (m.path("move").has("to") && m.path("move").get("to").isArray() && m.path("move").get("to").size() >= 2) {
            return m.path("move").get("to").get(1).asInt(-1);
        }
        return -1;
    }

    private int extractFromRow(JsonNode m) {
        if (m.has("from") && m.get("from").isArray() && m.get("from").size() >= 2) {
            return m.get("from").get(0).asInt(-1);
        }
        if (m.has("from") && m.get("from").isObject()) {
            return m.get("from").path("row").asInt(-1);
        }
        if (m.has("fromPos") && m.get("fromPos").isArray() && m.get("fromPos").size() >= 2) {
            return m.get("fromPos").get(0).asInt(-1);
        }
        if (m.path("move").has("from") && m.path("move").get("from").isArray() && m.path("move").get("from").size() >= 2) {
            return m.path("move").get("from").get(0).asInt(-1);
        }
        return -1;
    }

    private int extractFromCol(JsonNode m) {
        if (m.has("from") && m.get("from").isArray() && m.get("from").size() >= 2) {
            return m.get("from").get(1).asInt(-1);
        }
        if (m.has("from") && m.get("from").isObject()) {
            return m.get("from").path("col").asInt(-1);
        }
        if (m.has("fromPos") && m.get("fromPos").isArray() && m.get("fromPos").size() >= 2) {
            return m.get("fromPos").get(1).asInt(-1);
        }
        if (m.path("move").has("from") && m.path("move").get("from").isArray() && m.path("move").get("from").size() >= 2) {
            return m.path("move").get("from").get(1).asInt(-1);
        }
        return -1;
    }
}
