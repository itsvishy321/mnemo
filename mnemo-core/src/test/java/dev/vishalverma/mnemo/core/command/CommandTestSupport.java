package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.Engine;
import dev.vishalverma.mnemo.core.store.ManualClock;
import dev.vishalverma.mnemo.core.type.Bytes;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives commands the way a client would — through {@link Engine#dispatch}, so every test also
 * exercises name lookup, arity checking, and exception-to-reply mapping.
 */
abstract class CommandTestSupport {

    protected final ManualClock clock = new ManualClock();
    protected final Engine engine = new Engine(clock);

    protected Reply run(String... args) {
        List<Bytes> argv = Arrays.stream(args).map(Bytes::of).toList();
        return engine.dispatch(argv);
    }

    protected static Bytes bytes(String s) {
        return Bytes.of(s);
    }

    /** Asserts a bulk reply carrying exactly this text. */
    protected static void assertBulk(Reply reply, String expected) {
        assertThat(reply).isEqualTo(new Reply.Bulk(Bytes.of(expected)));
    }

    protected static void assertInt(Reply reply, long expected) {
        assertThat(reply).isEqualTo(new Reply.Int(expected));
    }

    protected static void assertSimple(Reply reply, String expected) {
        assertThat(reply).isEqualTo(new Reply.Simple(expected));
    }

    protected static void assertOk(Reply reply) {
        assertThat(reply).isEqualTo(Reply.OK);
    }

    protected static void assertError(Reply reply, String code, String message) {
        assertThat(reply).isEqualTo(new Reply.Err(code, message));
    }

    protected static void assertWrongType(Reply reply) {
        assertError(reply, "WRONGTYPE", "Operation against a key holding the wrong kind of value");
    }
}
