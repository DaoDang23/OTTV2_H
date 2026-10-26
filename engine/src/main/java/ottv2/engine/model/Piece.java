package ottv2.engine.model;

import ottv2.engine.movement.MovementStrategy;

public class Piece {
    private final int id;
    private final Team team;
    private Position position;
    private boolean alive;
    private PieceType type;
    private MovementStrategy movement;

    public Piece(int id, Team team, Position position, PieceType type, MovementStrategy movement) {
        this.id = id;
        this.team = team;
        this.position = position;
        this.type = type;
        this.movement = movement;
        this.alive = true;
    }

    public int getId() { return id; }
    public Team getTeam() { return team; }
    public Position getPosition() { return position; }
    public void setPosition(Position position) { this.position = position; }
    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
    public PieceType getType() { return type; }
    public MovementStrategy getMovement() { return movement; }

    public void mutate() {
        this.type = this.type.next();
    }

    public boolean canCapture(Piece other) {
        return this.type.beats(other.getType());
    }
}
