package ottv2.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter kiểm tra Header Authorization (Bearer) hoặc X-Auth-Token.
 * Giải quyết dứt điểm vấn đề trình duyệt chặn cookie bên thứ ba (Third-Party Cookie)
 * trong chế độ Ẩn danh (Incognito) hoặc cross-domain Vercel -> Ngrok.
 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final TokenSessionService tokenSessionService;

    public TokenAuthenticationFilter(TokenSessionService tokenSessionService) {
        this.tokenSessionService = tokenSessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        if (token != null && !token.isBlank()) {
            ApiPrincipal principal = tokenSessionService.getPrincipal(token);
            if (principal != null) {
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );
                SecurityContext context = SecurityContextHolder.getContext();
                if (context.getAuthentication() == null) {
                    context.setAuthentication(authentication);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    public static String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            String token = bearer.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String xAuth = request.getHeader("X-Auth-Token");
        if (xAuth != null && !xAuth.isBlank()) {
            return xAuth.trim();
        }
        return null;
    }
}
