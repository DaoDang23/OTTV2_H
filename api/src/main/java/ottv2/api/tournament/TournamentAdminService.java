package ottv2.api.tournament;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.tournament.TournamentDtos.*;

import java.time.Instant;
import java.util.*;

@Service
public class TournamentAdminService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TournamentExecutionService executionService;
    private final TournamentBracketService bracketService;
    private final TournamentBroadcaster broadcaster;

    public TournamentAdminService(JdbcTemplate jdbc, ObjectMapper mapper,
                                  TournamentExecutionService executionService,
                                  TournamentBracketService bracketService) {
        this(jdbc, mapper, executionService, bracketService, null);
    }

    @Autowired
    public TournamentAdminService(JdbcTemplate jdbc, ObjectMapper mapper,
                                  TournamentExecutionService executionService,
                                  TournamentBracketService bracketService,
                                  TournamentBroadcaster broadcaster) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.executionService = executionService;
        this.bracketService = bracketService;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public TeamResponse approveTeam(long tournamentId, long teamId, Long operatorId) {
        int updated = jdbc.update("""
                UPDATE tournament_teams
                SET status = 'VERIFIED', updated_at = now()
                WHERE tournament_id = ? AND id = ? AND status = 'REGISTERED'
                """, tournamentId, teamId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Team cannot be approved (not found or not in REGISTERED state)");
        }

        recordAuditLog(tournamentId, operatorId, "TEAM_APPROVED",
                "Team ID " + teamId + " approved by operator " + operatorId,
                Map.of("teamId", teamId));

        return getTeam(tournamentId, teamId);
    }

    @Transactional
    public TeamResponse disqualifyTeam(long tournamentId, long teamId, Long operatorId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Disqualification reason is required");
        }

        int updated = jdbc.update("""
                UPDATE tournament_teams
                SET status = 'DISQUALIFIED', updated_at = now()
                WHERE tournament_id = ? AND id = ?
                """, tournamentId, teamId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found in this tournament");
        }

        recordAuditLog(tournamentId, operatorId, "TEAM_DISQUALIFIED",
                "Team ID " + teamId + " disqualified. Reason: " + reason.trim(),
                Map.of("teamId", teamId, "reason", reason.trim()));

        return getTeam(tournamentId, teamId);
    }

    @PostConstruct
    public void ensureStatusColumnCapacity() {
        try {
            jdbc.execute("ALTER TABLE tournaments ALTER COLUMN status TYPE VARCHAR(32)");
            jdbc.execute("ALTER TABLE tournaments ALTER COLUMN format TYPE VARCHAR(32)");
        } catch (Exception ignored) { }
    }

    @Transactional
    public TournamentResponse openRegistration(long tournamentId, Long operatorId) {
        // 1. Verify existence of tournament
        List<String> statuses = jdbc.query("SELECT status FROM tournaments WHERE id = ?",
                (rs, row) -> rs.getString("status"), tournamentId);
        if (statuses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found (id: " + tournamentId + ")");
        }

        String currentStatus = statuses.getFirst();
        if ("REGISTRATION_OPEN".equalsIgnoreCase(currentStatus)) {
            // Already open, idempotent success
            return getTournament(tournamentId);
        }

        if (!"DRAFT".equalsIgnoreCase(currentStatus) && !"REGISTRATION_CLOSED".equalsIgnoreCase(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Registration can only be opened when in DRAFT or REGISTRATION_CLOSED state (current: " + currentStatus + ")");
        }

        // 2. Pure UPDATE strictly by ID on existing record
        int updated = jdbc.update("""
                UPDATE tournaments
                SET status = 'REGISTRATION_OPEN', updated_at = now()
                WHERE id = ? AND status IN ('DRAFT', 'REGISTRATION_CLOSED')
                """, tournamentId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Registration can only be opened when in DRAFT or REGISTRATION_CLOSED state");
        }

        recordAuditLog(tournamentId, operatorId, "REGISTRATION_OPENED",
                "Registration opened by operator " + operatorId,
                Map.of("status", "REGISTRATION_OPEN"));

        if (broadcaster != null) {
            broadcaster.publishBracketChanged(tournamentId);
        }

        return getTournament(tournamentId);
    }

    @Transactional
    public TournamentResponse lockRegistration(long tournamentId, Long operatorId) {
        int updated = jdbc.update("""
                UPDATE tournaments
                SET status = 'CHECK_IN', updated_at = now()
                WHERE id = ? AND status = 'REGISTRATION_OPEN'
                """, tournamentId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Registration can only be locked when in REGISTRATION_OPEN state");
        }

        recordAuditLog(tournamentId, operatorId, "REGISTRATION_LOCKED",
                "Registration closed and moved to CHECK_IN by operator " + operatorId,
                Map.of("status", "CHECK_IN"));

        if (broadcaster != null) {
            broadcaster.publishBracketChanged(tournamentId);
        }

        return getTournament(tournamentId);
    }

    @Transactional
    public TournamentResponse startTournament(long tournamentId, Long operatorId) {
        return executionService.startTournament(tournamentId, operatorId);
    }

    @Transactional
    public TournamentResponse suspendTournament(long tournamentId, Long operatorId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Suspension reason is required");
        }

        int updated = jdbc.update("""
                UPDATE tournaments
                SET status = 'SUSPENDED', updated_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """, tournamentId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only tournaments in RUNNING state can be suspended");
        }

        recordAuditLog(tournamentId, operatorId, "TOURNAMENT_SUSPENDED",
                "Tournament suspended. Reason: " + reason.trim(),
                Map.of("reason", reason.trim()));

        return getTournament(tournamentId);
    }

    @Transactional
    public TournamentResponse resumeTournament(long tournamentId, Long operatorId) {
        int updated = jdbc.update("""
                UPDATE tournaments
                SET status = 'RUNNING', updated_at = now()
                WHERE id = ? AND status = 'SUSPENDED'
                """, tournamentId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only tournaments in SUSPENDED state can be resumed");
        }

        recordAuditLog(tournamentId, operatorId, "TOURNAMENT_RESUMED",
                "Tournament resumed by operator " + operatorId,
                Map.of("status", "RUNNING"));

        return getTournament(tournamentId);
    }

    @Transactional
    public TournamentResponse cancelTournament(long tournamentId, Long operatorId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cancellation reason is required");
        }

        int updated = jdbc.update("""
                UPDATE tournaments
                SET status = 'CANCELLED', updated_at = now()
                WHERE id = ? AND status NOT IN ('COMPLETED', 'CANCELLED')
                """, tournamentId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tournament cannot be cancelled (already completed or cancelled)");
        }

        // Cancel scheduled matches
        jdbc.update("""
                UPDATE tournament_matches
                SET status = 'CANCELLED', updated_at = now()
                WHERE tournament_id = ? AND status IN ('SCHEDULED', 'READY', 'RUNNING')
                """, tournamentId);

        recordAuditLog(tournamentId, operatorId, "TOURNAMENT_CANCELLED",
                "Tournament cancelled. Reason: " + reason.trim(),
                Map.of("reason", reason.trim()));

        return getTournament(tournamentId);
    }

    @Transactional
    public void deleteTournament(long tournamentId, Long operatorId) {
        // 1. Verify existence of tournament
        List<String> statuses = jdbc.query("SELECT status FROM tournaments WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString("status"), tournamentId);
        if (statuses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giải đấu (ID: " + tournamentId + ")");
        }

        String currentStatus = statuses.getFirst();
        // 2. Guard: Only DRAFT or CANCELLED allowed
        if (!"DRAFT".equalsIgnoreCase(currentStatus) && !"CANCELLED".equalsIgnoreCase(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Chỉ có thể xóa giải đấu ở trạng thái Bản Nháp (DRAFT) hoặc Đã Hủy (CANCELLED). Không thể xóa giải đấu đang ở trạng thái: " + currentStatus);
        }

        // 3. Clean up legacy bracket tables first (if any)
        try {
            jdbc.update("DELETE FROM bracket_games WHERE node_id IN (SELECT id FROM bracket_nodes WHERE tournament_id = ?)", tournamentId);
            jdbc.update("DELETE FROM bracket_nodes WHERE tournament_id = ?", tournamentId);
            jdbc.update("DELETE FROM tournament_entries WHERE tournament_id = ?", tournamentId);
        } catch (Exception ignored) {}

        // 4. Clean up tournament engine tables in foreign-key safe order:
        // Audit logs -> matches -> members -> teams
        jdbc.update("DELETE FROM tournament_audit_logs WHERE tournament_id = ?", tournamentId);
        jdbc.update("DELETE FROM tournament_matches WHERE tournament_id = ?", tournamentId);
        jdbc.update("DELETE FROM tournament_members WHERE tournament_id = ?", tournamentId);
        jdbc.update("DELETE FROM tournament_teams WHERE tournament_id = ?", tournamentId);

        // 5. Delete tournament row
        int deleted = jdbc.update("DELETE FROM tournaments WHERE id = ?", tournamentId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Giải đấu không tồn tại hoặc đã bị xóa trước đó");
        }

        if (broadcaster != null) {
            try {
                broadcaster.publishBracketChanged(tournamentId);
            } catch (Exception ignored) {}
        }
    }

    @Transactional
    public TournamentMatchResponse resolveMatchDispute(long tournamentId, long matchId,
                                                      Long operatorId, ResolveDisputeRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dispute resolution reason is required");
        }

        List<MatchAdminRow> rows = jdbc.query("""
                SELECT id, tournament_id, round_no, match_no, stage, team_a_id, team_b_id, status
                FROM tournament_matches
                WHERE id = ? AND tournament_id = ? FOR UPDATE
                """, (rs, row) -> new MatchAdminRow(
                rs.getLong("id"),
                rs.getLong("tournament_id"),
                rs.getInt("round_no"),
                rs.getInt("match_no"),
                rs.getString("stage"),
                (Long) rs.getObject("team_a_id"),
                (Long) rs.getObject("team_b_id"),
                rs.getString("status")
        ), matchId, tournamentId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found in this tournament");
        }
        MatchAdminRow match = rows.getFirst();

        if (!request.winnerTeamId().equals(match.teamAId()) && !request.winnerTeamId().equals(match.teamBId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Declared winner must be one of the participating teams (" + match.teamAId() + ", " + match.teamBId() + ")");
        }

        int scoreA = request.scoreA() != null ? request.scoreA() : (request.winnerTeamId().equals(match.teamAId()) ? 1 : 0);
        int scoreB = request.scoreB() != null ? request.scoreB() : (request.winnerTeamId().equals(match.teamBId()) ? 1 : 0);

        jdbc.update("""
                UPDATE tournament_matches
                SET winner_team_id = ?, score_a = ?, score_b = ?, status = 'FINISHED', updated_at = now()
                WHERE id = ?
                """, request.winnerTeamId(), scoreA, scoreB, matchId);

        // Standings recalculation
        bracketService.recalculateStandingsAndBuchholz(tournamentId);

        // Advance single elimination if applicable
        String format = jdbc.queryForObject("SELECT format FROM tournaments WHERE id = ?", String.class, tournamentId);
        if ("SINGLE_ELIMINATION".equals(format)) {
            bracketService.advanceSingleEliminationWinner(tournamentId, match.roundNo(), match.matchNo(), request.winnerTeamId());
            executionService.triggerReadyMatches(tournamentId);
        }

        recordAuditLog(tournamentId, operatorId, "MATCH_DISPUTE_RESOLVED",
                "Match ID " + matchId + " dispute resolved manually. Winner: Team " + request.winnerTeamId() +
                ". Reason: " + request.reason().trim(),
                Map.of("matchId", matchId, "winnerTeamId", request.winnerTeamId(),
                        "scoreA", scoreA, "scoreB", scoreB, "reason", request.reason().trim()));

        return bracketService.listMatches(tournamentId).stream()
                .filter(m -> m.id() == matchId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Match not found"));
    }

    public void retryMatch(long tournamentId, long matchId, Long operatorId) {
        executionService.retryFailedMatch(tournamentId, matchId, operatorId);
    }

    @Transactional
    public TournamentMatchResponse startMatch(long tournamentId, long matchId, Long operatorId) {
        List<TournamentMatchResponse> matches = bracketService.listMatches(tournamentId);
        TournamentMatchResponse match = matches.stream()
                .filter(m -> m.id() == matchId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found in tournament"));

        if (!"READY".equals(match.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only matches in READY status can be started (current: " + match.status() + ")");
        }

        executionService.executeMatch(matchId);

        recordAuditLog(tournamentId, operatorId, "MATCH_MANUALLY_STARTED",
                "Admin manually started match ID " + matchId,
                Map.of("matchId", matchId, "roundNo", match.roundNo(), "matchNo", match.matchNo()));

        return bracketService.listMatches(tournamentId).stream()
                .filter(m -> m.id() == matchId)
                .findFirst()
                .orElse(match);
    }

    public void recordAuditLog(long tournamentId, Long operatorId, String action, String message, Map<String, Object> extra) {
        try {
            Map<String, Object> details = new HashMap<>(extra != null ? extra : Map.of());
            details.put("message", message);
            details.put("timestamp", Instant.now().toString());
            String json = mapper.writeValueAsString(details);
            jdbc.update("""
                    INSERT INTO tournament_audit_logs (tournament_id, operator_id, action, details)
                    VALUES (?, ?, ?, ?::jsonb)
                    """, tournamentId, operatorId, action, json);
        } catch (Exception ignored) { }
    }

    private TournamentResponse getTournament(long tournamentId) {
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

    private TeamResponse getTeam(long tournamentId, long teamId) {
        return jdbc.queryForObject("""
                SELECT tt.id, tt.tournament_id, tt.name, tt.team_code, tt.captain_id,
                       u.display_name AS captain_name, tt.bot_id, b.name AS bot_name,
                       tt.submission_id, tt.source_sha256, tt.version_no, tt.status,
                       tt.seed, tt.score, tt.buchholz_score, tt.created_at
                FROM tournament_teams tt
                JOIN users u ON u.id = tt.captain_id
                LEFT JOIN bots b ON b.id = tt.bot_id
                WHERE tt.tournament_id = ? AND tt.id = ?
                """, (rs, row) -> new TeamResponse(
                rs.getLong("id"),
                rs.getLong("tournament_id"),
                rs.getString("name"),
                rs.getString("team_code"),
                rs.getLong("captain_id"),
                rs.getString("captain_name"),
                (Long) rs.getObject("bot_id"),
                rs.getString("bot_name"),
                (Long) rs.getObject("submission_id"),
                rs.getString("source_sha256"),
                (Integer) rs.getObject("version_no"),
                rs.getString("status"),
                (Integer) rs.getObject("seed"),
                rs.getDouble("score"),
                rs.getDouble("buchholz_score"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                List.of()
        ), tournamentId, teamId);
    }

    private RulesConfig readRulesConfig(String json) {
        if (json == null || json.isBlank()) return RulesConfig.defaultBlitz();
        try { return mapper.readValue(json, RulesConfig.class); }
        catch (Exception e) { return RulesConfig.defaultBlitz(); }
    }

    private record MatchAdminRow(long id, long tournamentId, int roundNo, int matchNo, String stage,
                                 Long teamAId, Long teamBId, String status) { }
}
