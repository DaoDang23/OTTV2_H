package ottv2.engine.combat;

import ottv2.engine.model.Piece;

public class RpsCombatResolver implements CombatResolver {
    @Override
    public CombatResult resolve(Piece attacker, Piece defender) {
        if (attacker.canCapture(defender)) {
            return CombatResult.ATTACKER_WINS;
        } else if (defender.canCapture(attacker)) {
            return CombatResult.DEFENDER_WINS;
        }
        return CombatResult.DRAW; // Same type
    }
}
