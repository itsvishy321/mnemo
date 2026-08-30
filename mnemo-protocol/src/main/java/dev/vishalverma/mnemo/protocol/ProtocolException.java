package dev.vishalverma.mnemo.protocol;

/**
 * A malformed or oversized RESP stream.
 *
 * <p>Distinct from a bad <em>command</em>, which {@code Engine.dispatch} turns into an error reply
 * while keeping the connection alive. A protocol violation means the byte stream itself can no
 * longer be trusted to frame correctly, so the only safe response is to close the connection —
 * callers must treat it that way.
 *
 * <p>Unchecked, matching {@code MnemoException} in core: these describe bad input, not programming
 * errors, and threading {@code throws} clauses through a parse loop the caller already wraps would
 * be noise.
 */
public final class ProtocolException extends RuntimeException {

    // -Xlint:serial + -Werror: Throwable is Serializable, so this is required to compile.
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
