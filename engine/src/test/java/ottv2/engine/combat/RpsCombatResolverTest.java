package ottv2.engine.combat;

import org.junit.jupiter.api.Test;
import ottv2.engine.model.*;
import ottv2.engine.movement.OrthogonalOneStep;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RpsCombatResolverTest {
    private final CombatResolver resolver = new RpsCombatResolver();
    
    private Piece createPiece(int id, PieceType type, Team team) {
        return new Piece(id, team, new Position(0,0), type, new OrthogonalOneStep());
    }

    @Test
    void testScissorsBeatsPaper() {
        Piece attacker = createPiece(1, PieceType.SCISSORS, Team.A);
        Piece defender = createPiece(2, PieceType.PAPER, Team.B);
        assertEquals(CombatResult.ATTACKER_WINS, resolver.resolve(attacker, defender));
        assertEquals(CombatResult.DEFENDER_WINS, resolver.resolve(defender, attacker));
    }

    @Test
    void testPaperBeatsRock() {
        Piece attacker = createPiece(1, PieceType.PAPER, Team.A);
        Piece defender = createPiece(2, PieceType.ROCK, Team.B);
        assertEquals(CombatResult.ATTACKER_WINS, resolver.resolve(attacker, defender));
        assertEquals(CombatResult.DEFENDER_WINS, resolver.resolve(defender, attacker));
    }

    @Test
    void testRockBeatsScissors() {
        Piece attacker = createPiece(1, PieceType.ROCK, Team.A);
        Piece defender = createPiece(2, PieceType.SCISSORS, Team.B);
        assertEquals(CombatResult.ATTACKER_WINS, resolver.resolve(attacker, defender));
        assertEquals(CombatResult.DEFENDER_WINS, resolver.resolve(defender, attacker));
    }

    @Test
    void testDraw() {
        Piece p1 = createPiece(1, PieceType.ROCK, Team.A);
        Piece p2 = createPiece(2, PieceType.ROCK, Team.B);
        assertEquals(CombatResult.DRAW, resolver.resolve(p1, p2));
    }
}
