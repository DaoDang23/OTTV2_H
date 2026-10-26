package ottv2.api.tournament;

import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Advances a bracket only after a best-of-three node has a resolved winner. */
@Component
public class TournamentResultService {
    private final JdbcTemplate jdbc;
    private final TournamentMatchPublisher publisher;
    private final TournamentBroadcaster broadcaster;
    private final TournamentExecutionService executionService;

    public TournamentResultService(JdbcTemplate jdbc, TournamentMatchPublisher publisher,
                                   TournamentBroadcaster broadcaster,
                                   TournamentExecutionService executionService) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.broadcaster = broadcaster;
        this.executionService = executionService;
    }

    public void recordFinishedGame(long matchId, Date month) {
        // 1. Process V9 Tournament Matches (Single Elimination, Round Robin, Swiss)
        List<MatchOutcomeRow> outcomes = jdbc.query(
                "SELECT winner_side, end_reason FROM matches WHERE id=? AND created_month=?",
                (rs, row) -> new MatchOutcomeRow(rs.getString("winner_side"), rs.getString("end_reason")),
                matchId, month);
        String winnerSide = outcomes.isEmpty() ? null : outcomes.getFirst().winnerSide();
        String endReason = outcomes.isEmpty() ? null : outcomes.getFirst().endReason();
        executionService.onMatchFinished(matchId, month.toLocalDate(), winnerSide, endReason);

        // 2. Legacy bracket_nodes processing for backward compatibility
        List<NodeReference> references = jdbc.query("""
                SELECT n.id, n.tournament_id, n.next_node_id, n.next_side, n.bot_a_id, n.bot_b_id, m.seed
                FROM bracket_games bg JOIN bracket_nodes n ON n.id=bg.node_id
                JOIN matches m ON m.id=bg.match_id
                WHERE bg.match_id=? AND m.created_month=? AND m.match_type='TOURNAMENT'
                """, (rs, row) -> new NodeReference(rs.getLong("id"), rs.getLong("tournament_id"),
                (Long) rs.getObject("next_node_id"), rs.getString("next_side"),
                rs.getLong("bot_a_id"), rs.getLong("bot_b_id"), rs.getLong("seed")), matchId, month);
        if (references.isEmpty()) return;
        NodeReference node = references.getFirst();
        List<String> states = jdbc.query("SELECT status FROM bracket_nodes WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), node.id());
        if (states.isEmpty() || !"RUNNING".equals(states.getFirst())) return;
        List<GameResult> results = jdbc.query("""
                SELECT bg.game_no, m.winner_side, winner.bot_id
                FROM bracket_games bg JOIN matches m ON m.id=bg.match_id
                LEFT JOIN match_participants winner ON winner.match_id=m.id AND winner.created_month=m.created_month
                    AND winner.side=m.winner_side
                WHERE bg.node_id=? AND m.status='FINISHED' ORDER BY bg.game_no
                """, (rs, row) -> new GameResult(rs.getInt("game_no"), rs.getString("winner_side"),
                (Long) rs.getObject("bot_id")), node.id());
        if (results.size() < 2) return;
        long winsA = results.stream().filter(result -> result.winnerBotId() != null
                && result.winnerBotId() == node.botA()).count();
        long winsB = results.stream().filter(result -> result.winnerBotId() != null
                && result.winnerBotId() == node.botB()).count();
        if (results.size() == 2 && winsA < 2 && winsB < 2) {
            publisher.startDecider(node.id(), node.seed());
            broadcaster.publishBracketChanged(node.tournamentId());
            return;
        }
        if (results.size() < 3 && winsA < 2 && winsB < 2) return;
        long winner;
        if (winsA == winsB) winner = higherSeed(node.tournamentId(), node.botA(), node.botB());
        else winner = winsA > winsB ? node.botA() : node.botB();
        finishNode(node, winner);
        broadcaster.publishBracketChanged(node.tournamentId());
    }

    private long higherSeed(long tournamentId, long botA, long botB) {
        return jdbc.queryForObject("""
                SELECT bot_id FROM tournament_entries WHERE tournament_id=? AND bot_id IN (?, ?)
                ORDER BY seed_no LIMIT 1
                """, Long.class, tournamentId, botA, botB);
    }

    private void finishNode(NodeReference node, long winner) {
        jdbc.update("UPDATE bracket_nodes SET winner_bot_id=?, status='FINISHED' WHERE id=? AND status='RUNNING'",
                winner, node.id());
        if (node.nextNodeId() == null) {
            jdbc.update("UPDATE tournaments SET status='FINISHED' WHERE id=?", node.tournamentId());
            return;
        }
        if ("A".equals(node.nextSide())) {
            jdbc.update("UPDATE bracket_nodes SET bot_a_id=? WHERE id=? AND status='PENDING'", winner, node.nextNodeId());
        } else {
            jdbc.update("UPDATE bracket_nodes SET bot_b_id=? WHERE id=? AND status='PENDING'", winner, node.nextNodeId());
        }
        List<Boolean> ready = jdbc.query("""
                SELECT bot_a_id IS NOT NULL AND bot_b_id IS NOT NULL AND status='PENDING'
                FROM bracket_nodes WHERE id=?
                """, (rs, row) -> rs.getBoolean(1), node.nextNodeId());
        if (!ready.isEmpty() && ready.getFirst()) publisher.startNode(node.nextNodeId());
    }

    public void recordFailedGame(long matchId, Date month, String error) {
        executionService.onMatchFailed(matchId, month.toLocalDate(), error);
    }

    private record NodeReference(long id, long tournamentId, Long nextNodeId, String nextSide,
                                 long botA, long botB, long seed) { }
    private record GameResult(int gameNo, String winnerSide, Long winnerBotId) { }
    private record MatchOutcomeRow(String winnerSide, String endReason) { }
}
