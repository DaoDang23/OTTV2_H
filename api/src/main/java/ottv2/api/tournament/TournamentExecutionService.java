package ottv2.api.tournament;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.match.MatchJob;
import ottv2.api.tournament.TournamentDtos.*;

import java.security.SecureRandom;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class TournamentExecutionService {
    private static final MatchJob.MapConfiguration DEFAULT_MAP = new MatchJob.MapConfiguration(8, 0, 0, 0);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TournamentBracketService bracketService;
    private final SecureRandom random = new SecureRandom();

    public TournamentExecutionService(JdbcTemplate jdbc, ObjectMapper mapper, TournamentBracketService bracketService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.bracketService = bracketService;
    }

    @Transactional
    public TournamentResponse startTournament(long tournamentId, Long operatorId) {
        TournamentState tourney = getTournamentState(tournamentId, true);
        if (!"CHECK_IN".equals(tourney.status()) && !"REGISTRATION_CLOSED".equals(tourney.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tournament can only be started from CHECK_IN or REGISTRATION_CLOSED stage (current: " + tourney.status() + ")");
        }

        int checkedIn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournament_teams WHERE tournament_id = ? AND status = 'CHECKED_IN'",
                Integer.class, tournamentId);

        if (checkedIn < 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot start tournament: requires at least 2 checked-in teams (found " + checkedIn + ")");
        }

        // Disqualify teams that failed to check in
        jdbc.update("""
                UPDATE tournament_teams
                SET status = 'DISQUALIFIED', updated_at = now()
                WHERE tournament_id = ? AND status NOT IN ('CHECKED_IN', 'DISQUALIFIED', 'ELIMINATED')
                """, tournamentId);

        // Update status to RUNNING
        jdbc.update("UPDATE tournaments SET status = 'RUNNING', updated_at = now() WHERE id = ?", tournamentId);

        // Generate bracket matches - all matches start in READY status for manual admin launch
        bracketService.generateInitialBracket(tournamentId);

        recordAuditLog(tournamentId, operatorId, "TOURNAMENT_STARTED",
                "Tournament started with " + checkedIn + " checked-in teams");

        return getTournamentResponse(tournamentId);
    }

    @Transactional
    public void triggerReadyMatches(long tournamentId) {
        List<Long> readyMatchIds = jdbc.query("""
                SELECT id FROM tournament_matches
                WHERE tournament_id = ? AND status = 'READY'
                ORDER BY round_no ASC, match_no ASC
                """, (rs, row) -> rs.getLong(1), tournamentId);

        for (Long matchId : readyMatchIds) {
            executeMatch(matchId);
        }
    }

    @Transactional
    public void executeMatch(long tournamentMatchId) {
        List<TourneyMatchRow> rows = jdbc.query("""
                SELECT tm.id, tm.tournament_id, t.season_id, tm.round_no, tm.match_no,
                       tm.team_a_id, tm.team_b_id, tm.status
                FROM tournament_matches tm
                JOIN tournaments t ON t.id = tm.tournament_id
                WHERE tm.id = ? FOR UPDATE
                """, (rs, row) -> new TourneyMatchRow(
                rs.getLong("id"),
                rs.getLong("tournament_id"),
                (Long) rs.getObject("season_id"),
                rs.getInt("round_no"),
                rs.getInt("match_no"),
                (Long) rs.getObject("team_a_id"),
                (Long) rs.getObject("team_b_id"),
                rs.getString("status")
        ), tournamentMatchId);

        if (rows.isEmpty()) return;
        TourneyMatchRow row = rows.getFirst();

        if (!"READY".equals(row.status())) return;

        // Handle BYE match
        if (row.teamBId() == null) {
            jdbc.update("""
                    UPDATE tournament_matches
                    SET status = 'BYE', winner_team_id = ?, updated_at = now()
                    WHERE id = ?
                    """, row.teamAId(), row.id());

            bracketService.advanceSingleEliminationWinner(row.tournamentId(), row.roundNo(), row.matchNo(), row.teamAId());
            bracketService.recalculateStandingsAndBuchholz(row.tournamentId());
            return;
        }

        // Fetch participant teams with frozen bot submission snapshots
        ParticipantData sideA = getParticipantData(row.tournamentId(), row.teamAId());
        ParticipantData sideB = getParticipantData(row.tournamentId(), row.teamBId());

        long seed = random.nextLong();
        String mapJson = writeJson(DEFAULT_MAP);

        // Insert into matches with match_type = 'TOURNAMENT'
        MatchInsert inserted = jdbc.queryForObject("""
                INSERT INTO matches(season_id, match_type, seed, map_config, engine_ver, status)
                VALUES (?, 'TOURNAMENT', ?, ?::jsonb, '1', 'QUEUED')
                RETURNING id, public_id, created_month
                """, (rs, r) -> new MatchInsert(rs.getLong("id"), rs.getObject("public_id", UUID.class),
                rs.getDate("created_month").toLocalDate()), row.seasonId(), seed, mapJson);

        // Snapshot frozen submission versions and baseline elo into match_participants
        jdbc.update("""
                INSERT INTO match_participants(match_id, created_month, side, bot_id, submission_id, source_sha256, version_no, elo_before)
                VALUES (?, ?, 'A', ?, ?, ?, ?, COALESCE((SELECT elo FROM bot_ratings WHERE bot_id=? AND season_id=?), 1200)),
                       (?, ?, 'B', ?, ?, ?, ?, COALESCE((SELECT elo FROM bot_ratings WHERE bot_id=? AND season_id=?), 1200))
                """, inserted.id(), Date.valueOf(inserted.createdMonth()), sideA.botId(), sideA.submissionId(), sideA.sourceSha256(), sideA.versionNo(), sideA.botId(), row.seasonId(),
                inserted.id(), Date.valueOf(inserted.createdMonth()), sideB.botId(), sideB.submissionId(), sideB.sourceSha256(), sideB.versionNo(), sideB.botId(), row.seasonId());

        // Update tournament match with real match reference and status RUNNING
        jdbc.update("""
                UPDATE tournament_matches
                SET match_id = ?, match_created_month = ?, status = 'RUNNING', updated_at = now()
                WHERE id = ?
                """, inserted.id(), Date.valueOf(inserted.createdMonth()), row.id());

        // Dispatch MatchJob to outbox on topic 'match.tournament.q'
        MatchJob job = new MatchJob("MATCH_JOB", inserted.id(), inserted.publicId().toString(),
                inserted.createdMonth().toString(), seed, DEFAULT_MAP,
                sideA.toJob(), sideB.toJob());

        jdbc.update("INSERT INTO outbox_events(topic, payload) VALUES ('match.tournament.q', ?::jsonb)", writeJson(job));
    }

    @Transactional
    public void onMatchFinished(long matchId, LocalDate month, String winnerSide, String endReason) {
        List<TourneyMatchRow> rows = jdbc.query("""
                SELECT tm.id, tm.tournament_id, t.season_id, tm.round_no, tm.match_no,
                       tm.team_a_id, tm.team_b_id, tm.status
                FROM tournament_matches tm
                JOIN tournaments t ON t.id = tm.tournament_id
                WHERE tm.match_id = ? FOR UPDATE
                """, (rs, row) -> new TourneyMatchRow(
                rs.getLong("id"),
                rs.getLong("tournament_id"),
                (Long) rs.getObject("season_id"),
                rs.getInt("round_no"),
                rs.getInt("match_no"),
                (Long) rs.getObject("team_a_id"),
                (Long) rs.getObject("team_b_id"),
                rs.getString("status")
        ), matchId);

        if (rows.isEmpty()) return; // Not a tournament match or not registered
        TourneyMatchRow tm = rows.getFirst();

        if ("FINISHED".equals(tm.status()) || "CANCELLED".equals(tm.status())) {
            return; // Already resolved
        }

        Long winnerTeamId = null;
        int scoreA = 0;
        int scoreB = 0;

        if ("A".equals(winnerSide)) {
            winnerTeamId = tm.teamAId();
            scoreA = 1;
        } else if ("B".equals(winnerSide)) {
            winnerTeamId = tm.teamBId();
            scoreB = 1;
        }

        // Update match outcome
        jdbc.update("""
                UPDATE tournament_matches
                SET status = 'FINISHED', winner_team_id = ?, score_a = ?, score_b = ?, updated_at = now()
                WHERE id = ?
                """, winnerTeamId, scoreA, scoreB, tm.id());

        // Recalculate standings and Buchholz scores
        bracketService.recalculateStandingsAndBuchholz(tm.tournamentId());

        String format = jdbc.queryForObject("SELECT format FROM tournaments WHERE id = ?", String.class, tm.tournamentId());

        if ("SINGLE_ELIMINATION".equals(format)) {
            if (winnerTeamId != null) {
                bracketService.advanceSingleEliminationWinner(tm.tournamentId(), tm.roundNo(), tm.matchNo(), winnerTeamId);
            }

            // Check if final round match finished
            int maxRound = jdbc.queryForObject(
                    "SELECT MAX(round_no) FROM tournament_matches WHERE tournament_id = ?",
                    Integer.class, tm.tournamentId());

            if (tm.roundNo() == maxRound) {
                // Final match completed!
                jdbc.update("UPDATE tournaments SET status = 'COMPLETED', updated_at = now() WHERE id = ?", tm.tournamentId());
                recordAuditLog(tm.tournamentId(), null, "TOURNAMENT_COMPLETED",
                        "Tournament completed! Champion team ID: " + winnerTeamId);
            }
            // Next round matches remain in READY status for manual admin launch

        } else if ("ROUND_ROBIN".equals(format)) {
            // Check if all matches across all rounds are finished
            int unfinished = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM tournament_matches
                    WHERE tournament_id = ? AND status IN ('SCHEDULED', 'READY', 'RUNNING')
                    """, Integer.class, tm.tournamentId());

            if (unfinished == 0) {
                jdbc.update("UPDATE tournaments SET status = 'COMPLETED', updated_at = now() WHERE id = ?", tm.tournamentId());
                recordAuditLog(tm.tournamentId(), null, "TOURNAMENT_COMPLETED", "Round Robin tournament completed!");
            } else {
                // If all matches in current round finished, advance next round from SCHEDULED to READY
                int unfinishedInRound = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM tournament_matches
                        WHERE tournament_id = ? AND round_no = ? AND status IN ('SCHEDULED', 'READY', 'RUNNING')
                        """, Integer.class, tm.tournamentId(), tm.roundNo());

                if (unfinishedInRound == 0) {
                    jdbc.update("""
                            UPDATE tournament_matches
                            SET status = 'READY', updated_at = now()
                            WHERE tournament_id = ? AND round_no = ? AND status = 'SCHEDULED'
                            """, tm.tournamentId(), tm.roundNo() + 1);
                    // Next round matches remain in READY status for manual admin launch
                }
            }

        } else if ("SWISS".equals(format)) {
            // Check if current Swiss round has finished
            int unfinishedInRound = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM tournament_matches
                    WHERE tournament_id = ? AND round_no = ? AND status IN ('SCHEDULED', 'READY', 'RUNNING')
                    """, Integer.class, tm.tournamentId(), tm.roundNo());

            if (unfinishedInRound == 0) {
                RulesConfig rules = readRulesConfig(jdbc.queryForObject(
                        "SELECT rules_config::text FROM tournaments WHERE id = ?", String.class, tm.tournamentId()));
                int teamCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tournament_teams WHERE tournament_id = ? AND status = 'CHECKED_IN'",
                        Integer.class, tm.tournamentId());
                int totalRounds = rules.swissRoundsCount(teamCount);

                if (tm.roundNo() < totalRounds) {
                    bracketService.generateSwissNextRound(tm.tournamentId(), tm.roundNo() + 1);
                    // Next round matches remain in READY status for manual admin launch
                } else {
                    jdbc.update("UPDATE tournaments SET status = 'COMPLETED', updated_at = now() WHERE id = ?", tm.tournamentId());
                    recordAuditLog(tm.tournamentId(), null, "TOURNAMENT_COMPLETED", "Swiss tournament completed after " + totalRounds + " rounds!");
                }
            }
        }
    }

    @Transactional
    public void onMatchFailed(long matchId, LocalDate month, String error) {
        List<Long> tourneyMatchIds = jdbc.query(
                "SELECT id FROM tournament_matches WHERE match_id = ? FOR UPDATE",
                (rs, row) -> rs.getLong(1), matchId);

        if (tourneyMatchIds.isEmpty()) return;

        long tmId = tourneyMatchIds.getFirst();
        jdbc.update("""
                UPDATE tournament_matches
                SET status = 'ERROR', updated_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """, tmId);

        Long tourneyId = jdbc.queryForObject(
                "SELECT tournament_id FROM tournament_matches WHERE id = ?", Long.class, tmId);
        recordAuditLog(tourneyId, null, "MATCH_FAILED",
                "Match ID " + tmId + " failed with worker/infra error: " + error);
    }

    @Transactional
    public void retryFailedMatch(long tournamentId, long tournamentMatchId, Long operatorId) {
        List<TourneyMatchRow> rows = jdbc.query("""
                SELECT tm.id, tm.tournament_id, t.season_id, tm.round_no, tm.match_no,
                       tm.team_a_id, tm.team_b_id, tm.status
                FROM tournament_matches tm
                JOIN tournaments t ON t.id = tm.tournament_id
                WHERE tm.id = ? AND tm.tournament_id = ? FOR UPDATE
                """, (rs, row) -> new TourneyMatchRow(
                rs.getLong("id"),
                rs.getLong("tournament_id"),
                (Long) rs.getObject("season_id"),
                rs.getInt("round_no"),
                rs.getInt("match_no"),
                (Long) rs.getObject("team_a_id"),
                (Long) rs.getObject("team_b_id"),
                rs.getString("status")
        ), tournamentMatchId, tournamentId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament match not found");
        }
        TourneyMatchRow tm = rows.getFirst();

        if (!"ERROR".equals(tm.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only matches in ERROR status can be retried (current: " + tm.status() + ")");
        }

        Long matchId = jdbc.queryForObject(
                "SELECT match_id FROM tournament_matches WHERE id = ?", Long.class, tm.id());

        if (matchId != null) {
            long seed = random.nextLong();
            // Reset existing matches record with standard map config and fresh seed
            jdbc.update("""
                    UPDATE matches
                    SET status = 'QUEUED', failure_detail = NULL, finished_at = NULL, map_config = ?::jsonb, seed = ?
                    WHERE id = ?
                    """, writeJson(DEFAULT_MAP), seed, matchId);

            jdbc.update("UPDATE match_participants SET fault = NULL WHERE match_id = ?", matchId);

            ParticipantData sideA = getParticipantData(tm.tournamentId(), tm.teamAId());
            ParticipantData sideB = getParticipantData(tm.tournamentId(), tm.teamBId());

            MatchInsert key = jdbc.queryForObject(
                    "SELECT public_id, created_month FROM matches WHERE id = ?",
                    (rs, row) -> new MatchInsert(matchId, rs.getObject("public_id", UUID.class), rs.getDate("created_month").toLocalDate()),
                    matchId);

            MatchJob job = new MatchJob("MATCH_JOB", matchId, key.publicId().toString(),
                    key.createdMonth().toString(), seed, DEFAULT_MAP,
                    sideA.toJob(), sideB.toJob());

            jdbc.update("""
                    UPDATE tournament_matches
                    SET status = 'RUNNING', updated_at = now()
                    WHERE id = ?
                    """, tm.id());

            jdbc.update("INSERT INTO outbox_events(topic, payload) VALUES ('match.tournament.q', ?::jsonb)", writeJson(job));
        } else {
            // Re-trigger from scratch
            jdbc.update("UPDATE tournament_matches SET status = 'READY', updated_at = now() WHERE id = ?", tm.id());
            executeMatch(tm.id());
        }

        recordAuditLog(tournamentId, operatorId, "MATCH_RETRIED",
                "Admin retried tournament match ID " + tm.id());
    }

    private ParticipantData getParticipantData(long tournamentId, long teamId) {
        return jdbc.queryForObject("""
                SELECT b.id AS bot_id, b.name AS bot_name, b.owner_id,
                       tt.submission_id, s.language, s.artifact_key, tt.source_sha256, tt.version_no
                FROM tournament_teams tt
                JOIN bots b ON b.id = tt.bot_id
                JOIN submissions s ON s.id = tt.submission_id
                WHERE tt.tournament_id = ? AND tt.id = ?
                """, (rs, row) -> new ParticipantData(
                rs.getLong("bot_id"),
                rs.getString("bot_name"),
                rs.getLong("owner_id"),
                rs.getLong("submission_id"),
                rs.getString("language"),
                rs.getString("artifact_key"),
                rs.getString("source_sha256").trim(),
                rs.getInt("version_no")
        ), tournamentId, teamId);
    }

    private TournamentState getTournamentState(long tournamentId, boolean forUpdate) {
        String sql = "SELECT id, status, max_teams, team_size FROM tournaments WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try {
            return jdbc.queryForObject(sql,
                    (rs, row) -> new TournamentState(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)),
                    tournamentId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        }
    }

    private TournamentResponse getTournamentResponse(long tournamentId) {
        return jdbc.queryForObject("""
                SELECT t.id, COALESCE(t.title, t.name) AS title, t.slug, t.description,
                       t.format, t.status, t.max_teams, t.team_size, t.rules_config::text AS rules_config,
                       t.created_by, t.created_at, t.updated_at,
                       (SELECT COUNT(*) FROM tournament_teams tt WHERE tt.tournament_id = t.id) AS team_count
                FROM tournaments t WHERE t.id = ?
                """, (rs, row) -> new TournamentResponse(
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
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("team_count")
        ), tournamentId);
    }

    private void recordAuditLog(long tournamentId, Long operatorId, String action, String message) {
        try {
            Map<String, Object> details = Map.of(
                    "message", message,
                    "timestamp", Instant.now().toString()
            );
            String json = mapper.writeValueAsString(details);
            jdbc.update("""
                    INSERT INTO tournament_audit_logs (tournament_id, operator_id, action, details)
                    VALUES (?, ?, ?, ?::jsonb)
                    """, tournamentId, operatorId, action, json);
        } catch (Exception ignored) { }
    }

    private RulesConfig readRulesConfig(String json) {
        if (json == null || json.isBlank()) return RulesConfig.defaultBlitz();
        try { return mapper.readValue(json, RulesConfig.class); }
        catch (Exception e) { return RulesConfig.defaultBlitz(); }
    }

    private String writeJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Failed to serialize json", e); }
    }

    record TourneyMatchRow(long id, long tournamentId, Long seasonId, int roundNo, int matchNo,
                           Long teamAId, Long teamBId, String status) { }
    record MatchInsert(long id, UUID publicId, LocalDate createdMonth) { }
    record TournamentState(long id, String status, int maxTeams, int teamSize) { }

    record ParticipantData(long botId, String botName, long ownerId, long submissionId,
                           String language, String artifactKey, String sourceSha256, int versionNo) {
        MatchJob.ParticipantJob toJob() {
            return new MatchJob.ParticipantJob(botId, botName, ownerId, submissionId, language, artifactKey, sourceSha256);
        }
    }
}
