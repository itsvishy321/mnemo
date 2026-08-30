package dev.vishalverma.mnemo.core.error;

/**
 * Thrown when a command operates on a key holding a different value type — {@code GET} on a list,
 * {@code INCR} on a hash. The message is byte-for-byte what real Redis sends, because clients
 * match on it.
 */
public final class WrongTypeException extends MnemoException {

    private static final long serialVersionUID = 1L;

    public WrongTypeException() {
        super("WRONGTYPE", "Operation against a key holding the wrong kind of value");
    }
}
