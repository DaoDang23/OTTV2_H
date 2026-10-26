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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.tournament.TournamentDtos.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TournamentLifecycleAndRegistrationTest {

    @Mock JdbcTemplate jdbc;
    @Mock TransactionTemplate transaction;
    @Mock TournamentMatchPublisher publisher;
    @Mock TournamentBroadcaster broadcaster;
    private final ObjectMapper mapper = new ObjectMapper();

    private TournamentService service;

    @BeforeEach
    void setUp() {
        lenient().when(transaction.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        service = new TournamentService(jdbc, transaction, publisher, broadcaster, mapper);
    }

    // =========================================================================
    // 1. Vòng đời giải đấu tuần tự: DRAFT -> REGISTRATION_OPEN -> REGISTRATION_CLOSED -> CHECK_IN -> RUNNING -> COMPLETED
    // =========================================================================
    @Test
    @DisplayName("Scenario 1: Status transitions follow legitimate strict state machine sequence")
    void test1_lifecycleTransitionsFollowStrictOrder() {
        assertTrue(TournamentStatus.DRAFT.canTransitionTo(TournamentStatus.REGISTRATION_OPEN));
        assertTrue(TournamentStatus.REGISTRATION_OPEN.canTransitionTo(TournamentStatus.REGISTRATION_CLOSED));
        assertTrue(TournamentStatus.REGISTRATION_CLOSED.canTransitionTo(TournamentStatus.CHECK_IN));
        assertTrue(TournamentStatus.CHECK_IN.canTransitionTo(TournamentStatus.RUNNING));
        assertTrue(TournamentStatus.RUNNING.canTransitionTo(TournamentStatus.COMPLETED));
        assertTrue(TournamentStatus.RUNNING.canTransitionTo(TournamentStatus.SUSPENDED));
        assertTrue(TournamentStatus.SUSPENDED.canTransitionTo(TournamentStatus.RUNNING));
    }

    // =========================================================================
    // 2. Nghiêm cấm nhảy cóc trạng thái (ví dụ: DRAFT -> RUNNING)
    // =========================================================================
    @Test
    @DisplayName("Scenario 2: Cannot skip lifecycle stages (e.g. DRAFT cannot directly jump to RUNNING)")
    void test2_cannotSkipLifecycleStages() {
        assertFalse(TournamentStatus.DRAFT.canTransitionTo(TournamentStatus.RUNNING));
        assertFalse(TournamentStatus.DRAFT.canTransitionTo(TournamentStatus.CHECK_IN));
        assertFalse(TournamentStatus.REGISTRATION_OPEN.canTransitionTo(TournamentStatus.RUNNING));
        assertFalse(TournamentStatus.COMPLETED.canTransitionTo(TournamentStatus.RUNNING));

        long tourneyId = 100L;
        when(jdbc.queryForObject(contains("SELECT id, status, max_teams, team_size FROM tournaments WHERE id=? FOR UPDATE"),
                ArgumentMatchers.<RowMapper<Object>>any(), eq(tourneyId)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$TournamentStateRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(tourneyId, "DRAFT", 16, 1);
                });

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.changeTournamentStatus(tourneyId, 1L, new ChangeStatusRequest(TournamentStatus.RUNNING, "Skip")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Invalid lifecycle transition"));
    }

    // =========================================================================
    // 3. Không cho phép sửa luật hoặc thông tin khi giải đã chuyển sang RUNNING hoặc COMPLETED
    // =========================================================================
    @Test
    @DisplayName("Scenario 3: Cannot modify tournament rules or settings when RUNNING or COMPLETED")
    void test3_cannotModifyTournamentRulesWhenRunningOrCompleted() {
        long tourneyId = 200L;
        when(jdbc.queryForObject(contains("SELECT id, status, max_teams, team_size FROM tournaments WHERE id=? FOR UPDATE"),
                ArgumentMatchers.<RowMapper<Object>>any(), eq(tourneyId)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$TournamentStateRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(tourneyId, "RUNNING", 16, 1);
                });

        UpdateTournamentRequest updateReq = new UpdateTournamentRequest("New Title", "Description", 32, 2, RulesConfig.defaultBlitz());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateTournament(tourneyId, 1L, updateReq));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Cannot modify tournament rules"));
    }

    // =========================================================================
    // 4. Đăng ký đội: kiểm tra max_teams và chống trùng lặp tên đội / captain
    // =========================================================================
    @Test
    @DisplayName("Scenario 4: Team registration enforces tournament capacity and captain uniqueness")
    void test4_teamRegistrationEnforcesCapacityAndUniqueness() {
        long tourneyId = 300L;
        long captainId = 10L;

        // Tournament is in REGISTRATION_OPEN with max_teams = 2
        when(jdbc.queryForObject(contains("SELECT id, status, max_teams, team_size FROM tournaments WHERE id=? FOR UPDATE"),
                ArgumentMatchers.<RowMapper<Object>>any(), eq(tourneyId)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$TournamentStateRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(tourneyId, "REGISTRATION_OPEN", 2, 1);
                });

        // 4a. Capacity reached
        when(jdbc.queryForObject(contains("COUNT(*) FROM tournament_teams WHERE tournament_id=? AND status NOT IN ('DISQUALIFIED', 'ELIMINATED')"),
                eq(Integer.class), eq(tourneyId))).thenReturn(2);

        RegisterTeamRequest regReq = new RegisterTeamRequest("Team Alpha", "ALPHA", 1L, 10L, List.of());
        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class,
                () -> service.registerTeam(tourneyId, captainId, regReq));
        assertEquals(HttpStatus.CONFLICT, ex1.getStatusCode());
        assertTrue(ex1.getReason().contains("capacity reached"));

        // 4b. Captain already has a team
        when(jdbc.queryForObject(contains("COUNT(*) FROM tournament_teams WHERE tournament_id=? AND status NOT IN ('DISQUALIFIED', 'ELIMINATED')"),
                eq(Integer.class), eq(tourneyId))).thenReturn(1);
        when(jdbc.queryForObject(contains("COUNT(*) FROM tournament_teams WHERE tournament_id=? AND captain_id=?"),
                eq(Integer.class), eq(tourneyId), eq(captainId))).thenReturn(1);

        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class,
                () -> service.registerTeam(tourneyId, captainId, regReq));
        assertEquals(HttpStatus.CONFLICT, ex2.getStatusCode());
        assertTrue(ex2.getReason().contains("already captain"));
    }

    // =========================================================================
    // 5. Đảm bảo một user không thể tham gia 2 đội trong cùng 1 giải
    // =========================================================================
    @Test
    @DisplayName("Scenario 5: User cannot be a member in multiple teams within the same tournament")
    void test5_teamRegistrationRejectsDuplicateMemberInSameTournament() {
        long tourneyId = 400L;
        long captainId = 20L;
        long teammateId = 30L;

        when(jdbc.queryForObject(contains("SELECT id, status, max_teams, team_size FROM tournaments WHERE id=? FOR UPDATE"),
                ArgumentMatchers.<RowMapper<Object>>any(), eq(tourneyId)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$TournamentStateRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(tourneyId, "REGISTRATION_OPEN", 16, 2);
                });
        when(jdbc.queryForObject(contains("COUNT(*) FROM tournament_teams WHERE tournament_id=? AND status NOT IN ('DISQUALIFIED', 'ELIMINATED')"),
                eq(Integer.class), eq(tourneyId))).thenReturn(5);
        when(jdbc.queryForObject(contains("COUNT(*) FROM tournament_teams WHERE tournament_id=? AND captain_id=?"),
                eq(Integer.class), eq(tourneyId), eq(captainId))).thenReturn(0);
        when(jdbc.queryForObject(contains("COUNT(*) FROM tournament_teams WHERE tournament_id=? AND name=?"),
                eq(Integer.class), eq(tourneyId), eq("Team Beta"))).thenReturn(0);

        // Bot is valid
        when(jdbc.query(contains("FROM bots b JOIN submissions s"), ArgumentMatchers.<RowMapper<Object>>any(), eq(5L), eq(50L)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$BotSubRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return List.of(c.newInstance(5L, "BotBeta", captainId, 50L, "sha50", 1, "COMPILED", "art50.tar"));
                });

        // Teammate is ALREADY enrolled in another team in this tournament
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tournament_members WHERE tournament_id=? AND user_id=?"),
                eq(Integer.class), eq(tourneyId), eq(captainId))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tournament_members WHERE tournament_id=? AND user_id=?"),
                eq(Integer.class), eq(tourneyId), eq(teammateId))).thenReturn(1);

        RegisterTeamRequest req = new RegisterTeamRequest("Team Beta", "BETA", 5L, 50L, List.of(teammateId));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.registerTeam(tourneyId, captainId, req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("already enrolled"));
    }

    // =========================================================================
    // 6. Đăng ký thành công: Lưu snapshot source_sha256 và version_no bất biến
    // =========================================================================
    @Test
    @DisplayName("Scenario 6: Successful registration records immutable bot version and source SHA snapshot")
    void test6_teamRegistrationRequiresCompiledSubmissionAndFreezesSnapshot() {
        long tourneyId = 500L;
        long captainId = 77L;

        when(jdbc.queryForObject(contains("SELECT id, status, max_teams, team_size FROM tournaments WHERE id=? FOR UPDATE"),
                ArgumentMatchers.<RowMapper<Object>>any(), eq(tourneyId)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$TournamentStateRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(tourneyId, "REGISTRATION_OPEN", 16, 1);
                });
        when(jdbc.queryForObject(contains("COUNT(*) FROM tournament_teams WHERE tournament_id=? AND status NOT IN ('DISQUALIFIED', 'ELIMINATED')"),
                eq(Integer.class), eq(tourneyId))).thenReturn(3);
        when(jdbc.queryForObject(contains("COUNT(*) FROM tournament_teams WHERE tournament_id=? AND captain_id=?"),
                eq(Integer.class), eq(tourneyId), eq(captainId))).thenReturn(0);
        when(jdbc.queryForObject(contains("COUNT(*) FROM tournament_teams WHERE tournament_id=? AND name=?"),
                eq(Integer.class), eq(tourneyId), eq("Team Gamma"))).thenReturn(0);

        when(jdbc.query(contains("FROM bots b JOIN submissions s"), ArgumentMatchers.<RowMapper<Object>>any(), eq(9L), eq(99L)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$BotSubRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return List.of(c.newInstance(9L, "GammaBot", captainId, 99L, "sha_gamma_v4", 4, "COMPILED", "art99.tar"));
                });

        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tournament_members WHERE tournament_id=? AND user_id=?"),
                eq(Integer.class), eq(tourneyId), eq(captainId))).thenReturn(0);

        // Team insert returns ID 123
        when(jdbc.queryForObject(contains("INSERT INTO tournament_teams"), eq(Long.class),
                eq(tourneyId), eq("Team Gamma"), eq("GAMMA"), eq(captainId), eq(9L), eq(99L), eq("sha_gamma_v4"), eq(4)))
                .thenReturn(123L);

        // Mock getTeam query
        when(jdbc.queryForObject(contains("SELECT tt.id, tt.tournament_id, tt.name, tt.team_code"),
                ArgumentMatchers.<RowMapper<TeamResponse>>any(), eq(tourneyId), eq(123L)))
                .thenReturn(new TeamResponse(123L, tourneyId, "Team Gamma", "GAMMA", captainId, "Captain 77",
                        9L, "GammaBot", 99L, "sha_gamma_v4", 4, "REGISTERED", 1, 0, 0, Instant.now(), List.of()));

        RegisterTeamRequest req = new RegisterTeamRequest("Team Gamma", "GAMMA", 9L, 99L, List.of());
        TeamResponse registered = service.registerTeam(tourneyId, captainId, req);

        assertNotNull(registered);
        assertEquals("sha_gamma_v4", registered.sourceSha256());
        assertEquals(4, registered.versionNo());
        assertEquals("REGISTERED", registered.status());

        // Verify audit log recorded
        verify(jdbc).update(contains("INSERT INTO tournament_audit_logs"), eq(tourneyId), eq(captainId), eq("TEAM_REGISTERED"), any());
    }

    // =========================================================================
    // 7. Check-in khóa phiên bản Bot trước khi giải bắt đầu
    // =========================================================================
    @Test
    @DisplayName("Scenario 7: Team check-in locks and freezes bot submission in CHECK_IN stage")
    void test7_checkInFreezesBotVersionAndPreventsLateEdits() {
        long tourneyId = 600L;
        long teamId = 456L;
        long captainId = 88L;

        when(jdbc.queryForObject(contains("SELECT id, status, max_teams, team_size FROM tournaments WHERE id=? FOR UPDATE"),
                ArgumentMatchers.<RowMapper<Object>>any(), eq(tourneyId)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$TournamentStateRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(tourneyId, "CHECK_IN", 16, 1);
                });

        when(jdbc.query(contains("FROM tournament_teams WHERE id=? AND tournament_id=? FOR UPDATE"),
                ArgumentMatchers.<RowMapper<Object>>any(), eq(teamId), eq(tourneyId)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$TeamStateRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return List.of(c.newInstance(teamId, tourneyId, "Team Phoenix", captainId, 15L, 150L, "REGISTERED"));
                });

        // Newly compiled submission v5 for check-in
        when(jdbc.query(contains("FROM bots b JOIN submissions s"), ArgumentMatchers.<RowMapper<Object>>any(), eq(15L), eq(155L)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$BotSubRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return List.of(c.newInstance(15L, "PhoenixBot", captainId, 155L, "sha_phoenix_v5", 5, "ACTIVE", "art155.tar"));
                });

        when(jdbc.queryForObject(contains("SELECT tt.id, tt.tournament_id, tt.name, tt.team_code"),
                ArgumentMatchers.<RowMapper<TeamResponse>>any(), eq(tourneyId), eq(teamId)))
                .thenReturn(new TeamResponse(teamId, tourneyId, "Team Phoenix", "PHOENIX", captainId, "Captain Phoenix",
                        15L, "PhoenixBot", 155L, "sha_phoenix_v5", 5, "CHECKED_IN", 1, 0, 0, Instant.now(), List.of()));

        TeamResponse checkedIn = service.checkInTeam(tourneyId, teamId, captainId, new CheckInTeamRequest(155L));

        assertNotNull(checkedIn);
        assertEquals("CHECKED_IN", checkedIn.status());
        assertEquals("sha_phoenix_v5", checkedIn.sourceSha256());
        assertEquals(5, checkedIn.versionNo());

        // Verify DB update sets CHECKED_IN and locked SHA
        verify(jdbc).update(contains("SET status='CHECKED_IN'"),
                eq(155L), eq("sha_phoenix_v5"), eq(5), eq(teamId));
    }

    // =========================================================================
    // 8. Chuyển sang RUNNING yêu cầu tối thiểu 2 đội đã check-in
    // =========================================================================
    @Test
    @DisplayName("Scenario 8: Transition to RUNNING requires at least 2 checked-in teams and disqualifies others")
    void test8_tournamentRequiresMinimumCheckedInTeamsToRun() {
        long tourneyId = 700L;

        when(jdbc.queryForObject(contains("SELECT id, status, max_teams, team_size FROM tournaments WHERE id=? FOR UPDATE"),
                ArgumentMatchers.<RowMapper<Object>>any(), eq(tourneyId)))
                .thenAnswer(inv -> {
                    var c = Class.forName("ottv2.api.tournament.TournamentService$TournamentStateRow").getDeclaredConstructors()[0];
                    c.setAccessible(true);
                    return c.newInstance(tourneyId, "CHECK_IN", 16, 1);
                });

        // Only 1 team checked in -> Failure
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tournament_teams WHERE tournament_id=? AND status='CHECKED_IN'"),
                eq(Integer.class), eq(tourneyId))).thenReturn(1);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.changeTournamentStatus(tourneyId, 1L, new ChangeStatusRequest(TournamentStatus.RUNNING, "Start")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("requires at least 2 checked-in teams"));
    }
}
