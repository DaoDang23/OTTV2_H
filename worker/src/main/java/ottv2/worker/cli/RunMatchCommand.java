package ottv2.worker.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import ottv2.engine.combat.RpsCombatResolver;
import ottv2.engine.core.GameEngine;
import ottv2.engine.core.GameEngineBuilder;
import ottv2.engine.core.MatchRunner;
import ottv2.engine.event.ReplayRecorder;
import ottv2.engine.mapgen.MapConfig;
import ottv2.engine.mapgen.SymmetricMapGenerator;
import ottv2.engine.model.Board;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Team;
import ottv2.engine.rules.AnnihilationCondition;
import ottv2.engine.rules.CompositeWinCondition;
import ottv2.engine.rules.ReachTargetCondition;
import ottv2.engine.rules.TurnLimitCondition;
import ottv2.engine.vision.Observation;
import ottv2.worker.bot.ProcessBotController;
import ottv2.worker.sandbox.IsolateSandbox;
import ottv2.worker.sandbox.LocalProcessSandbox;
import ottv2.worker.sandbox.Sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** CLI entry point: run-match --botA <path> --botB <path> --seed N [--sandbox isolate|local] [--replay file] */
public final class RunMatchCommand {
    private RunMatchCommand() {}

    public static void main(String[] args) throws Exception {
        var options = parse(args);
        long seed = Long.parseLong(required(options, "seed"));
        List<String> commandA = botCommand(Path.of(required(options, "botA")));
        List<String> commandB = botCommand(Path.of(required(options, "botB")));
        String sandboxMode = options.getOrDefault("sandbox", "isolate");
        Sandbox sandboxA;
        Sandbox sandboxB;
        if (sandboxMode.equals("local")) {
            sandboxA = new LocalProcessSandbox();
            sandboxB = new LocalProcessSandbox();
        } else if (sandboxMode.equals("isolate")) {
            int base = Integer.parseInt(options.getOrDefault("box-id", "0"));
            String rootTemplate = options.getOrDefault("box-root", "/var/lib/isolate/{id}/box");
            sandboxA = isolateSandbox(base, rootTemplate);
            sandboxB = isolateSandbox(base + 1, rootTemplate);
        } else {
            throw new IllegalArgumentException("--sandbox must be isolate or local");
        }

        SymmetricMapGenerator generator = new SymmetricMapGenerator();
        MapConfig config = new MapConfig(8);
        Board board = generator.generate(seed, config);
        List<Piece> pieces = generator.generatePieces(seed);
        pieces.forEach(piece -> board.get(piece.getPosition()).setOccupant(piece));
        var state = new ottv2.engine.model.GameState(board, pieces, seed);
        var publisher = new ottv2.engine.event.GameEventPublisher();
        var replay = new ReplayRecorder("cli-" + seed, seed, config, required(options, "botA"), required(options, "botB"));
        replay.recordInitialState(board, pieces);
        publisher.subscribe(replay);
        var wins = new CompositeWinCondition(List.of(new ReachTargetCondition(), new AnnihilationCondition(), new TurnLimitCondition(TurnLimitCondition.DEFAULT_LIMIT)));
        GameEngine engine = new GameEngineBuilder()
                .withState(state)
                .withPublisher(publisher)
                .withWinCondition(wins)
                .withVisionStrategy((team, game) -> new Observation(
                        game.getPieces().stream().filter(p -> p.isAlive() && p.getTeam() == team).toList(),
                        game.getPieces().stream().filter(p -> p.isAlive() && p.getTeam() != team).toList(),
                        allVisibleCells(game.getBoard())))
                .withCombatResolver(new RpsCombatResolver())
                .build();

        new MatchRunner().run(engine,
                new ProcessBotController(commandA, sandboxA, "A"),
                new ProcessBotController(commandB, sandboxB, "B"), replay);
        String replayJson = replay.getJson();
        Path replayPath = Path.of(options.getOrDefault("replay", "replay-" + seed + ".json"));
        Files.writeString(replayPath, replayJson);
        System.out.println(new ObjectMapper().readTree(replayJson).path("result"));
        System.out.println("Replay: " + replayPath.toAbsolutePath());
    }

    private static IsolateSandbox isolateSandbox(int boxId, String rootTemplate) {
        Path boxRoot = Path.of(rootTemplate.replace("{id}", Integer.toString(boxId)));
        return new IsolateSandbox("isolate", boxId, 200, 600, 256, 16, boxRoot);
    }

    private static List<Observation.CellInfo> allVisibleCells(Board board) {
        List<Observation.CellInfo> cells = new ArrayList<>();
        for (int r = 0; r < Board.SIZE; r++) for (int c = 0; c < Board.SIZE; c++) {
            var position = new ottv2.engine.model.Position(r, c);
            var cell = board.get(position);
            var visibleType = cell.isHidden() ? ottv2.engine.model.CellType.EMPTY : cell.getType();
            cells.add(new Observation.CellInfo(position, visibleType));
        }
        return cells;
    }

    private static List<String> botCommand(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) throw new IllegalArgumentException("Bot path does not exist: " + absolute);
        if (Files.isDirectory(absolute)) {
            Path pythonEntry = absolute.resolve("bot.py");
            if (Files.isRegularFile(pythonEntry)) return List.of("/usr/bin/python3", "-u", pythonEntry.toString());
            Path pythonPackage = absolute.resolve("__main__.py");
            if (Files.isRegularFile(pythonPackage)) return List.of("/usr/bin/python3", "-u", absolute.toString());
            Path jar = absolute.resolve("bot.jar");
            if (Files.isRegularFile(jar)) return javaBotCommand(jar);
            throw new IllegalArgumentException("Bot directory must contain bot.py, __main__.py, or bot.jar: " + absolute);
        }
        String value = absolute.toString();
        if (value.endsWith(".py")) return List.of("/usr/bin/python3", "-u", value);
        if (value.endsWith(".jar")) return javaBotCommand(absolute);
        return List.of(value);
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static List<String> javaBotCommand(Path jar) {
        return List.of(javaExecutable(), "-Xms16m", "-Xmx64m", "-XX:+UseSerialGC",
                "-XX:ReservedCodeCacheSize=16m", "-XX:CompressedClassSpaceSize=32m",
                "-XX:MaxMetaspaceSize=48m", "-Xss256k", "-XX:ActiveProcessorCount=1",
                "-jar", jar.toString());
    }

    private static java.util.Map<String, String> parse(String[] args) {
        var values = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("Expected --name value arguments");
            values.put(key.substring(2), args[++i]);
        }
        return values;
    }

    private static String required(java.util.Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + name);
        return value;
    }
}
