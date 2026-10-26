package ottv2.api.room;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RoomService {

    private final Map<String, RoomSession> rooms = new ConcurrentHashMap<>();

    public RoomSession createRoom(String hostName, Object timeControl) {
        // Generate a 6-digit random number (e.g. 882914)
        String code;
        int attempts = 0;
        do {
            int randomNum = ThreadLocalRandom.current().nextInt(100000, 1000000);
            code = String.format("%06d", randomNum);
            attempts++;
        } while (rooms.containsKey(code) && attempts < 100);

        RoomSession session = new RoomSession(code, hostName, timeControl);
        rooms.put(code, session);
        return session;
    }

    public RoomSession joinRoom(String code, String guestName) {
        if (code == null || code.isBlank()) {
            throw new RoomNotFoundException("Mã phòng không hợp lệ");
        }
        String cleanCode = code.trim().toUpperCase();
        RoomSession session = rooms.get(cleanCode);
        if (session == null) {
            throw new RoomNotFoundException("Mã phòng " + cleanCode + " không tồn tại hoặc đã hết hạn!");
        }

        if (session.isFull()) {
            throw new RoomFullException("Phòng " + cleanCode + " đã đủ 2 người chơi!");
        }

        session.setGuestName(guestName != null && !guestName.isBlank() ? guestName : "Khách");
        session.setStatus(RoomSession.Status.PLAYING);
        session.setJoinedAt(Instant.now());
        session.updateLastActive();
        return session;
    }

    public RoomSession getRoom(String code) {
        if (code == null) return null;
        return rooms.get(code.trim().toUpperCase());
    }

    public void removeRoom(String code) {
        if (code != null) {
            rooms.remove(code.trim().toUpperCase());
        }
    }

    public int getActiveRoomsCount() {
        return rooms.size();
    }

    // Auto cleanup rooms inactive for > 2 hours every 15 minutes
    @Scheduled(fixedRate = 900_000)
    public void cleanupOldRooms() {
        Instant threshold = Instant.now().minus(Duration.ofHours(2));
        rooms.entrySet().removeIf(entry -> {
            RoomSession room = entry.getValue();
            return room.getLastActiveAt().isBefore(threshold);
        });
    }
}
