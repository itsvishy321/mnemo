package dev.vishalverma.mnemo.core.error;

/**
 * Thrown when a value that must be a 64-bit integer is not one — either malformed
 * ({@code "abc"}, {@code "01"}, {@code " 1"}) or outside {@code long} range. Redis reports both
 * cases with this single message, so we do too.
 */
public final class NotAnIntegerException extends MnemoException {

    private static final long serialVersionUID = 1L;

    public NotAnIntegerException() {
        super("ERR", "value is not an integer or out of range");
    }
}
