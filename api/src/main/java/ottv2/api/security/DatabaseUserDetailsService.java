package ottv2.api.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public DatabaseUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public ApiPrincipal loadUserByUsername(String email) {
        return jdbc.query("SELECT id, student_code, email, password_hash, role FROM users WHERE lower(email)=lower(?)",
                (rs, row) -> new ApiPrincipal(rs.getLong("id"), rs.getString("student_code"),
                        rs.getString("email"), rs.getString("password_hash"), rs.getString("role")),
                email).stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }
}
