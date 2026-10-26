package ottv2.api.tournament;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.match.MatchJob;
import ottv2.api.tournament.TournamentDtos.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class TournamentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BracketBuilder builder;
    private final TournamentMatchPublisher publisher;
    private final TournamentBroadcaster broadcaster;
    private final ObjectMapper mapper;

    public TournamentService(JdbcTemplate jdbc, TransactionTemplate transaction, TournamentMatchPublisher publisher,
                             TournamentBroadcaster broadcaster, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.publisher = publisher;
        this.broadcaster = broadcaster;
        this.mapper = mapper;
        this.builder = new BracketBuilder();
    }

    // =========================================================================
    // 1. Quản lý Vòng đời Giải đấu (Tournament Lifecycle Management)
    // =========================================================================

    public TournamentResponse createTournament(Long creatorId, CreateTournamentRequest request) {
        String title = request.title().trim();
        String slug = request.slug();
        if (slug == null || slug.isBlank()) {
            slug = slugify(title) + "-" + UUID.randomUUID().toString().substring(0, 8);
        } else {
            slug = slug.trim().toLowerCase();
        }

        // Check slug uniqueness
        int slugExists = jdbc.queryForObject("SELECT COUNT(*) FROM tournaments WHERE slug=?", Integer.class, slug);
        if (slugExists > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tournament slug already exists");
        }

        TournamentFormat format = request.format() != null ? request.format() : TournamentFormat.SINGLE_ELIMINATION;
        int maxTeams = request.maxTeams() != null ? request.maxTeams() : 16;
        int teamSize = request.teamSize() != null ? request.teamSize() : 1;
        RulesConfig rules = request.rulesConfig() != null ? request.rulesConfig() : RulesConfig.defaultBlitz();
        String rulesJson = writeJson(rules);

        Long id = jdbc.queryForObject("""
                INSERT INTO tournaments (title, name, slug, description, format, status, max_teams,
                                        team_size, rules_config, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?::jsonb, ?, now(), now())
                RETURNING id
                """, Long.class, title, title, slug, request.description(), format.name(),
                maxTeams, teamSize, rulesJson, creatorId);

        recordAuditLog(id, creatorId, "TOURNAMENT_CREATED", "Tournament '" + title + "' created in DRAFT state");
        return getTournament(id);
    }

    public TournamentResponse updateTournament(long tournamentId, Long operatorId, UpdateTournamentRequest request) {
        return transaction.execute(status -> {
            TournamentStateRow tourney = getTournamentState(tournamentId, true);
            if ("RUNNING".equals(tourney.status()) || "COMPLETED".equals(tourney.status()) || "FINISHED".equals(tourney.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot modify tournament rules or settings when tournament is " + tourney.status());
            }

            int maxTeams = request.maxTeams() != null ? request.maxTeams() : tourney.maxTeams();
            int teamSize = request.teamSize() != null ? request.teamSize() : tourney.teamSize();
            RulesConfig rules = request.rulesConfig() != null ? request.rulesConfig() : RulesConfig.defaultBlitz();
            String rulesJson = writeJson(rules);

            jdbc.update("""
                    UPDATE tournaments
                    SET title = ?, name = ?, description = ?, max_teams = ?, team_size = ?, rules_config = ?::jsonb, updated_at = now()
                    WHERE id = ?
                    """, request.title().trim(), request.title().trim(), request.description(),
                    maxTeams, teamSize, rulesJson, tournamentId);

            recordAuditLog(tournamentId, operatorId, "TOURNAMENT_UPDATED", "Tournament settings updated");
            return getTournament(tournamentId);
        });
    }

    public TournamentResponse changeTournamentStatus(long tournamentId, Long operatorId, ChangeStatusRequest request) {
        return transaction.execute(status -> {
            TournamentStateRow tourney = getTournamentState(tournamentId, true);
            TournamentStatus current = parseStatus(tourney.status());
            TournamentStatus next = request.status();

            if (!current.canTransitionTo(next)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Invalid lifecycle transition: cannot transition directly from " + current + " to " + next);
            }

            // State specific validation
            if (next == TournamentStatus.RUNNING) {
                int checkedIn = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tournament_teams WHERE tournament_id=? AND status='CHECKED_IN'",
                        Integer.class, tournamentId);
                if (checkedIn < 2) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Cannot start tournament: requires at least 2 checked-in teams (found " + checkedIn + ")");
                }
                // Disqualify teams that failed to check-in
                jdbc.update("""
                        UPDATE tournament_teams
                        SET status='DISQUALIFIED', updated_at=now()
                        WHERE tournament_id=? AND status NOT IN ('CHECKED_IN', 'DISQUALIFIED', 'ELIMINATED')
                        """, tournamentId);
            }

            jdbc.update("UPDATE tournaments SET status=?, updated_at=now() WHERE id=?", next.name(), tournamentId);

            String reasonMsg = request.reason() != null && !request.reason().isBlank() ? " (Reason: " + request.reason() + ")" : "";
            recordAuditLog(tournamentId, operatorId, "STATUS_CHANGED", "Status changed from " + current + " to " + next + reasonMsg);

            return getTournament(tournamentId);
        });
    }

    // =========================================================================
    // 2. Đăng ký & Khóa Phiên bản Bot (Team Registration & Bot Freeze)
    // =========================================================================

    public TeamResponse registerTeam(long tournamentId, long captainId, RegisterTeamRequest request) {
        return transaction.execute(status -> {
            TournamentStateRow tourney = getTournamentState(tournamentId, true);
            if (!"REGISTRATION_OPEN".equals(tourney.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Registration is not open for this tournament (current status: " + tourney.status() + ")");
            }

            // Check capacity
            int currentTeams = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tournament_teams WHERE tournament_id=? AND status NOT IN ('DISQUALIFIED', 'ELIMINATED')",
                    Integer.class, tournamentId);
            if (currentTeams >= tourney.maxTeams()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Tournament team capacity reached (maximum: " + tourney.maxTeams() + ")");
            }

            // Check captain uniqueness in this tournament
            int captainExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tournament_teams WHERE tournament_id=? AND captain_id=?",
                    Integer.class, tournamentId, captainId);
            if (captainExists > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "You are already captain of a team in this tournament");
            }

            // Check team name uniqueness in this tournament
            int nameExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tournament_teams WHERE tournament_id=? AND name=?",
                    Integer.class, tournamentId, request.name().trim());
            if (nameExists > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Team name '" + request.name().trim() + "' is already taken in this tournament");
            }

            // Validate bot and submission
            List<BotSubRow> subRows = jdbc.query("""
                    SELECT b.id AS bot_id, b.name AS bot_name, b.owner_id, s.id AS sub_id,
                           s.source_sha256, s.version_no, s.status AS sub_status, s.artifact_key
                    FROM bots b JOIN submissions s ON s.bot_id = b.id
                    WHERE b.id=? AND s.id=? AND (b.deleted IS FALSE OR b.deleted IS NULL)
                    """, (rs, row) -> new BotSubRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                    rs.getLong(4), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8)),
                    request.botId(), request.submissionId());

            if (subRows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot or submission not found");
            }
            BotSubRow botSub = subRows.getFirst();

            // Validate members
            Set<Long> memberIds = new LinkedHashSet<>();
            memberIds.add(captainId);
            if (request.memberUserIds() != null) {
                memberIds.addAll(request.memberUserIds());
            }

            if (botSub.ownerId() != captainId && !memberIds.contains(botSub.ownerId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Registered bot must be owned by the team captain or a team member");
            }

            if (!"COMPILED".equals(botSub.subStatus()) && !"ACTIVE".equals(botSub.subStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Submission must be compiled");
            }
            if (botSub.artifactKey() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Submission artifact key is missing");
            }

            if (memberIds.size() > tourney.teamSize()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Team size exceeds tournament limit (allowed: " + tourney.teamSize() + ", provided: " + memberIds.size() + ")");
            }

            // Ensure no member is already in another team in this tournament
            for (Long memberId : memberIds) {
                int alreadyRegistered = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tournament_members WHERE tournament_id=? AND user_id=?",
                        Integer.class, tournamentId, memberId);
                if (alreadyRegistered > 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "User ID " + memberId + " is already enrolled in another team in this tournament");
                }
            }

            // Insert team with immutable snapshot of bot version
            Long teamId = jdbc.queryForObject("""
                    INSERT INTO tournament_teams (tournament_id, name, team_code, captain_id, bot_id,
                                                  submission_id, source_sha256, version_no, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REGISTERED', now(), now())
                    RETURNING id
                    """, Long.class, tournamentId, request.name().trim(),
                    request.teamCode() == null ? null : request.teamCode().trim(),
                    captainId, botSub.botId(), botSub.subId(),
                    botSub.sourceSha256().trim(), botSub.versionNo());

            // Insert members
            for (Long memberId : memberIds) {
                String role = memberId.equals(captainId) ? "CAPTAIN" : "MEMBER";
                jdbc.update("""
                        INSERT INTO tournament_members (team_id, tournament_id, user_id, role, joined_at)
                        VALUES (?, ?, ?, ?, now())
                        """, teamId, tournamentId, memberId, role);
            }

            recordAuditLog(tournamentId, captainId, "TEAM_REGISTERED",
                    "Team '" + request.name().trim() + "' registered by captain " + captainId +
                    " with bot '" + botSub.botName() + "' (version " + botSub.versionNo() + ")");

            return getTeam(tournamentId, teamId);
        });
    }

    public TeamResponse checkInTeam(long tournamentId, long teamId, Long operatorId, CheckInTeamRequest request) {
        return transaction.execute(status -> {
            TournamentStateRow tourney = getTournamentState(tournamentId, true);
            if (!"CHECK_IN".equals(tourney.status()) && !"REGISTRATION_CLOSED".equals(tourney.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Check-in is only allowed in CHECK_IN or REGISTRATION_CLOSED stage (current: " + tourney.status() + ")");
            }

            List<TeamStateRow> teams = jdbc.query("""
                    SELECT id, tournament_id, name, captain_id, bot_id, submission_id, status
                    FROM tournament_teams WHERE id=? AND tournament_id=? FOR UPDATE
                    """, (rs, row) -> new TeamStateRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getString(7)),
                    teamId, tournamentId);

            if (teams.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found");
            TeamStateRow team = teams.getFirst();

            if ("DISQUALIFIED".equals(team.status()) || "ELIMINATED".equals(team.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Team is disqualified or eliminated");
            }

            // Optional submission upgrade before freeze
            long targetSubId = (request != null && request.submissionId() != null) ? request.submissionId() : team.submissionId();
            List<BotSubRow> subRows = jdbc.query("""
                    SELECT b.id AS bot_id, b.name AS bot_name, b.owner_id, s.id AS sub_id,
                           s.source_sha256, s.version_no, s.status AS sub_status, s.artifact_key
                    FROM bots b JOIN submissions s ON s.bot_id = b.id
                    WHERE b.id=? AND s.id=? AND (b.deleted IS FALSE OR b.deleted IS NULL)
                    """, (rs, row) -> new BotSubRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                    rs.getLong(4), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8)),
                    team.botId(), targetSubId);

            if (subRows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found for team bot");
            }
            BotSubRow botSub = subRows.getFirst();
            if (!"COMPILED".equals(botSub.subStatus()) && !"ACTIVE".equals(botSub.subStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Submission must be compiled");
            }

            // Freeze bot version and mark CHECKED_IN
            jdbc.update("""
                    UPDATE tournament_teams
                    SET status='CHECKED_IN', submission_id=?, source_sha256=?, version_no=?, updated_at=now()
                    WHERE id=?
                    """, botSub.subId(), botSub.sourceSha256().trim(), botSub.versionNo(), teamId);

            recordAuditLog(tournamentId, operatorId, "TEAM_CHECKED_IN",
                    "Team '" + team.name() + "' checked in and locked bot version " + botSub.versionNo() +
                    " (SHA: " + botSub.sourceSha256().trim() + ")");

            return getTeam(tournamentId, teamId);
        });
    }

    // =========================================================================
    // 3. Query Methods & DTO Converters
    // =========================================================================

    public TournamentResponse getTournament(long tournamentId) {
        try {
            return jdbc.queryForObject("""
                    SELECT t.id, COALESCE(t.title, t.name) AS title, t.slug, t.description,
                           t.format, t.status, t.max_teams, t.team_size, t.rules_config::text AS rules_config,
                           t.created_by, t.created_at, t.updated_at,
                           (SELECT COUNT(*) FROM tournament_teams tt WHERE tt.tournament_id = t.id) AS team_count
                    FROM tournaments t WHERE t.id = ?
                    """, (rs, row) -> mapTournament(rs), tournamentId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        }
    }

    public TournamentResponse getTournamentBySlug(String slug) {
        try {
            return jdbc.queryForObject("""
                    SELECT t.id, COALESCE(t.title, t.name) AS title, t.slug, t.description,
                           t.format, t.status, t.max_teams, t.team_size, t.rules_config::text AS rules_config,
                           t.created_by, t.created_at, t.updated_at,
                           (SELECT COUNT(*) FROM tournament_teams tt WHERE tt.tournament_id = t.id) AS team_count
                    FROM tournaments t WHERE t.slug = ?
                    """, (rs, row) -> mapTournament(rs), slug.trim().toLowerCase());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found with slug: " + slug);
        }
    }

    public List<TournamentResponse> listTournaments() {
        return jdbc.query("""
                SELECT t.id, COALESCE(t.title, t.name) AS title, t.slug, t.description,
                       t.format, t.status, t.max_teams, t.team_size, t.rules_config::text AS rules_config,
                       t.created_by, t.created_at, t.updated_at,
                       (SELECT COUNT(*) FROM tournament_teams tt WHERE tt.tournament_id = t.id) AS team_count
                FROM tournaments t ORDER BY t.created_at DESC, t.id DESC LIMIT 100
                """, (rs, row) -> mapTournament(rs));
    }

    public List<TeamResponse> listTeams(long tournamentId) {
        return jdbc.query("""
                SELECT tt.id, tt.tournament_id, tt.name, tt.team_code, tt.captain_id,
                       u.display_name AS captain_name, tt.bot_id, b.name AS bot_name,
                       tt.submission_id, tt.source_sha256, tt.version_no, tt.status,
                       tt.seed, tt.score, tt.buchholz_score, tt.created_at
                FROM tournament_teams tt
                JOIN users u ON u.id = tt.captain_id
                LEFT JOIN bots b ON b.id = tt.bot_id
                WHERE tt.tournament_id = ?
                ORDER BY tt.seed ASC NULLS LAST, tt.score DESC, tt.id ASC
                """, (rs, row) -> mapTeam(rs), tournamentId);
    }

    public TeamResponse getTeam(long tournamentId, long teamId) {
        try {
            return jdbc.queryForObject("""
                    SELECT tt.id, tt.tournament_id, tt.name, tt.team_code, tt.captain_id,
                           u.display_name AS captain_name, tt.bot_id, b.name AS bot_name,
                           tt.submission_id, tt.source_sha256, tt.version_no, tt.status,
                           tt.seed, tt.score, tt.buchholz_score, tt.created_at
                    FROM tournament_teams tt
                    JOIN users u ON u.id = tt.captain_id
                    LEFT JOIN bots b ON b.id = tt.bot_id
                    WHERE tt.tournament_id = ? AND tt.id = ?
                    """, (rs, row) -> mapTeam(rs), tournamentId, teamId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found");
        }
    }

    public List<AuditLogResponse> listAuditLogs(long tournamentId) {
        return jdbc.query("""
                SELECT a.id, a.tournament_id, a.operator_id, a.action, a.details::text, a.created_at
                FROM tournament_audit_logs a
                WHERE a.tournament_id = ?
                ORDER BY a.created_at DESC, a.id DESC LIMIT 200
                """, (rs, row) -> new AuditLogResponse(
                rs.getLong("id"), rs.getLong("tournament_id"), (Long) rs.getObject("operator_id"),
                rs.getString("action"), rs.getString("details"), rs.getTimestamp("created_at").toInstant()
        ), tournamentId);
    }

    private TournamentResponse mapTournament(java.sql.ResultSet rs) throws java.sql.SQLException {
        RulesConfig rules = readRulesConfig(rs.getString("rules_config"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new TournamentResponse(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("slug"),
                rs.getString("description"),
                rs.getString("format"),
                rs.getString("status"),
                rs.getInt("max_teams"),
                rs.getInt("team_size"),
                rules,
                (Long) rs.getObject("created_by"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant(),
                rs.getInt("team_count")
        );
    }

    private TeamResponse mapTeam(java.sql.ResultSet rs) throws java.sql.SQLException {
        long teamId = rs.getLong("id");
        List<MemberResponse> members = jdbc.query("""
                SELECT tm.user_id, u.student_code, u.display_name, tm.role, tm.joined_at
                FROM tournament_members tm
                JOIN users u ON u.id = tm.user_id
                WHERE tm.team_id = ?
                ORDER BY tm.role DESC, tm.joined_at ASC
                """, (mRs, mRow) -> new MemberResponse(
                mRs.getLong("user_id"), mRs.getString("student_code"), mRs.getString("display_name"),
                mRs.getString("role"), mRs.getTimestamp("joined_at").toInstant()
        ), teamId);

        Timestamp createdAt = rs.getTimestamp("created_at");
        return new TeamResponse(
                teamId,
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
                createdAt == null ? null : createdAt.toInstant(),
                members
        );
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
        } catch (Exception ignored) {
        }
    }

    private RulesConfig readRulesConfig(String json) {
        if (json == null || json.isBlank()) return RulesConfig.defaultBlitz();
        try {
            return mapper.readValue(json, RulesConfig.class);
        } catch (Exception e) {
            return RulesConfig.defaultBlitz();
        }
    }

    private String writeJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String slugify(String input) {
        return input.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private TournamentStatus parseStatus(String status) {
        try {
            return TournamentStatus.valueOf(status);
        } catch (Exception e) {
            if ("FINISHED".equalsIgnoreCase(status)) return TournamentStatus.COMPLETED;
            return TournamentStatus.DRAFT;
        }
    }

    private TournamentStateRow getTournamentState(long tournamentId, boolean forUpdate) {
        String sql = "SELECT id, status, max_teams, team_size FROM tournaments WHERE id=?" + (forUpdate ? " FOR UPDATE" : "");
        try {
            return jdbc.queryForObject(sql,
                    (rs, row) -> new TournamentStateRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)),
                    tournamentId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        }
    }

    // =========================================================================
    // Legacy / Backward Compatible Methods (for 32-bot bracket & tests)
    // =========================================================================

    public TournamentSummary create(long seasonId, String name) {
        List<String> seasons = jdbc.query("SELECT status FROM seasons WHERE id=?", (rs, row) -> rs.getString(1), seasonId);
        if (seasons.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found");
        if (!"ACTIVE".equals(seasons.getFirst())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tournament requires an active season");
        String title = name.trim();
        String slug = slugify(title) + "-" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                INSERT INTO tournaments(season_id, title, name, slug, format, max_teams, team_size, bracket_size, best_of, status)
                VALUES (?, ?, ?, ?, 'SINGLE_ELIMINATION', 32, 1, 32, 3, 'DRAFT')
                RETURNING id, season_id, name, status
                """, (rs, row) -> new TournamentSummary(rs.getLong("id"), rs.getLong("season_id"),
                rs.getString("name"), rs.getString("status")), seasonId, title, title, slug);
    }

    public List<TournamentSummary> list() {
        return jdbc.query("SELECT id, season_id, name, status FROM tournaments ORDER BY id DESC LIMIT 100",
                (rs, row) -> new TournamentSummary(rs.getLong("id"), (Long) rs.getObject("season_id"),
                        rs.getString("name"), rs.getString("status")));
    }

    public TournamentView seed(long tournamentId) {
        TournamentView seeded = transaction.execute(status -> {
            List<Long> seasons = jdbc.query("SELECT season_id FROM tournaments WHERE id=? AND status='DRAFT' FOR UPDATE",
                    (rs, row) -> rs.getLong(1), tournamentId);
            if (seasons.isEmpty() || seasons.getFirst() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Tournament is not in DRAFT state or has no season");
            }
            long seasonId = seasons.getFirst();
            int frozen = jdbc.update("UPDATE seasons SET status='FROZEN' WHERE id=? AND status='ACTIVE'", seasonId);
            if (frozen != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Season is already frozen");
            List<BracketBuilder.Entry> entries = jdbc.query("""
                    SELECT ranked.bot_id, ranked.submission_id,
                           row_number() OVER (ORDER BY ranked.elo DESC, ranked.wins DESC, ranked.bot_id) AS seed_no
                    FROM (
                      SELECT r.bot_id, r.elo, r.wins, s.id AS submission_id
                      FROM bot_ratings r JOIN bots b ON b.id=r.bot_id
                      JOIN LATERAL (
                        SELECT id FROM submissions WHERE bot_id=b.id AND status IN ('COMPILED','ACTIVE')
                          AND artifact_key IS NOT NULL ORDER BY version_no DESC LIMIT 1
                      ) s ON TRUE
                      WHERE r.season_id=? AND b.ladder_enabled=TRUE
                    ) ranked
                    ORDER BY seed_no LIMIT 32
                    """, (rs, row) -> new BracketBuilder.Entry(rs.getInt("seed_no"), rs.getLong("bot_id"),
                    rs.getLong("submission_id")), seasonId);
            if (entries.size() != 32) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A tournament needs 32 rated bots with compiled submissions; found " + entries.size());

            for (BracketBuilder.Entry entry : entries) {
                Integer elo = jdbc.queryForObject("SELECT elo FROM bot_ratings WHERE season_id=? AND bot_id=?",
                        Integer.class, seasonId, entry.botId());
                jdbc.update("""
                        INSERT INTO tournament_entries(tournament_id, bot_id, submission_id, seed_no, elo_snapshot)
                        VALUES (?, ?, ?, ?, ?)
                        """, tournamentId, entry.botId(), entry.submissionId(), entry.seedNo(), elo);
            }

            BracketBuilder.Bracket bracket = builder.build(entries);
            Map<String, Long> ids = new HashMap<>();
            for (BracketBuilder.Node node : bracket.nodes()) {
                Long id = jdbc.queryForObject("""
                        INSERT INTO bracket_nodes(tournament_id, round_no, slot_no, bot_a_id, bot_b_id,
                                                  next_side, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING') RETURNING id
                        """, Long.class, tournamentId, node.roundNo(), node.slotNo(), node.botAId(), node.botBId(),
                        node.nextSide() == null ? null : node.nextSide().toString());
                ids.put(key(node.roundNo(), node.slotNo()), id);
            }
            for (BracketBuilder.Node node : bracket.nodes()) {
                if (node.nextRoundNo() == null) continue;
                long childId = ids.get(key(node.roundNo(), node.slotNo()));
                long nextNodeId = ids.get(key(node.nextRoundNo(), node.nextSlotNo()));
                jdbc.update("UPDATE bracket_nodes SET next_node_id=? WHERE id=?", nextNodeId, childId);
            }
            jdbc.update("UPDATE tournaments SET status='RUNNING', seeded_at=now() WHERE id=?", tournamentId);
            for (int slot = 1; slot <= 16; slot++) publisher.startNode(ids.get(key(1, slot)));
            return get(tournamentId);
        });
        broadcaster.publishBracketChanged(tournamentId);
        return seeded;
    }

    public TournamentView get(long tournamentId) {
        TournamentSummary summary;
        try {
            summary = jdbc.queryForObject("SELECT id, season_id, name, status FROM tournaments WHERE id=?",
                    (rs, row) -> new TournamentSummary(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4)),
                    tournamentId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament not found");
        }
        List<BracketMatchup> nodes = jdbc.query("""
                SELECT n.id, n.round_no, n.slot_no, n.status, n.winner_bot_id,
                       ba.name AS bot_a_name, bb.name AS bot_b_name, bw.name AS winner_name
                FROM bracket_nodes n LEFT JOIN bots ba ON ba.id=n.bot_a_id
                LEFT JOIN bots bb ON bb.id=n.bot_b_id LEFT JOIN bots bw ON bw.id=n.winner_bot_id
                WHERE n.tournament_id=? ORDER BY n.round_no, n.slot_no
                """, (rs, row) -> new BracketMatchup(rs.getLong("id"), rs.getInt("round_no"), rs.getInt("slot_no"),
                rs.getString("status"), rs.getString("bot_a_name"), rs.getString("bot_b_name"),
                (Long) rs.getObject("winner_bot_id"), rs.getString("winner_name"), List.of()), tournamentId);
        List<BracketMatchup> withGames = nodes.stream().map(node -> {
            List<GameSummary> games = jdbc.query("""
                    SELECT bg.game_no, m.public_id, m.status, m.winner_side, m.end_reason
                    FROM bracket_games bg JOIN matches m ON m.id=bg.match_id
                    WHERE bg.node_id=? ORDER BY bg.game_no
                    """, (rs, row) -> new GameSummary(rs.getInt("game_no"), rs.getObject("public_id", UUID.class),
                    rs.getString("status"), rs.getString("winner_side"), rs.getString("end_reason")), node.nodeId());
            return new BracketMatchup(node.nodeId(), node.round(), node.slot(), node.status(), node.botA(),
                    node.botB(), node.winnerBotId(), node.winner(), games);
        }).toList();
        return new TournamentView(summary, withGames);
    }

    public void retryFailedMatch(long tournamentId, UUID publicId) {
        transaction.executeWithoutResult(status -> {
            List<RetryData> rows = jdbc.query("""
                    SELECT m.id, m.created_month, m.seed, m.map_config::text AS map_config,
                           a.bot_id AS a_bot_id, ba.name AS a_bot_name, ba.owner_id AS a_owner_id,
                           a.submission_id AS a_submission_id, sa.language AS a_language,
                           sa.artifact_key AS a_artifact_key, sa.source_sha256 AS a_sha,
                           b.bot_id AS b_bot_id, bb.name AS b_bot_name, bb.owner_id AS b_owner_id,
                           b.submission_id AS b_submission_id, sb.language AS b_language,
                           sb.artifact_key AS b_artifact_key, sb.source_sha256 AS b_sha
                    FROM matches m JOIN bracket_games bg ON bg.match_id=m.id
                    JOIN bracket_nodes n ON n.id=bg.node_id AND n.tournament_id=?
                    JOIN match_participants a ON a.match_id=m.id AND a.created_month=m.created_month AND a.side='A'
                    JOIN bots ba ON ba.id=a.bot_id JOIN submissions sa ON sa.id=a.submission_id
                    JOIN match_participants b ON b.match_id=m.id AND b.created_month=m.created_month AND b.side='B'
                    JOIN bots bb ON bb.id=b.bot_id JOIN submissions sb ON sb.id=b.submission_id
                    WHERE m.public_id=? AND m.match_type='TOURNAMENT' AND m.status='ERROR'
                    FOR UPDATE OF m
                    """, (rs, row) -> new RetryData(rs.getLong("id"), rs.getDate("created_month").toLocalDate(),
                    rs.getLong("seed"), rs.getString("map_config"),
                    new MatchJob.ParticipantJob(rs.getLong("a_bot_id"), rs.getString("a_bot_name"), rs.getLong("a_owner_id"),
                            rs.getLong("a_submission_id"), rs.getString("a_language"), rs.getString("a_artifact_key"), rs.getString("a_sha").trim()),
                    new MatchJob.ParticipantJob(rs.getLong("b_bot_id"), rs.getString("b_bot_name"), rs.getLong("b_owner_id"),
                            rs.getLong("b_submission_id"), rs.getString("b_language"), rs.getString("b_artifact_key"), rs.getString("b_sha").trim())),
                    tournamentId, publicId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Failed tournament match not found");
            RetryData retry = rows.getFirst();
            jdbc.update("UPDATE matches SET status='QUEUED', failure_detail=NULL, finished_at=NULL WHERE id=? AND created_month=? AND status='ERROR'",
                    retry.matchId(), java.sql.Date.valueOf(retry.month()));
            jdbc.update("UPDATE match_participants SET fault=NULL WHERE match_id=? AND created_month=?",
                    retry.matchId(), java.sql.Date.valueOf(retry.month()));
            MatchJob job = new MatchJob("MATCH_JOB", retry.matchId(), publicId.toString(), retry.month().toString(),
                    retry.seed(), readMap(retry.mapConfig()), retry.a(), retry.b());
            String payload;
            try { payload = mapper.writeValueAsString(job); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Could not encode tournament retry", e); }
            jdbc.update("INSERT INTO outbox_events(topic, payload) VALUES ('match.tournament.q', ?::jsonb)", payload);
        });
    }

    public List<UserTournamentParticipation> getUserTournaments(long userId) {
        return jdbc.query("""
                SELECT t.id AS tournament_id, t.title AS tournament_title, t.format AS tournament_format, t.status AS tournament_status,
                       tt.id AS team_id, tt.name AS team_name, tt.status AS team_status, tt.score, tt.buchholz_score, tt.seed,
                       b.id AS bot_id, b.name AS bot_name, tt.version_no
                FROM tournament_teams tt
                JOIN tournaments t ON t.id = tt.tournament_id
                LEFT JOIN bots b ON b.id = tt.bot_id
                WHERE tt.captain_id = ?
                   OR EXISTS (SELECT 1 FROM tournament_members tm WHERE tm.team_id = tt.id AND tm.user_id = ?)
                ORDER BY t.created_at DESC
                """, (rs, row) -> new UserTournamentParticipation(
                rs.getLong("tournament_id"),
                rs.getString("tournament_title"),
                rs.getString("tournament_format"),
                rs.getString("tournament_status"),
                rs.getLong("team_id"),
                rs.getString("team_name"),
                rs.getString("team_status"),
                rs.getDouble("score"),
                rs.getDouble("buchholz_score"),
                (Integer) rs.getObject("seed"),
                (Long) rs.getObject("bot_id"),
                rs.getString("bot_name"),
                (Integer) rs.getObject("version_no")
        ), userId, userId);
    }

    private MatchJob.MapConfiguration readMap(String json) {
        try { return mapper.readValue(json, MatchJob.MapConfiguration.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored match map configuration is invalid", e); }
    }

    private static String key(int round, int slot) { return round + ":" + slot; }

    public record TournamentSummary(long id, Long seasonId, String name, String status) { }
    public record TournamentView(TournamentSummary tournament, List<BracketMatchup> bracket) { }
    public record BracketMatchup(long nodeId, int round, int slot, String status, String botA,
                                 String botB, Long winnerBotId, String winner, List<GameSummary> games) { }
    public record GameSummary(int gameNo, UUID matchId, String status, String winnerSide, String endReason) { }
    private record RetryData(long matchId, java.time.LocalDate month, long seed, String mapConfig,
                             MatchJob.ParticipantJob a, MatchJob.ParticipantJob b) { }
    record TournamentStateRow(long id, String status, int maxTeams, int teamSize) { }
    private record TeamStateRow(long id, long tournamentId, String name, long captainId, long botId, long submissionId, String status) { }
    private record BotSubRow(long botId, String botName, long ownerId, long subId, String sourceSha256, int versionNo, String subStatus, String artifactKey) { }
}
