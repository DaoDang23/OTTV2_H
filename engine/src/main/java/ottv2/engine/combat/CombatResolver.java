package ottv2.engine.combat;

import ottv2.engine.model.Piece;

public interface CombatResolver {
    CombatResult resolve(Piece attacker, Piece defender);
}
