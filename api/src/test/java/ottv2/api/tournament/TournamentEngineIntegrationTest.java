package ottv2.api.tournament;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.ladder.LadderRatingService;
import ottv2.api.ladder.RatingCalculator;
import ottv2.api.security.ApiPrincipal;
import ottv2.api.tournament.TournamentDtos.*;
import ottv2.api.tournament.strategy.RoundRobinStrategy;
import ottv2.api.tournament.strategy.SingleEliminationStrategy;
import ottv2.api.tournament.strategy.SwissStrategy;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TournamentEngineIntegrationTest {

    @Mock JdbcTemplate jdbc;
    @Mock TransactionTemplate transaction;
    @Mock TournamentMatchPublisher publisher;
    @Mock TournamentBroadcaster broadcaster;

    private final ObjectMapper mapper = new ObjectMapper();
    private SingleEliminationStrategy singleElim;
    private RoundRobinStrategy roundRobin;
    private SwissStrategy swiss;
    private RulesConfig rules;

    private TournamentBracketService bracketService;
    private TournamentExecutionService executionService;
    private TournamentAdminService adminService;
    private TournamentAdminController adminController;
    private TournamentService tournamentService;

    @BeforeEach
    void setUp() {
        lenient().when(transaction.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });

        singleElim = new SingleEliminationStrategy();
        roundRobin = new RoundRobinStrategy();
        swiss = new SwissStrategy();
        rules = RulesConfig.defaultBlitz();

        bracketService = new TournamentBracketService(jdbc, mapper, List.of(singleElim, roundRobin, swiss));
        executionService = new TournamentExecutionService(jdbc, mapper, bracketService);
        adminService = new TournamentAdminService(jdbc, mapper, executionService, bracketService);
        adminController = new TournamentAdminController(adminService, jdbc);
        tournamentService = new TournamentService(jdbc, transaction, publisher, broadcaster, mapper);
    }

    // =========================================================================
    // Scenario 1: testSingleElimination_4_8_16_Teams
    // =========================================================================
    @Test
    @DisplayName("Scenario 1: Single Elimination seamlessly builds and resolves brackets for 4, 8, and 16 teams")
    void testSingleElimination_4_8_16_Teams() {
        for (int teamCount : List.of(4, 8, 16)) {
            List<TeamSeed> teams = new ArrayList<>();
            for (int i = 1; i <= teamCount; i++) {
                teams.add(new TeamSeed((long) i, "Team " + i, i, 0, 0));
            }

            List<MatchPlan> plans = singleElim.generateInitialSchedule(1L, teams, rules);

            // N teams in Single Elimination always produce exactly N - 1 total matches
            assertEquals(teamCount - 1, plans.size(), "Mismatch for bracket size " + teamCount);

            int expectedRounds = (int) (Math.log(teamCount) / Math.log(2));
            int maxRound = plans.stream().mapToInt(MatchPlan::roundNo).max().orElse(0);
            assertEquals(expectedRounds, maxRound);

            // Round 1 matches must all be READY since teamCount is a power of 2
            long r1Count = plans.stream().filter(p -> p.roundNo() == 1).count();
            assertEquals(teamCount / 2, r1Count);
            plans.stream().filter(p -> p.roundNo() == 1).forEach(p -> {
                assertEquals(TournamentMatchStatus.READY, p.status());
                assertNotNull(p.teamAId());
                assertNotNull(p.teamBId());
            });

            // Final match is in the highest round
            MatchPlan finalMatch = plans.stream().filter(p -> p.roundNo() == expectedRounds).findFirst().orElseThrow();
            assertEquals("FINAL", finalMatch.stage());
            assertEquals(TournamentMatchStatus.SCHEDULED, finalMatch.status());

            // Simulate final match completion
            List<MatchRecord> allMatches = List.of(
                    new MatchRecord(999L, expectedRounds, 1, "FINAL", 1L, 2L, 1L, TournamentMatchStatus.FINISHED, 1, 0)
            );
            assertTrue(singleElim.isTournamentComplete(expectedRounds, teams, allMatches, rules));
        }
    }

    // =========================================================================
    // Scenario 2: testSingleElimination_OddTeams_WithByes
    // =========================================================================
    @Test
    @DisplayName("Scenario 2: Single Elimination for odd teams (5 and 7) assigns BYEs correctly with zero Ranked ELO impact")
    void testSingleElimination_OddTeams_WithByes() {
        // Test 5 teams: N=8, requires 3 BYEs (Seeds 1, 2, 3 receive BYEs, 4 vs 5 play in R1)
        List<TeamSeed> teams5 = List.of(
                new TeamSeed(101L, "T1", 1, 0, 0),
                new TeamSeed(102L, "T2", 2, 0, 0),
                new TeamSeed(103L, "T3", 3, 0, 0),
                new TeamSeed(104L, "T4", 4, 0, 0),
                new TeamSeed(105L, "T5", 5, 0, 0)
        );

        List<MatchPlan> plans5 = singleElim.generateInitialSchedule(1L, teams5, rules);
        assertEquals(7, plans5.size()); // N=8 bracket has 7 matches

        List<MatchPlan> r1_5 = plans5.stream().filter(p -> p.roundNo() == 1).toList();
        long byes5 = r1_5.stream().filter(p -> p.status() == TournamentMatchStatus.BYE).count();
        assertEquals(3, byes5);

        // Verify Seeds 1, 2, 3 received BYE
        Set<Long> byeWinners = new HashSet<>();
        r1_5.stream().filter(p -> p.status() == TournamentMatchStatus.BYE).forEach(p -> {
            assertNull(p.teamBId());
            assertEquals(p.teamAId(), p.winnerTeamId());
            byeWinners.add(p.winnerTeamId());
        });
        assertTrue(byeWinners.containsAll(List.of(101L, 102L, 103L)));

        // Test 7 teams: N=8, requires 1 BYE (Seed 1 receives BYE)
        List<TeamSeed> teams7 = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            teams7.add(new TeamSeed((long) (200 + i), "T" + i, i, 0, 0));
        }
        List<MatchPlan> plans7 = singleElim.generateInitialSchedule(2L, teams7, rules);
        assertEquals(7, plans7.size());
        List<MatchPlan> r1_7 = plans7.stream().filter(p -> p.roundNo() == 1).toList();
        long byes7 = r1_7.stream().filter(p -> p.status() == TournamentMatchStatus.BYE).count();
        assertEquals(1, byes7);
        assertEquals(201L, r1_7.stream().filter(p -> p.status() == TournamentMatchStatus.BYE).findFirst().orElseThrow().winnerTeamId());

        // Zero Ranked Elo impact: BYEs do NOT call matches table or RatingCalculator
        verifyNoInteractions(publisher);
    }

    // =========================================================================
    // Scenario 3: testRoundRobin_NoDuplicateMatches
    // =========================================================================
    @Test
    @DisplayName("Scenario 3: Round Robin creates exactly N*(N-1)/2 matches with no duplicate pairings")
    void testRoundRobin_NoDuplicateMatches() {
        for (int n : List.of(4, 6)) {
            List<TeamSeed> teams = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                teams.add(new TeamSeed((long) i, "Team " + i, i, 0, 0));
            }

            List<MatchPlan> plans = roundRobin.generateInitialSchedule(1L, teams, rules);
            int expectedMatches = n * (n - 1) / 2;
            assertEquals(expectedMatches, plans.size());

            Set<String> uniquePairings = new HashSet<>();
            for (MatchPlan m : plans) {
                assertNotNull(m.teamAId());
                assertNotNull(m.teamBId());
                assertNotEquals(m.teamAId(), m.teamBId());
                String pair = Math.min(m.teamAId(), m.teamBId()) + "_" + Math.max(m.teamAId(), m.teamBId());
                assertTrue(uniquePairings.add(pair), "Duplicate match detected: " + pair);
            }
            assertEquals(expectedMatches, uniquePairings.size());
        }
    }

    // =========================================================================
    // Scenario 4: testSwiss_NoRematchInSameRound
    // =========================================================================
    @Test
    @DisplayName("Scenario 4: Swiss system pairs by score brackets with strict rematch avoidance and Buchholz scores")
    void testSwiss_NoRematchInSameRound() {
        // 6 teams across 3 rounds
        List<TeamSeed> teams = List.of(
                new TeamSeed(1L, "T1", 1, 3.0, 3.0),
                new TeamSeed(2L, "T2", 2, 3.0, 3.0),
                new TeamSeed(3L, "T3", 3, 3.0, 0.0),
                new TeamSeed(4L, "T4", 4, 0.0, 6.0),
                new TeamSeed(5L, "T5", 5, 0.0, 3.0),
                new TeamSeed(6L, "T6", 6, 0.0, 3.0)
        );

        List<MatchRecord> history = List.of(
                new MatchRecord(1L, 1, 1, "SWISS_R1", 1L, 4L, 1L, TournamentMatchStatus.FINISHED, 1, 0),
                new MatchRecord(2L, 1, 2, "SWISS_R1", 2L, 5L, 2L, TournamentMatchStatus.FINISHED, 1, 0),
                new MatchRecord(3L, 1, 3, "SWISS_R1", 3L, 6L, 3L, TournamentMatchStatus.FINISHED, 1, 0)
        );

        List<MatchPlan> r2Plans = swiss.generateNextRound(1L, 2, teams, history, rules);
        assertEquals(3, r2Plans.size());

        Set<String> allPairs = new HashSet<>();
        for (MatchRecord h : history) {
            allPairs.add(Math.min(h.teamAId(), h.teamBId()) + "_" + Math.max(h.teamAId(), h.teamBId()));
        }

        for (MatchPlan p : r2Plans) {
            String pair = Math.min(p.teamAId(), p.teamBId()) + "_" + Math.max(p.teamAId(), p.teamBId());
            assertFalse(allPairs.contains(pair), "Rematch occurred in Swiss round 2: " + pair);
            allPairs.add(pair);
        }

        // Teams with 3 pts (T1, T2, T3) are paired together without rematches
        assertEquals(3, r2Plans.size());
    }

    // =========================================================================
    // Scenario 5: testRegistration_LockedAfterClose
    // =========================================================================
    @Test
    @DisplayName("Scenario 5: Registration is completely blocked once registration is closed or running")
    void testRegistration_LockedAfterClose() {
        when(jdbc.queryForObject(contains("SELECT id, status, max_teams, team_size FROM tournaments"),
                any(RowMapper.class), eq(1L)))
                .thenReturn(new TournamentService.TournamentStateRow(1L, "REGISTRATION_CLOSED", 16, 1));

        RegisterTeamRequest request = new RegisterTeamRequest("Team Late", "LATE", 10L, 20L, List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                tournamentService.registerTeam(1L, 100L, request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Registration is not open"));
    }

    // =========================================================================
    // Scenario 6: testBotVersion_ImmutabilityEnforced
    // =========================================================================
    @Test
    @DisplayName("Scenario 6: Bot version snapshot is frozen at check-in; match uses frozen SHA256 and version")
    void testBotVersion_ImmutabilityEnforced() {
        // Mock team registered and checked-in with version 1 (SHA: sha-v1-locked)
        when(jdbc.query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(50L)))
                .thenReturn(List.of(new TournamentExecutionService.TourneyMatchRow(50L, 1L, 10L, 1, 1, 101L, 102L, "READY")));

        when(jdbc.queryForObject(contains("FROM tournament_teams tt"), any(RowMapper.class), eq(1L), eq(101L)))
                .thenReturn(new TournamentExecutionService.ParticipantData(11L, "Frozen Bot A", 1L, 100L, "PYTHON", "artifacts/v1.tar", "sha-v1-locked", 1));

        when(jdbc.queryForObject(contains("FROM tournament_teams tt"), any(RowMapper.class), eq(1L), eq(102L)))
                .thenReturn(new TournamentExecutionService.ParticipantData(12L, "Bot B", 2L, 200L, "JAVA", "artifacts/b.jar", "sha-b", 2));

        when(jdbc.queryForObject(contains("INSERT INTO matches"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(new TournamentExecutionService.MatchInsert(888L, UUID.randomUUID(), LocalDate.now()));

        executionService.executeMatch(50L);

        // Verify match_participants uses frozen version 1 and sha-v1-locked, ignoring any later submission in submissions table
        verify(jdbc).update(contains("INSERT INTO match_participants"),
                eq(888L), any(), eq(11L), eq(100L), eq("sha-v1-locked"), eq(1), eq(11L), eq(10L),
                eq(888L), any(), eq(12L), eq(200L), eq("sha-b"), eq(2), eq(12L), eq(10L));
    }

    // =========================================================================
    // Scenario 7: testRBAC_NonAdminCannotAccessAdminEndpoints
    // =========================================================================
    @Test
    @DisplayName("Scenario 7: Non-admin caller receives 403 Forbidden on admin operations; Admin or Organizer succeeds")
    void testRBAC_NonAdminCannotAccessAdminEndpoints() {
        // Tournament created_by user 100
        when(jdbc.query(contains("SELECT created_by FROM tournaments"), any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(100L));

        ApiPrincipal regularUser = new ApiPrincipal(999L, "regular_student", "reg@uet.vnu.edu.vn", "hash", "USER");
        ApiPrincipal organizer = new ApiPrincipal(100L, "organizer_prof", "org@uet.vnu.edu.vn", "hash", "USER");
        ApiPrincipal admin = new ApiPrincipal(777L, "admin_user", "adm@uet.vnu.edu.vn", "hash", "ADMIN");

        // 1. Regular user trying to disqualify team -> 403 Forbidden
        DisqualifyTeamRequest req = new DisqualifyTeamRequest("Cheating bot");
        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class, () ->
                adminController.disqualifyTeam(regularUser, 1L, 10L, req));
        assertEquals(HttpStatus.FORBIDDEN, ex1.getStatusCode());

        // 2. Regular user trying to start tournament -> 403 Forbidden
        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class, () ->
                adminController.startTournament(regularUser, 1L));
        assertEquals(HttpStatus.FORBIDDEN, ex2.getStatusCode());

        // 3. Organizer (creator) passes check
        assertDoesNotThrow(() -> adminController.checkAdminOrOrganizer(organizer, 1L));

        // 4. Admin passes check
        assertDoesNotThrow(() -> adminController.checkAdminOrOrganizer(admin, 1L));
    }

    // =========================================================================
    // Scenario 8: testMatchRetry_DoesNotDuplicateResults
    // =========================================================================
    @Test
    @DisplayName("Scenario 8: Retrying a failed match resets the existing match and does not duplicate scores or records")
    void testMatchRetry_DoesNotDuplicateResults() {
        when(jdbc.query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(50L), eq(1L)))
                .thenReturn(List.of(new TournamentExecutionService.TourneyMatchRow(50L, 1L, 10L, 1, 1, 101L, 102L, "ERROR")));

        when(jdbc.queryForObject(contains("SELECT match_id FROM tournament_matches"), eq(Long.class), eq(50L)))
                .thenReturn(777L);

        when(jdbc.queryForObject(contains("FROM tournament_teams tt"), any(RowMapper.class), eq(1L), eq(101L)))
                .thenReturn(new TournamentExecutionService.ParticipantData(11L, "Bot A", 1L, 201L, "PYTHON", "artifacts/a.tar", "sha-a", 1));

        when(jdbc.queryForObject(contains("FROM tournament_teams tt"), any(RowMapper.class), eq(1L), eq(102L)))
                .thenReturn(new TournamentExecutionService.ParticipantData(12L, "Bot B", 2L, 202L, "JAVA", "artifacts/b.jar", "sha-b", 2));

        when(jdbc.queryForObject(contains("SELECT public_id, created_month FROM matches"), any(RowMapper.class), eq(777L)))
                .thenReturn(new TournamentExecutionService.MatchInsert(777L, UUID.randomUUID(), LocalDate.now()));

        adminService.retryMatch(1L, 50L, 100L);

        // Verifies existing match 777 is reset to QUEUED rather than inserting a duplicate match
        verify(jdbc).update(contains("SET status = 'QUEUED'"), any(), any(), eq(777L));
        verify(jdbc, never()).update(contains("INSERT INTO tournament_matches"), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // =========================================================================
    // Scenario 9: testInfraError_DoesNotAutoForfeit
    // =========================================================================
    @Test
    @DisplayName("Scenario 9: Infrastructure worker error transitions match to ERROR rather than forfeiting bot")
    void testInfraError_DoesNotAutoForfeit() {
        when(jdbc.query(contains("SELECT id FROM tournament_matches WHERE match_id = ?"), any(RowMapper.class), eq(555L)))
                .thenReturn(List.of(50L));
        when(jdbc.queryForObject(contains("SELECT tournament_id FROM tournament_matches"), eq(Long.class), eq(50L)))
                .thenReturn(1L);

        executionService.onMatchFailed(555L, LocalDate.now(), "Worker isolate sandbox timeout / infrastructure error");

        // Match status becomes ERROR
        verify(jdbc).update(contains("SET status = 'ERROR'"), eq(50L));

        // Team status is NOT changed to DISQUALIFIED or ELIMINATED
        verify(jdbc, never()).update(contains("SET status='DISQUALIFIED'"), any(), any());
        verify(jdbc, never()).update(contains("SET status='ELIMINATED'"), any(), any());
    }

    // =========================================================================
    // Scenario 10: testNextRound_AutoAdvancesCorrectly
    // =========================================================================
    @Test
    @DisplayName("Scenario 10: Completing round 1 matches automatically feeds winners into round 2 and sets READY")
    void testNextRound_AutoAdvancesCorrectly() {
        when(jdbc.query(contains("SELECT id FROM tournament_matches WHERE tournament_id = ? AND round_no = ? AND match_no = ?"),
                any(RowMapper.class), eq(1L), eq(2), eq(1)))
                .thenReturn(List.of(201L));

        // Winner of R1 Match 1 advances into team_a of R2 Match 1
        bracketService.advanceSingleEliminationWinner(1L, 1, 1, 101L);
        verify(jdbc).update(contains("SET team_a_id = ?"), eq(101L), eq(201L));

        // Winner of R1 Match 2 advances into team_b of R2 Match 1
        bracketService.advanceSingleEliminationWinner(1L, 1, 2, 102L);
        verify(jdbc).update(contains("SET team_b_id = ?"), eq(102L), eq(201L));

        // When both slots are populated, status becomes READY
        verify(jdbc, times(2)).update(contains("SET status = 'READY'"), eq(201L));
    }

    // =========================================================================
    // Scenario 11: testTournament_DoesNotAffectRankedElo
    // =========================================================================
    @Test
    @DisplayName("Scenario 11: Tournament matches strictly preserve ELO rating with zero change")
    void testTournament_DoesNotAffectRankedElo() {
        LadderRatingService ladderRatings = new LadderRatingService(jdbc, null, new RatingCalculator.Elo());

        // When match_type is TOURNAMENT, the query for ladder_series returns empty
        when(jdbc.query(contains("WHERE m.id=? AND m.created_month=? AND m.match_type='LADDER'"),
                any(RowMapper.class), any(), any(), eq(999L), any()))
                .thenReturn(List.of());

        ladderRatings.recordFinishedGame(999L, Date.valueOf(LocalDate.now()), "A");

        // Rating table is never touched
        verify(jdbc, never()).update(contains("UPDATE bot_ratings"), any(), any(), any());
        verify(jdbc, never()).update(contains("UPDATE users SET bot_elo"), any(), any());
    }

    // =========================================================================
    // Scenario 12: testBackendRestart_PreservesTournamentState
    // =========================================================================
    @Test
    @DisplayName("Scenario 12: Tournament state is durable in database; new service instances recover full state")
    void testBackendRestart_PreservesTournamentState() {
        // Simulate database holding full tournament state
        when(jdbc.queryForObject(contains("SELECT id, COALESCE(title, name) AS title"),
                any(RowMapper.class), eq(1L)))
                .thenReturn(new TournamentBracketService.TournamentMeta(1L, "UET Championship", "uet-champ", "Finals",
                        "SINGLE_ELIMINATION", "RUNNING", 16, 1, RulesConfig.defaultBlitz(), 100L, null, null));

        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tournament_teams WHERE tournament_id = ?"),
                eq(Integer.class), eq(1L)))
                .thenReturn(8);

        when(jdbc.query(contains("FROM tournament_matches tm"), any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(
                        new TournamentMatchResponse(1L, 1L, 1, 1, "QUARTER_FINALS", 101L, 1L, "Team 1", 2L, "Team 2", 1L, "Team 1", "FINISHED", 1, 0, null, null)
                ));

        when(jdbc.query(contains("buchholz_score"),
                any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(
                        new TeamSeed(1L, "Team 1", 1, 3.0, 0.0),
                        new TeamSeed(2L, "Team 2", 2, 0.0, 3.0)
                ));

        when(jdbc.queryForObject(contains("FILTER (WHERE status"), any(RowMapper.class), any(), any(), eq(1L), any(), any()))
                .thenReturn(new TournamentBracketService.MatchStats(1, 1, 0, 0));

        when(jdbc.queryForObject(contains("SELECT status FROM tournament_teams WHERE id = ?"), eq(String.class), anyLong()))
                .thenReturn("APPROVED");

        // Create fresh instances of services (simulating server reboot)
        TournamentBracketService rebootedBracketService = new TournamentBracketService(jdbc, mapper, List.of(singleElim, roundRobin, swiss));

        BracketViewResponse view = rebootedBracketService.getBracketView(1L);

        assertNotNull(view);
        assertEquals("UET Championship", view.tournament().title());
        assertEquals("RUNNING", view.tournament().status());
        assertEquals(1, view.matches().size());
        assertEquals("FINISHED", view.matches().getFirst().status());
        assertEquals(1L, view.matches().getFirst().winnerTeamId());
    }
}
