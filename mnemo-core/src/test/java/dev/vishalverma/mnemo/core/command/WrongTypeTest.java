package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.type.Bytes;
import dev.vishalverma.mnemo.core.type.ListValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every string command must refuse a key holding another type, with the identical error.
 *
 * <p>The list is planted directly through the keyspace because no command creates one until M4 —
 * which is exactly why {@code ListValue} ships in M1: without a second type, this criterion could
 * not be tested at all.
 */
class WrongTypeTest extends CommandTestSupport {

    @BeforeEach
    void plantAList() {
        engine.keyspace().set(bytes("list"), new ListValue(List.of(Bytes.of("a"))));
    }

    @Test
    void getRefusesANonString() {
        assertWrongType(run("GET", "list"));
    }

    @Test
    void strlenRefusesANonString() {
        assertWrongType(run("STRLEN", "list"));
    }

    @Test
    void appendRefusesANonString() {
        assertWrongType(run("APPEND", "list", "x"));
    }

    @Test
    void incrRefusesANonString() {
        assertWrongType(run("INCR", "list"));
    }

    @Test
    void decrByRefusesANonString() {
        assertWrongType(run("DECRBY", "list", "1"));
    }

    @Test
    void getDelRefusesANonStringWithoutDeletingIt() {
        assertWrongType(run("GETDEL", "list"));

        // The refusal must not have destroyed the value.
        assertThat(engine.keyspace().exists(bytes("list"))).isTrue();
    }

    @Test
    void setWithGetRefusesANonString() {
        assertWrongType(run("SET", "list", "v", "GET"));
    }

    /** Type-agnostic commands must keep working on any type. */
    @Test
    void typeAgnosticCommandsStillWork() {
        assertSimple(run("TYPE", "list"), "list");
        assertInt(run("EXISTS", "list"), 1);
        assertInt(run("DEL", "list"), 1);
    }

    /** MGET is defined never to fail partway, so an odd type yields nil rather than an error. */
    @Test
    void mgetReturnsNilForANonStringRatherThanFailing() {
        run("SET", "str", "v");

        assertThat(run("MGET", "str", "list")).isEqualTo(new Reply.Arr(List.of(
            new Reply.Bulk(Bytes.of("v")),
            Reply.NIL)));
    }

    /** A plain SET overwrites whatever was there, whatever its type. */
    @Test
    void plainSetOverwritesAnyType() {
        assertOk(run("SET", "list", "now-a-string"));
        assertBulk(run("GET", "list"), "now-a-string");
    }
}
