package ottv2.api.tournament;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ottv2.api.match.MatchJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TournamentMatchPublisher {
    private static final MatchJob.MapConfiguration MAP = new MatchJob.MapConfiguration(8, 0, 0, 0);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public TournamentMatchPublisher(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void startNode(long nodeId) {
        Node node = node(nodeId);
        if (node.status().equals("RUNNING")) return;
        if (!node.status().equals("PENDING") || node.botA() == null || node.botB() == null) {
            throw new IllegalStateException("Bracket node is not ready to start");
        }
        Participant a = participant(node.tournamentId(), node.botA());
        Participant b = participant(node.tournamentId(), node.botB());
        long seed = random.nextLong();
        createGame(node, 1, seed, a, b);
        createGame(node, 2, seed, b, a);
        jdbc.update("UPDATE bracket_nodes SET status='RUNNING' WHERE id=? AND status='PENDING'", nodeId);
    }

    public void startDecider(long nodeId, long seed) {
        Node node = node(nodeId);
        Participant a = participant(node.tournamentId(), node.botA());
        Participant b = participant(node.tournamentId(), node.botB());
        if (random.nextBoolean()) createGame(node, 3, seed, a, b);
        else createGame(node, 3, seed, b, a);
    }

    private void createGame(Node node, int gameNo, long seed, Participant a, Participant b) {
        Long matchId = jdbc.queryForObject("""
                INSERT INTO matches(season_id, match_type, seed, map_config, engine_ver, status)
                VALUES (?, 'TOURNAMENT', ?, ?::jsonb, '1', 'QUEUED') RETURNING id
                """, Long.class, node.seasonId(), seed, writeJson(MAP));
        MatchKey key = jdbc.queryForObject("SELECT public_id, created_month FROM matches WHERE id=?",
                (rs, row) -> new MatchKey(rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate()), matchId);
        jdbc.update("""
                INSERT INTO match_participants(match_id, created_month, side, bot_id, submission_id, elo_before)
                VALUES (?, ?, 'A', ?, ?, (SELECT elo_snapshot FROM tournament_entries WHERE tournament_id=? AND bot_id=?)),
                       (?, ?, 'B', ?, ?, (SELECT elo_snapshot FROM tournament_entries WHERE tournament_id=? AND bot_id=?))
                """, matchId, Date.valueOf(key.month()), a.botId(), a.submissionId(), node.tournamentId(), a.botId(),
                matchId, Date.valueOf(key.month()), b.botId(), b.submissionId(), node.tournamentId(), b.botId());
        jdbc.update("INSERT INTO bracket_games(node_id, game_no, match_id) VALUES (?, ?, ?)", node.id(), gameNo, matchId);
        MatchJob job = new MatchJob("MATCH_JOB", matchId, key.publicId().toString(), key.month().toString(), seed,
                MAP, a.toJob(), b.toJob());
        jdbc.update("INSERT INTO outbox_events(topic, payload) VALUES ('match.tournament.q', ?::jsonb)", writeJson(job));
    }

    private Node node(long id) {
        return jdbc.queryForObject("""
                SELECT n.id, n.tournament_id, t.season_id, n.bot_a_id, n.bot_b_id, n.status
                FROM bracket_nodes n JOIN tournaments t ON t.id=n.tournament_id WHERE n.id=? FOR UPDATE OF n
                """, (rs, row) -> new Node(rs.getLong("id"), rs.getLong("tournament_id"), rs.getLong("season_id"),
                (Long) rs.getObject("bot_a_id"), (Long) rs.getObject("bot_b_id"), rs.getString("status")), id);
    }

    private Participant participant(long tournamentId, long botId) {
        return jdbc.queryForObject("""
                SELECT b.id, b.name, b.owner_id, e.submission_id, s.language, s.artifact_key, s.source_sha256
                FROM tournament_entries e JOIN bots b ON b.id=e.bot_id JOIN submissions s ON s.id=e.submission_id
                WHERE e.tournament_id=? AND e.bot_id=? AND s.status IN ('COMPILED','ACTIVE') AND s.artifact_key IS NOT NULL
                """, (rs, row) -> new Participant(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getString(7).trim()), tournamentId, botId);
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not encode tournament match job", e); }
    }

    private record Node(long id, long tournamentId, long seasonId, Long botA, Long botB, String status) { }
    private record MatchKey(UUID publicId, LocalDate month) { }
    private record Participant(long botId, String name, long ownerId, long submissionId,
                               String language, String artifactKey, String sha256) {
        MatchJob.ParticipantJob toJob() {
            return new MatchJob.ParticipantJob(botId, name, ownerId, submissionId, language, artifactKey, sha256);
        }
    }
}
