package ottv2.api.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CompileResultConsumerTest {
    @Test
    void appliesCompileResultOnlyToPendingOrCompilingSubmissions() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CompileResultConsumer consumer = new CompileResultConsumer(jdbc, new ObjectMapper());
        byte[] message = new ObjectMapper().writeValueAsBytes(
                new CompileResultConsumer.CompileResult(9, "COMPILED", "artifacts/hash/java", "ok"));

        consumer.consume(message);

        verify(jdbc).update(eq("""
                UPDATE submissions SET status=?, artifact_key=?, compile_log=?
                WHERE id=? AND status IN ('PENDING','COMPILING')
                """), eq("COMPILED"), eq("artifacts/hash/java"), eq("ok"), eq(9L));
    }
}
