package ottv2.engine.model;

public enum Team {
    A, B;

    public Team opponent() {
        return this == A ? B : A;
    }
}
