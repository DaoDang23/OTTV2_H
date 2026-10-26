package ottv2.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenAuthTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tokenSessionServiceLifecycle() {
        TokenSessionService service = new TokenSessionService();
        ApiPrincipal principal = new ApiPrincipal(10L, "B20DCCN001", "student@test.com", "hash", "USER");

        String token = service.createToken(principal);
        assertNotNull(token);
        assertFalse(token.isBlank());

        ApiPrincipal found = service.getPrincipal(token);
        assertEquals(principal, found);

        service.invalidate(token);
        assertNull(service.getPrincipal(token));
    }

    @Test
    void filterAuthenticatesWithBearerHeader() throws ServletException, IOException {
        TokenSessionService service = new TokenSessionService();
        ApiPrincipal principal = new ApiPrincipal(20L, "B20DCCN002", "student2@test.com", "hash", "USER");
        String token = service.createToken(principal);

        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(service);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(principal, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void filterAuthenticatesWithXAuthTokenHeader() throws ServletException, IOException {
        TokenSessionService service = new TokenSessionService();
        ApiPrincipal principal = new ApiPrincipal(30L, "B20DCCN003", "student3@test.com", "hash", "USER");
        String token = service.createToken(principal);

        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(service);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Auth-Token", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(principal, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void filterProceedsWithoutAuthWhenNoToken() throws ServletException, IOException {
        TokenSessionService service = new TokenSessionService();
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(service);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
