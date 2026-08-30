package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.error.NotAnIntegerException;
import dev.vishalverma.mnemo.core.error.WrongTypeException;
import dev.vishalverma.mnemo.core.type.Bytes;
import dev.vishalverma.mnemo.core.type.RedisValue;
import dev.vishalverma.mnemo.core.type.StringValue;

import java.util.Locale;

/** Argument parsing and type-checking shared across commands. */
public final class Args {

    private Args() {
    }

    /**
     * Parses a strict decimal 64-bit integer, matching Redis's {@code string2ll}. Deliberately
     * stricter than {@link Long#parseLong}: no surrounding whitespace, no {@code +} sign, no
     * leading zeros ({@code "01"} is invalid), and no {@code "-0"}.
     *
     * <p>Accumulates <em>negatively</em>. The negative long range is one larger than the positive
     * one, so building the value positively and negating at the end cannot represent
     * {@code -9223372036854775808} — a perfectly legal stored value that {@code INCR} must be able
     * to read back.
     */
    public static long toLong(Bytes bytes) {
        byte[] a = bytes.array();
        // 20 = 19 digits plus a sign; anything longer cannot fit and would only overflow below.
        if (a.length == 0 || a.length > 20) {
            throw new NotAnIntegerException();
        }

        int i = 0;
        boolean negative = a[0] == '-';
        if (negative && ++i == a.length) {
            throw new NotAnIntegerException();  // a bare "-"
        }

        if (a[i] == '0') {
            // "0" is the only value allowed to start with a zero digit. Rejects "01" and "-0".
            if (negative || a.length - i != 1) {
                throw new NotAnIntegerException();
            }
            return 0;
        }

        long acc = 0;
        try {
            for (; i < a.length; i++) {
                int digit = a[i] - '0';
                if (digit < 0 || digit > 9) {
                    throw new NotAnIntegerException();
                }
                acc = Math.subtractExact(Math.multiplyExact(acc, 10), digit);
            }
            return negative ? acc : Math.negateExact(acc);
        } catch (ArithmeticException overflow) {
            // Redis reports malformed and out-of-range with the same message.
            throw new NotAnIntegerException();
        }
    }

    /** Renders a long back into a stored value. */
    public static Bytes fromLong(long value) {
        return Bytes.of(Long.toString(value));
    }

    /** Uppercases an option token for comparison. Options are ASCII, so the locale is fixed. */
    public static String upper(Bytes bytes) {
        return bytes.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * The one place a value is narrowed to a string. Centralising it is what "the WRONGTYPE
     * discipline" amounts to in practice — every string command funnels through here, so none of
     * them can forget the check or word the error differently.
     */
    public static StringValue asString(RedisValue value) {
        if (value instanceof StringValue s) {
            return s;
        }
        throw new WrongTypeException();
    }
}
