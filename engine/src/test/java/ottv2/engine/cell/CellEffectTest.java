package ottv2.engine.cell;

import org.junit.jupiter.api.Test;
import ottv2.engine.model.*;
import ottv2.engine.movement.OrthogonalOneStep;
import ottv2.engine.event.*;
import java.util.ArrayList;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CellEffectTest {

    private GameState createDummyState() {
        return new GameState(null, new ArrayList<>(), 123);
    }
    
    private Piece createPiece() {
        return new Piece(1, Team.A, new Position(0,0), PieceType.ROCK, new OrthogonalOneStep());
    }

    @Test
    void testTargetEffect() {
        Piece piece = createPiece();
        TargetEffect effect = new TargetEffect();
        Optional<GameEvent> event = effect.onEnter(piece, createDummyState());
        
        assertTrue(event.isPresent());
        assertTrue(event.get() instanceof TargetReachedEvent);
    }

    @Test
    void testMutationEffectCyclesPieceTypeAndReturnsEvent() {
        Piece piece = createPiece();
        Optional<GameEvent> event = new MutationEffect().onEnter(piece, createDummyState());

        assertEquals(PieceType.PAPER, piece.getType());
        assertTrue(event.isPresent());
        assertTrue(event.get() instanceof PieceMutatedEvent);
    }

    @Test
    void testTrapAndSpeedEffectsAreSafeNoOpsWhileRulesAreDeferredOrCommandHandled() {
        Piece piece = createPiece();
        GameState state = createDummyState();

        assertTrue(new TrapEffect().onEnter(piece, state).isEmpty());
        assertTrue(new SpeedEffect().onEnter(piece, state).isEmpty());
        assertEquals(PieceType.ROCK, piece.getType());
    }
}
