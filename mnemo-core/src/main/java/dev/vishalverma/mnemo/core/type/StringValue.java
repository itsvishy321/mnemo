package dev.vishalverma.mnemo.core.type;

/**
 * A binary-safe string value. The only type with commands in M1.
 *
 * <p>Backed by {@link Bytes} rather than {@code byte[]} so equality is by content — which both
 * makes tests meaningful and keeps the record's generated {@code equals} correct.
 */
public record StringValue(Bytes value) implements RedisValue {

    @Override
    public String typeName() {
        return "string";
    }

    @Override
    public int sizeBytes() {
        // Payload plus a rough constant for the object header, the Bytes wrapper, and the array
        // header. M5 replaces the guess with a JOL-calibrated figure.
        return value.length() + 48;
    }
}
