package baritone.api.utils;

/** Immutable pair replacing the Minecraft Tuple removed in 26.2. */
public record Tuple<A, B>(A a, B b) {
    public A getA() { return a; }
    public B getB() { return b; }
}
