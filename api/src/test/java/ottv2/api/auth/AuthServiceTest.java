package ottv2.api.auth;

import ottv2.api.auth.AuthDtos.RegisterRequest;
import ottv2.api.auth.AuthDtos.UserResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks AuthService authService;

    @Test
    void registerStoresOnlyEncodedPasswordAndReturnsPublicUserFields() {
        RegisterRequest request = new RegisterRequest("S-1", "student@example.test", "a-long-password", "Student");
        UserResponse expected = new UserResponse(7, "S-1", "student@example.test", "Student", "STUDENT");
        when(passwordEncoder.encode("a-long-password")).thenReturn("bcrypt-hash");
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<UserResponse>>any(),
                eq("S-1"), eq("student@example.test"), eq("bcrypt-hash"), eq("Student")))
                .thenReturn(expected);

        UserResponse actual = authService.register(request);

        assertEquals(expected, actual);
        verify(passwordEncoder).encode("a-long-password");
        verify(jdbc).queryForObject(anyString(), ArgumentMatchers.<RowMapper<UserResponse>>any(),
                eq("S-1"), eq("student@example.test"), eq("bcrypt-hash"), eq("Student"));
    }
}
