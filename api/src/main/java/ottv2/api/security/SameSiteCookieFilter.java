package ottv2.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Filter cấu hình thuộc tính SameSite và Secure cho cookie một cách thông minh:
 * - Khi kết nối qua HTTPS (Ngrok, Vercel, Domain có SSL): Sử dụng SameSite=None; Secure để hỗ trợ Cross-Site.
 * - Khi kết nối qua HTTP (mạng LAN 192.168.x.x, localhost không SSL): Dùng SameSite=Lax và KHÔNG gán Secure.
 *   Nếu gán Secure trên kết nối HTTP thường, trình duyệt (Chrome, Cốc Cốc, Edge) sẽ chặn/hủy bỏ cookie hoàn toàn,
 *   gây ra lỗi 401 Unauthorized và vòng lặp đăng nhập.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SameSiteCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        final boolean isHttps = isHttpsRequest(request);

        HttpServletResponseWrapper responseWrapper = new HttpServletResponseWrapper(response) {
            @Override
            public void addHeader(String name, String value) {
                if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) {
                    value = formatCookie(value, isHttps);
                }
                super.addHeader(name, value);
            }

            @Override
            public void setHeader(String name, String value) {
                if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) {
                    value = formatCookie(value, isHttps);
                }
                super.setHeader(name, value);
            }

            @Override
            public void addCookie(Cookie cookie) {
                if (cookie != null) {
                    if (isHttps) {
                        cookie.setSecure(true);
                        cookie.setAttribute("SameSite", "None");
                    } else {
                        cookie.setSecure(false);
                        cookie.setAttribute("SameSite", "Lax");
                    }
                }
                super.addCookie(cookie);
            }

            private String formatCookie(String cookieHeader, boolean https) {
                if (cookieHeader == null) return null;
                if (https) {
                    String lower = cookieHeader.toLowerCase(Locale.ROOT);
                    StringBuilder sb = new StringBuilder(cookieHeader);
                    if (!lower.contains("samesite")) {
                        sb.append("; SameSite=None");
                    }
                    if (!lower.contains("secure")) {
                        sb.append("; Secure");
                    }
                    return sb.toString();
                } else {
                    // Trên kết nối HTTP thường (LAN 192.168.x.x hoặc localhost),
                    // gỡ bỏ thuộc tính Secure và chuyển SameSite=None thành SameSite=Lax
                    String cleaned = cookieHeader.replaceAll("(?i);\\s*secure", "");
                    if (cleaned.toLowerCase(Locale.ROOT).contains("samesite=none")) {
                        cleaned = cleaned.replaceAll("(?i)samesite=none", "SameSite=Lax");
                    } else if (!cleaned.toLowerCase(Locale.ROOT).contains("samesite")) {
                        cleaned += "; SameSite=Lax";
                    }
                    return cleaned;
                }
            }
        };

        filterChain.doFilter(request, responseWrapper);
    }

    private boolean isHttpsRequest(HttpServletRequest request) {
        if (request.isSecure()) return true;
        String proto = request.getHeader("X-Forwarded-Proto");
        if ("https".equalsIgnoreCase(proto)) return true;
        String scheme = request.getHeader("X-Forwarded-Scheme");
        if ("https".equalsIgnoreCase(scheme)) return true;
        String ssl = request.getHeader("X-Forwarded-Ssl");
        if ("on".equalsIgnoreCase(ssl)) return true;
        String origin = request.getHeader("Origin");
        if (origin != null && origin.startsWith("https://")) return true;
        String referer = request.getHeader("Referer");
        if (referer != null && referer.startsWith("https://")) return true;
        return false;
    }
}
