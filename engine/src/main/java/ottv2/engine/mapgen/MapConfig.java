package ottv2.engine.mapgen;

public record MapConfig(int obstacles, int traps, int speeds, int mutations) {
    /** Backwards-compatible obstacle-only configuration. */
    public MapConfig(int obstacles) {
        this(obstacles, 0, 0, 0);
    }
}
