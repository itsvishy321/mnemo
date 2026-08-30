package dev.vishalverma.mnemo.core.type;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A binary-safe byte string with value-based equality — the storage primitive for keys, string
 * values, and bulk reply payloads alike.
 *
 * <p>This class exists for one reason: a raw {@code byte[]} uses <em>identity</em>
 * {@code equals}/{@code hashCode}, so using one as a {@code HashMap} key silently fails every
 * lookup. Redis keys and values are binary safe — arbitrary bytes including embedded {@code \0}
 * and invalid UTF-8 — so storing them as {@code String} is not an option either.
 *
 * <p><strong>Ownership:</strong> {@link #wrap} takes ownership of the array; the caller must not
 * mutate it afterwards. Skipping the defensive copy is deliberate — copying on construction
 * <em>and</em> on read would double the allocation cost of every value, and a {@code GET} of a
 * large value would copy it needlessly. Every producer here (the REPL tokenizer today, the RESP
 * decoder in M2) hands over a freshly allocated array. Use {@link #copyOf} when that is not true.
 */
public final class Bytes {

    public static final Bytes EMPTY = wrap(new byte[0]);

    private final byte[] value;

    /**
     * Cached the same way {@link String} caches its hash: a plain non-volatile field. The race is
     * benign — two threads racing compute the identical value and int writes are atomic. A value
     * that genuinely hashes to 0 is simply recomputed each time, which is correct if not optimal.
     */
    private int hash;

    private Bytes(byte[] value) {
        this.value = value;
    }

    /** Wraps without copying. The array becomes ours — do not mutate it afterwards. */
    public static Bytes wrap(byte[] value) {
        return new Bytes(value);
    }

    /** Copies defensively. For callers that must retain their own array. */
    public static Bytes copyOf(byte[] value) {
        return new Bytes(value.clone());
    }

    /** For command names, integer rendering, and tests — never for round-tripping user data. */
    public static Bytes of(String text) {
        return new Bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The backing array, exposed so encoders can write it without copying. Do not mutate. */
    public byte[] array() {
        return value;
    }

    public int length() {
        return value.length;
    }

    @Override
    public boolean equals(Object o) {
        // Arrays.equals is a JIT intrinsic (vectorised) — a hand-rolled loop would be slower.
        return o instanceof Bytes other && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0 && value.length > 0) {
            h = Arrays.hashCode(value);
            hash = h;
        }
        return h;
    }

    /**
     * Lossy: invalid UTF-8 becomes replacement characters. For logs and REPL display only —
     * never use this to round-trip stored data.
     */
    @Override
    public String toString() {
        return new String(value, StandardCharsets.UTF_8);
    }
}
