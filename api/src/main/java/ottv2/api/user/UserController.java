package ottv2.api.user;

import java.util.List;
import java.util.Map;
import ottv2.api.match.MatchDtos.MatchResponse;
import ottv2.api.match.MatchService;
import ottv2.api.security.ApiPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final JdbcTemplate jdbc;
    private final MatchService matchService;

    @Autowired
    public UserController(JdbcTemplate jdbc, @Autowired(required = false) MatchService matchService) {
        this.jdbc = jdbc;
        this.matchService = matchService;
    }

    public UserController(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    public record UserLeaderboardEntry(
            long id,
            String studentCode,
            String email,
            String displayName,
            String username,
            String role,
            int pvpElo,
            int botElo,
            int matchesCount,
            int wins,
            int losses,
            int draws,
            int winRate,
            int distinctOpponents,
            boolean provisional,
            int botsCount,
            String lastActiveAt
    ) {
        public UserLeaderboardEntry(long id, String studentCode, String email, String displayName, String username,
                                    String role, int pvpElo, int botElo, int matchesCount, int wins, int losses,
                                    int winRate, int botsCount, String lastActiveAt) {
            this(id, studentCode, email, displayName, username, role, pvpElo, botElo, matchesCount, wins, losses, 0,
                    winRate, 0, matchesCount < 10, botsCount, lastActiveAt);
        }
    }

    @GetMapping
    public List<UserLeaderboardEntry> listUsers() {
        return jdbc.query("""
                SELECT u.id, u.student_code, u.email, u.display_name, u.role,
                       COALESCE(u.pvp_elo, 1200) AS pvp_elo,
                       COALESCE(u.bot_elo, 1200) AS bot_elo,
                       to_char(COALESCE(MAX(um.created_at), u.created_at), 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS last_active_at,
                       COUNT(CASE WHEN um.is_ranked = TRUE AND UPPER(um.mode) = 'PVP' THEN 1 END) AS matches_count,
                       COUNT(CASE WHEN um.is_ranked = TRUE AND UPPER(um.mode) = 'PVP' AND um.user_won = true THEN 1 END) AS wins_count,
                       COUNT(CASE WHEN um.is_ranked = TRUE AND UPPER(um.mode) = 'PVP' AND um.user_won = false AND um.winner <> 'DRAW' THEN 1 END) AS losses_count,
                       COUNT(CASE WHEN um.is_ranked = TRUE AND UPPER(um.mode) = 'PVP' AND um.winner = 'DRAW' THEN 1 END) AS draws_count,
                       (SELECT COUNT(DISTINCT opponent) FROM user_matches
                        WHERE user_id = u.id AND is_ranked = TRUE AND UPPER(mode) = 'PVP') AS distinct_opponents,
                       (SELECT COUNT(*) FROM bots b WHERE b.owner_id = u.id AND (b.deleted IS FALSE OR b.deleted IS NULL)) AS bots_count
                FROM users u
                LEFT JOIN user_matches um ON um.user_id = u.id
                GROUP BY u.id, u.student_code, u.email, u.display_name, u.role, u.pvp_elo, u.bot_elo, u.created_at
                ORDER BY COALESCE(u.pvp_elo, 1200) DESC, u.id ASC
                """, (rs, rowNum) -> {
            int matches = rs.getInt("matches_count");
            int wins = rs.getInt("wins_count");
            int losses = rs.getInt("losses_count");
            int draws = rs.getInt("draws_count");
            int distinctOpponents = rs.getInt("distinct_opponents");
            int winRate = matches > 0 ? (int) Math.round((double) wins / matches * 100) : 0;
            boolean provisional = matches < 10 && distinctOpponents < 5;
            String studentCode = rs.getString("student_code");
            String email = rs.getString("email");
            String displayName = rs.getString("display_name");
            String username = (studentCode != null && !studentCode.isBlank()) ? studentCode : email;
            int botsCount = rs.getInt("bots_count");
            return new UserLeaderboardEntry(
                    rs.getLong("id"),
                    studentCode,
                    email,
                    displayName,
                    username,
                    rs.getString("role"),
                    rs.getInt("pvp_elo"),
                    rs.getInt("bot_elo"),
                    matches,
                    wins,
                    losses,
                    draws,
                    winRate,
                    distinctOpponents,
                    provisional,
                    botsCount,
                    rs.getString("last_active_at")
            );
        });
    }

    @GetMapping("/{username}/matches")
    public ResponseEntity<?> getUserMatches(
            @AuthenticationPrincipal ApiPrincipal principal,
            @PathVariable String username) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "UNAUTHORIZED",
                    "message", "Vui lòng đăng nhập để xem lịch sử trận đấu!"
            ));
        }
        // Chống rò rỉ dữ liệu chéo: Nếu tài khoản yêu cầu không khớp với tài khoản đang đăng nhập, chặn ngay bằng 403
        if (!principal.matchesUsername(username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "FORBIDDEN",
                    "message", "Không được phép xem lịch sử của tài khoản khác!"
            ));
        }
        if (matchService != null) {
            return ResponseEntity.ok(matchService.list(principal.id()));
        }
        return ResponseEntity.ok(List.of());
    }
}
