package ottv2.api.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.security.ApiPrincipal;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private JdbcTemplate jdbc;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(jdbc);
    }

    @Test
    @DisplayName("Admin resets test data successfully")
    void testResetTestData_success() {
        ApiPrincipal admin = new ApiPrincipal(1L, "admin", "admin@school.edu", "Admin", "ADMIN");
        when(jdbc.update(contains("UPDATE users"))).thenReturn(5);
        when(jdbc.update(contains("UPDATE bot_ratings SET elo = 1200"))).thenReturn(10);

        Map<String, Object> res = controller.resetTestData(admin);

        assertNotNull(res);
        assertTrue((Boolean) res.get("success"));
        assertEquals(5, res.get("usersReset"));
        assertEquals(10, res.get("botsReset"));

        verify(jdbc).execute(contains("TRUNCATE TABLE user_matches"));
    }

    @Test
    @DisplayName("Non-admin receives 403 FORBIDDEN")
    void testResetTestData_forbidden() {
        ApiPrincipal student = new ApiPrincipal(2L, "student", "student@school.edu", "Student", "STUDENT");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.resetTestData(student));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(jdbc, never()).execute(anyString());
    }
}
