package ottv2.worker.bot;

import org.junit.jupiter.api.Test;
import ottv2.engine.model.Position;
import ottv2.engine.vision.Observation;
import ottv2.worker.sandbox.Sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessBotControllerTest {
    private static final Observation EMPTY_OBSERVATION = new Observation(List.of(), List.of(), List.of());

    @Test
    void exchangesOneJsonResponseForEachTurn() {
        ProcessBotController bot = new ProcessBotController(helperCommand("valid"), localSandbox(), "A");
        try {
            bot.init("Team A");
            var move = bot.requestMove(EMPTY_OBSERVATION, Duration.ofSeconds(1));

            assertEquals(17, move.pieceId());
            assertEquals(new Position(3, 4), move.to());
        } finally {
            bot.shutdown();
        }
    }

    @Test
    void exchangesMoveWithGlobalTimeLeft() {
        ProcessBotController bot = new ProcessBotController(helperCommand("valid"), localSandbox(), "A");
        try {
            bot.init("Team A");
            var move = bot.requestMove(EMPTY_OBSERVATION, Duration.ofSeconds(3), Duration.ofMillis(180_000));

            assertEquals(17, move.pieceId());
            assertEquals(new Position(3, 4), move.to());
        } finally {
            bot.shutdown();
        }
    }

    @Test
    void rejectsOutputLinesLargerThanProtocolLimit() {
        ProcessBotController bot = new ProcessBotController(helperCommand("large"), localSandbox(), "A");
        try {
            bot.init("Team A");
            assertThrows(ProcessBotController.BotProcessException.class,
                    () -> bot.requestMove(EMPTY_OBSERVATION, Duration.ofSeconds(2)));
        } finally {
            bot.shutdown();
        }
    }

    private static Sandbox localSandbox() {
        return command -> new ProcessBuilder(command).start();
    }

    private static List<String> helperCommand(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Path source = Path.of("src", "test", "java", "ottv2", "worker", "bot", "BotProtocolTestProcess.java");
        if (!source.toFile().isFile()) {
            source = Path.of("worker", "src", "test", "java", "ottv2", "worker", "bot", "BotProtocolTestProcess.java");
        }
        // Keep the source path relative. On Windows, an absolute path under a
        // non-ASCII workspace path can be mangled by the Java source launcher.
        return List.of(java, source.toString(), mode);
    }
}
