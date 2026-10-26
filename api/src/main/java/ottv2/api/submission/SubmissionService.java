package ottv2.api.submission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import ottv2.api.messaging.OutboxPublisher;
import ottv2.api.storage.SourceObjectStore;
import ottv2.api.submission.SubmissionDtos.CompileJob;
import ottv2.api.submission.SubmissionDtos.CreateSubmissionRequest;
import ottv2.api.submission.SubmissionDtos.Language;
import ottv2.api.submission.SubmissionDtos.SubmissionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubmissionService {
    private static final RowMapper<SubmissionResponse> RESPONSE_MAPPER = (rs, row) -> new SubmissionResponse(
            rs.getLong("id"), rs.getLong("bot_id"), rs.getInt("version_no"),
            Language.valueOf(rs.getString("language")), rs.getString("status"),
            rs.getString("source_sha256").trim(), rs.getString("compile_log"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SourceObjectStore objectStore;
    private final ObjectMapper mapper;
    private final int maxSourceBytes;
    private final int cooldownSeconds;
    private final int dailyLimit;

    public SubmissionService(JdbcTemplate jdbc, TransactionTemplate transaction, SourceObjectStore objectStore,
                             ObjectMapper mapper,
                             @Value("${ottv2.submissions.max-source-bytes:262144}") int maxSourceBytes,
                             @Value("${ottv2.submissions.cooldown-seconds:30}") int cooldownSeconds,
                             @Value("${ottv2.submissions.daily-limit:50}") int dailyLimit) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.objectStore = objectStore;
        this.mapper = mapper;
        this.maxSourceBytes = maxSourceBytes;
        this.cooldownSeconds = cooldownSeconds;
        this.dailyLimit = dailyLimit;
    }

    public SubmissionResponse submit(long ownerId, long botId, CreateSubmissionRequest request) {
        byte[] source = request.source().getBytes(StandardCharsets.UTF_8);
        if (source.length > maxSourceBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Source exceeds configured size limit");
        }
        String sha = sha256(source);
        String sourceKey = "sources/" + sha;

        return transaction.execute(status -> {
            lockOwner(ownerId);
            lockOwnedBot(ownerId, botId);
            enforceRateLimits(ownerId);
            objectStore.put(sourceKey, source);
            int nextVersion = jdbc.queryForObject("SELECT COALESCE(MAX(version_no), 0) + 1 FROM submissions WHERE bot_id=?",
                    Integer.class, botId);

            SubmissionResponse created = jdbc.queryForObject("""
                    INSERT INTO submissions(bot_id, version_no, language, source_key, source_sha256, status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                    RETURNING id, bot_id, version_no, language, status, source_sha256, compile_log, created_at
                    """, RESPONSE_MAPPER, botId, nextVersion, request.language().name(), sourceKey, sha);

            // Ensure bot has active_submission_id assigned if currently null
            jdbc.update("UPDATE bots SET active_submission_id = ? WHERE id = ? AND active_submission_id IS NULL",
                    created.id(), botId);

            // Ensure bot has initial ratings in bot_ratings (1200 ELO)
            try {
                Long seasonId = null;
                try {
                    seasonId = jdbc.queryForObject(
                            "SELECT id FROM seasons WHERE status='ACTIVE' AND starts_at<=now() AND ends_at>now() ORDER BY starts_at LIMIT 1",
                            Long.class);
                } catch (Exception ignored) {}
                if (seasonId == null) seasonId = 1L;
                jdbc.update("""
                        INSERT INTO bot_ratings(bot_id, season_id, elo, games, wins, losses, draws)
                        VALUES (?, ?, 1200, 0, 0, 0, 0)
                        ON CONFLICT (bot_id, season_id) DO NOTHING
                        """, botId, seasonId);
            } catch (Exception ignored) {}
            try {
                CompileJob job = new CompileJob(created.id(), botId, created.versionNo(), request.language().name(), sourceKey, sha);
                String payload = mapper.writeValueAsString(job);
                jdbc.update("INSERT INTO outbox_events(topic, payload) VALUES ('compile.q', ?::jsonb)", payload);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not encode compile request", e);
            }
            return created;
        });
    }

    public List<SubmissionResponse> list(long ownerId, long botId) {
        requireOwnedBot(ownerId, botId);
        return jdbc.query("""
                SELECT s.id, s.bot_id, s.version_no, s.language, s.status, s.source_sha256, s.compile_log, s.created_at
                FROM submissions s JOIN bots b ON b.id=s.bot_id
                WHERE b.id=? AND b.owner_id=? ORDER BY s.version_no DESC
                """, RESPONSE_MAPPER, botId, ownerId);
    }

    public SubmissionResponse get(long ownerId, long botId, long submissionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT s.id, s.bot_id, s.version_no, s.language, s.status, s.source_sha256, s.compile_log, s.created_at
                    FROM submissions s JOIN bots b ON b.id=s.bot_id
                    WHERE b.id=? AND b.owner_id=? AND s.id=?
                    """, RESPONSE_MAPPER, botId, ownerId, submissionId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found");
        }
    }

    private void lockOwner(long ownerId) {
        try {
            jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, ownerId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
    }

    private void lockOwnedBot(long ownerId, long botId) {
        requireOwnedBot(ownerId, botId, true);
    }

    private void requireOwnedBot(long ownerId, long botId) { requireOwnedBot(ownerId, botId, false); }

    private void requireOwnedBot(long ownerId, long botId, boolean lock) {
        try {
            jdbc.queryForObject("SELECT id FROM bots WHERE id=? AND owner_id=?" + (lock ? " FOR UPDATE" : ""),
                    Long.class, botId, ownerId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found");
        }
    }

    private void enforceRateLimits(long ownerId) {
        Integer recent = jdbc.queryForObject("""
                SELECT COUNT(*) FROM submissions s JOIN bots b ON b.id=s.bot_id
                WHERE b.owner_id=? AND s.created_at >= now() - (? * interval '1 second')
                """, Integer.class, ownerId, cooldownSeconds);
        if (recent != null && recent > 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before submitting another version");
        }
        Integer today = jdbc.queryForObject("""
                SELECT COUNT(*) FROM submissions s JOIN bots b ON b.id=s.bot_id
                WHERE b.owner_id=? AND s.created_at >= date_trunc('day', now())
                """, Integer.class, ownerId);
        if (today != null && today >= dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Daily submission limit reached");
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}
