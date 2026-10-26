package ottv2.api.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoomServiceTest {

    private RoomService roomService;
    private RoomController roomController;

    @BeforeEach
    void setUp() {
        roomService = new RoomService();
        roomController = new RoomController(roomService);
    }

    @Test
    void testCreateRoomReturns6DigitCode() {
        RoomSession room = roomService.createRoom("Player1", Map.of("id", "3+2"));
        assertNotNull(room);
        assertNotNull(room.getCode());
        assertEquals(6, room.getCode().length());
        assertTrue(room.getCode().matches("\\d{6}"));
        assertEquals("Player1", room.getHostName());
        assertEquals(RoomSession.Status.WAITING, room.getStatus());
        assertFalse(room.isFull());
    }

    @Test
    void testJoinRoomSuccess() {
        RoomSession room = roomService.createRoom("HostPlayer", null);
        String code = room.getCode();

        RoomSession joined = roomService.joinRoom(code, "GuestPlayer");
        assertNotNull(joined);
        assertEquals("GuestPlayer", joined.getGuestName());
        assertEquals(RoomSession.Status.PLAYING, joined.getStatus());
        assertTrue(joined.isFull());
    }

    @Test
    void testJoinRoomNotFound() {
        assertThrows(RoomNotFoundException.class, () -> roomService.joinRoom("999999", "Guest"));
    }

    @Test
    void testJoinRoomAlreadyFull() {
        RoomSession room = roomService.createRoom("HostPlayer", null);
        String code = room.getCode();

        roomService.joinRoom(code, "GuestPlayer1");
        assertThrows(RoomFullException.class, () -> roomService.joinRoom(code, "GuestPlayer2"));
    }

    @Test
    void testControllerCreateAndJoinApi() {
        ResponseEntity<?> createRes = roomController.createRoom(Map.of("hostName", "Master"));
        assertEquals(HttpStatus.CREATED, createRes.getStatusCode());
        Map<?, ?> createBody = (Map<?, ?>) createRes.getBody();
        assertNotNull(createBody);
        String code = String.valueOf(createBody.get("code"));
        assertEquals(6, code.length());

        // Join room
        ResponseEntity<?> joinRes = roomController.joinRoom(code, Map.of("guestName", "Challenger"));
        assertEquals(HttpStatus.OK, joinRes.getStatusCode());

        // Join room when full -> 400
        ResponseEntity<?> joinResFull = roomController.joinRoom(code, Map.of("guestName", "ThirdWheel"));
        assertEquals(HttpStatus.BAD_REQUEST, joinResFull.getStatusCode());

        // Join non-existent room -> 404
        ResponseEntity<?> joinResNotFound = roomController.joinRoom("000000", Map.of("guestName", "Ghost"));
        assertEquals(HttpStatus.NOT_FOUND, joinResNotFound.getStatusCode());
    }

    @Test
    void testStompHandlers() {
        RoomSession room = roomService.createRoom("Player1", null);
        roomService.joinRoom(room.getCode(), "Player2");
        String code = room.getCode();

        Map<String, Object> moveData = new java.util.HashMap<>(Map.of(
                "sender", "A",
                "playerToken", room.getHostPlayerToken(),
                "from", new int[]{0, 0},
                "to", new int[]{1, 0}
        ));
        Map<String, Object> result = roomController.handleMove(code, moveData);
        assertNotNull(result);
        assertEquals("A", result.get("sender"));
        assertNull(result.get("playerToken"), "playerToken should be stripped before broadcast");

        Map<String, Object> actionData = new java.util.HashMap<>(Map.of(
                "type", "READY",
                "sender", "A",
                "playerToken", room.getHostPlayerToken()
        ));
        Map<String, Object> actionResult = roomController.handleAction(code, actionData);
        assertNotNull(actionResult);
        assertEquals("READY", actionResult.get("type"));
        assertNull(actionResult.get("playerToken"), "playerToken should be stripped before broadcast");

        // Unauthorized move from non-existent room or invalid token
        Map<String, Object> unauthorizedMove = Map.of(
                "sender", "A",
                "playerToken", "invalid-token",
                "from", new int[]{0, 0},
                "to", new int[]{1, 0}
        );
        assertNull(roomController.handleMove(code, unauthorizedMove));

        // Unauthorized action
        Map<String, Object> unauthorizedAction = Map.of(
                "type", "READY",
                "sender", "A",
                "playerToken", "wrong-token"
        );
        assertNull(roomController.handleAction(code, unauthorizedAction));
    }
}
