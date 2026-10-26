package ottv2.api.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public record ApiPrincipal(long id, String studentCode, String email, String passwordHash, String role)
        implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }

    public String username() {
        return studentCode != null && !studentCode.isBlank() ? studentCode : (email != null ? email : String.valueOf(id));
    }

    public boolean matchesUsername(String target) {
        if (target == null || target.isBlank()) return false;
        String trimmed = target.trim();
        if (studentCode != null && studentCode.equalsIgnoreCase(trimmed)) return true;
        if (email != null) {
            if (email.equalsIgnoreCase(trimmed)) return true;
            String prefix = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            if (prefix.equalsIgnoreCase(trimmed)) return true;
        }
        return false;
    }
}
