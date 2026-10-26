package ottv2.api.room;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

public class RoomSession {
    public enum Status {
        WAITING,
        PLAYING,
        FINISHED
    }

    private final String code;
    private String hostName;
    private String guestName;
    private Status status;
    private final Object timeControl;
    private final Instant createdAt;
    private Instant joinedAt;
    private volatile Instant lastActiveAt;
    private boolean hostReady;
    private boolean guestReady;
    private volatile String playerLeft;

    private final String hostPlayerToken;
    private volatile String guestPlayerToken;
    private volatile Long hostUserId;
    private volatile Long guestUserId;
    private volatile String hostUsername;
    private volatile String guestUsername;

    public RoomSession(String code, String hostName, Object timeControl) {
        this.code = code;
        this.hostName = hostName != null && !hostName.isBlank() ? hostName : "Chủ Phòng";
        this.timeControl = timeControl;
        this.status = Status.WAITING;
        this.createdAt = Instant.now();
        this.lastActiveAt = this.createdAt;
        this.hostReady = true;
        this.guestReady = false;
        this.hostPlayerToken = UUID.randomUUID().toString();
    }

    public String getCode() {
        return code;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getGuestName() {
        return guestName;
    }

    public void setGuestName(String guestName) {
        this.guestName = guestName;
        if (this.guestPlayerToken == null) {
            this.guestPlayerToken = UUID.randomUUID().toString();
        }
    }

    public String getHostPlayerToken() {
        return hostPlayerToken;
    }

    public String getGuestPlayerToken() {
        return guestPlayerToken;
    }

    public Long getHostUserId() {
        return hostUserId;
    }

    public void setHostUserId(Long hostUserId) {
        this.hostUserId = hostUserId;
    }

    public Long getGuestUserId() {
        return guestUserId;
    }

    public void setGuestUserId(Long guestUserId) {
        this.guestUserId = guestUserId;
    }

    public String getHostUsername() {
        return hostUsername;
    }

    public void setHostUsername(String hostUsername) {
        this.hostUsername = hostUsername;
    }

    public String getGuestUsername() {
        return guestUsername;
    }

    public void setGuestUsername(String guestUsername) {
        this.guestUsername = guestUsername;
    }

    /**
     * Xác thực người gửi có thuộc phòng và có quyền gửi tin nhắn cho phe (A/B) hay không.
     * Chấp nhận:
     * 1. playerToken khớp với secret token được server cấp khi tạo phòng (A) hoặc vào phòng (B).
     * 2. Hoặc Principal xác thực khớp với username/id/tên của bên tương ứng.
     */
    public boolean isSenderAuthorized(String sender, String token, Principal principal) {
        if (sender == null) return false;
        String s = sender.trim().toUpperCase();
        if (!"A".equals(s) && !"B".equals(s)) return false;

        boolean isA = "A".equals(s);

        // 1. Kiểm tra secret playerToken cấp cho mỗi bên khi tạo/vào phòng
        if (token != null && !token.isBlank()) {
            String expectedToken = isA ? hostPlayerToken : guestPlayerToken;
            if (expectedToken != null && token.equals(expectedToken)) {
                return true;
            }
        }

        // 2. Kiểm tra danh tính Principal (nếu WebSocket truyền authentication)
        if (principal != null) {
            String pName = principal.getName();
            String expectedUsername = isA ? hostUsername : guestUsername;
            String expectedName = isA ? hostName : guestName;
            Long expectedUserId = isA ? hostUserId : guestUserId;

            if (expectedUsername != null && expectedUsername.equalsIgnoreCase(pName)) {
                return true;
            }
            if (expectedName != null && expectedName.equalsIgnoreCase(pName)) {
                return true;
            }
            if (expectedUserId != null && String.valueOf(expectedUserId).equals(pName)) {
                return true;
            }
        }

        return false;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Object getTimeControl() {
        return timeControl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void updateLastActive() {
        this.lastActiveAt = Instant.now();
    }

    public boolean isHostReady() {
        return hostReady;
    }

    public void setHostReady(boolean hostReady) {
        this.hostReady = hostReady;
    }

    public boolean isGuestReady() {
        return guestReady;
    }

    public void setGuestReady(boolean guestReady) {
        this.guestReady = guestReady;
    }

    public boolean isFull() {
        return guestName != null && !guestName.isBlank();
    }

    public String getPlayerLeft() {
        return playerLeft;
    }

    public void setPlayerLeft(String playerLeft) {
        this.playerLeft = playerLeft;
    }
}
