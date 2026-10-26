package ottv2.worker.compile;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IsolateCompilerTest {
    private final IsolateCompiler compiler = new IsolateCompiler("missing-isolate", 300,
            Path.of("unused-root"), Path.of("unused-worker.jar"), 8);

    @Test
    void rejectsUnsupportedLanguageWithoutStartingSandbox() throws Exception {
        CompileOutcome outcome = compiler.compile(job("KOTLIN"), "fun main() {}".getBytes());
        assertEquals("COMPILE_ERROR", outcome.result().status());
        assertNull(outcome.artifactBytes());
    }

    @Test
    void rejectsOversizedSourceWithoutStartingSandbox() throws Exception {
        CompileOutcome outcome = compiler.compile(job("PYTHON"), "0123456789".getBytes());
        assertEquals("COMPILE_ERROR", outcome.result().status());
        assertEquals("Source exceeds configured size limit", outcome.result().compileLog());
        assertNull(outcome.artifactBytes());
    }

    private static CompileJob job(String language) {
        return new CompileJob(1, 2, 1, language, "sources/key", "abc");
    }
}
