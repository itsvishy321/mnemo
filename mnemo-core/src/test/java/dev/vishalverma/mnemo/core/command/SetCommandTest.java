package dev.vishalverma.mnemo.core.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SET's option grammar is the most intricate parsing in M1; this covers the interactions. */
class SetCommandTest extends CommandTestSupport {

    @Test
    void setsAndGets() {
        assertThat(run("SET", "k", "v")).isEqualTo(Reply.OK);
        assertBulk(run("GET", "k"), "v");
    }

    @Test
    void isCaseInsensitiveOnTheCommandName() {
        assertThat(run("set", "k", "v")).isEqualTo(Reply.OK);
        assertBulk(run("get", "k"), "v");
    }

    // ------------------------------------------------------------- NX / XX

    @Test
    void nxOnlySetsWhenAbsent() {
        assertThat(run("SET", "k", "first", "NX")).isEqualTo(Reply.OK);
        assertThat(run("SET", "k", "second", "NX")).isEqualTo(Reply.NIL);
        assertBulk(run("GET", "k"), "first");
    }

    @Test
    void xxOnlySetsWhenPresent() {
        assertThat(run("SET", "k", "v", "XX")).isEqualTo(Reply.NIL);
        assertThat(run("GET", "k")).isEqualTo(Reply.NIL);

        run("SET", "k", "first");
        assertThat(run("SET", "k", "second", "XX")).isEqualTo(Reply.OK);
        assertBulk(run("GET", "k"), "second");
    }

    @Test
    void nxWithXxIsASyntaxError() {
        assertError(run("SET", "k", "v", "NX", "XX"), "ERR", "syntax error");
        assertError(run("SET", "k", "v", "XX", "NX"), "ERR", "syntax error");
    }

    // ----------------------------------------------------------------- GET

    @Test
    void getReturnsThePreviousValue() {
        run("SET", "k", "old");

        assertBulk(run("SET", "k", "new", "GET"), "old");
        assertBulk(run("GET", "k"), "new");
    }

    @Test
    void getOnAMissingKeyReturnsNilAndStillSets() {
        assertThat(run("SET", "k", "v", "GET")).isEqualTo(Reply.NIL);
        assertBulk(run("GET", "k"), "v");
    }

    @Test
    void getReturnsTheOldValueEvenWhenNxAbortsTheWrite() {
        run("SET", "k", "old");

        assertBulk(run("SET", "k", "new", "NX", "GET"), "old");
        assertBulk(run("GET", "k"), "old");   // the write really was aborted
    }

    // --------------------------------------------------------------- expiry

    @Test
    void exSetsASecondsTtl() {
        run("SET", "k", "v", "EX", "10");

        clock.advance(9_999);
        assertBulk(run("GET", "k"), "v");
        clock.advance(1);
        assertThat(run("GET", "k")).isEqualTo(Reply.NIL);
    }

    @Test
    void pxSetsAMillisecondTtl() {
        run("SET", "k", "v", "PX", "50");

        clock.advance(49);
        assertBulk(run("GET", "k"), "v");
        clock.advance(1);
        assertThat(run("GET", "k")).isEqualTo(Reply.NIL);
    }

    @Test
    void plainSetClearsAnExistingTtl() {
        run("SET", "k", "v", "EX", "10");
        run("SET", "k", "w");

        clock.advance(100_000);

        assertBulk(run("GET", "k"), "w");
    }

    @Test
    void keepTtlPreservesAnExistingTtl() {
        run("SET", "k", "v", "EX", "10");
        run("SET", "k", "w", "KEEPTTL");

        clock.advance(9_999);
        assertBulk(run("GET", "k"), "w");
        clock.advance(1);
        assertThat(run("GET", "k")).isEqualTo(Reply.NIL);
    }

    // ------------------------------------------------------ syntax failures

    @Test
    void conflictingExpiryOptionsAreASyntaxError() {
        assertError(run("SET", "k", "v", "EX", "1", "PX", "1"), "ERR", "syntax error");
        assertError(run("SET", "k", "v", "EX", "1", "KEEPTTL"), "ERR", "syntax error");
        assertError(run("SET", "k", "v", "KEEPTTL", "EX", "1"), "ERR", "syntax error");
    }

    @Test
    void expiryOptionWithoutItsValueIsASyntaxError() {
        assertError(run("SET", "k", "v", "EX"), "ERR", "syntax error");
    }

    @Test
    void unknownOptionIsASyntaxError() {
        assertError(run("SET", "k", "v", "NOPE"), "ERR", "syntax error");
    }

    @Test
    void nonPositiveExpiryIsRejected() {
        assertError(run("SET", "k", "v", "EX", "0"), "ERR", "invalid expire time in 'set' command");
        assertError(run("SET", "k", "v", "EX", "-1"), "ERR", "invalid expire time in 'set' command");
    }

    /** EX near Long.MAX_VALUE must error rather than wrapping into a deadline in the past. */
    @Test
    void overflowingExpiryIsRejectedRatherThanWrapping() {
        assertError(run("SET", "k", "v", "EX", "9223372036854775807"),
            "ERR", "invalid expire time in 'set' command");
        assertThat(run("GET", "k")).isEqualTo(Reply.NIL);
    }

    @Test
    void nonNumericExpiryIsRejected() {
        assertError(run("SET", "k", "v", "EX", "abc"), "ERR", "value is not an integer or out of range");
    }

    @Test
    void tooFewArgumentsIsAnArityError() {
        assertError(run("SET", "k"), "ERR", "wrong number of arguments for 'set' command");
    }
}
