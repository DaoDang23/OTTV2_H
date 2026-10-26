package ottv2.engine.model;

public enum PieceType {
    SCISSORS, ROCK, PAPER;

    public boolean beats(PieceType other) {
        return (this == SCISSORS && other == PAPER)
            || (this == PAPER && other == ROCK)
            || (this == ROCK && other == SCISSORS);
    }

    public PieceType next() {
        return values()[(ordinal() + 1) % 3];
    }
}
