package dev.vishalverma.mnemo.protocol;

/**
 * Bounds the decoder will not exceed, and the reason a hostile client cannot exhaust our heap.
 *
 * <p>A record rather than a set of constants so tests can construct a decoder with tiny limits and
 * exercise the rejection paths without allocating anything large. That testability is the whole
 * point — a limit nobody can cheaply test is a limit nobody verifies.
 *
 * @param maxBulkLength      largest accepted {@code $<n>} payload
 * @param maxMultibulkLength largest accepted {@code *<n>} element count
 * @param maxInlineLength    longest accepted inline (non-RESP) command line
 * @param maxBuffer          hard cap on a single connection's accumulated, not-yet-parsed bytes
 */
public record RespLimits(
    int maxBulkLength,
    int maxMultibulkLength,
    int maxInlineLength,
    int maxBuffer) {

    public RespLimits {
        if (maxBulkLength <= 0 || maxMultibulkLength <= 0 || maxInlineLength <= 0 || maxBuffer <= 0) {
            throw new IllegalArgumentException("every RESP limit must be positive");
        }
        if (maxBuffer < maxBulkLength) {
            // Otherwise a legal maximum-size bulk could never be assembled — the accumulator would
            // trip its own cap partway through receiving it.
            throw new IllegalArgumentException("maxBuffer must be at least maxBulkLength");
        }
    }

    /**
     * Redis's defaults, with one deliberate deviation: {@code maxBulkLength} is 64 MB rather than
     * Redis's 512 MB {@code proto-max-bulk-len}. Since the accumulator has to hold a whole bulk
     * before it can be parsed, 512 MB would let a single client dribbling an incomplete command
     * pin half a gigabyte. 64 MB is still far above any realistic value. Recorded as a documented
     * deviation in docs/protocol.md.
     */
    public static RespLimits defaults() {
        int maxBulk = 64 * 1024 * 1024;
        return new RespLimits(maxBulk, 1024 * 1024, 64 * 1024, maxBulk + 64 * 1024);
    }
}
