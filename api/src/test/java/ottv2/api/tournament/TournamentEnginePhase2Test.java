package ottv2.api.tournament;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.tournament.TournamentDtos.*;
import ottv2.api.tournament.strategy.RoundRobinStrategy;
import ottv2.api.tournament.strategy.SingleEliminationStrategy;
import ottv2.api.tournament.strategy.SwissStrategy;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TournamentEnginePhase2Test {

    private SingleEliminationStrategy singleElim;
    private RoundRobinStrategy roundRobin;
    private SwissStrategy swiss;
    private RulesConfig rules;

    @BeforeEach
    void setUp() {
        singleElim = new SingleEliminationStrategy();
        roundRobin = new RoundRobinStrategy();
        swiss = new SwissStrategy();
        rules = RulesConfig.defaultBlitz();
    }

    // =========================================================================
    // 1. Single Elimination Algorithm Tests
    // =========================================================================

    @Test
    @DisplayName("Single Elimination: 4-team power-of-2 generates balanced bracket without BYEs")
    void singleEliminationPowerOfTwo() {
        List<TeamSeed> teams = List.of(
                new TeamSeed(101L, "Team A", 1, 0, 0),
                new TeamSeed(102L, "Team B", 2, 0, 0),
                new TeamSeed(103L, "Team C", 3, 0, 0),
                new TeamSeed(104L, "Team D", 4, 0, 0)
        );

        List<MatchPlan> plans = singleElim.generateInitialSchedule(1L, teams, rules);

        // 4 teams -> 2 rounds, 3 matches total (2 in R1, 1 Final)
        assertEquals(3, plans.size());

        MatchPlan r1m1 = plans.stream().filter(p -> p.roundNo() == 1 && p.matchNo() == 1).findFirst().orElseThrow();
        MatchPlan r1m2 = plans.stream().filter(p -> p.roundNo() == 1 && p.matchNo() == 2).findFirst().orElseThrow();
        MatchPlan r2m1 = plans.stream().filter(p -> p.roundNo() == 2 && p.matchNo() == 1).findFirst().orElseThrow();

        // Seed pairs for 4 teams: (1 vs 4) and (2 vs 3)
        assertEquals(101L, r1m1.teamAId());
        assertEquals(104L, r1m1.teamBId());
        assertEquals(TournamentMatchStatus.READY, r1m1.status());

        assertEquals(102L, r1m2.teamAId());
        assertEquals(103L, r1m2.teamBId());
        assertEquals(TournamentMatchStatus.READY, r1m2.status());

        assertEquals("FINAL", r2m1.stage());
        assertNull(r2m1.teamAId());
        assertNull(r2m1.teamBId());
        assertEquals(TournamentMatchStatus.SCHEDULED, r2m1.status());
    }

    @Test
    @DisplayName("Single Elimination: 5 teams correctly assigns 3 BYEs to top seeds and advances them")
    void singleEliminationWithByes() {
        List<TeamSeed> teams = List.of(
                new TeamSeed(101L, "Seed 1", 1, 0, 0),
                new TeamSeed(102L, "Seed 2", 2, 0, 0),
                new TeamSeed(103L, "Seed 3", 3, 0, 0),
                new TeamSeed(104L, "Seed 4", 4, 0, 0),
                new TeamSeed(105L, "Seed 5", 5, 0, 0)
        );

        List<MatchPlan> plans = singleElim.generateInitialSchedule(1L, teams, rules);

        // 5 teams -> Bracket size N=8 -> 3 rounds, 7 matches total (4 in R1, 2 in R2, 1 in R3)
        assertEquals(7, plans.size());

        List<MatchPlan> r1 = plans.stream().filter(p -> p.roundNo() == 1).toList();
        assertEquals(4, r1.size());

        // Seeds 1, 2, and 3 must get BYEs because opponents 8, 7, 6 are absent
        long byeCount = r1.stream().filter(p -> p.status() == TournamentMatchStatus.BYE).count();
        assertEquals(3, byeCount);

        // Seed 4 vs Seed 5 must be READY to play in Round 1
        MatchPlan playedMatch = r1.stream().filter(p -> p.status() == TournamentMatchStatus.READY).findFirst().orElseThrow();
        assertEquals(104L, playedMatch.teamAId());
        assertEquals(105L, playedMatch.teamBId());

        // Check Round 2 pre-advancement: BYE winners should already be placed in Round 2
        List<MatchPlan> r2 = plans.stream().filter(p -> p.roundNo() == 2).toList();
        assertEquals(2, r2.size());

        // Match 1 of Round 2 has Seed 1 (from R1 match 1 BYE) waiting for winner of R1 match 2 (4 vs 5)
        MatchPlan r2m1 = r2.stream().filter(p -> p.matchNo() == 1).findFirst().orElseThrow();
        assertEquals(101L, r2m1.teamAId());
        assertNull(r2m1.teamBId());
        assertEquals(TournamentMatchStatus.SCHEDULED, r2m1.status());

        // Match 2 of Round 2 has Seed 2 (from R1 match 3 BYE) and Seed 3 (from R1 match 4 BYE) -> Both present, READY!
        MatchPlan r2m2 = r2.stream().filter(p -> p.matchNo() == 2).findFirst().orElseThrow();
        assertEquals(102L, r2m2.teamAId());
        assertEquals(103L, r2m2.teamBId());
        assertEquals(TournamentMatchStatus.READY, r2m2.status());
    }

    // =========================================================================
    // 2. Round Robin Algorithm Tests
    // =========================================================================

    @Test
    @DisplayName("Round Robin: 4 teams produces 3 rounds, 6 unique matches without duplicates")
    void roundRobinEvenTeamsNoDuplicates() {
        List<TeamSeed> teams = List.of(
                new TeamSeed(1L, "Team 1", 1, 0, 0),
                new TeamSeed(2L, "Team 2", 2, 0, 0),
                new TeamSeed(3L, "Team 3", 3, 0, 0),
                new TeamSeed(4L, "Team 4", 4, 0, 0)
        );

        List<MatchPlan> plans = roundRobin.generateInitialSchedule(1L, teams, rules);

        // 4 teams -> 3 rounds, 2 matches per round = 6 matches
        assertEquals(6, plans.size());

        Set<String> pairs = new HashSet<>();
        for (MatchPlan p : plans) {
            assertNotNull(p.teamAId());
            assertNotNull(p.teamBId());
            assertNotEquals(p.teamAId(), p.teamBId());
            String key = Math.min(p.teamAId(), p.teamBId()) + "_" + Math.max(p.teamAId(), p.teamBId());
            assertTrue(pairs.add(key), "Duplicate matchup detected in Round Robin: " + key);
        }

        // Total unique pairs of 4 teams = 4 * 3 / 2 = 6
        assertEquals(6, pairs.size());

        // Round 1 matches must be READY; subsequent rounds SCHEDULED
        long r1Ready = plans.stream().filter(p -> p.roundNo() == 1 && p.status() == TournamentMatchStatus.READY).count();
        assertEquals(2, r1Ready);

        long r2Scheduled = plans.stream().filter(p -> p.roundNo() > 1 && p.status() == TournamentMatchStatus.SCHEDULED).count();
        assertEquals(4, r2Scheduled);
    }

    @Test
    @DisplayName("Round Robin: 5 odd teams creates 5 rounds with 1 BYE per team and 10 unique matches")
    void roundRobinOddTeamsWithByes() {
        List<TeamSeed> teams = List.of(
                new TeamSeed(1L, "T1", 1, 0, 0),
                new TeamSeed(2L, "T2", 2, 0, 0),
                new TeamSeed(3L, "T3", 3, 0, 0),
                new TeamSeed(4L, "T4", 4, 0, 0),
                new TeamSeed(5L, "T5", 5, 0, 0)
        );

        List<MatchPlan> plans = roundRobin.generateInitialSchedule(1L, teams, rules);

        // 5 teams + 1 dummy = 6 size -> 5 rounds, 3 matches/round = 15 total plans (10 real matches + 5 BYE plans)
        assertEquals(15, plans.size());

        long byeCount = plans.stream().filter(p -> p.status() == TournamentMatchStatus.BYE).count();
        assertEquals(5, byeCount);

        // Each team must receive exactly 1 BYE
        Set<Long> byeTeamIds = new HashSet<>();
        plans.stream()
                .filter(p -> p.status() == TournamentMatchStatus.BYE)
                .forEach(p -> assertTrue(byeTeamIds.add(p.teamAId()), "Team received multiple BYEs"));
        assertEquals(5, byeTeamIds.size());

        // Real matches must be 10 unique pairs
        Set<String> realPairs = new HashSet<>();
        plans.stream()
                .filter(p -> p.status() != TournamentMatchStatus.BYE)
                .forEach(p -> {
                    String key = Math.min(p.teamAId(), p.teamBId()) + "_" + Math.max(p.teamAId(), p.teamBId());
                    assertTrue(realPairs.add(key), "Duplicate real match in Round Robin: " + key);
                });
        assertEquals(10, realPairs.size());
    }

    // =========================================================================
    // 3. Swiss System Algorithm Tests
    // =========================================================================

    @Test
    @DisplayName("Swiss System: Round 1 splits top half vs bottom half")
    void swissRound1Pairing() {
        List<TeamSeed> teams = List.of(
                new TeamSeed(1L, "Team 1", 1, 0, 0),
                new TeamSeed(2L, "Team 2", 2, 0, 0),
                new TeamSeed(3L, "Team 3", 3, 0, 0),
                new TeamSeed(4L, "Team 4", 4, 0, 0)
        );

        List<MatchPlan> plans = swiss.generateInitialSchedule(1L, teams, rules);

        assertEquals(2, plans.size());
        // Seed 1 vs Seed 3 (1 vs 1 + 2), Seed 2 vs Seed 4 (2 vs 2 + 2)
        assertEquals(1L, plans.get(0).teamAId());
        assertEquals(3L, plans.get(0).teamBId());
        assertEquals(TournamentMatchStatus.READY, plans.get(0).status());

        assertEquals(2L, plans.get(1).teamAId());
        assertEquals(4L, plans.get(1).teamBId());
        assertEquals(TournamentMatchStatus.READY, plans.get(1).status());
    }

    @Test
    @DisplayName("Swiss System: Round 2 pairs winners with winners and prevents rematches")
    void swissRound2PairingByScoreAndNoRematch() {
        // Teams after Round 1: Team 1 and Team 2 won (3 pts); Team 3 and Team 4 lost (0 pts)
        List<TeamSeed> teams = List.of(
                new TeamSeed(1L, "Team 1", 1, 3.0, 0.0),
                new TeamSeed(2L, "Team 2", 2, 3.0, 0.0),
                new TeamSeed(3L, "Team 3", 3, 0.0, 3.0),
                new TeamSeed(4L, "Team 4", 4, 0.0, 3.0)
        );

        List<MatchRecord> completedR1 = List.of(
                new MatchRecord(10L, 1, 1, "SWISS_ROUND_1", 1L, 3L, 1L, TournamentMatchStatus.FINISHED, 1, 0),
                new MatchRecord(11L, 1, 2, "SWISS_ROUND_1", 2L, 4L, 2L, TournamentMatchStatus.FINISHED, 1, 0)
        );

        List<MatchPlan> r2Plans = swiss.generateNextRound(1L, 2, teams, completedR1, rules);

        assertEquals(2, r2Plans.size());

        // Match 1 must pair the two winners (1 vs 2)
        MatchPlan winnersMatch = r2Plans.get(0);
        assertTrue((winnersMatch.teamAId() == 1L && winnersMatch.teamBId() == 2L) ||
                   (winnersMatch.teamAId() == 2L && winnersMatch.teamBId() == 1L));

        // Match 2 must pair the two 0-pt teams (3 vs 4)
        MatchPlan losersMatch = r2Plans.get(1);
        assertTrue((losersMatch.teamAId() == 3L && losersMatch.teamBId() == 4L) ||
                   (losersMatch.teamAId() == 4L && losersMatch.teamBId() == 3L));

        // Neither match is a rematch of Round 1
        assertNotEquals(3L, winnersMatch.teamBId());
        assertNotEquals(4L, losersMatch.teamBId() == 4L ? losersMatch.teamAId() : losersMatch.teamBId());
    }

    @Test
    @DisplayName("Swiss System: Odd teams gives BYE to lowest-scoring team that has not had a BYE")
    void swissOddTeamsByeAssignment() {
        List<TeamSeed> teams = List.of(
                new TeamSeed(1L, "T1", 1, 3.0, 0.0),
                new TeamSeed(2L, "T2", 2, 3.0, 0.0),
                new TeamSeed(3L, "T3", 3, 0.0, 3.0),
                new TeamSeed(4L, "T4", 4, 0.0, 3.0),
                new TeamSeed(5L, "T5", 5, 3.0, 0.0) // T5 had a BYE in Round 1
        );

        List<MatchRecord> completedR1 = List.of(
                new MatchRecord(10L, 1, 1, "SWISS_ROUND_1", 1L, 3L, 1L, TournamentMatchStatus.FINISHED, 1, 0),
                new MatchRecord(11L, 1, 2, "SWISS_ROUND_1", 2L, 4L, 2L, TournamentMatchStatus.FINISHED, 1, 0),
                new MatchRecord(12L, 1, 3, "SWISS_ROUND_1", 5L, null, 5L, TournamentMatchStatus.BYE, 0, 0)
        );

        List<MatchPlan> r2Plans = swiss.generateNextRound(1L, 2, teams, completedR1, rules);

        assertEquals(3, r2Plans.size());

        // One match must be BYE
        MatchPlan byeMatch = r2Plans.stream().filter(p -> p.status() == TournamentMatchStatus.BYE).findFirst().orElseThrow();

        // T5 already had BYE in Round 1, so T5 CANNOT receive BYE in Round 2!
        assertNotEquals(5L, byeMatch.teamAId());

        // Lowest scoring teams are T3 and T4 (0 pts). One of them must receive the BYE.
        assertTrue(byeMatch.teamAId() == 3L || byeMatch.teamAId() == 4L);
    }

    // =========================================================================
    // 4. Tournament Execution & Safe Retry Tests
    // =========================================================================

    @Test
    @DisplayName("Execution: Triggering READY match creates TOURNAMENT match and dispatches outbox event")
    void executeMatchCreatesTournamentRecord() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        TournamentBracketService bracketService = mock(TournamentBracketService.class);
        TournamentExecutionService executionService = new TournamentExecutionService(jdbc, mapper, bracketService);

        // Mock tournament match lookup
        when(jdbc.query(contains("FROM tournament_matches tm"), any(org.springframework.jdbc.core.RowMapper.class), eq(50L)))
                .thenReturn(List.of(new TournamentExecutionService.TourneyMatchRow(50L, 1L, 10L, 1, 1, 101L, 102L, "READY")));

        // Mock participant data lookup for team A and B
        when(jdbc.queryForObject(contains("FROM tournament_teams tt"), any(org.springframework.jdbc.core.RowMapper.class), eq(1L), eq(101L)))
                .thenReturn(new TournamentExecutionService.ParticipantData(11L, "Bot A", 1L, 201L, "PYTHON", "artifacts/a.tar", "sha-a-12345", 1));

        when(jdbc.queryForObject(contains("FROM tournament_teams tt"), any(org.springframework.jdbc.core.RowMapper.class), eq(1L), eq(102L)))
                .thenReturn(new TournamentExecutionService.ParticipantData(12L, "Bot B", 2L, 202L, "JAVA", "artifacts/b.jar", "sha-b-67890", 2));

        // Mock match insertion
        when(jdbc.queryForObject(contains("INSERT INTO matches"), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
                .thenReturn(new TournamentExecutionService.MatchInsert(999L, UUID.randomUUID(), LocalDate.now()));

        executionService.executeMatch(50L);

        // Verify matches record has match_type = 'TOURNAMENT'
        verify(jdbc).queryForObject(contains("'TOURNAMENT'"), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any());

        // Verify participants snapshot includes exact sha256 and version_no
        verify(jdbc).update(contains("INSERT INTO match_participants"),
                eq(999L), any(), eq(11L), eq(201L), eq("sha-a-12345"), eq(1), eq(11L), eq(10L),
                eq(999L), any(), eq(12L), eq(202L), eq("sha-b-67890"), eq(2), eq(12L), eq(10L));

        // Verify tournament_matches is marked RUNNING
        verify(jdbc).update(contains("UPDATE tournament_matches"), eq(999L), any(), eq(50L));

        // Verify job dispatched on match.tournament.q
        verify(jdbc).update(contains("match.tournament.q"), anyString());
    }

    @Test
    @DisplayName("Execution: Retry fails if match is not in ERROR status")
    void retryNonErrorMatchThrowsConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        TournamentBracketService bracketService = mock(TournamentBracketService.class);
        TournamentExecutionService executionService = new TournamentExecutionService(jdbc, mapper, bracketService);

        when(jdbc.query(contains("FROM tournament_matches tm"), any(org.springframework.jdbc.core.RowMapper.class), eq(50L), eq(1L)))
                .thenReturn(List.of(new TournamentExecutionService.TourneyMatchRow(50L, 1L, 10L, 1, 1, 101L, 102L, "FINISHED")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                executionService.retryFailedMatch(1L, 50L, 99L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Only matches in ERROR status"));
    }

    @Test
    @DisplayName("Execution: Retry an ERROR match resets matches table and re-queues safely without duplicate")
    void retryErrorMatchResetsStatus() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        TournamentBracketService bracketService = mock(TournamentBracketService.class);
        TournamentExecutionService executionService = new TournamentExecutionService(jdbc, mapper, bracketService);

        when(jdbc.query(contains("FROM tournament_matches tm"), any(org.springframework.jdbc.core.RowMapper.class), eq(50L), eq(1L)))
                .thenReturn(List.of(new TournamentExecutionService.TourneyMatchRow(50L, 1L, 10L, 1, 1, 101L, 102L, "ERROR")));

        when(jdbc.queryForObject(contains("SELECT match_id FROM tournament_matches"), eq(Long.class), eq(50L)))
                .thenReturn(999L);

        when(jdbc.queryForObject(contains("FROM tournament_teams tt"), any(org.springframework.jdbc.core.RowMapper.class), eq(1L), eq(101L)))
                .thenReturn(new TournamentExecutionService.ParticipantData(11L, "Bot A", 1L, 201L, "PYTHON", "artifacts/a.tar", "sha-a-12345", 1));

        when(jdbc.queryForObject(contains("FROM tournament_teams tt"), any(org.springframework.jdbc.core.RowMapper.class), eq(1L), eq(102L)))
                .thenReturn(new TournamentExecutionService.ParticipantData(12L, "Bot B", 2L, 202L, "JAVA", "artifacts/b.jar", "sha-b-67890", 2));

        when(jdbc.queryForObject(contains("SELECT public_id, created_month FROM matches"), any(org.springframework.jdbc.core.RowMapper.class), eq(999L)))
                .thenReturn(new TournamentExecutionService.MatchInsert(999L, UUID.randomUUID(), LocalDate.now()));

        executionService.retryFailedMatch(1L, 50L, 99L);

        // Verify matches is reset to QUEUED
        verify(jdbc).update(contains("SET status = 'QUEUED'"), any(), any(), eq(999L));

        // Verify tournament match set to RUNNING
        verify(jdbc).update(contains("SET status = 'RUNNING'"), eq(50L));

        // Verify outbox event re-dispatched
        verify(jdbc).update(contains("match.tournament.q"), anyString());
    }
}
