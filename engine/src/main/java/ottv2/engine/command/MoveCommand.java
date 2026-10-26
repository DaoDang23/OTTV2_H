package ottv2.engine.command;

import ottv2.engine.model.*;
import ottv2.engine.event.*;
import ottv2.engine.combat.CombatResolver;
import ottv2.engine.combat.CombatResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MoveCommand implements GameCommand {
    private final int pieceId;
    private final Position to;
    private final CombatResolver combatResolver;

    public MoveCommand(int pieceId, Position to, CombatResolver combatResolver) {
        this.pieceId = pieceId;
        this.to = to;
        this.combatResolver = combatResolver;
    }

    @Override
    public ValidationResult validate(GameState state) {
        if (state.getPhase() != ottv2.engine.state.GamePhase.ONGOING) {
            return ValidationResult.invalid("Game is not ongoing.");
        }
        Piece piece = state.getPieceById(pieceId);
        if (piece == null || !piece.isAlive()) {
            return ValidationResult.invalid("Piece does not exist or is dead.");
        }
        if (piece.getTeam() != state.getCurrentTeam()) {
            return ValidationResult.invalid("Not your turn or not your piece.");
        }
        
        List<Position> legalMoves = piece.getMovement().legalMoves(piece, state.getBoard(), state);
        if (!legalMoves.contains(to)) {
            return ValidationResult.invalid("Illegal move destination.");
        }
        
        return ValidationResult.valid();
    }

    @Override
    public List<GameEvent> execute(GameState state) {
        List<GameEvent> events = new ArrayList<>();
        Piece piece = state.getPieceById(pieceId);
        Position from = piece.getPosition();
        Board board = state.getBoard();

        // Remove from old cell
        board.get(from).setOccupant(null);

        // Move to new cell
        piece.setPosition(to);
        events.add(new PieceMovedEvent(pieceId, from, to));

        Cell targetCell = board.get(to);
        Piece occupant = targetCell.getOccupant();

        boolean pieceSurvives = true;

        // Combat
        if (occupant != null) {
            CombatResult result = combatResolver.resolve(piece, occupant);
            if (result == CombatResult.ATTACKER_WINS) {
                occupant.setAlive(false);
                events.add(new PieceCapturedEvent(piece.getId(), occupant.getId()));
            } else if (result == CombatResult.DEFENDER_WINS) {
                piece.setAlive(false);
                pieceSurvives = false;
                events.add(new PieceCapturedEvent(occupant.getId(), piece.getId()));
            } else {
                // Draw - both survive or both die? Docs say "Không được đi vào ô đó" -> Validated away in MovementStrategy
                // So this should not happen.
            }
        }

        // Apply CellEffect if survived
        if (pieceSurvives) {
            targetCell.setOccupant(piece);
            Optional<GameEvent> effectEvent = targetCell.getEffect().onEnter(piece, state);
            effectEvent.ifPresent(events::add);

            // SPEED is resolved here because the slide depends on the move direction.
            // SpeedEffect intentionally does not apply a second movement.
            if (targetCell.getType() == CellType.SPEED) {
                int dr = to.row() - from.row();
                int dc = to.col() - from.col();
                Position nextPos = new Position(to.row() + dr, to.col() + dc);
                
                if (board.inBounds(nextPos) && board.get(nextPos).getType() != CellType.OBSTACLE) {
                    Piece nextOccupant = board.get(nextPos).getOccupant();
                    if (nextOccupant == null || nextOccupant.getTeam() != piece.getTeam()) {
                        if (nextOccupant == null || nextOccupant.getType() != piece.getType()) {
                            // Slide!
                            board.get(to).setOccupant(null);
                            piece.setPosition(nextPos);
                            events.add(new PieceMovedEvent(pieceId, to, nextPos));
                            
                            Cell nextCell = board.get(nextPos);
                            Piece nextOcc = nextCell.getOccupant();
                            boolean slideSurvives = true;
                            if (nextOcc != null) {
                                CombatResult slideRes = combatResolver.resolve(piece, nextOcc);
                                if (slideRes == CombatResult.ATTACKER_WINS) {
                                    nextOcc.setAlive(false);
                                    events.add(new PieceCapturedEvent(piece.getId(), nextOcc.getId()));
                                } else if (slideRes == CombatResult.DEFENDER_WINS) {
                                    piece.setAlive(false);
                                    slideSurvives = false;
                                    events.add(new PieceCapturedEvent(nextOcc.getId(), piece.getId()));
                                }
                            }
                            if (slideSurvives) {
                                nextCell.setOccupant(piece);
                                Optional<GameEvent> slideEffect = nextCell.getEffect().onEnter(piece, state);
                                slideEffect.ifPresent(events::add);
                            }
                        }
                    }
                }
            }
        }

        return events;
    }
}
