package ottv2.engine.model;

import ottv2.engine.state.GamePhase;
import java.util.List;
import java.util.Random;

public class GameState {
    private final Board board;
    private final List<Piece> pieces;
    private int turn;
    private Team currentTeam;
    private GamePhase phase;
    private final Random rng;
    private final long seed;
    private Integer winnerScore;

    public GameState(Board board, List<Piece> pieces, long seed) {
        this.board = board;
        this.pieces = pieces;
        this.turn = 1;
        this.currentTeam = Team.A;
        this.phase = GamePhase.INIT;
        this.seed = seed;
        this.rng = new Random(seed);
    }

    public Board getBoard() { return board; }
    public List<Piece> getPieces() { return pieces; }
    public int getTurn() { return turn; }
    public void incrementTurn() { this.turn++; }
    public Team getCurrentTeam() { return currentTeam; }
    public void setCurrentTeam(Team team) { this.currentTeam = team; }
    public GamePhase getPhase() { return phase; }
    public void setPhase(GamePhase phase) { this.phase = phase; }
    public Random getRng() { return rng; }
    public long getSeed() { return seed; }
    
    public Integer getWinnerScore() { return winnerScore; }
    public void setWinnerScore(Integer score) { this.winnerScore = score; }

    public Piece getPieceById(int id) {
        for (Piece p : pieces) {
            if (p.getId() == id) {
                return p;
            }
        }
        return null;
    }
}
