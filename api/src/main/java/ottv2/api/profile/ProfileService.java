package ottv2.api.profile;

import java.util.List;
import ottv2.api.auth.AuthDtos.UserResponse;
import ottv2.api.auth.AuthService;
import ottv2.api.profile.ProfileDtos.EloHistoryItem;
import ottv2.api.profile.ProfileDtos.ProfileResponse;
import ottv2.api.profile.ProfileDtos.RecordMatchRequest;
import ottv2.api.profile.ProfileDtos.UpdateProfileRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    private final JdbcTemplate jdbc;
    private final AuthService authService;
    private final ottv2.api.ladder.RatingCalculator calculator;

    public ProfileService(JdbcTemplate jdbc, AuthService authService) {
        this(jdbc, authService, new ottv2.api.ladder.RatingCalculator.Elo(32));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProfileService(JdbcTemplate jdbc, AuthService authService, ottv2.api.ladder.RatingCalculator calculator) {
        this.jdbc = jdbc;
        this.authService = authService;
        this.calculator = calculator;
    }

    public record MatchStats(
            int matchesCount,
            int wins,
            int losses,
            int pvpMatchesCount,
            int pvpWins,
            int pvpLosses,
            int botMatchesCount,
            int botWins,
            int botLosses,
            int distinctOpponents,
            int pvpDistinctOpponents
    ) {
        public MatchStats(
                int matchesCount,
                int wins,
                int losses,
                int pvpMatchesCount,
                int pvpWins,
                int pvpLosses,
                int botMatchesCount,
                int botWins,
                int botLosses,
                int distinctOpponents
        ) {
            this(matchesCount, wins, losses, pvpMatchesCount, pvpWins, pvpLosses,
                    botMatchesCount, botWins, botLosses, distinctOpponents, 0);
        }

        public MatchStats(
                int matchesCount,
                int wins,
                int losses,
                int pvpMatchesCount,
                int pvpWins,
                int pvpLosses,
                int botMatchesCount,
                int botWins,
                int botLosses
        ) {
            this(matchesCount, wins, losses, pvpMatchesCount, pvpWins, pvpLosses, botMatchesCount, botWins, botLosses, 0, 0);
        }
    }

    @jakarta.annotation.PostConstruct
    public void ensureReplayIdColumn() {
        try {
            jdbc.execute("ALTER TABLE user_matches ADD COLUMN IF NOT EXISTS replay_id VARCHAR(120)");
            jdbc.execute("ALTER TABLE user_matches ADD COLUMN IF NOT EXISTS replay_data JSONB");
        } catch (Exception ignored) { }
    }

    public ProfileResponse getProfile(long userId) {
        UserResponse user = authService.getUser(userId);

        Integer bots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bots WHERE owner_id = ? AND (deleted IS FALSE OR deleted IS NULL)",
                Integer.class, userId
        );
        int botsCount = bots == null ? 0 : bots;

        MatchStats stats = jdbc.queryForObject("""
                SELECT
                    COUNT(CASE WHEN is_ranked = TRUE THEN 1 END) AS matches_count,
                    COUNT(CASE WHEN is_ranked = TRUE AND user_won = true THEN 1 END) AS wins_count,
                    COUNT(CASE WHEN is_ranked = TRUE AND user_won = false AND winner <> 'DRAW' THEN 1 END) AS losses_count,
                    COUNT(CASE WHEN is_ranked = TRUE AND UPPER(mode) = 'PVP' THEN 1 END) AS pvp_matches,
                    COUNT(CASE WHEN is_ranked = TRUE AND UPPER(mode) = 'PVP' AND user_won = true THEN 1 END) AS pvp_wins,
                    COUNT(CASE WHEN is_ranked = TRUE AND UPPER(mode) = 'PVP' AND user_won = false AND winner <> 'DRAW' THEN 1 END) AS pvp_losses,
                    COUNT(CASE WHEN is_ranked = TRUE AND UPPER(mode) IN ('BOT', 'LADDER') THEN 1 END) AS bot_matches,
                    COUNT(CASE WHEN is_ranked = TRUE AND UPPER(mode) IN ('BOT', 'LADDER') AND user_won = true THEN 1 END) AS bot_wins,
                    COUNT(CASE WHEN is_ranked = TRUE AND UPPER(mode) IN ('BOT', 'LADDER') AND user_won = false AND winner <> 'DRAW' THEN 1 END) AS bot_losses,
                    COUNT(DISTINCT CASE WHEN is_ranked = TRUE THEN opponent END) AS distinct_opponents,
                    COUNT(DISTINCT CASE WHEN is_ranked = TRUE AND UPPER(mode) = 'PVP' THEN opponent END) AS pvp_distinct_opponents
                FROM user_matches
                WHERE user_id = ?
                """, (rs, rowNum) -> new MatchStats(
                rs.getInt("matches_count"),
                rs.getInt("wins_count"),
                rs.getInt("losses_count"),
                rs.getInt("pvp_matches"),
                rs.getInt("pvp_wins"),
                rs.getInt("pvp_losses"),
                rs.getInt("bot_matches"),
                rs.getInt("bot_wins"),
                rs.getInt("bot_losses"),
                rs.getInt("distinct_opponents"),
                rs.getInt("pvp_distinct_opponents")
        ), userId);

        if (stats == null) {
            stats = new MatchStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        int matchesCount = stats.matchesCount();
        int wins = stats.wins();
        int losses = stats.losses();
        int winRate = matchesCount > 0 ? (int) Math.round((double) wins / matchesCount * 100) : 0;
        int distinctOpponents = stats.distinctOpponents();
        boolean isProvisional = stats.pvpMatchesCount() < 10 && stats.pvpDistinctOpponents() < 5;

        List<EloHistoryItem> eloHistory = jdbc.query("""
                SELECT um.id, um.opponent,
                       CASE WHEN UPPER(um.mode) IN ('BOT', 'LADDER') THEN (
                           SELECT opponent_participant.elo_before
                           FROM matches ladder_match
                           JOIN match_participants own_participant
                             ON own_participant.match_id=ladder_match.id
                            AND own_participant.created_month=ladder_match.created_month
                           JOIN bots own_bot ON own_bot.id=own_participant.bot_id
                           JOIN match_participants opponent_participant
                             ON opponent_participant.match_id=own_participant.match_id
                            AND opponent_participant.created_month=own_participant.created_month
                            AND opponent_participant.side <> own_participant.side
                           WHERE ladder_match.public_id::text=um.replay_id
                             AND own_bot.owner_id=um.user_id
                           ORDER BY ladder_match.created_month DESC, ladder_match.id DESC
                           LIMIT 1
                       ) ELSE um.opponent_elo END AS opponent_elo,
                       um.mode, um.user_won, um.elo_change, um.new_elo,
                       to_char(um.created_at, 'DD/MM/YYYY HH24:MI:SS') AS timestamp_str,
                       to_char(um.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS created_at_iso,
                       COALESCE(um.replay_id, um.id::text) AS replay_id,
                       COALESCE(um.is_ranked, false) AS is_ranked,
                       COALESCE(um.winner, '') AS winner
                FROM user_matches um
                WHERE um.user_id = ?
                ORDER BY um.created_at DESC, um.id DESC
                LIMIT 50
                """, (rs, rowNum) -> new EloHistoryItem(
                rs.getLong("id"),
                rs.getString("opponent"),
                rs.getInt("opponent_elo"),
                rs.getString("mode"),
                rs.getBoolean("user_won"),
                rs.getInt("elo_change"),
                rs.getInt("new_elo"),
                rs.getString("timestamp_str"),
                rs.getString("replay_id"),
                rs.getBoolean("is_ranked"),
                rs.getString("winner"),
                rs.getString("created_at_iso")
        ), userId);

        return new ProfileResponse(
                user,
                user.pvpElo(),
                user.botElo(),
                matchesCount,
                wins,
                losses,
                winRate,
                botsCount,
                stats.pvpMatchesCount(),
                stats.pvpWins(),
                stats.pvpLosses(),
                stats.botMatchesCount(),
                stats.botWins(),
                stats.botLosses(),
                eloHistory,
                distinctOpponents,
                isProvisional,
                stats.pvpDistinctOpponents()
        );
    }

    @Transactional
    public ProfileResponse recordMatch(long userId, RecordMatchRequest request) {
        UserResponse user = authService.getUser(userId);

        // Row-level lock on users to prevent race conditions during concurrent match completions
        Integer lockedPvpElo = null;
        try {
            lockedPvpElo = jdbc.queryForObject(
                    "SELECT pvp_elo FROM users WHERE id = ? FOR UPDATE", Integer.class, userId);
        } catch (Exception ignored) {
        }
        int currentPvpElo = lockedPvpElo != null ? lockedPvpElo : user.pvpElo();
        int currentBotElo = user.botElo();

        String rawMode = request.mode() != null ? request.mode().toUpperCase() : "FRIENDLY";
        boolean isQuickMatch = "PVP_QUICK".equalsIgnoreCase(rawMode);
        if (isQuickMatch) {
            rawMode = "PVP";
        }
        String oppType = request.opponentType() != null ? request.opponentType().toUpperCase() : "";
        String oppName = request.opponent() != null ? request.opponent() : "Unknown";
        String lowerOppName = oppName.toLowerCase();

        // BẮT BUỘC: Phát hiện triệt để Sparring Machine (Máy 1-12), Bot dự phòng hoặc các biến thể AI
        boolean isSparringMachine = "SPARRING".equalsIgnoreCase(oppType)
                || "SPARRING_MACHINE".equalsIgnoreCase(oppType)
                || "MACHINE".equalsIgnoreCase(oppType)
                || "BOT".equalsIgnoreCase(oppType)
                || lowerOppName.startsWith("máy ")
                || lowerOppName.startsWith("sys_machine_")
                || lowerOppName.contains("sparring")
                || lowerOppName.contains("tân binh")
                || lowerOppName.contains("tập sự")
                || lowerOppName.contains("cảm tử")
                || lowerOppName.contains("hộ vệ")
                || lowerOppName.contains("tốc biến")
                || lowerOppName.contains("cẩn trọng")
                || lowerOppName.contains("bầy đàn")
                || lowerOppName.contains("chiến thuật")
                || lowerOppName.contains("bậc thầy")
                || lowerOppName.contains("kiện tướng")
                || lowerOppName.contains("đại kiện tướng")
                || lowerOppName.contains("huyền thoại")
                || oppName.matches("(?i).*máy\\s*(1[0-2]|[1-9]).*")
                || ottv2.api.room.MatchmakingController.SPARRING_MACHINES.stream()
                        .anyMatch(m -> lowerOppName.contains(m.name().toLowerCase()) || lowerOppName.contains("máy " + m.level()));

        // Detect PRACTICE: Sparring AI, test bots, system bots, or mode PRACTICE
        boolean isPractice = "PRACTICE".equalsIgnoreCase(rawMode)
                || isSparringMachine
                || oppName.startsWith("SYSTEM-");

        // Detect FRIENDLY: manual friendly challenge or mode FRIENDLY (chỉ khi không phải PVP_QUICK)
        boolean isFriendly = !isQuickMatch && ("FRIENDLY".equalsIgnoreCase(rawMode) || "FRIENDLY".equalsIgnoreCase(oppType));

        // Client-reported BOT matches are always PRACTICE or FRIENDLY because official BOT matches run through LadderScheduler
        if ("BOT".equalsIgnoreCase(rawMode)) {
            isPractice = true;
        }

        // BẢO MẬT TUYỆT ĐỐI (ZERO-TRUST CLIENT):
        // Mọi trận do Client tự báo cáo qua endpoint /api/profile/matches đều bị ép là trận luyện tập (PRACTICE).
        // TUYỆT ĐỐI KHÔNG cho phép Client tự khai báo để cộng/trừ ELO qua endpoint này!
        // Trận Ranked PvP chính thức chỉ được cập nhật ELO có thẩm quyền qua server endpoint:
        // POST /api/matches/{publicId}/finish
        boolean isRanked = false;
        int finalChange = 0;
        int finalNewElo = "BOT".equalsIgnoreCase(rawMode) ? currentBotElo : currentPvpElo;
        String matchType = isSparringMachine || isPractice ? "PRACTICE" : "PRACTICE";

        String replayId = request.replayId() != null && !request.replayId().isBlank()
                ? request.replayId().trim()
                : "pvp-" + userId + "-" + System.currentTimeMillis();

        String replayJson = null;
        if (request.replayData() != null) {
            try {
                if (request.replayData() instanceof String s) {
                    replayJson = s;
                } else {
                    replayJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.replayData());
                }
            } catch (Exception ignored) { }
        }

        if (replayJson != null) {
            jdbc.update("""
                    INSERT INTO user_matches (user_id, mode, opponent, opponent_elo, opponent_type,
                                             user_won, winner, reason, elo_change, new_elo, is_ranked, match_type, replay_id, replay_data)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    userId, rawMode, oppName, request.opponentElo(),
                    request.opponentType(), request.userWon(), request.winner(), request.reason(),
                    finalChange, finalNewElo, isRanked, matchType, replayId, replayJson
            );
        } else {
            jdbc.update("""
                    INSERT INTO user_matches (user_id, mode, opponent, opponent_elo, opponent_type,
                                             user_won, winner, reason, elo_change, new_elo, is_ranked, match_type, replay_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    userId, rawMode, oppName, request.opponentElo(),
                    request.opponentType(), request.userWon(), request.winner(), request.reason(),
                    finalChange, finalNewElo, isRanked, matchType, replayId
            );
        }

        return getProfile(userId);
    }

    @Transactional
    public ProfileResponse updateProfile(long userId, UpdateProfileRequest request) {
        if (request != null) {
            if (request.studentCode() != null && !request.studentCode().isBlank()) {
                jdbc.update("UPDATE users SET student_code = ? WHERE id = ?", request.studentCode().trim(), userId);
            }
            if (request.displayName() != null && !request.displayName().isBlank()) {
                jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", request.displayName().trim(), userId);
            }
        }
        return getProfile(userId);
    }
}
