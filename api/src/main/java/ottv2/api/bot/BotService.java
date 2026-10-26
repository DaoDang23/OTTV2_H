package ottv2.api.bot;

import java.util.List;

import ottv2.api.bot.BotDtos.BotResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BotService {
    private final JdbcTemplate jdbc;

    public BotService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public BotResponse create(long ownerId, String name) {
        BotResponse bot = jdbc.queryForObject("""
                        INSERT INTO bots(owner_id, name) VALUES (?, ?) RETURNING id, name, ladder_enabled
                        """,
                (rs, row) -> new BotResponse(rs.getLong("id"), rs.getString("name"), rs.getBoolean("ladder_enabled")),
                ownerId, name.trim());

        long seasonId = getActiveSeasonId();
        try {
            jdbc.update("""
                    INSERT INTO bot_ratings(bot_id, season_id, elo, games, wins, losses, draws)
                    VALUES (?, ?, 1200, 0, 0, 0, 0)
                    ON CONFLICT (bot_id, season_id) DO NOTHING
                    """, bot.id(), seasonId);
        } catch (Exception ignored) {}

        return new BotResponse(bot.id(), bot.name(), bot.ladderEnabled(), 1200, 0, 0, 0, 0, 0.0, true);
    }

    public List<BotResponse> listForOwner(long ownerId) {
        return jdbc.query("""
                SELECT b.id, b.name, b.ladder_enabled,
                       COALESCE(r.elo, 1200) AS elo,
                       COALESCE(r.games, 0) AS games,
                       COALESCE(r.wins, 0) AS wins,
                       COALESCE(r.losses, 0) AS losses,
                       COALESCE(r.draws, 0) AS draws
                FROM bots b
                LEFT JOIN (
                    SELECT DISTINCT ON (bot_id) bot_id, elo, games, wins, losses, draws
                    FROM bot_ratings
                    ORDER BY bot_id, updated_at DESC
                ) r ON r.bot_id = b.id
                WHERE b.owner_id = ? AND (b.deleted IS FALSE OR b.deleted IS NULL)
                ORDER BY b.id
                """, (rs, row) -> {
            int games = rs.getInt("games");
            int wins = rs.getInt("wins");
            int losses = rs.getInt("losses");
            int draws = rs.getInt("draws");
            double winRate = games > 0 ? Math.round(((double) wins / games) * 1000.0) / 10.0 : 0.0;
            boolean provisional = games < 10;
            return new BotResponse(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getBoolean("ladder_enabled"),
                    rs.getInt("elo"),
                    games,
                    wins,
                    losses,
                    draws,
                    winRate,
                    provisional
            );
        }, ownerId);
    }

    @Transactional
    public BotResponse setLadderActive(long ownerId, long botId, Boolean requestedActive) {
        List<Long> botList = jdbc.query("SELECT id FROM bots WHERE id = ? AND owner_id = ? AND (deleted IS FALSE OR deleted IS NULL)",
                (rs, row) -> rs.getLong(1), botId, ownerId);
        if (botList.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Bot not found");
        }

        boolean currentActive = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT ladder_enabled FROM bots WHERE id = ?", Boolean.class, botId));
        boolean targetActive = requestedActive != null ? requestedActive : !currentActive;

        if (targetActive) {
            // Ensure bot has an active submission
            Long activeSub = jdbc.queryForObject("SELECT active_submission_id FROM bots WHERE id = ?", Long.class, botId);
            if (activeSub == null) {
                List<Long> latest = jdbc.query("SELECT id FROM submissions WHERE bot_id = ? ORDER BY version_no DESC LIMIT 1",
                        (rs, row) -> rs.getLong(1), botId);
                if (!latest.isEmpty()) {
                    jdbc.update("UPDATE bots SET active_submission_id = ? WHERE id = ?", latest.getFirst(), botId);
                }
            }
            // Ensure bot has rating initialized
            long seasonId = getActiveSeasonId();
            jdbc.update("""
                    INSERT INTO bot_ratings(bot_id, season_id, elo, games, wins, losses, draws)
                    VALUES (?, ?, 1200, 0, 0, 0, 0)
                    ON CONFLICT (bot_id, season_id) DO NOTHING
                    """, botId, seasonId);

            // Exclusive active: disable other bots for this owner, enable this bot
            jdbc.update("UPDATE bots SET ladder_enabled = FALSE WHERE owner_id = ? AND id <> ?", ownerId, botId);
            jdbc.update("UPDATE bots SET ladder_enabled = TRUE WHERE id = ?", botId);
        } else {
            jdbc.update("UPDATE bots SET ladder_enabled = FALSE WHERE id = ?", botId);
        }

        List<BotResponse> updated = listForOwner(ownerId).stream()
                .filter(b -> b.id() == botId).toList();
        return updated.isEmpty() ? new BotResponse(botId, "", targetActive) : updated.getFirst();
    }

    public long getActiveSeasonId() {
        try {
            Long id = jdbc.queryForObject("SELECT id FROM seasons WHERE status='ACTIVE' AND starts_at<=now() AND ends_at>now() ORDER BY starts_at LIMIT 1",
                    Long.class);
            if (id != null) return id;
        } catch (Exception ignored) {}
        try {
            Long id = jdbc.queryForObject("SELECT id FROM seasons WHERE status='ACTIVE' ORDER BY starts_at DESC LIMIT 1", Long.class);
            if (id != null) return id;
        } catch (Exception ignored) {}
        try {
            Long id = jdbc.queryForObject("SELECT id FROM seasons ORDER BY id DESC LIMIT 1", Long.class);
            if (id != null) return id;
        } catch (Exception ignored) {}
        return 1L;
    }

    @Transactional
    public boolean delete(long ownerId, long botId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bots WHERE id=? AND owner_id=?",
                Integer.class, botId, ownerId
        );
        if (count == null || count == 0) {
            return false;
        }

        // 1. Luôn gỡ tham chiếu active_submission_id, tắt ladder và đánh dấu deleted = TRUE
        jdbc.update("UPDATE bots SET active_submission_id = NULL, ladder_enabled = FALSE, deleted = TRUE WHERE id=? AND owner_id=?", botId, ownerId);

        // 2. Dọn bot_ratings
        try {
            jdbc.update("DELETE FROM bot_ratings WHERE bot_id=?", botId);
        } catch (Exception ignored) {}

        // 3. Kiểm tra xem bot có lịch sử đấu (match_participants hoặc ladder_series) không
        Integer matchCount = 0;
        try {
            matchCount = jdbc.queryForObject("SELECT COUNT(*) FROM match_participants WHERE bot_id=?", Integer.class, botId);
        } catch (Exception ignored) {}

        Integer ladderCount = 0;
        try {
            ladderCount = jdbc.queryForObject("SELECT COUNT(*) FROM ladder_series WHERE bot_a_id=? OR bot_b_id=?", Integer.class, botId, botId);
        } catch (Exception ignored) {}

        if ((matchCount != null && matchCount > 0) || (ladderCount != null && ladderCount > 0)) {
            // Bot đã có lịch sử đấu: soft delete đã hoàn tất an toàn ở bước 1 mà không gây lỗi FK
            return true;
        }

        // 4. Nếu bot hoàn toàn chưa có trận đấu nào, có thể dọn dẹp vật lý submissions và bots
        try {
            jdbc.update("DELETE FROM submissions WHERE bot_id=?", botId);
            jdbc.update("DELETE FROM bots WHERE id=? AND owner_id=?", botId, ownerId);
        } catch (Exception ignored) {
            // Đã được soft delete ở bước 1
        }
        return true;
    }

    public BotDtos.BotDetailResponse getBotDetails(long botId) {
        List<BotDtos.BotDetailResponse> list = jdbc.query("""
                SELECT b.id, b.name, b.owner_id, COALESCE(u.display_name, u.student_code) AS owner_name,
                       s.language, s.version_no, s.source_sha256
                FROM bots b
                JOIN users u ON u.id = b.owner_id
                LEFT JOIN submissions s ON s.id = b.active_submission_id
                WHERE b.id = ? AND (b.deleted IS FALSE OR b.deleted IS NULL)
                """, (rs, row) -> {
            long id = rs.getLong("id");
            String name = rs.getString("name");
            long ownerId = rs.getLong("owner_id");
            String ownerName = rs.getString("owner_name");
            String language = rs.getString("language");
            int versionNo = rs.getInt("version_no");
            String sourceSha256 = rs.getString("source_sha256");

            // If active_submission_id wasn't set, try latest submission
            if (language == null) {
                List<String[]> latestSub = jdbc.query(
                        "SELECT language, version_no::text, source_sha256 FROM submissions WHERE bot_id=? ORDER BY version_no DESC LIMIT 1",
                        (rs2, r2) -> new String[]{rs2.getString(1), rs2.getString(2), rs2.getString(3)}, id);
                if (!latestSub.isEmpty()) {
                    language = latestSub.getFirst()[0];
                    try { versionNo = Integer.parseInt(latestSub.getFirst()[1]); } catch (Exception ignored) {}
                    sourceSha256 = latestSub.getFirst()[2];
                }
            }

            int elo = 1200;
            int games = 0;
            int wins = 0;
            int losses = 0;
            int draws = 0;

            List<int[]> ratingStats = jdbc.query(
                    "SELECT elo, games, wins, losses, draws FROM bot_ratings WHERE bot_id=? ORDER BY updated_at DESC LIMIT 1",
                    (rs3, r3) -> new int[]{rs3.getInt(1), rs3.getInt(2), rs3.getInt(3), rs3.getInt(4), rs3.getInt(5)}, id);
            if (!ratingStats.isEmpty()) {
                elo = ratingStats.getFirst()[0];
                games = ratingStats.getFirst()[1];
                wins = ratingStats.getFirst()[2];
                losses = ratingStats.getFirst()[3];
                draws = ratingStats.getFirst()[4];
            }

            int distinctOpponents = 0;
            try {
                Integer oppCount = jdbc.queryForObject("""
                        SELECT COALESCE(
                            NULLIF((SELECT COUNT(DISTINCT opp.bot_id)
                                    FROM match_participants self
                                    JOIN match_participants opp ON opp.match_id = self.match_id AND opp.side <> self.side
                                    WHERE self.bot_id = ?), 0),
                            (SELECT COUNT(DISTINCT CASE WHEN ls.bot_a_id = ? THEN ls.bot_b_id ELSE ls.bot_a_id END)
                             FROM ladder_series ls
                             WHERE (ls.bot_a_id = ? OR ls.bot_b_id = ?) AND ls.finished_at IS NOT NULL),
                            0
                        )
                        """, Integer.class, id, id, id, id);
                if (oppCount != null) distinctOpponents = oppCount;
            } catch (Exception ignored) {}

            boolean provisional = games < 10 && distinctOpponents < 5;
            double winRate = games > 0 ? Math.round(((double) wins / games) * 1000.0) / 10.0 : 0.0;

            // Fetch 10 recent matches
            List<BotDtos.BotMatchSummary> recentMatches = jdbc.query("""
                    SELECT m.public_id, m.match_type, m.status, m.winner_side, m.finished_at,
                           mp_self.side AS self_side,
                           mp_self.elo_before AS self_elo_before,
                           mp_self.elo_after AS self_elo_after,
                           mp_opp.elo_before AS opponent_elo,
                           b_opp.name AS opp_bot_name,
                           COALESCE(u_opp.display_name, u_opp.student_code) AS opp_owner_name
                    FROM match_participants mp_self
                    JOIN matches m ON m.id = mp_self.match_id AND m.created_month = mp_self.created_month
                    LEFT JOIN match_participants mp_opp ON mp_opp.match_id = m.id AND mp_opp.created_month = m.created_month AND mp_opp.side != mp_self.side
                    LEFT JOIN bots b_opp ON b_opp.id = mp_opp.bot_id
                    LEFT JOIN users u_opp ON u_opp.id = b_opp.owner_id
                    WHERE mp_self.bot_id = ?
                    ORDER BY m.id DESC LIMIT 10
                    """, (rsm, rm) -> {
                String selfSide = rsm.getString("self_side");
                String winnerSide = rsm.getString("winner_side");
                String status = rsm.getString("status");
                String result = "PENDING";
                if ("FINISHED".equalsIgnoreCase(status)) {
                    if (winnerSide == null) result = "DRAW";
                    else if (winnerSide.equalsIgnoreCase(selfSide)) result = "WIN";
                    else result = "LOSS";
                }

                java.sql.Timestamp ts = rsm.getTimestamp("finished_at");
                return new BotDtos.BotMatchSummary(
                        (java.util.UUID) rsm.getObject("public_id"),
                        rsm.getString("match_type"),
                        status,
                        result,
                        rsm.getString("opp_bot_name"),
                        rsm.getString("opp_owner_name"),
                        (Integer) rsm.getObject("opponent_elo"),
                        (Integer) rsm.getObject("self_elo_before"),
                        (Integer) rsm.getObject("self_elo_after"),
                        ts != null ? ts.toInstant() : null
                );
            }, id);

            return new BotDtos.BotDetailResponse(
                    id,
                    name,
                    ownerId,
                    ownerName,
                    elo,
                    language != null ? language : "PYTHON",
                    versionNo > 0 ? versionNo : 1,
                    sourceSha256 != null ? sourceSha256.trim() : "N/A",
                    games,
                    wins,
                    losses,
                    draws,
                    winRate,
                    distinctOpponents,
                    provisional,
                    recentMatches
            );
        }, botId);

        if (list.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Bot not found");
        }
        return list.getFirst();
    }
}
