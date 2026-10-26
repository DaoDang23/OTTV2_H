package ottv2.api.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CompileResultConsumer {
    private static final int MAX_LOG_CHARS = 64 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MatchResultConsumer matchResults;

    public CompileResultConsumer(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, null);
    }

    @Autowired
    public CompileResultConsumer(JdbcTemplate jdbc, ObjectMapper mapper, MatchResultConsumer matchResults) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.matchResults = matchResults;
    }

    @RabbitListener(queues = "result.q")
    public void consume(byte[] body) throws Exception {
        JsonNode envelope = mapper.readTree(body);
        if (envelope.has("type")) {
            if (matchResults == null) throw new IllegalArgumentException("Match result handler is unavailable");
            matchResults.consume(body);
            return;
        }
        CompileResult result = mapper.readValue(body, CompileResult.class);
        if (!result.status().equals("COMPILED") && !result.status().equals("COMPILE_ERROR")) {
            throw new IllegalArgumentException("Unsupported compile status: " + result.status());
        }
        String logText = result.compileLog() == null ? "" : result.compileLog();
        if (logText.length() > MAX_LOG_CHARS) logText = logText.substring(0, MAX_LOG_CHARS);
        jdbc.update("""
                UPDATE submissions SET status=?, artifact_key=?, compile_log=?
                WHERE id=? AND status IN ('PENDING','COMPILING')
                """, result.status(), result.artifactKey(), logText, result.submissionId());

        if ("COMPILED".equals(result.status())) {
            jdbc.update("""
                    UPDATE bots b SET active_submission_id = s.id
                    FROM submissions s
                    WHERE s.id = ? AND b.id = s.bot_id
                    """, result.submissionId());
        }
    }

    public record CompileResult(long submissionId, String status, String artifactKey, String compileLog) { }
}
