package dev.vishalverma.mnemo.core.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NumericCommandsTest extends CommandTestSupport {

    private static final String NOT_AN_INTEGER = "value is not an integer or out of range";
    private static final String OVERFLOW = "increment or decrement would overflow";

    @Test
    void incrTreatsAMissingKeyAsZero() {
        assertInt(run("INCR", "counter"), 1);
        assertInt(run("INCR", "counter"), 2);
        assertBulk(run("GET", "counter"), "2");
    }

    @Test
    void decrTreatsAMissingKeyAsZero() {
        assertInt(run("DECR", "counter"), -1);
    }

    @Test
    void incrByAndDecrByApplyTheGivenDelta() {
        assertInt(run("INCRBY", "n", "41"), 41);
        assertInt(run("INCRBY", "n", "1"), 42);
        assertInt(run("DECRBY", "n", "2"), 40);
    }

    @Test
    void negativeDeltasWork() {
        assertInt(run("INCRBY", "n", "-5"), -5);
        assertInt(run("DECRBY", "n", "-5"), 0);
    }

    // ------------------------------------------------------------- overflow

    @Test
    void incrAtMaxValueErrorsRatherThanWrapping() {
        run("SET", "big", "9223372036854775807");

        assertError(run("INCR", "big"), "ERR", OVERFLOW);
        // The stored value must be untouched by the refused write.
        assertBulk(run("GET", "big"), "9223372036854775807");
    }

    @Test
    void decrAtMinValueErrorsRatherThanWrapping() {
        run("SET", "small", "-9223372036854775808");

        assertError(run("DECR", "small"), "ERR", OVERFLOW);
        assertBulk(run("GET", "small"), "-9223372036854775808");
    }

    /**
     * The case that separates subtracting from negate-then-add: negating MIN_VALUE overflows on
     * its own, but {@code -1 - MIN_VALUE} is exactly MAX_VALUE and must succeed.
     */
    @Test
    void decrByLongMinValueSucceedsWhereNegatingWouldOverflow() {
        run("SET", "n", "-1");

        assertInt(run("DECRBY", "n", "-9223372036854775808"), Long.MAX_VALUE);
    }

    /** The same delta from 0 genuinely does overflow, and must be reported rather than wrapped. */
    @Test
    void decrByLongMinValueFromZeroOverflows() {
        assertError(run("DECRBY", "n", "-9223372036854775808"), "ERR", OVERFLOW);
    }

    @Test
    void incrByOverflowingDeltaErrors() {
        run("SET", "n", "1");
        assertError(run("INCRBY", "n", "9223372036854775807"), "ERR", OVERFLOW);
    }

    // -------------------------------------------------------------- parsing

    @Test
    void incrOnANonNumericValueErrors() {
        run("SET", "k", "abc");
        assertError(run("INCR", "k"), "ERR", NOT_AN_INTEGER);
    }

    @Test
    void incrOnAValueWithWhitespaceErrors() {
        run("SET", "padded", " 1");
        assertError(run("INCR", "padded"), "ERR", NOT_AN_INTEGER);
    }

    @Test
    void incrOnALeadingZeroValueErrors() {
        run("SET", "zeroed", "01");
        assertError(run("INCR", "zeroed"), "ERR", NOT_AN_INTEGER);
    }

    @Test
    void incrOnAnEmptyValueErrors() {
        run("SET", "empty", "");
        assertError(run("INCR", "empty"), "ERR", NOT_AN_INTEGER);
    }

    @Test
    void incrByWithANonNumericDeltaErrors() {
        assertError(run("INCRBY", "n", "abc"), "ERR", NOT_AN_INTEGER);
    }

    // ------------------------------------------------------------------ TTL

    @Test
    void incrementingPreservesTheTtl() {
        run("SET", "n", "1", "EX", "10");

        assertInt(run("INCR", "n"), 2);

        clock.advance(9_999);
        assertBulk(run("GET", "n"), "2");
        clock.advance(1);
        assertThat(run("GET", "n")).isEqualTo(Reply.NIL);
    }
}
