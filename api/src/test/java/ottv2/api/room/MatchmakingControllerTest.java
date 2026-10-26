package ottv2.api.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import ottv2.api.security.ApiPrincipal;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MatchmakingControllerTest {

    private RoomService roomService;
    private SimpMessagingTemplate messagingTemplate;
    private MatchmakingController controller;

    @BeforeEach
    void setUp() {
        roomService = new RoomService();
        messagingTemplate = mock(SimpMessagingTemplate.class);
        controller = new MatchmakingController(roomService, messagingTemplate, null);
    }

    @Test
    void sendChallenge_requiresTargetUsername() {
        ResponseEntity<?> res = controller.sendChallenge(null, Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    }

    @Test
    void sendChallenge_createsRoomAndBroadcastsWebSocketMessage() {
        ApiPrincipal principal = new ApiPrincipal(1L, "B22DCCN001", "user1@ptit.edu.vn", "hash", "STUDENT");

        Map<String, Object> body = Map.of(
                "targetUsername", "B22DCCN002",
                "timeControl", Map.of("id", "3+2", "name", "⚡ Chớp 3+2")
        );

        ResponseEntity<?> response = controller.sendChallenge(principal, body);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> bodyRes = (Map<String, Object>) response.getBody();
        assertNotNull(bodyRes);
        assertNotNull(bodyRes.get("roomCode"));
        assertEquals("B22DCCN001", bodyRes.get("hostName"));
        assertEquals("B22DCCN002", bodyRes.get("targetUsername"));

        // Verify WebSocket message sent to /topic/user/B22DCCN002
        verify(messagingTemplate).convertAndSend(eq("/topic/user/B22DCCN002"), any(Object.class));
    }

    @Test
    void randomMatchmaking_findsCandidateWithin300Elo() {
        ApiPrincipal principal = new ApiPrincipal(2L, "B22DCCN002", "user2@ptit.edu.vn", "hash", "STUDENT");

        // Player ELO is 1200 -> can match with Máy 1 (1050), Máy 2 (1120), Máy 3 (1200), Máy 4 (1280), Máy 5 (1360), Máy 6 (1450)
        Map<String, Object> body = Map.of("pvpElo", 1200);

        ResponseEntity<?> response = controller.randomMatchmaking(principal, body);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> bodyRes = (Map<String, Object>) response.getBody();
        assertNotNull(bodyRes);
        assertNotNull(bodyRes.get("roomCode"));
        assertTrue((Boolean) bodyRes.get("isHost"));

        @SuppressWarnings("unchecked")
        Map<String, Object> opponent = (Map<String, Object>) bodyRes.get("opponent");
        assertNotNull(opponent);
        int opponentElo = (Integer) opponent.get("elo");
        assertTrue(Math.abs(opponentElo - 1200) <= 300, "Opponent Elo should be within 300 of 1200");
    }

    @Test
    void randomMatchmaking_returnsNotFoundWhenNoCandidateWithin300Elo() {
        // Extreme Elo: 5000 (all sparring machines are 1050 to 2150, max is 2150, diff = 2850 > 300)
        Map<String, Object> body = Map.of("pvpElo", 5000);

        ResponseEntity<?> response = controller.randomMatchmaking(null, body);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void heartbeat_callsQueueServiceAndReturnsStatus() {
        ottv2.api.ladder.MatchmakingQueueService mockQueue = mock(ottv2.api.ladder.MatchmakingQueueService.class);
        MatchmakingController ctrl = new MatchmakingController(roomService, messagingTemplate, null, mockQueue);

        ApiPrincipal principal = new ApiPrincipal(10L, "B22DCCN010", "u10@ptit.edu.vn", "hash", "STUDENT");

        when(mockQueue.heartbeat(10L)).thenReturn(true);
        when(mockQueue.getStatus(10L)).thenReturn(Map.of("inQueue", true));

        ResponseEntity<?> res = ctrl.heartbeat(principal, Map.of());
        assertEquals(HttpStatus.OK, res.getStatusCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("heartbeat"));
        assertTrue((Boolean) body.get("inQueue"));

        verify(mockQueue).heartbeat(10L);
    }
}
