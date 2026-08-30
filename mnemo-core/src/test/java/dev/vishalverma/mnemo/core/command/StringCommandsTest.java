package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.type.Bytes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StringCommandsTest extends CommandTestSupport {

    @Test
    void getOnAMissingKeyIsNil() {
        assertThat(run("GET", "missing")).isEqualTo(Reply.NIL);
    }

    @Test
    void strlenCountsBytesAndIsZeroWhenAbsent() {
        assertInt(run("STRLEN", "missing"), 0);

        run("SET", "k", "hello");
        assertInt(run("STRLEN", "k"), 5);
    }

    @Test
    void appendCreatesWhenAbsentThenConcatenates() {
        assertInt(run("APPEND", "k", "foo"), 3);
        assertInt(run("APPEND", "k", "bar"), 6);
        assertBulk(run("GET", "k"), "foobar");
    }

    @Test
    void getDelReturnsTheValueAndRemovesTheKey() {
        run("SET", "k", "v");

        assertBulk(run("GETDEL", "k"), "v");
        assertThat(run("GET", "k")).isEqualTo(Reply.NIL);
        assertInt(run("EXISTS", "k"), 0);
    }

    @Test
    void getDelOnAMissingKeyIsNil() {
        assertThat(run("GETDEL", "missing")).isEqualTo(Reply.NIL);
    }

    @Test
    void setNxReportsWhetherItSet() {
        assertInt(run("SETNX", "k", "first"), 1);
        assertInt(run("SETNX", "k", "second"), 0);
        assertBulk(run("GET", "k"), "first");
    }

    @Test
    void msetAndMgetRoundTrip() {
        assertThat(run("MSET", "a", "1", "b", "2")).isEqualTo(Reply.OK);

        assertThat(run("MGET", "a", "b", "missing")).isEqualTo(new Reply.Arr(List.of(
            new Reply.Bulk(Bytes.of("1")),
            new Reply.Bulk(Bytes.of("2")),
            Reply.NIL)));
    }

    @Test
    void msetWithAnOddNumberOfArgumentsIsASyntaxError() {
        assertError(run("MSET", "a", "1", "b"), "ERR", "syntax error");
        // Nothing should have been written.
        assertInt(run("DBSIZE"), 0);
    }

    @Test
    void emptyValuesAreLegal() {
        run("SET", "k", "");

        assertBulk(run("GET", "k"), "");
        assertInt(run("STRLEN", "k"), 0);
        assertInt(run("EXISTS", "k"), 1);
    }
}
