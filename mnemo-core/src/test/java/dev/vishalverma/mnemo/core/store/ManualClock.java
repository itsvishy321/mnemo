package dev.vishalverma.mnemo.core.store;

/**
 * A clock the test moves by hand.
 *
 * <p>Six lines that replace every {@code Thread.sleep} in the TTL tests — and strictly better
 * than sleeping, because the result is deterministic rather than merely probable. Awaitility
 * would not help here either: there is nothing asynchronous to await, only a decision about what
 * "now" means.
 */
public final class ManualClock implements Clock {

    // A fixed, plausible wall-clock instant so failures read as real timestamps.
    private long nowMs = 1_700_000_000_000L;

    @Override
    public long nowMs() {
        return nowMs;
    }

    public void advance(long millis) {
        nowMs += millis;
    }
}
