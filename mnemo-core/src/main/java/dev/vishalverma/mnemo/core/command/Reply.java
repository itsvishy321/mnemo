package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.type.Bytes;

import java.util.List;

/**
 * What a command returns, shaped to map one-to-one onto RESP2 reply types.
 *
 * <p>A sealed interface of records, which is the point: the encoder M2 adds becomes an exhaustive
 * {@code switch} with no {@code default} branch, so adding a reply type makes the compiler list
 * every site that must handle it.
 *
 * <p>{@link Bulk} carries {@link Bytes} rather than a raw {@code byte[]} — a record with an array
 * component inherits identity equality, which would silently break every assertion comparing two
 * replies. {@code Bytes.array()} still gives the encoder zero-copy access.
 */
public sealed interface Reply {

    /** {@code +OK} — a simple status string. */
    record Simple(String value) implements Reply { }

    /** {@code -WRONGTYPE ...} — an error code plus message. */
    record Err(String code, String message) implements Reply { }

    /** {@code :42} — an integer. */
    record Int(long value) implements Reply { }

    /** {@code $3\r\nfoo} — a binary-safe bulk string. */
    record Bulk(Bytes payload) implements Reply { }

    /** {@code *2\r\n...} — an array of replies. */
    record Arr(List<Reply> items) implements Reply { }

    /** {@code $-1} — the null bulk string. */
    record Nil() implements Reply { }

    Reply OK = new Simple("OK");
    Reply NIL = new Nil();

    /** {@code null} payload becomes {@link #NIL} — the shape almost every read command needs. */
    static Reply bulkOrNil(Bytes payload) {
        return payload == null ? NIL : new Bulk(payload);
    }
}
