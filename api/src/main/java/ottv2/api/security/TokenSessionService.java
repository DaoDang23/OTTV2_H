package ottv2.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service lưu trữ và quản lý token xác thực dành cho môi trường Cross-Origin / Incognito
 * khi trình duyệt chặn cookie bên thứ ba (Third-Party Cookies).
 */
@Service
public class TokenSessionService {
    private static final Logger log = LoggerFactory.getLogger(TokenSessionService.class);
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);

    public record TokenRecord(ApiPrincipal principal, Instant expiresAt) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final Map<String, TokenRecord> tokenStore = new ConcurrentHashMap<>();

    public String createToken(ApiPrincipal principal) {
        if (principal == null) return null;
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(DEFAULT_TTL);
        tokenStore.put(token, new TokenRecord(principal, expiresAt));
        return token;
    }

    public ApiPrincipal getPrincipal(String token) {
        if (token == null || token.isBlank()) return null;
        TokenRecord record = tokenStore.get(token);
        if (record == null) return null;
        if (record.isExpired()) {
            tokenStore.remove(token);
            return null;
        }
        return record.principal();
    }

    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            tokenStore.remove(token);
        }
    }

    public void invalidateByUser(long userId) {
        tokenStore.entrySet().removeIf(entry -> entry.getValue().principal().id() == userId);
    }

    @Scheduled(fixedRate = 300000) // Dọn dẹp token hết hạn mỗi 5 phút
    public void cleanupExpiredTokens() {
        tokenStore.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
}
