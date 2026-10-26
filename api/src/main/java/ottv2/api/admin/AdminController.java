package ottv2.api.admin;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.security.ApiPrincipal;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JdbcTemplate jdbc;

    public AdminController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/reset-test-data")
    public Map<String, Object> resetTestData(@AuthenticationPrincipal ApiPrincipal principal) {
        if (principal == null || !"ADMIN".equalsIgnoreCase(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin permission required to reset test data");
        }

        // 1. Truncate / Delete match tables safely
        try {
            jdbc.execute("TRUNCATE TABLE user_matches, match_participants, replays, matches CASCADE");
        } catch (Exception ex) {
            try { jdbc.execute("DELETE FROM user_matches"); } catch (Exception ignored) {}
            try { jdbc.execute("DELETE FROM replays"); } catch (Exception ignored) {}
            try { jdbc.execute("DELETE FROM match_participants"); } catch (Exception ignored) {}
            try { jdbc.execute("DELETE FROM tournament_matches"); } catch (Exception ignored) {}
            try { jdbc.execute("DELETE FROM matches"); } catch (Exception ignored) {}
        }
        try {
            jdbc.execute("TRUNCATE TABLE ladder_series CASCADE");
        } catch (Exception ex) {
            try { jdbc.execute("DELETE FROM ladder_series"); } catch (Exception ignored) {}
        }
        try {
            jdbc.execute("DELETE FROM bracket_games");
        } catch (Exception ignored) {}

        // 2. Reset user elos and stats counters
        int usersReset;
        try {
            usersReset = jdbc.update("""
                    UPDATE users 
                    SET pvp_elo = 1200, bot_elo = 1200, 
                        pvp_wins = 0, pvp_losses = 0, pvp_draws = 0, 
                        bot_wins = 0, bot_losses = 0
                    """);
        } catch (Exception ex) {
            usersReset = jdbc.update("UPDATE users SET pvp_elo = 1200, bot_elo = 1200");
        }

        // 3. Reset bot ratings
        int botsReset = jdbc.update("UPDATE bot_ratings SET elo = 1200, games = 0, wins = 0, losses = 0, draws = 0");
        try {
            jdbc.update("UPDATE bot_ratings SET games = 0, wins = 0, losses = 0, draws = 0");
        } catch (Exception ignored) {}

        return Map.of(
                "success", true,
                "message", "Đã xóa toàn bộ dữ liệu trận đấu test và đặt lại ELO về 1200 thành công!",
                "usersReset", usersReset,
                "botsReset", botsReset
        );
    }
}
