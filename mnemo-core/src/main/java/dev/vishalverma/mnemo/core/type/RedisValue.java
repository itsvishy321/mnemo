package dev.vishalverma.mnemo.core.type;

/**
 * The value stored under a key. Sealed so that adding a type in M4 makes the compiler point at
 * every switch that must learn about it — the same reason {@code Reply} is sealed.
 *
 * <p>Only {@link StringValue} has commands in M1. {@link ListValue} exists as a data-only
 * variant so the {@code WRONGTYPE} path is reachable and testable before M4 arrives.
 */
public sealed interface RedisValue permits StringValue, ListValue {

    /** The name {@code TYPE} reports, e.g. {@code string}. */
    String typeName();

    /**
     * Rough retained size in bytes. Nothing reads this in M1 — it is populated now because
     * retrofitting it later means revisiting every mutation site. M5 calibrates the per-type
     * constants against JOL and feeds them to the memory accountant.
     */
    int sizeBytes();
}
