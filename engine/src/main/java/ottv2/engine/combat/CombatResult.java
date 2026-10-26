package ottv2.engine.combat;

public enum CombatResult {
    ATTACKER_WINS,
    DEFENDER_WINS,
    DRAW // Both die or bounce back (currently not possible with basic RPS unless same type encounters)
}
