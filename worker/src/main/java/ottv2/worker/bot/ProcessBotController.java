package ottv2.worker.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ottv2.engine.bot.BotController;
import ottv2.engine.bot.BotTimeoutException;
import ottv2.engine.model.Move;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Position;
import ottv2.engine.vision.Observation;
import ottv2.worker.sandbox.Sandbox;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent one-process-per-bot adapter for the JSON-lines bot protocol. */
public final class ProcessBotController implements BotController {
    public static final int MAX_STDOUT_LINE_BYTES = 64 * 1024;
    public static final long MAX_STDERR_BYTES = 4L * 1024 * 1024;

    private final List<String> command;
    private final Sandbox sandbox;
    private final String team;
    private final ObjectMapper mapper = new ObjectMapper();
    private Process process;
    private BufferedWriter stdin;
    private BlockingQueue<ReadResult> stdoutLines;
    private Thread stdoutReader;
    private final AtomicLong stderrBytes = new AtomicLong();
    private final Object stderrLock = new Object();
    private final ByteArrayOutputStream stderrCapture = new ByteArrayOutputStream();
    private Thread stderrReader;

    public ProcessBotController(List<String> command, Sandbox sandbox, String team) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Bot command is required");
        this.command = List.copyOf(command);
        this.sandbox = sandbox;
        this.team = team;
    }

    @Override
    public synchronized void init(String matchInfo) {
        try {
            requestCount = 0;
            stderrBytes.set(0);
            synchronized (stderrLock) {
                stderrCapture.reset();
            }
            process = sandbox.start(command);
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdoutLines = new ArrayBlockingQueue<>(1);
            stdoutReader = daemon("bot-stdout-reader", () -> readStdout(process.getInputStream()));
            stderrReader = daemon("bot-stderr-reader", () -> drainStderr(process.getErrorStream()));
            stdoutReader.start();
            stderrReader.start();
        } catch (IOException e) {
            String message = "Team " + team + ": unable to start bot process: " + e.getMessage();
            System.err.println(message);
            throw new BotProcessException(message, e);
        }
    }

    private Duration currentGlobalTimeLeft;
    private Double lastTurnCpuTimeSeconds;

    @Override
    public Double getLastCpuTimeSeconds() {
        return lastTurnCpuTimeSeconds;
    }

    @Override
    public synchronized Move requestMove(Observation observation, Duration timeLimit, Duration globalTimeLeft) {
        this.currentGlobalTimeLeft = globalTimeLeft;
        return requestMove(observation, timeLimit);
    }

    @Override
    public synchronized Move requestMove(Observation observation, Duration timeLimit) {
        if (process == null || !process.isAlive()) throw failure("Bot process is not running", null);
        try {
            lastTurnCpuTimeSeconds = null;
            if (sandbox != null && sandbox.getMetaPath() != null) {
                try {
                    java.nio.file.Files.deleteIfExists(sandbox.getMetaPath());
                } catch (Exception ignored) { }
            }
            stdin.write(mapper.writeValueAsString(toProtocolState(observation, timeLimit)));
            stdin.write('\n');
            stdin.flush(); // Deliver this turn's complete JSON line immediately.
            ReadResult result = stdoutLines.poll(Math.max(1, timeLimit.toMillis()), TimeUnit.MILLISECONDS);
            if (result == null) {
                BotProcessException timeout = failure("Bot timed out or produced no response", null);
                throw new BotTimeoutException(timeout.getMessage(), timeout);
            }
            if (result.error != null) throw failure(result.error, null);
            JsonNode response = mapper.readTree(result.line);
            if (response == null || !response.has("pieceId") || !response.has("to")
                    || !response.get("pieceId").canConvertToInt() || !response.get("to").isArray()
                    || response.get("to").size() != 2 || !response.get("to").get(0).canConvertToInt()
                    || !response.get("to").get(1).canConvertToInt()) {
                throw failure("Bot response must contain integer pieceId and to:[row,col]", null);
            }

            readIsolateMetaCpuTime();

            return new Move(response.get("pieceId").intValue(),
                    new Position(response.get("to").get(0).intValue(), response.get("to").get(1).intValue()));
        } catch (IOException e) {
            throw failure("Bot protocol I/O failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("Interrupted while waiting for bot", e);
        }
    }

    public Double readIsolateMetaCpuTime() {
        if (sandbox == null || sandbox.getMetaPath() == null) return null;
        java.nio.file.Path metaPath = sandbox.getMetaPath();
        if (java.nio.file.Files.isRegularFile(metaPath)) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(metaPath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.startsWith("time:")) {
                        double sec = Double.parseDouble(line.substring("time:".length()).trim());
                        lastTurnCpuTimeSeconds = sec;
                        return sec;
                    }
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private ObjectNode toProtocolState(Observation observation, Duration timeLimit) {
        ObjectNode state = mapper.createObjectNode();
        long turn = requestCount++ * 2L + ("B".equals(team) ? 2 : 1);
        state.put("turn", turn);
        state.put("you", team);
        long remainingMs = currentGlobalTimeLeft != null ? currentGlobalTimeLeft.toMillis() : timeLimit.toMillis();
        state.put("timeLeftMs", remainingMs);
        ArrayNode yourPieces = state.putArray("yourPieces");
        observation.yourPieces().forEach(piece -> appendPiece(yourPieces, piece));
        ArrayNode enemies = state.putArray("visibleEnemies");
        observation.visibleEnemies().forEach(piece -> appendPiece(enemies, piece));
        state.set("enemyPieces", enemies);
        state.set("opponentPieces", enemies);
        state.set("enemies", enemies);

        ArrayNode cells = state.putArray("visibleCells");
        ArrayNode obstacles = state.putArray("obstacles");
        observation.visibleCells().forEach(cell -> {
            ObjectNode item = cells.addObject();
            item.put("r", cell.pos().row());
            item.put("c", cell.pos().col());
            item.put("type", cell.type().name());
            if (cell.type() == ottv2.engine.model.CellType.OBSTACLE) {
                ArrayNode ob = obstacles.addArray();
                ob.add(cell.pos().row()).add(cell.pos().col());
            }
        });
        state.set("cells", cells);
        ArrayNode targets = state.putArray("targets");
        if ("A".equals(team)) {
            targets.addArray().add(0).add(8);
        } else {
            targets.addArray().add(8).add(0);
        }
        return state;
    }

    private long requestCount;

    private void appendPiece(ArrayNode array, Piece piece) {
        ObjectNode item = array.addObject();
        item.put("id", piece.getId());
        item.put("type", piece.getType().name());
        item.put("r", piece.getPosition().row());
        item.put("c", piece.getPosition().col());
    }

    public byte[] stderrLog() {
        synchronized (stderrLock) {
            return stderrCapture.toByteArray();
        }
    }

    private void readStdout(InputStream stream) {
        try (var in = stream) {
            while (true) {
                byte[] buffer = new byte[MAX_STDOUT_LINE_BYTES];
                int length = 0;
                int value;
                boolean sawAny = false;
                while ((value = in.read()) != -1 && value != '\n') {
                    sawAny = true;
                    if (length == buffer.length) {
                        stdoutLines.offer(new ReadResult(null, "Bot stdout line exceeded 64 KB"));
                        terminate();
                        return;
                    }
                    if (value != '\r') buffer[length++] = (byte) value;
                }
                if (!sawAny && value == -1) {
                    stdoutLines.offer(new ReadResult(null, "Bot closed stdout"));
                    return;
                }
                ReadResult line = new ReadResult(new String(buffer, 0, length, StandardCharsets.UTF_8), null);
                if (!stdoutLines.offer(line)) {
                    stdoutLines.clear();
                    stdoutLines.offer(new ReadResult(null, "Bot produced more than one response before the next turn"));
                    terminate();
                    return;
                }
            }
        } catch (Exception e) {
            stdoutLines.offer(new ReadResult(null, "Failed reading bot stdout: " + e.getMessage()));
        }
    }

    private void drainStderr(InputStream stream) {
        try (var in = stream) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                long previous = stderrBytes.getAndAdd(n);
                int remaining = (int) Math.max(0, MAX_STDERR_BYTES - previous);
                int captured = Math.min(n, remaining);
                if (captured > 0) {
                    synchronized (stderrLock) {
                        stderrCapture.write(buffer, 0, captured);
                    }
                }
                if (previous + n > MAX_STDERR_BYTES) {
                    terminate();
                    return;
                }
                // stderr is kept separate from protocol stdout and logged only on failure.
            }
        } catch (IOException ignored) {
            // Process termination closes this stream.
        }
    }

    private BotProcessException failure(String reason, Throwable cause) {
        terminate();
        Thread reader = stderrReader;
        if (reader != null && reader != Thread.currentThread()) {
            try {
                reader.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String stderr;
        synchronized (stderrLock) {
            byte[] bytes = stderrCapture.toByteArray();
            int maxLogBytes = 64 * 1024;
            int start = Math.max(0, bytes.length - maxLogBytes);
            stderr = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8)
                    .replace("\r", "")
                    .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "?")
                    .strip();
            if (start > 0) stderr = "[earlier stderr omitted]\n" + stderr;
        }

        String message = "Team " + team + ": " + reason;
        if (!stderr.isEmpty()) message += "\nBot/sandbox stderr:\n" + stderr;
        System.err.println(message);
        return cause == null ? new BotProcessException(message) : new BotProcessException(message, cause);
    }

    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private synchronized void terminate() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    @Override
    public synchronized void shutdown() {
        try {
            if (process != null) {
                if (process.isAlive()) process.destroy();
                try {
                    if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            Thread reader = stderrReader;
            if (reader != null && reader != Thread.currentThread()) {
                try { reader.join(250); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        } finally {
            try {
                sandbox.cleanup();
            } catch (IOException e) {
                throw new BotProcessException("Unable to clean up bot sandbox", e);
            }
        }
    }

    private record ReadResult(String line, String error) {}

    public static final class BotProcessException extends RuntimeException {
        public BotProcessException(String message) { super(message); }
        public BotProcessException(String message, Throwable cause) { super(message, cause); }
    }
}
