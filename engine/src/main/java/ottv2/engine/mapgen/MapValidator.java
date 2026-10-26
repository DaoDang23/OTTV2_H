package ottv2.engine.mapgen;

import ottv2.engine.model.Board;
import ottv2.engine.model.Cell;
import ottv2.engine.model.CellType;
import ottv2.engine.model.Position;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.List;

public class MapValidator {

    public record ValidationResult(boolean valid, String reason) {}

    public static ValidationResult validate(Board board, List<Position> teamAStarts, List<Position> teamBStarts) {
        // 1. Point Symmetry
        for (int r = 0; r < Board.SIZE; r++) {
            for (int c = 0; c < Board.SIZE; c++) {
                Cell cell1 = board.get(new Position(r, c));
                Cell cell2 = board.get(new Position(Board.SIZE - 1 - r, Board.SIZE - 1 - c));
                if (cell1.getType() != cell2.getType()) {
                    return new ValidationResult(false, "Map is not symmetric at " + r + "," + c);
                }
            }
        }

        // 2. BFS for connectivity and equal distance
        Set<Position> targetA = Set.of(ottv2.engine.rules.ReachTargetCondition.TARGET_A);
        Set<Position> targetB = Set.of(ottv2.engine.rules.ReachTargetCondition.TARGET_B);
        int minDistanceA = getMinDistance(board, teamAStarts, targetA);
        int minDistanceB = getMinDistance(board, teamBStarts, targetB);

        if (minDistanceA == -1 || minDistanceB == -1) {
            return new ValidationResult(false, "Targets are unreachable from one or both start zones.");
        }

        if (minDistanceA != minDistanceB) {
            return new ValidationResult(false, "Unfair map: distance A=" + minDistanceA + ", B=" + minDistanceB);
        }

        return new ValidationResult(true, "OK");
    }

    private static int getMinDistance(Board board, List<Position> starts, Set<Position> targets) {
        Queue<Position> queue = new LinkedList<>(starts);
        Set<Position> visited = new HashSet<>(starts);
        int dist = 0;

        while (!queue.isEmpty()) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                Position p = queue.poll();
                if (targets.contains(p)) {
                    return dist;
                }

                for (Position n : p.neighbors()) {
                    if (board.inBounds(n) && !visited.contains(n)) {
                        if (board.get(n).getType() != CellType.OBSTACLE) {
                            visited.add(n);
                            queue.add(n);
                        }
                    }
                }
            }
            dist++;
        }
        return -1;
    }
}
