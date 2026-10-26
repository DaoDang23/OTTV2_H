package ottv2.api.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.org.springframework.web.socket=INFO",
                "logging.level.org.springframework.messaging=INFO"
        }
)
class RoomWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomController roomController;

    private List<StompSession> activeSessions;

    @BeforeEach
    void setUp() {
        activeSessions = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : activeSessions) {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private WebSocketStompClient createStompClient() {
        SockJsClient sockJsClient = new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new CompositeMessageConverter(List.of(
                new MappingJackson2MessageConverter(),
                new StringMessageConverter(),
                new ByteArrayMessageConverter()
        )));
        return stompClient;
    }

    private StompSession connectClient(String url, BlockingQueue<Throwable> errors) throws Exception {
        WebSocketStompClient stompClient = createStompClient();
        StompSessionHandler sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
                errors.add(exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                errors.add(exception);
            }
        };

        StompSession session = stompClient.connectAsync(url, sessionHandler).get(5, TimeUnit.SECONDS);
        activeSessions.add(session);
        return session;
    }

    @Test
    void testWebSocketHandshakeAndValidAction() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession session = connectClient(url, errors);

        assertNotNull(session);
        assertTrue(session.isConnected(), "STOMP session should be connected");

        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        RoomSession room = roomService.createRoom("TestHost1", null);
        String roomCode = room.getCode();

        session.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((Map<String, Object>) payload);
            }
        });

        // Send action with explicit application/json header
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/room/" + roomCode + "/action");
        headers.set("content-type", "application/json");

        String payload = "{\"type\":\"READY_CHANGE\",\"roomCode\":\"" + roomCode + "\",\"sender\":\"A\",\"playerToken\":\"" + room.getHostPlayerToken() + "\",\"ready\":true}";
        session.send(headers, payload.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> received = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "Should receive broadcasted message");
        assertEquals("READY_CHANGE", received.get("type"));
        assertEquals(roomCode, received.get("roomCode"));
        assertEquals("A", received.get("sender"));
        assertEquals(Boolean.TRUE, received.get("ready"));

        // Assert session is still connected (no 1002 close)
        assertTrue(session.isConnected(), "Session must remain connected after sending valid action");
        assertTrue(errors.isEmpty(), "No transport or STOMP errors should have occurred");
    }

    @Test
    void testMalformedActionKeepsConnectionAlive() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession session = connectClient(url, errors);

        assertTrue(session.isConnected());

        String roomCode = "test-malformed-2";
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();

        session.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((Map<String, Object>) payload);
            }
        });

        // Send intentionally malformed JSON
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/room/" + roomCode + "/action");
        headers.set("content-type", "application/json");
        session.send(headers, "{not-a-valid-json-string".getBytes(StandardCharsets.UTF_8));

        // Sleep briefly to let server process
        Thread.sleep(500);

        // Connection must still be alive!
        assertTrue(session.isConnected(), "Connection must stay alive even when client sends malformed JSON");
    }

    @Test
    void testMoveHandlingAndBroadcast() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession session = connectClient(url, errors);

        assertTrue(session.isConnected());

        RoomSession room = roomService.createRoom("TestHost3", null);
        String roomCode = room.getCode();
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();

        session.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((Map<String, Object>) payload);
            }
        });

        // Send move with valid playerToken
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/room/" + roomCode + "/move");
        headers.set("content-type", "application/json");

        String payload = "{\"type\":\"MAKE_MOVE\",\"roomCode\":\"" + roomCode + "\",\"sender\":\"A\",\"playerToken\":\"" + room.getHostPlayerToken() + "\",\"pieceId\":1,\"from\":[8,0],\"to\":[7,0]}";
        session.send(headers, payload.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> received = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "Should receive move broadcast");
        assertEquals("MAKE_MOVE", received.get("type"));
        assertEquals(roomCode, received.get("roomCode"));
        assertEquals(1, received.get("pieceId"));
        assertNull(received.get("playerToken"), "playerToken must NOT be leaked into broadcast message");

        assertTrue(session.isConnected(), "Session must remain connected after sending move");
    }

    @Test
    void testTwoPlayersMultiplayerBroadcast() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> hostErrors = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> guestErrors = new LinkedBlockingQueue<>();

        StompSession hostSession = connectClient(url, hostErrors);
        StompSession guestSession = connectClient(url, guestErrors);

        assertTrue(hostSession.isConnected());
        assertTrue(guestSession.isConnected());

        RoomSession room = roomService.createRoom("TestHost4", null);
        roomService.joinRoom(room.getCode(), "GuestUser");
        String roomCode = room.getCode();

        BlockingQueue<Map<String, Object>> hostReceived = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> guestReceived = new LinkedBlockingQueue<>();

        hostSession.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                hostReceived.add((Map<String, Object>) payload);
            }
        });

        guestSession.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                guestReceived.add((Map<String, Object>) payload);
            }
        });

        // Guest sends PLAYER_JOINED with valid guest playerToken
        StompHeaders guestHeaders = new StompHeaders();
        guestHeaders.setDestination("/app/room/" + roomCode + "/action");
        guestHeaders.set("content-type", "application/json");

        String joinPayload = "{\"type\":\"PLAYER_JOINED\",\"roomCode\":\"" + roomCode + "\",\"sender\":\"B\",\"playerToken\":\"" + room.getGuestPlayerToken() + "\",\"senderName\":\"GuestUser\",\"ready\":true}";
        guestSession.send(guestHeaders, joinPayload.getBytes(StandardCharsets.UTF_8));

        // Both host and guest should receive the event
        Map<String, Object> msgForHost = hostReceived.poll(5, TimeUnit.SECONDS);
        Map<String, Object> msgForGuest = guestReceived.poll(5, TimeUnit.SECONDS);

        assertNotNull(msgForHost, "Host should receive PLAYER_JOINED event");
        assertEquals("PLAYER_JOINED", msgForHost.get("type"));
        assertEquals("GuestUser", msgForHost.get("senderName"));
        assertNull(msgForHost.get("playerToken"), "Token must not be leaked");

        assertNotNull(msgForGuest, "Guest should receive PLAYER_JOINED event");
        assertEquals("PLAYER_JOINED", msgForGuest.get("type"));

        // Both sessions remain connected
        assertTrue(hostSession.isConnected(), "Host session must remain connected");
        assertTrue(guestSession.isConnected(), "Guest session must remain connected");
    }

    @Test
    void testUnauthorizedMoveAndActionRejected() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession session = connectClient(url, errors);

        assertTrue(session.isConnected());

        RoomSession room = roomService.createRoom("SecureHost", null);
        String roomCode = room.getCode();

        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        session.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((Map<String, Object>) payload);
            }
        });

        // 1. Attack attempt: attacker tries to inject fake move without playerToken
        StompHeaders moveHeaders = new StompHeaders();
        moveHeaders.setDestination("/app/room/" + roomCode + "/move");
        moveHeaders.set("content-type", "application/json");
        String fakeMovePayload = "{\"type\":\"MAKE_MOVE\",\"roomCode\":\"" + roomCode + "\",\"sender\":\"A\",\"pieceId\":1,\"from\":[8,0],\"to\":[7,0]}";
        session.send(moveHeaders, fakeMovePayload.getBytes(StandardCharsets.UTF_8));

        // Verify that server dropped it (nothing broadcast to subscribers)
        Map<String, Object> receivedMove = messages.poll(1, TimeUnit.SECONDS);
        assertNull(receivedMove, "Unauthorized move without playerToken MUST NOT be broadcast");

        // 2. Attack attempt: attacker tries to inject fake PLAYER_LEFT forfeit with invalid token
        StompHeaders actionHeaders = new StompHeaders();
        actionHeaders.setDestination("/app/room/" + roomCode + "/action");
        actionHeaders.set("content-type", "application/json");
        String fakeLeftPayload = "{\"type\":\"PLAYER_LEFT\",\"roomCode\":\"" + roomCode + "\",\"sender\":\"A\",\"playerToken\":\"hacked-token\"}";
        session.send(actionHeaders, fakeLeftPayload.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> receivedAction = messages.poll(1, TimeUnit.SECONDS);
        assertNull(receivedAction, "Unauthorized action with wrong playerToken MUST NOT be broadcast");

        // Connection must still be healthy
        assertTrue(session.isConnected(), "Session must remain connected after unauthorized attempts");
        assertTrue(errors.isEmpty(), "No fatal transport exceptions");
    }

    @Test
    void testAuthorizedHostMoveWithPlayerTokenBroadcastsSuccessfully() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession hostSession = connectClient(url, errors);
        StompSession guestSession = connectClient(url, errors);

        RoomSession room = roomService.createRoom("HostPlayer", null);
        roomService.joinRoom(room.getCode(), "GuestPlayer");
        String roomCode = room.getCode();

        BlockingQueue<Map<String, Object>> guestReceived = new LinkedBlockingQueue<>();
        guestSession.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                guestReceived.add((Map<String, Object>) payload);
            }
        });

        // Host sends valid move with hostPlayerToken
        StompHeaders moveHeaders = new StompHeaders();
        moveHeaders.setDestination("/app/room/" + roomCode + "/move");
        moveHeaders.set("content-type", "application/json");

        String validMove = "{\"type\":\"MAKE_MOVE\",\"roomCode\":\"" + roomCode + "\",\"sender\":\"A\",\"playerToken\":\""
                + room.getHostPlayerToken() + "\",\"pieceId\":1,\"from\":{\"r\":8,\"c\":0},\"to\":{\"r\":7,\"c\":0}}";
        hostSession.send(moveHeaders, validMove.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> receivedMove = guestReceived.poll(5, TimeUnit.SECONDS);
        assertNotNull(receivedMove, "Guest must receive the move sent by Host with valid token");
        assertEquals("A", receivedMove.get("sender"));
        assertNull(receivedMove.get("playerToken"), "Token must be stripped before broadcast");
    }

    @Test
    void testGetRoomTokenRecovery() {
        RoomSession room = roomService.createRoom("RecoveryHost", null);
        room.setHostUserId(99L);
        room.setHostUsername("RecoveryHost");

        // 1. Calling getRoom with matching host info returns host token
        org.springframework.http.ResponseEntity<?> res = roomController.getRoom(room.getCode(), null, 99L, "RecoveryHost");
        assertTrue(res.getStatusCode().is2xxSuccessful());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("isHost"));
        assertEquals(room.getHostPlayerToken(), body.get("playerToken"));

        // 2. Calling getRoom without matching identity does NOT expose playerToken
        org.springframework.http.ResponseEntity<?> anonRes = roomController.getRoom(room.getCode(), null, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> anonBody = (Map<String, Object>) anonRes.getBody();
        assertNotNull(anonBody);
        assertNull(anonBody.get("playerToken"), "Anonymous requester must NOT see playerToken");
    }
}
