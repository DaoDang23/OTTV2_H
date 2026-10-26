package ottv2.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() { }

    public record RegisterRequest(
            @NotBlank @Size(max = 32) String studentCode,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank @Size(max = 100) String displayName) { }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) { }
    public record UserResponse(long id, String studentCode, String email, String displayName, String role,
                               int pvpElo, int botElo, String token) {
        public UserResponse(long id, String studentCode, String email, String displayName, String role,
                           int pvpElo, int botElo) {
            this(id, studentCode, email, displayName, role, pvpElo, botElo, null);
        }

        public UserResponse(long id, String studentCode, String email, String displayName, String role) {
            this(id, studentCode, email, displayName, role, 1200, 1200, null);
        }
    }
    public record CsrfResponse(String token, String headerName) { }
}
