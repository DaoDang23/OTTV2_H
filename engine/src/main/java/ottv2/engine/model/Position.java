package ottv2.engine.model;

import java.util.ArrayList;
import java.util.List;

public record Position(int row, int col) {
    public List<Position> neighbors() {
        List<Position> list = new ArrayList<>();
        list.add(new Position(row - 1, col));
        list.add(new Position(row + 1, col));
        list.add(new Position(row, col - 1));
        list.add(new Position(row, col + 1));
        return list;
    }

    public int chebyshevDistance(Position other) {
        return Math.max(Math.abs(this.row - other.row), Math.abs(this.col - other.col));
    }
}
