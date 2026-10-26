# 02 - Game Engine: Class Diagram, Pattern, Giao thức bot, Replay

## Package
```
ottv2.engine
├── model    : Board, Cell, Position, Piece, Move, GameState, Team
├── cell     : CellEffect (Strategy)
├── piece    : PieceType, PieceFactory (Factory)
├── movement : MovementStrategy (Strategy)
├── combat   : CombatResolver (Strategy)
├── vision   : VisionStrategy (Strategy) -> fog of war
├── rules    : WinCondition (Strategy + Composite), MoveValidator
├── mapgen   : MapGenerator (Builder + Template Method), MapValidator
├── event    : GameEvent, GameEventPublisher, GameEventListener (Observer)
├── command  : GameCommand, MoveCommand, PassCommand (Command)
├── state    : GamePhase (State)
├── bot      : BotController (Adapter) -> ProcessBotController, ScriptedBotController, RandomBotController
└── core     : GameEngine (Facade), MatchRunner (Template Method)
```

## Class diagram (text)
```
«enum» PieceType { SCISSORS, ROCK, PAPER;  beats(other): boolean;  next(): PieceType }
«enum» CellType  { EMPTY, OBSTACLE, TRAP, SPEED, MUTATION, TARGET }
«enum» Team      { A, B;  opponent() }

Position (record): row, col; neighbors()
Move (record): pieceId, Position to

Board 1 ◆──► 81 Cell
  Board: cells Cell[9][9], targets Set<Position>; get(Position), inBounds(Position)
  Cell : type, effect: CellEffect, hidden (bẫy ẩn), occupant: Piece?

Piece: id, team, position, alive, type: PieceType, movement: MovementStrategy
       mutate(), canCapture(Piece)
  -> Dùng 1 class Piece + enum PieceType + Strategy (để ô biến dị đổi type lúc runtime)

«interface» PieceFactory { create(PieceType, Team, Position): Piece }
   ├── StandardPieceFactory
   └── ConfigurablePieceFactory (loadout từ config/mode)

«interface» MovementStrategy { List<Position> legalMoves(Piece, Board, GameState) }
   ├── OrthogonalOneStep (4 hướng, 1 ô; mặc định)
   ├── OrthogonalWithSlide (tính ô SPEED)
   └── DiagonalStep (chế độ mở rộng)

«interface» CellEffect { EffectResult onEnter(Piece, GameState) }
   ├── NoEffect
   ├── TrapEffect
   ├── SpeedEffect (bonus move)
   ├── MutationEffect (piece.mutate())
   └── TargetEffect

«interface» CombatResolver { CombatResult resolve(Piece attacker, Piece defender) }
   ├── RpsCombatResolver (Kéo>Bao>Búa>Kéo; cùng hệ -> bị chặn)
   └── HpCombatResolver (mở rộng)

«interface» VisionStrategy { Observation observe(Team, GameState) }
   ├── FullVision
   └── RadiusFogVision(radius)

«interface» WinCondition { Optional<Outcome> check(GameState) }
   ├── ReachTargetCondition
   ├── AnnihilationCondition
   ├── TurnLimitCondition (tính điểm khi hết lượt)
   └── CompositeWinCondition (kiểm tra theo thứ tự ưu tiên)

GameState: board, pieces, turn, currentTeam, rng: SeededRandom, phase
           snapshot(), apply(GameCommand): List<GameEvent>

«interface» GameCommand { validate(GameState): ValidationResult; execute(GameState): List<GameEvent> }
   ├── MoveCommand(pieceId, to) -> MovementStrategy -> CombatResolver -> CellEffect
   └── PassCommand (bot timeout/lỗi nhẹ)

«interface» GameEventListener { onEvent(GameEvent) }
GameEventPublisher: subscribe/unsubscribe/publish
   -> ReplayRecorder, SpectatorBroadcaster, MatchStatsCollector, AuditLogger
GameEvent = PieceMoved | PieceCaptured | PieceMutated | TrapTriggered
          | TargetReached | TurnStarted | BotTimeout | GameEnded

«interface» MapGenerator { Board generate(long seed, MapConfig) }
   ├── SymmetricMapGenerator (Template: placeObstacles -> placeSpecials -> mirror -> validate)
   └── FixedMapGenerator (map thủ công cho chung kết/test)
MapValidator: BFS connectivity + đối xứng + công bằng đường đi

«interface» BotController {              // Adapter
    void init(MatchInfo);
    Move requestMove(Observation, Duration timeLimit) throws BotFailure;
    void shutdown();
}

GameEngine (Facade): state, publisher, rules, vision, combat, validator
                     create(seed, config) qua GameEngineBuilder; step(BotController): StepResult

MatchRunner (Template Method): run(GameEngine, BotController a, b): MatchResult
   loop: observe -> requestMove -> validate -> execute -> publish -> checkWin
   xử lý timeout / crash / illegal move theo "chính sách phạt"
```

## Ánh xạ Design Pattern

| Pattern | Áp dụng | Lợi ích |
|---|---|---|
| Strategy | MovementStrategy, CombatResolver, VisionStrategy, WinCondition, CellEffect, MatchmakingStrategy, RatingCalculator | Thêm luật mới không sửa engine |
| Factory | PieceFactory, CellEffectFactory, BotControllerFactory | Gom logic khởi tạo |
| Observer | GameEventPublisher -> Replay, Spectator, Stats | Engine không biết ai nghe |
| Command | MoveCommand, PassCommand | Validate, log, replay |
| Builder | GameEngineBuilder, MapConfig, BracketBuilder | Cấu hình nhiều tham số |
| Template Method | MapGenerator, MatchRunner | Khung cố định, bước thay đổi được |
| Adapter | BotController | Ẩn chi tiết process/pipe |
| Facade | GameEngine, MatchRunner | API gọn cho worker |
| State | GamePhase, MatchStatus | Tránh if rải rác |
| Composite | CompositeWinCondition | Ghép nhiều điều kiện thắng |

## Code mẫu
```java
public enum PieceType {
    SCISSORS, ROCK, PAPER;
    public boolean beats(PieceType o) {
        return (this == SCISSORS && o == PAPER)
            || (this == PAPER    && o == ROCK)
            || (this == ROCK     && o == SCISSORS);
    }
    public PieceType next() { return values()[(ordinal() + 1) % 3]; }
}

public interface CellEffect { EffectResult onEnter(Piece piece, GameState state); }

public final class MutationEffect implements CellEffect {
    public EffectResult onEnter(Piece p, GameState s) {
        PieceType before = p.type();
        p.mutate();
        return EffectResult.of(new PieceMutated(p.id(), before, p.type()));
    }
}

publisher.subscribe(new ReplayRecorder(matchId));
publisher.subscribe(new SpectatorBroadcaster(ws));
```

## Giao thức bot (stdin/stdout, mỗi dòng một JSON)
```
Referee -> Bot (mỗi lượt):
{"turn":12,"you":"A","yourPieces":[{"id":3,"type":"ROCK","r":4,"c":2}],
 "visibleEnemies":[{"id":7,"type":"PAPER","r":5,"c":3}],
 "visibleCells":[...], "targets":[[8,4]], "timeLeftMs":1800}

Bot -> Referee:
{"pieceId":3,"to":[5,2]}
```
Lượt đầu referee gửi thêm bản đồ terrain tĩnh. Mỗi ngôn ngữ có thư viện mẫu để sinh viên chỉ viết hàm `decide(state) -> move`.

## Replay format
```json
{ "version": 1, "matchId": "...", "seed": 918273645, "mapConfig": {},
  "teams": {"A": "botId1", "B": "botId2"},
  "turns": [ {"t":1,"team":"A","move":{"p":3,"to":[5,2]},
              "events":[{"type":"CAPTURE","by":3,"victim":7}] } ],
  "result": {"winner":"A","reason":"TARGET_REACHED"},
  "botLogs": {"A":"stderr gzip..."} }
```
Lưu `.json.gz` trên MinIO, DB chỉ giữ metadata. Client dựng lại trận từ seed + nước đi.
