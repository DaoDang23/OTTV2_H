package ottv2.api.auth;

import ottv2.api.auth.AuthDtos.RegisterRequest;
import ottv2.api.auth.AuthDtos.UserResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse register(RegisterRequest request) {
        return jdbc.queryForObject("""
                        INSERT INTO users (student_code, email, password_hash, display_name)
                        VALUES (?, lower(?), ?, ?) RETURNING id, student_code, email, display_name, role, pvp_elo, bot_elo
                        """,
                (rs, row) -> new UserResponse(rs.getLong("id"), rs.getString("student_code"),
                        rs.getString("email"), rs.getString("display_name"), rs.getString("role"),
                        rs.getInt("pvp_elo"), rs.getInt("bot_elo")),
                request.studentCode().trim(), request.email().trim(),
                passwordEncoder.encode(request.password()), request.displayName().trim());
    }

    public UserResponse getUser(long userId) {
        return jdbc.queryForObject("SELECT id, student_code, email, display_name, role, pvp_elo, bot_elo FROM users WHERE id=?",
                (rs, row) -> new UserResponse(rs.getLong("id"), rs.getString("student_code"),
                        rs.getString("email"), rs.getString("display_name"), rs.getString("role"),
                        rs.getInt("pvp_elo"), rs.getInt("bot_elo")), userId);
    }
}
