package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.error.NotAnIntegerException;
import dev.vishalverma.mnemo.core.type.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Redis's {@code string2ll} grammar is stricter than {@link Long#parseLong}; this pins it. */
class ArgsTest {

    @Test
    void parsesPlainIntegers() {
        assertThat(Args.toLong(Bytes.of("0"))).isZero();
        assertThat(Args.toLong(Bytes.of("1"))).isEqualTo(1);
        assertThat(Args.toLong(Bytes.of("-1"))).isEqualTo(-1);
        assertThat(Args.toLong(Bytes.of("42"))).isEqualTo(42);
    }

    @Test
    void parsesLongMaxValue() {
        assertThat(Args.toLong(Bytes.of("9223372036854775807"))).isEqualTo(Long.MAX_VALUE);
    }

    /**
     * The case a naive parser fails: accumulating positively and negating at the end cannot
     * represent MIN_VALUE, because the negative range is one larger than the positive one.
     */
    @Test
    void parsesLongMinValue() {
        assertThat(Args.toLong(Bytes.of("-9223372036854775808"))).isEqualTo(Long.MIN_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",                      // empty
        " 1",                    // leading space
        "1 ",                    // trailing space
        "+1",                    // explicit plus
        "01",                    // leading zero
        "-0",                    // negative zero
        "-",                     // bare sign
        "1.0",                   // not an integer
        "abc",
        "1a",
        "9223372036854775808",   // MAX_VALUE + 1
        "-9223372036854775809",  // MIN_VALUE - 1
        "99999999999999999999999",
    })
    void rejectsMalformedOrOutOfRange(String input) {
        assertThatThrownBy(() -> Args.toLong(Bytes.of(input)))
            .isInstanceOf(NotAnIntegerException.class)
            .hasMessage("value is not an integer or out of range");
    }

    @Test
    void roundTripsThroughFromLong() {
        for (long value : new long[] {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertThat(Args.toLong(Args.fromLong(value))).isEqualTo(value);
        }
    }
}
