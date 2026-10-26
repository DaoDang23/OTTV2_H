package ottv2.engine.model;

import ottv2.engine.cell.CellEffect;

public class Cell {
    private final CellType type;
    private final CellEffect effect;
    private final boolean hidden;
    private Piece occupant;

    public Cell(CellType type, CellEffect effect, boolean hidden) {
        this.type = type;
        this.effect = effect;
        this.hidden = hidden;
        this.occupant = null;
    }

    public CellType getType() { return type; }
    public CellEffect getEffect() { return effect; }
    public boolean isHidden() { return hidden; }
    public Piece getOccupant() { return occupant; }
    public void setOccupant(Piece occupant) { this.occupant = occupant; }
}
