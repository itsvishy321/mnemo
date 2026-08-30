package dev.vishalverma.mnemo.core.command;

/**
 * How many arguments a command accepts, counting the command name itself — so {@code SET key value}
 * is 3. That matches Redis's {@code argv} convention and keeps the numbers recognisable.
 *
 * <p>Redis encodes this as a signed int where a negative means "at least |n|". That is compact and
 * cryptic; {@code Arity.atLeast(3)} carries the same information and reads as what it means.
 */
public record Arity(int min, int max) {

    public static Arity exactly(int n) {
        return new Arity(n, n);
    }

    public static Arity atLeast(int n) {
        return new Arity(n, Integer.MAX_VALUE);
    }

    public boolean accepts(int argCount) {
        return argCount >= min && argCount <= max;
    }
}
