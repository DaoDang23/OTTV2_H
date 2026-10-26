package ottv2.worker.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import ottv2.engine.combat.RpsCombatResolver;
import ottv2.engine.core.GameEngine;
import ottv2.engine.core.GameEngineBuilder;
import ottv2.engine.core.MatchRunner;
import ottv2.engine.event.GameEventPublisher;
import ottv2.engine.event.ReplayRecorder;
import ottv2.engine.mapgen.MapConfig;
import ottv2.engine.mapgen.SymmetricMapGenerator;
import ottv2.engine.model.Board;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Position;
import ottv2.engine.model.Team;
import ottv2.engine.rules.AnnihilationCondition;
import ottv2.engine.rules.CompositeWinCondition;
import ottv2.engine.rules.ReachTargetCondition;
import ottv2.engine.rules.TurnLimitCondition;
import ottv2.engine.vision.Observation;
import ottv2.worker.bot.ProcessBotController;
import ottv2.worker.sandbox.IsolateSandbox;

public final class MatchExecutionService {
    private static final int MAX_REPLAY_BYTES = 16 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final ObjectStorageFacade storage;
    private final ArtifactCache cache;
    private final int isolateBoxBase;
    private final String isolateExecutable;
    private final Path isolateBoxRoot;

    public MatchExecutionService(ObjectMapper mapper, ObjectStorageFacade storage, ArtifactCache cache,
                                 int isolateBoxBase, String isolateExecutable, Path isolateBoxRoot) {
        this.mapper = mapper;
        this.storage = storage;
        this.cache = cache;
        this.isolateBoxBase = isolateBoxBase;
        this.isolateExecutable = isolateExecutable;
        this.isolateBoxRoot = isolateBoxRoot;
    }

    public MatchOutcome execute(MatchJob job) throws Exception {
        if (!"MATCH_JOB".equals(job.type())) throw new IllegalArgumentException("Unsupported match job type");
        Path work = Files.createTempDirectory("ottv2-match-");
        try {
            Path artifactA = artifact(job.sideA(), work.resolve("a"));
            Path artifactB = artifact(job.sideB(), work.resolve("b"));
            ProcessBotController botA = new ProcessBotController(command(job.sideA().language(), artifactA), sandbox(isolateBoxBase), "A");
            ProcessBotController botB = new ProcessBotController(command(job.sideB().language(), artifactB), sandbox(isolateBoxBase + 1), "B");
            ReplayRecorder replay = new ReplayRecorder(job.publicId(), job.seed(), engineMap(job.mapConfig()),
                    job.sideA().botName(), job.sideB().botName());
            replay.recordInitialState(boardFor(job.seed(), job.mapConfig()), piecesFor(job.seed()));
            GameEngine engine = createEngine(job.seed(), engineMap(job.mapConfig()), replay);
            new MatchRunner().run(engine, botA, botB, replay);
            String replayJson = replay.getJson();
            byte[] replayBytes = gzip(replayJson.getBytes(StandardCharsets.UTF_8));
            if (replayBytes.length > MAX_REPLAY_BYTES) throw new IOException("Compressed replay exceeded the size limit");

            JsonNode json = mapper.readTree(replayJson);
            JsonNode result = json.path("result");
            if (result.isMissingNode()) throw new IOException("Match ended without a replay result");
            String winner = result.path("winner").asText("DRAW");
            String winnerSide = "A".equals(winner) || "B".equals(winner) ? winner : null;
            String reason = result.path("reason").asText("UNKNOWN");
            String base = "matches/" + job.publicId() + "/";
            String replayKey = base + "replay.json.gz";
            String logAKey = base + "bot-A.log.gz";
            String logBKey = base + "bot-B.log.gz";
            byte[] stderrA = gzip(botA.stderrLog());
            byte[] stderrB = gzip(botB.stderrLog());
            storage.put(replayKey, replayBytes, "application/gzip");
            storage.put(logAKey, stderrA, "application/gzip");
            storage.put(logBKey, stderrB, "application/gzip");

            String faultA = faultFor(reason, winnerSide, "A");
            String faultB = faultFor(reason, winnerSide, "B");
            return new MatchOutcome(job.matchId(), job.createdMonth(), winnerSide, reason,
                    json.path("turns").size(), faultA, faultB, replayKey, replayBytes.length, logAKey, logBKey, replayJson);
        } finally {
            deleteTree(work);
        }
    }

    private Path artifact(MatchJob.ParticipantJob participant, Path destination) throws Exception {
        byte[] contents = cache.get(participant.artifactKey(),
                () -> storage.get(participant.artifactKey(), 128 * 1024 * 1024));
        Files.createDirectories(destination);
        String extension = switch (participant.language()) {
            case "JAVA" -> ".jar";
            case "CPP" -> "";
            case "PYTHON" -> ".py";
            default -> throw new IllegalArgumentException("Unsupported bot language: " + participant.language());
        };
        Path file = destination.resolve("bot" + extension);
        Files.write(file, contents);
        file.toFile().setExecutable(true, true);
        return file;
    }

    private static List<String> command(String language, Path artifact) {
        return switch (language) {
            case "PYTHON" -> List.of("/usr/bin/python3", "-u", artifact.toAbsolutePath().toString());
            case "CPP" -> List.of(artifact.toAbsolutePath().toString());
            case "JAVA" -> List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xms16m", "-Xmx64m", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=16m",
                    "-XX:CompressedClassSpaceSize=32m", "-XX:MaxMetaspaceSize=48m", "-Xss256k",
                    "-XX:ActiveProcessorCount=1", "-jar", artifact.toAbsolutePath().toString());
            default -> throw new IllegalArgumentException("Unsupported bot language: " + language);
        };
    }

    private IsolateSandbox sandbox(int boxId) {
        return new IsolateSandbox(isolateExecutable, boxId, 200, 600, 256, 16,
                Path.of(isolateBoxRoot.toString().replace("{id}", Integer.toString(boxId))));
    }

    private static MapConfig engineMap(MatchJob.MapConfiguration config) {
        if (config == null) return new MapConfig(8, 0, 0, 0);
        return new MapConfig(config.obstacles(), config.traps(), config.speeds(), config.mutations());
    }

    private static GameEngine createEngine(long seed, MapConfig config, ReplayRecorder replay) {
        Board board = boardFor(seed, new MatchJob.MapConfiguration(config.obstacles(), config.traps(), config.speeds(), config.mutations()));
        List<Piece> pieces = piecesFor(seed);
        pieces.forEach(piece -> board.get(piece.getPosition()).setOccupant(piece));
        var state = new ottv2.engine.model.GameState(board, pieces, seed);
        GameEventPublisher publisher = new GameEventPublisher();
        publisher.subscribe(replay);
        var wins = new CompositeWinCondition(List.of(
                new ReachTargetCondition(), new AnnihilationCondition(), new TurnLimitCondition(TurnLimitCondition.DEFAULT_LIMIT)));
        return new GameEngineBuilder().withState(state).withPublisher(publisher).withWinCondition(wins)
                .withVisionStrategy((team, game) -> new Observation(
                        game.getPieces().stream().filter(p -> p.isAlive() && p.getTeam() == team).toList(),
                        game.getPieces().stream().filter(p -> p.isAlive() && p.getTeam() != team).toList(),
                        allVisibleCells(game.getBoard())))
                .withCombatResolver(new RpsCombatResolver()).build();
    }

    private static Board boardFor(long seed, MatchJob.MapConfiguration config) {
        return new SymmetricMapGenerator().generate(seed, engineMap(config));
    }

    private static List<Piece> piecesFor(long seed) {
        return new SymmetricMapGenerator().generatePieces(seed);
    }

    private static List<Observation.CellInfo> allVisibleCells(Board board) {
        List<Observation.CellInfo> cells = new ArrayList<>();
        for (int row = 0; row < Board.SIZE; row++) for (int col = 0; col < Board.SIZE; col++) {
            Position position = new Position(row, col);
            var cell = board.get(position);
            cells.add(new Observation.CellInfo(position,
                    cell.isHidden() ? ottv2.engine.model.CellType.EMPTY : cell.getType()));
        }
        return cells;
    }

    private static String faultFor(String reason, String winner, String side) {
        if (winner == null || winner.equals(side)) return null;
        return switch (reason) {
            case "TIMEOUT" -> "TLE";
            case "BOT_CRASHED" -> "RE";
            case "INVALID_MOVE" -> "ILLEGAL_MOVE";
            default -> null;
        };
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) { gzip.write(bytes); }
        return buffer.toByteArray();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public record MatchOutcome(long matchId, String createdMonth, String winnerSide, String endReason,
                               int totalTurns, String faultA, String faultB,
                               String replayKey, int replaySizeBytes, String botALogKey, String botBLogKey,
                               String replayJson) {
        public MatchOutcome(long matchId, String createdMonth, String winnerSide, String endReason,
                            int totalTurns, String faultA, String faultB,
                            String replayKey, int replaySizeBytes, String botALogKey, String botBLogKey) {
            this(matchId, createdMonth, winnerSide, endReason, totalTurns, faultA, faultB,
                 replayKey, replaySizeBytes, botALogKey, botBLogKey, null);
        }
    }

    @FunctionalInterface
    public interface ObjectStorageFacade {
        byte[] get(String key, int maxBytes) throws Exception;
        default void put(String key, byte[] value, String contentType) throws Exception {
            throw new UnsupportedOperationException("Object upload is unavailable");
        }
    }
}
