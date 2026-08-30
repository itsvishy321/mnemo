package dev.vishalverma.mnemo.core.error;

/**
 * Thrown for a malformed option list: an unknown token, a contradictory pair such as
 * {@code NX} with {@code XX}, or an option whose required argument is missing.
 */
public final class SyntaxException extends MnemoException {

    private static final long serialVersionUID = 1L;

    public SyntaxException() {
        super("ERR", "syntax error");
    }
}
