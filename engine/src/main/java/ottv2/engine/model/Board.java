package ottv2.engine.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Board {
    public static final int SIZE = 9;
    private final Cell[][] cells;
    private final Set<Position> targets;

    public Board(Cell[][] cells, Set<Position> targets) {
        if (cells.length != SIZE || cells[0].length != SIZE) {
            throw new IllegalArgumentException("Board must be " + SIZE + "x" + SIZE);
        }
        this.cells = cells;
        this.targets = new HashSet<>(targets);
    }

    public Cell get(Position pos) {
        if (!inBounds(pos)) {
            throw new IllegalArgumentException("Position out of bounds: " + pos);
        }
        return cells[pos.row()][pos.col()];
    }

    public boolean inBounds(Position pos) {
        return pos.row() >= 0 && pos.row() < SIZE && pos.col() >= 0 && pos.col() < SIZE;
    }

    public Set<Position> getTargets() {
        return Collections.unmodifiableSet(targets);
    }
}
