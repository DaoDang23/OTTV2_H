package ottv2.api.tournament;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.tournament.TournamentDtos.*;
import ottv2.api.tournament.strategy.TournamentFormatStrategy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TournamentBracketService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Map<TournamentFormat, TournamentFormatStrategy> strategies;

    public TournamentBracketService(JdbcTemplate jdbc, ObjectMapper mapper,
                                    List<TournamentFormatStrategy> formatStrategies) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.strategies = formatStrategies.stream()
                .collect(Collectors.toMap(TournamentFormatStrategy::getFormat, Function.identity()));
    }

    @Transactional
    public List<TournamentMatchResponse> generateInitialBracket(long tournamentId) {
        TournamentMeta tourney = getTournamentMeta(tournamentId);
        TournamentFormat format = TournamentFormat.valueOf(tourney.format());
        TournamentFormatStrategy strategy = strategies.get(format);
        if (strategy == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported tournament format: " + format);
        }

        // Fetch checked-in teams
        List<TeamSeed> teams = jdbc.query("""
                SELECT id, name, seed, score, buchholz_score
                FROM tournament_teams
                WHERE tournament_id = ? AND status = 'CHECKED_IN'
                ORDER BY seed ASC NULLS LAST, id ASC
                """, (rs, row) -> new TeamSeed(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("seed"),
                rs.getDouble("score"),
                rs.getDouble("buchholz_score")
        ), tournamentId);

        if (teams.size() < 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tournament requires at least 2 checked-in teams to generate bracket (found " + teams.size() + ")");
        }

        // Assign seeds if not already assigned
        for (int i = 0; i < teams.size(); i++) {
            TeamSeed t = teams.get(i);
            if (t.seed() <= 0) {
                int assignedSeed = i + 1;
                jdbc.update("UPDATE tournament_teams SET seed = ? WHERE id = ?", assignedSeed, t.teamId());
                teams.set(i, new TeamSeed(t.teamId(), t.teamName(), assignedSeed, t.score(), t.buchholzScore()));
            }
        }

        // Delete existing scheduled matches if any (to support re-generation before start)
        jdbc.update("DELETE FROM tournament_matches WHERE tournament_id = ? AND status IN ('SCHEDULED', 'READY', 'BYE')", tournamentId);

        List<MatchPlan> plans = strategy.generateInitialSchedule(tournamentId, teams, tourney.rules());

        for (MatchPlan plan : plans) {
            jdbc.update("""
                    INSERT INTO tournament_matches (tournament_id, round_no, match_no, stage,
                                                    team_a_id, team_b_id, winner_team_id, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                    """, tournamentId, plan.roundNo(), plan.matchNo(), plan.stage(),
                    plan.teamAId(), plan.teamBId(), plan.winnerTeamId(), plan.status().name());
        }

        // Initial standings calculation
        recalculateStandingsAndBuchholz(tournamentId);

        return listMatches(tournamentId);
    }

    @Transactional
    public void advanceSingleEliminationWinner(long tournamentId, int roundNo, int matchNo, long winnerTeamId) {
        int nextRoundNo = roundNo + 1;
        int nextMatchNo = (matchNo + 1) / 2;

        // Check if next round match exists
        List<Long> targetMatches = jdbc.query(
                "SELECT id FROM tournament_matches WHERE tournament_id = ? AND round_no = ? AND match_no = ?",
                (rs, row) -> rs.getLong(1), tournamentId, nextRoundNo, nextMatchNo);

        if (targetMatches.isEmpty()) {
            return; // Already the final match
        }

        long targetMatchId = targetMatches.getFirst();

        if (matchNo % 2 == 1) {
            // Feeds into team_a
            jdbc.update("UPDATE tournament_matches SET team_a_id = ?, updated_at = now() WHERE id = ?",
                    winnerTeamId, targetMatchId);
        } else {
            // Feeds into team_b
            jdbc.update("UPDATE tournament_matches SET team_b_id = ?, updated_at = now() WHERE id = ?",
                    winnerTeamId, targetMatchId);
        }

        // If both teams are now present and status was SCHEDULED, transition to READY
        jdbc.update("""
                UPDATE tournament_matches
                SET status = 'READY', updated_at = now()
                WHERE id = ? AND team_a_id IS NOT NULL AND team_b_id IS NOT NULL AND status = 'SCHEDULED'
                """, targetMatchId);
    }

    @Transactional
    public List<TournamentMatchResponse> generateSwissNextRound(long tournamentId, int nextRoundNo) {
        TournamentMeta tourney = getTournamentMeta(tournamentId);
        TournamentFormatStrategy strategy = strategies.get(TournamentFormat.SWISS);

        List<TeamSeed> teams = listTeamSeeds(tournamentId);
        List<MatchRecord> completedMatches = listMatchRecords(tournamentId);

        List<MatchPlan> plans = strategy.generateNextRound(tournamentId, nextRoundNo, teams, completedMatches, tourney.rules());

        for (MatchPlan plan : plans) {
            jdbc.update("""
                    INSERT INTO tournament_matches (tournament_id, round_no, match_no, stage,
                                                    team_a_id, team_b_id, winner_team_id, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                    """, tournamentId, plan.roundNo(), plan.matchNo(), plan.stage(),
                    plan.teamAId(), plan.teamBId(), plan.winnerTeamId(), plan.status().name());
        }

        return listMatches(tournamentId);
    }

    @Transactional
    public void recalculateStandingsAndBuchholz(long tournamentId) {
        TournamentMeta tourney = getTournamentMeta(tournamentId);
        RulesConfig rules = tourney.rules();
        int winPts = rules.winPts();
        int drawPts = rules.drawPts();

        List<TeamScoreData> teamData = jdbc.query("""
                SELECT tt.id, tt.name,
                       COALESCE(SUM(CASE WHEN tm.winner_team_id = tt.id THEN 1 ELSE 0 END), 0) AS wins,
                       COALESCE(SUM(CASE WHEN (tm.team_a_id = tt.id OR tm.team_b_id = tt.id)
                                          AND tm.status = 'FINISHED' AND tm.winner_team_id IS NULL THEN 1 ELSE 0 END), 0) AS draws,
                       COALESCE(SUM(CASE WHEN (tm.team_a_id = tt.id OR tm.team_b_id = tt.id)
                                          AND tm.status = 'FINISHED' AND tm.winner_team_id IS NOT NULL
                                          AND tm.winner_team_id != tt.id THEN 1 ELSE 0 END), 0) AS losses
                FROM tournament_teams tt
                LEFT JOIN tournament_matches tm ON (tm.team_a_id = tt.id OR tm.team_b_id = tt.id)
                     AND tm.status IN ('FINISHED', 'BYE')
                WHERE tt.tournament_id = ?
                GROUP BY tt.id, tt.name
                """, (rs, row) -> new TeamScoreData(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("wins"),
                rs.getInt("draws"),
                rs.getInt("losses")
        ), tournamentId);

        Map<Long, Double> scores = new HashMap<>();
        for (TeamScoreData t : teamData) {
            double score = (t.wins * winPts) + (t.draws * drawPts);
            scores.put(t.id, score);
        }

        // Calculate Buchholz score (sum of opponent scores)
        Map<Long, Set<Long>> opponentsMap = new HashMap<>();
        List<MatchRecord> matches = listMatchRecords(tournamentId);
        for (MatchRecord m : matches) {
            if (m.teamAId() != null && m.teamBId() != null &&
                    (m.status() == TournamentMatchStatus.FINISHED || m.status() == TournamentMatchStatus.RUNNING)) {
                opponentsMap.computeIfAbsent(m.teamAId(), k -> new HashSet<>()).add(m.teamBId());
                opponentsMap.computeIfAbsent(m.teamBId(), k -> new HashSet<>()).add(m.teamAId());
            }
        }

        for (TeamScoreData t : teamData) {
            double teamScore = scores.getOrDefault(t.id, 0.0);
            Set<Long> opponents = opponentsMap.getOrDefault(t.id, Set.of());
            double buchholz = opponents.stream().mapToDouble(oppId -> scores.getOrDefault(oppId, 0.0)).sum();

            jdbc.update("UPDATE tournament_teams SET score = ?, buchholz_score = ?, updated_at = now() WHERE id = ?",
                    teamScore, buchholz, t.id);
        }
    }

    public BracketViewResponse getBracketView(long tournamentId) {
        TournamentMeta tourney = getTournamentMeta(tournamentId);
        TournamentResponse tournamentResponse = mapTournamentMeta(tourney);
        List<TournamentMatchResponse> matches = listMatches(tournamentId);
        List<StandingRow> standings = listStandings(tournamentId);

        return new BracketViewResponse(tournamentResponse, matches, standings);
    }

    public List<TournamentMatchResponse> listMatches(long tournamentId) {
        return jdbc.query("""
                SELECT tm.id, tm.tournament_id, tm.round_no, tm.match_no, tm.stage, tm.match_id,
                       tm.team_a_id, ta.name AS team_a_name,
                       tm.team_b_id, tb.name AS team_b_name,
                       tm.winner_team_id, tw.name AS winner_team_name,
                       tm.status, tm.score_a, tm.score_b, tm.created_at, tm.updated_at
                FROM tournament_matches tm
                LEFT JOIN tournament_teams ta ON ta.id = tm.team_a_id
                LEFT JOIN tournament_teams tb ON tb.id = tm.team_b_id
                LEFT JOIN tournament_teams tw ON tw.id = tm.winner_team_id
                WHERE tm.tournament_id = ?
                ORDER BY tm.round_no ASC, tm.match_no ASC, tm.id ASC
                """, (rs, row) -> mapMatchResponse(rs), tournamentId);
    }

    public List<StandingRow> listStandings(long tournamentId) {
        List<TeamSeed> teams = new ArrayList<>(listTeamSeeds(tournamentId));
        // Sort standings by score desc, buchholz desc, seed asc
        teams.sort(Comparator.comparingDouble(TeamSeed::score).reversed()
                .thenComparing(Comparator.comparingDouble(TeamSeed::buchholzScore).reversed())
                .thenComparingInt(t -> t.seed() > 0 ? t.seed() : Integer.MAX_VALUE));

        List<StandingRow> rows = new ArrayList<>();
        int rank = 1;
        for (TeamSeed t : teams) {
            MatchStats stats = getTeamMatchStats(tournamentId, t.teamId());
            String status = jdbc.queryForObject("SELECT status FROM tournament_teams WHERE id = ?", String.class, t.teamId());
            rows.add(new StandingRow(
                    t.teamId(),
                    t.teamName(),
                    rank++,
                    stats.played(),
                    stats.wins(),
                    stats.draws(),
                    stats.losses(),
                    t.score(),
                    t.buchholzScore(),
                    status
            ));
        }
        return rows;
    }

    private MatchStats getTeamMatchStats(long tournamentId, long teamId) {
        return jdbc.queryForObject("""
                SELECT
                    COUNT(*) FILTER (WHERE status IN ('FINISHED', 'BYE')) AS played,
                    COUNT(*) FILTER (WHERE winner_team_id = ?) AS wins,
                    COUNT(*) FILTER (WHERE status = 'FINISHED' AND winner_team_id IS NULL) AS draws,
                    COUNT(*) FILTER (WHERE status = 'FINISHED' AND winner_team_id IS NOT NULL AND winner_team_id != ?) AS losses
                FROM tournament_matches
                WHERE tournament_id = ? AND (team_a_id = ? OR team_b_id = ?)
                """, (rs, row) -> new MatchStats(
                rs.getInt("played"),
                rs.getInt("wins"),
                rs.getInt("draws"),
                rs.getInt("losses")
        ), teamId, teamId, tournamentId, teamId, teamId);
    }

    private List<TeamSeed> listTeamSeeds(long tournamentId) {
        return jdbc.query("""
                SELECT id, name, seed, score, buchholz_score
                FROM tournament_teams WHERE tournament_id = ?
                ORDER BY seed ASC NULLS LAST, id ASC
                """, (rs, row) -> new TeamSeed(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("seed"),
                rs.getDouble("score"),
                rs.getDouble("buchholz_score")
        ), tournamentId);
    }

    private List<MatchRecord> listMatchRecords(long tournamentId) {
        return jdbc.query("""
                SELECT id, round_no, match_no, stage, team_a_id, team_b_id, winner_team_id, status, score_a, score_b
                FROM tournament_matches WHERE tournament_id = ?
                ORDER BY round_no ASC, match_no ASC
                """, (rs, row) -> new MatchRecord(
                rs.getLong("id"),
                rs.getInt("round_no"),
                rs.getInt("match_no"),
                rs.getString("stage"),
                (Long) rs.getObject("team_a_id"),
                (Long) rs.getObject("team_b_id"),
                (Long) rs.getObject("winner_team_id"),
                TournamentMatchStatus.valueOf(rs.getString("status")),
                rs.getInt("score_a"),
                rs.getInt("score_b")
        ), tournamentId);
    }

    private TournamentMeta getTournamentMeta(long tournamentId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id, COALESCE(title, name) AS title, slug, description, format, status,
                           max_teams, team_size, rules_config::text AS rules_config,
                           created_by, created_at, updated_at
                    FROM tournaments WHERE id = ?
                    """, (rs, row) -> new TournamentMeta(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("slug"),
                    rs.getString("description"),
                    rs.getString("format"),
                    rs.getString("status"),
                    rs.getInt("max_teams"),
                    rs.getInt("team_size"),
                    readRulesConfig(rs.getString("rules_config")),
                    (Long) rs.getObject("created_by"),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("updated_at")
            ), tournamentId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        }
    }

    private TournamentResponse mapTournamentMeta(TournamentMeta meta) {
        int teamCount = jdbc.queryForObject("SELECT COUNT(*) FROM tournament_teams WHERE tournament_id = ?",
                Integer.class, meta.id());
        return new TournamentResponse(
                meta.id(),
                meta.title(),
                meta.slug(),
                meta.description(),
                meta.format(),
                meta.status(),
                meta.maxTeams(),
                meta.teamSize(),
                meta.rules(),
                meta.createdBy(),
                meta.createdAt() == null ? null : meta.createdAt().toInstant(),
                meta.updatedAt() == null ? null : meta.updatedAt().toInstant(),
                teamCount
        );
    }

    private TournamentMatchResponse mapMatchResponse(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new TournamentMatchResponse(
                rs.getLong("id"),
                rs.getLong("tournament_id"),
                rs.getInt("round_no"),
                rs.getInt("match_no"),
                rs.getString("stage"),
                (Long) rs.getObject("match_id"),
                (Long) rs.getObject("team_a_id"),
                rs.getString("team_a_name"),
                (Long) rs.getObject("team_b_id"),
                rs.getString("team_b_name"),
                (Long) rs.getObject("winner_team_id"),
                rs.getString("winner_team_name"),
                rs.getString("status"),
                rs.getInt("score_a"),
                rs.getInt("score_b"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }

    private RulesConfig readRulesConfig(String json) {
        if (json == null || json.isBlank()) return RulesConfig.defaultBlitz();
        try {
            return mapper.readValue(json, RulesConfig.class);
        } catch (Exception e) {
            return RulesConfig.defaultBlitz();
        }
    }

    record TournamentMeta(
            long id,
            String title,
            String slug,
            String description,
            String format,
            String status,
            int maxTeams,
            int teamSize,
            RulesConfig rules,
            Long createdBy,
            Timestamp createdAt,
            Timestamp updatedAt
    ) { }

    private record TeamScoreData(long id, String name, int wins, int draws, int losses) { }
    record MatchStats(int played, int wins, int draws, int losses) { }
}
