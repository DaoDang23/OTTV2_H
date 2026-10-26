package ottv2.api.user;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {
    @Mock JdbcTemplate jdbc;
    UserController userController;

    @BeforeEach
    void setUp() {
        userController = new UserController(jdbc);
    }

    @Test
    void listUsersReturnsMappedUsersFromDatabase() {
        List<UserController.UserLeaderboardEntry> mockList = List.of(
                new UserController.UserLeaderboardEntry(1L, "B22DCCN001", "user1@ptit.edu.vn", "User One",
                        "B22DCCN001", "STUDENT", 1350, 1200, 10, 7, 3, 70, 2, "2026-10-16T20:00:00Z"),
                new UserController.UserLeaderboardEntry(2L, "B22DCCN002", "user2@ptit.edu.vn", "User Two",
                        "B22DCCN002", "STUDENT", 1240, 1200, 5, 3, 2, 60, 1, "2026-10-16T19:30:00Z")
        );
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UserController.UserLeaderboardEntry>>any()))
                .thenReturn(mockList);

        List<UserController.UserLeaderboardEntry> result = userController.listUsers();
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("User One", result.get(0).displayName());
        assertEquals(1350, result.get(0).pvpElo());
        assertEquals("B22DCCN001", result.get(0).username());
    }
}
