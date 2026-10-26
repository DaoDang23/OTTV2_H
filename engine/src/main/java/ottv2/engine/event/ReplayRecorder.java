package ottv2.engine.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ottv2.engine.model.Move;
import ottv2.engine.model.Board;
import ottv2.engine.model.Piece;
import ottv2.engine.model.Position;
import ottv2.engine.model.Team;
import java.util.ArrayList;
import java.util.List;

public class ReplayRecorder implements GameEventListener {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode replayNode = mapper.createObjectNode();
    private final ArrayNode turnsArray = mapper.createArrayNode();
    
    private int currentTurn;
    private Team currentTeam;
    private Move currentMove;
    private final List<GameEvent> currentEvents = new ArrayList<>();

    public ReplayRecorder(String matchId, long seed, Object mapConfig, String botA, String botB) {
        replayNode.put("version", 1);
        replayNode.put("matchId", matchId);
        replayNode.put("seed", seed);
        replayNode.set("mapConfig", mapper.valueToTree(mapConfig));
        
        ObjectNode teamsNode = mapper.createObjectNode();
        teamsNode.put("A", botA);
        teamsNode.put("B", botB);
        replayNode.set("teams", teamsNode);
        replayNode.set("turns", turnsArray);
        replayNode.set("moves", turnsArray);
    }

    public void recordTurnStart(int turn, Team team, Move move) {
        this.currentTurn = turn;
        this.currentTeam = team;
        this.currentMove = move;
        this.currentEvents.clear();
    }

    /** Stores the deterministic starting snapshot so a viewer need not reimplement map generation. */
    public void recordInitialState(Board board, List<Piece> pieces) {
        ArrayNode cells = mapper.createArrayNode();
        for (int row = 0; row < Board.SIZE; row++) {
            ArrayNode line = cells.addArray();
            for (int col = 0; col < Board.SIZE; col++) {
                line.add(board.get(new Position(row, col)).getType().name());
            }
        }
        replayNode.set("initialBoard", cells);
        ArrayNode initialPieces = mapper.createArrayNode();
        for (Piece piece : pieces) {
            ObjectNode item = initialPieces.addObject();
            item.put("id", piece.getId());
            item.put("team", piece.getTeam().name());
            item.put("type", piece.getType().name());
            item.put("row", piece.getPosition().row());
            item.put("col", piece.getPosition().col());
        }
        replayNode.set("initialPieces", initialPieces);
    }

    @Override
    public void onEvent(GameEvent event) {
        if (event instanceof GameEndedEvent gee) {
            ObjectNode resultNode = mapper.createObjectNode();
            resultNode.put("winner", gee.winner() == null ? "DRAW" : gee.winner().name());
            resultNode.put("reason", gee.reason());
            replayNode.set("result", resultNode);
        } else {
            currentEvents.add(event);
        }
    }

    public void recordTurnEnd() {
        if (currentMove != null) {
            ObjectNode turnNode = mapper.createObjectNode();
            turnNode.put("t", currentTurn);
            turnNode.put("team", currentTeam.name());
            
            ObjectNode moveNode = mapper.createObjectNode();
            moveNode.put("p", currentMove.pieceId());
            ArrayNode toNode = mapper.createArrayNode();
            toNode.add(currentMove.to().row()).add(currentMove.to().col());
            moveNode.set("to", toNode);
            turnNode.set("move", moveNode);
            
            ArrayNode eventsArray = mapper.createArrayNode();
            for (GameEvent e : currentEvents) {
                eventsArray.add(eventJson(e));
            }
            turnNode.set("events", eventsArray);
            turnsArray.add(turnNode);
        }
    }

    private ObjectNode eventJson(GameEvent event) {
        ObjectNode json = mapper.createObjectNode();
        if (event instanceof PieceMovedEvent moved) {
            json.put("type", "MOVED");
            json.put("pieceId", moved.pieceId());
            json.set("from", mapper.valueToTree(moved.from()));
            json.set("to", mapper.valueToTree(moved.to()));
        } else if (event instanceof PieceCapturedEvent captured) {
            json.put("type", "CAPTURED");
            json.put("attackerId", captured.attackerId());
            json.put("victimId", captured.victimId());
        } else if (event instanceof PieceMutatedEvent mutated) {
            json.put("type", "MUTATED");
            json.put("pieceId", mutated.pieceId());
            json.put("from", mutated.from().name());
            json.put("to", mutated.to().name());
        } else if (event instanceof TargetReachedEvent reached) {
            json.put("type", "TARGET_REACHED");
            json.put("pieceId", reached.pieceId());
        } else {
            json.put("type", event.getClass().getSimpleName());
        }
        return json;
    }

    public String getJson() {
        try {
            return mapper.writeValueAsString(replayNode);
        } catch (Exception e) {
            return "{}";
        }
    }
}
