package dev.vishalverma.mnemo.core.error;

/**
 * Base of the engine's error hierarchy. Every instance carries the RESP error code and message
 * separately so the dispatcher can turn it into a reply without parsing anything.
 *
 * <p>Concrete rather than abstract: the named subclasses cover the recurring cases, and this
 * class itself covers the one-off messages ({@code invalid expire time}, overflow) that do not
 * earn a type of their own.
 *
 * <p>Unchecked on purpose. These are protocol-level responses to bad client input, not
 * programming errors, and threading {@code throws} clauses through every command for something
 * the dispatcher catches in exactly one place would be noise.
 */
public class MnemoException extends RuntimeException {

    // Required: -Xlint:serial + -Werror fails the build without it, since Throwable is Serializable.
    private static final long serialVersionUID = 1L;

    private final String code;

    public MnemoException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The RESP error code, e.g. {@code ERR} or {@code WRONGTYPE}. */
    public String code() {
        return code;
    }
}
