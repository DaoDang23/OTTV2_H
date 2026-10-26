package ottv2.api.match;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.security.ApiPrincipal;
import ottv2.api.storage.SourceObjectStore;
import ottv2.api.user.UserController;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchControllerSecurityTest {

    @Mock
    private MatchService matchService;

    @Mock
    private SourceObjectStore objectStore;

    private MatchController matchController;
    private UserController userController;

    private ApiPrincipal userAlice;
    private ApiPrincipal userBob;

    @BeforeEach
    void setUp() {
        matchController = new MatchController(matchService, objectStore);
        userController = new UserController(null, matchService);

        userAlice = new ApiPrincipal(101L, "B22DCCN001", "alice@ptit.edu.vn", "hash1", "STUDENT");
        userBob = new ApiPrincipal(202L, "B22DCCN002", "bob@ptit.edu.vn", "hash2", "STUDENT");
    }

    @Test
    void unauthenticatedCallToMatchHistoryThrowsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
            matchController.history(null, null, null);
        });
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void aliceCanAccessHerOwnMatchHistory() {
        when(matchService.list(101L)).thenReturn(List.of());

        List<MatchDtos.MatchResponse> results = matchController.history(userAlice, null, null);
        assertNotNull(results);
        verify(matchService).list(101L);
    }

    @Test
    void aliceAttemptingToAccessBobHistoryViaUserIdThrowsForbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
            matchController.history(userAlice, null, 202L); // Bob's ID
        });
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(matchService, never()).list(anyLong());
    }

    @Test
    void aliceAttemptingToAccessBobHistoryViaUsernameThrowsForbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
            matchController.history(userAlice, "bob@ptit.edu.vn", null);
        });
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(matchService, never()).list(anyLong());
    }

    @Test
    void userControllerGetUserMatchesEnforcesPrincipalIdentity() {
        // Unauthenticated
        ResponseEntity<?> unauthRes = userController.getUserMatches(null, "alice@ptit.edu.vn");
        assertEquals(HttpStatus.UNAUTHORIZED, unauthRes.getStatusCode());

        // Alice accessing Bob's endpoint -> Forbidden
        ResponseEntity<?> forbiddenRes = userController.getUserMatches(userAlice, "bob@ptit.edu.vn");
        assertEquals(HttpStatus.FORBIDDEN, forbiddenRes.getStatusCode());

        // Alice accessing her own endpoint -> OK
        when(matchService.list(101L)).thenReturn(List.of());
        ResponseEntity<?> okRes = userController.getUserMatches(userAlice, "alice@ptit.edu.vn");
        assertEquals(HttpStatus.OK, okRes.getStatusCode());
        verify(matchService).list(101L);
    }
}
