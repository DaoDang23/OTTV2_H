package ottv2.api.tournament;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.tournament.TournamentDtos.TournamentResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TournamentAdminServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock TournamentExecutionService executionService;
    @Mock TournamentBracketService bracketService;
    @Mock TournamentBroadcaster broadcaster;
    private final ObjectMapper mapper = new ObjectMapper();

    private TournamentAdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new TournamentAdminService(jdbc, mapper, executionService, bracketService, broadcaster);
    }

    @Test
    @DisplayName("openRegistration successfully opens registration from DRAFT")
    void testOpenRegistration_success() {
        long tourneyId = 10L;
        Long operatorId = 1L;

        when(jdbc.query(contains("SELECT status FROM tournaments WHERE id = ?"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(tourneyId)))
                .thenReturn(java.util.List.of("DRAFT"));

        when(jdbc.update(contains("SET status = 'REGISTRATION_OPEN'"), eq(tourneyId))).thenReturn(1);

        TournamentResponse mockResponse = new TournamentResponse(
                tourneyId, "Spring Championship", "spring-championship", "Description",
                "SINGLE_ELIMINATION", "REGISTRATION_OPEN", 16, 1,
                TournamentDtos.RulesConfig.defaultBlitz(), operatorId, null, null, 0
        );
        when(jdbc.queryForObject(contains("FROM tournaments t WHERE t.id = ?"),
                ArgumentMatchers.<RowMapper<TournamentResponse>>any(), eq(tourneyId)))
                .thenReturn(mockResponse);

        TournamentResponse result = adminService.openRegistration(tourneyId, operatorId);

        assertNotNull(result);
        assertEquals("REGISTRATION_OPEN", result.status());
        verify(broadcaster).publishBracketChanged(tourneyId);
    }

    @Test
    @DisplayName("openRegistration throws 409 CONFLICT if tournament is in RUNNING state")
    void testOpenRegistration_conflict() {
        long tourneyId = 10L;
        Long operatorId = 1L;

        when(jdbc.query(contains("SELECT status FROM tournaments WHERE id = ?"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(tourneyId)))
                .thenReturn(java.util.List.of("RUNNING"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> adminService.openRegistration(tourneyId, operatorId));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Registration can only be opened"));
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("openRegistration throws 404 NOT FOUND if tournament does not exist")
    void testOpenRegistration_notFound() {
        long tourneyId = 999L;
        Long operatorId = 1L;

        when(jdbc.query(contains("SELECT status FROM tournaments WHERE id = ?"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(tourneyId)))
                .thenReturn(java.util.List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> adminService.openRegistration(tourneyId, operatorId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("deleteTournament successfully deletes tournament in DRAFT status and cleans child tables")
    void testDeleteTournament_success_draft() {
        long tourneyId = 20L;
        Long operatorId = 1L;

        when(jdbc.query(contains("SELECT status FROM tournaments WHERE id = ?"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(tourneyId)))
                .thenReturn(java.util.List.of("DRAFT"));
        when(jdbc.update(anyString(), eq(tourneyId))).thenReturn(1);

        assertDoesNotThrow(() -> adminService.deleteTournament(tourneyId, operatorId));

        verify(jdbc).update(contains("DELETE FROM tournament_audit_logs WHERE tournament_id = ?"), eq(tourneyId));
        verify(jdbc).update(contains("DELETE FROM tournament_matches WHERE tournament_id = ?"), eq(tourneyId));
        verify(jdbc).update(contains("DELETE FROM tournament_members WHERE tournament_id = ?"), eq(tourneyId));
        verify(jdbc).update(contains("DELETE FROM tournament_teams WHERE tournament_id = ?"), eq(tourneyId));
        verify(jdbc).update(contains("DELETE FROM tournaments WHERE id = ?"), eq(tourneyId));
        verify(broadcaster).publishBracketChanged(tourneyId);
    }

    @Test
    @DisplayName("deleteTournament successfully deletes tournament in CANCELLED status")
    void testDeleteTournament_success_cancelled() {
        long tourneyId = 21L;
        Long operatorId = 1L;

        when(jdbc.query(contains("SELECT status FROM tournaments WHERE id = ?"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(tourneyId)))
                .thenReturn(java.util.List.of("CANCELLED"));
        when(jdbc.update(anyString(), eq(tourneyId))).thenReturn(1);

        assertDoesNotThrow(() -> adminService.deleteTournament(tourneyId, operatorId));

        verify(jdbc).update(contains("DELETE FROM tournaments WHERE id = ?"), eq(tourneyId));
        verify(broadcaster).publishBracketChanged(tourneyId);
    }

    @Test
    @DisplayName("deleteTournament throws 409 CONFLICT if tournament is in RUNNING status")
    void testDeleteTournament_conflict_when_running() {
        long tourneyId = 22L;
        Long operatorId = 1L;

        when(jdbc.query(contains("SELECT status FROM tournaments WHERE id = ?"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(tourneyId)))
                .thenReturn(java.util.List.of("RUNNING"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> adminService.deleteTournament(tourneyId, operatorId));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Chỉ có thể xóa giải đấu ở trạng thái Bản Nháp"));
        verify(jdbc, never()).update(contains("DELETE FROM tournaments WHERE id = ?"), anyLong());
    }

    @Test
    @DisplayName("deleteTournament throws 409 CONFLICT if tournament is in CHECK_IN status")
    void testDeleteTournament_conflict_when_checkIn() {
        long tourneyId = 23L;
        Long operatorId = 1L;

        when(jdbc.query(contains("SELECT status FROM tournaments WHERE id = ?"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(tourneyId)))
                .thenReturn(java.util.List.of("CHECK_IN"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> adminService.deleteTournament(tourneyId, operatorId));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Chỉ có thể xóa giải đấu ở trạng thái Bản Nháp"));
        verify(jdbc, never()).update(contains("DELETE FROM tournaments WHERE id = ?"), anyLong());
    }

    @Test
    @DisplayName("deleteTournament throws 404 NOT FOUND if tournament does not exist")
    void testDeleteTournament_notFound() {
        long tourneyId = 999L;
        Long operatorId = 1L;

        when(jdbc.query(contains("SELECT status FROM tournaments WHERE id = ?"),
                ArgumentMatchers.<RowMapper<String>>any(), eq(tourneyId)))
                .thenReturn(java.util.List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> adminService.deleteTournament(tourneyId, operatorId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(jdbc, never()).update(contains("DELETE FROM tournaments WHERE id = ?"), anyLong());
    }

    @Test
    @DisplayName("startMatch executes match when status is READY")
    void testStartMatch_success() {
        long tourneyId = 10L;
        long matchId = 55L;
        Long operatorId = 1L;

        TournamentDtos.TournamentMatchResponse readyMatch = new TournamentDtos.TournamentMatchResponse(
                matchId, tourneyId, 1, 1, "ROUND_1", null, 101L, "Team A", 102L, "Team B",
                null, null, "READY", 0, 0, null, null
        );

        when(bracketService.listMatches(tourneyId)).thenReturn(java.util.List.of(readyMatch));

        TournamentDtos.TournamentMatchResponse result = adminService.startMatch(tourneyId, matchId, operatorId);

        assertNotNull(result);
        assertEquals(matchId, result.id());
        verify(executionService).executeMatch(matchId);
    }

    @Test
    @DisplayName("startMatch throws 409 CONFLICT when match is not in READY status")
    void testStartMatch_conflict_when_not_ready() {
        long tourneyId = 10L;
        long matchId = 55L;
        Long operatorId = 1L;

        TournamentDtos.TournamentMatchResponse runningMatch = new TournamentDtos.TournamentMatchResponse(
                matchId, tourneyId, 1, 1, "ROUND_1", null, 101L, "Team A", 102L, "Team B",
                null, null, "RUNNING", 0, 0, null, null
        );

        when(bracketService.listMatches(tourneyId)).thenReturn(java.util.List.of(runningMatch));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> adminService.startMatch(tourneyId, matchId, operatorId));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Only matches in READY status can be started"));
        verify(executionService, never()).executeMatch(anyLong());
    }
}
