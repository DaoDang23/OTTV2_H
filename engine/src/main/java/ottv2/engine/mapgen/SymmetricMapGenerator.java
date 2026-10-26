package ottv2.engine.mapgen;

import ottv2.engine.model.*;
import ottv2.engine.piece.PieceFactory;
import ottv2.engine.piece.StandardPieceFactory;
import ottv2.engine.cell.*;
import java.util.*;

public class SymmetricMapGenerator implements MapGenerator {
    private final PieceFactory pieceFactory = new StandardPieceFactory();

    @Override
    public Board generate(long seed, MapConfig config) {
        Random rng = new Random(seed);
        List<Position> startsA = new ArrayList<>();
        List<Position> startsB = new ArrayList<>();
        for (int c = 0; c < Board.SIZE; c++) {
            for (int r = 0; r < 3; r++) startsB.add(new Position(r, c));
            for (int r = Board.SIZE - 3; r < Board.SIZE; r++) startsA.add(new Position(r, c));
        }

        Board board = buildBoard(rng, config);
        MapValidator.ValidationResult result = MapValidator.validate(board, startsA, startsB);
        if (!result.valid()) {
            throw new IllegalStateException("Generated map is invalid: " + result.reason());
        }
        return board;
    }

    @Override
    public List<Piece> generatePieces(long seed) {
        List<Piece> pieces = new ArrayList<>();
        int id = 1;

        // ĐỘI A (Đội Xanh - Ở dưới cùng, đánh lên ô đích I1 [0, 8])
        // Hàng 7 (index 6): Tiền đạo C7 (Kéo), E7 (Búa), G7 (Bao)
        pieces.add(pieceFactory.create(id++, PieceType.SCISSORS, Team.A, new Position(6, 2))); // C7
        pieces.add(pieceFactory.create(id++, PieceType.ROCK, Team.A, new Position(6, 4)));     // E7
        pieces.add(pieceFactory.create(id++, PieceType.PAPER, Team.A, new Position(6, 6)));    // G7
        // Hàng 8 (index 7): Trung tuyến D8 (Bao), E8 (Kéo), F8 (Búa)
        pieces.add(pieceFactory.create(id++, PieceType.PAPER, Team.A, new Position(7, 3)));    // D8
        pieces.add(pieceFactory.create(id++, PieceType.SCISSORS, Team.A, new Position(7, 4))); // E8
        pieces.add(pieceFactory.create(id++, PieceType.ROCK, Team.A, new Position(7, 5)));     // F8
        // Hàng 9 (index 8): Hậu vệ C9 (Búa), E9 (Bao), G9 (Kéo)
        pieces.add(pieceFactory.create(id++, PieceType.ROCK, Team.A, new Position(8, 2)));     // C9
        pieces.add(pieceFactory.create(id++, PieceType.PAPER, Team.A, new Position(8, 4)));    // E9
        pieces.add(pieceFactory.create(id++, PieceType.SCISSORS, Team.A, new Position(8, 6))); // G9

        // ĐỘI B (Đội Đỏ - Ở trên cùng, đánh xuống ô đích A9 [8, 0] - Đối xứng tâm (8-r, 8-c))
        // Hàng 3 (index 2): Tiền đạo G3 (Kéo), E3 (Búa), C3 (Bao)
        pieces.add(pieceFactory.create(id++, PieceType.SCISSORS, Team.B, new Position(2, 6))); // G3
        pieces.add(pieceFactory.create(id++, PieceType.ROCK, Team.B, new Position(2, 4)));     // E3
        pieces.add(pieceFactory.create(id++, PieceType.PAPER, Team.B, new Position(2, 2)));    // C3
        // Hàng 2 (index 1): Trung tuyến F2 (Bao), E2 (Kéo), D2 (Búa)
        pieces.add(pieceFactory.create(id++, PieceType.PAPER, Team.B, new Position(1, 5)));    // F2
        pieces.add(pieceFactory.create(id++, PieceType.SCISSORS, Team.B, new Position(1, 4))); // E2
        pieces.add(pieceFactory.create(id++, PieceType.ROCK, Team.B, new Position(1, 3)));     // D2
        // Hàng 1 (index 0): Hậu vệ G1 (Búa), E1 (Bao), C1 (Kéo)
        pieces.add(pieceFactory.create(id++, PieceType.ROCK, Team.B, new Position(0, 6)));     // G1
        pieces.add(pieceFactory.create(id++, PieceType.PAPER, Team.B, new Position(0, 4)));    // E1
        pieces.add(pieceFactory.create(id++, PieceType.SCISSORS, Team.B, new Position(0, 2))); // C1

        return pieces;
    }

    private Board buildBoard(Random rng, MapConfig config) {
        CellType[][] types = new CellType[Board.SIZE][Board.SIZE];
        for (int r = 0; r < Board.SIZE; r++) {
            Arrays.fill(types[r], CellType.EMPTY);
        }

        // 2 ô đích đến: I1 (0, 8) cho Đội Xanh (Team A) và A9 (8, 0) cho Đội Đỏ (Team B)
        types[0][8] = CellType.TARGET;
        types[8][0] = CellType.TARGET;
        Set<Position> targets = new HashSet<>();
        targets.add(ottv2.engine.rules.ReachTargetCondition.TARGET_A);
        targets.add(ottv2.engine.rules.ReachTargetCondition.TARGET_B);

        Cell[][] cells = new Cell[Board.SIZE][Board.SIZE];
        for (int r = 0; r < Board.SIZE; r++) {
            for (int c = 0; c < Board.SIZE; c++) {
                CellType ct = types[r][c];
                CellEffect ce = (ct == CellType.TARGET) ? new TargetEffect() : new NoEffect();
                cells[r][c] = new Cell(ct, ce, false);
            }
        }

        return new Board(cells, targets);
    }
}