package dev.vishalverma.mnemo.core.repl;

import dev.vishalverma.mnemo.core.type.Bytes;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a typed line into arguments, the way {@code redis-cli} does for inline commands.
 *
 * <p>This is deliberately <em>not</em> RESP — the wire protocol arrives in M2. What it does share
 * with M2 is the need to produce binary-safe arguments, which is why the quoting rules include
 * {@code \xHH}: without it there is no way to type a key containing a NUL byte, and no way to
 * demonstrate interactively that the engine is binary-safe at all.
 */
final class InlineParser {

    private InlineParser() {
    }

    /** Thrown for an unbalanced quote or a malformed escape. */
    static final class ParseException extends Exception {

        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    static List<Bytes> parse(String line) throws ParseException {
        List<Bytes> args = new ArrayList<>();
        int i = 0;
        int length = line.length();

        while (i < length) {
            while (i < length && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= length) {
                break;
            }

            ByteArrayOutputStream token = new ByteArrayOutputStream();
            char quote = line.charAt(i);
            if (quote == '"' || quote == '\'') {
                i = readQuoted(line, i + 1, quote, token);
            } else {
                i = readBare(line, i, token);
            }
            args.add(Bytes.wrap(token.toByteArray()));
        }
        return args;
    }

    private static int readBare(String line, int start, ByteArrayOutputStream out) {
        int i = start;
        while (i < line.length() && !Character.isWhitespace(line.charAt(i))) {
            writeChar(out, line.charAt(i));
            i++;
        }
        return i;
    }

    private static int readQuoted(String line, int start, char quote, ByteArrayOutputStream out)
        throws ParseException {

        int i = start;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == quote) {
                return i + 1;
            }
            if (c != '\\') {
                writeChar(out, c);
                i++;
                continue;
            }

            // Escapes. Double quotes support the \xHH byte escape; single quotes, like the shell
            // and redis-cli, only allow escaping the quote itself.
            if (++i >= line.length()) {
                throw new ParseException("trailing backslash");
            }
            char escaped = line.charAt(i);
            if (quote == '\'') {
                writeChar(out, escaped);
                i++;
                continue;
            }
            switch (escaped) {
                case 'n' -> out.write('\n');
                case 'r' -> out.write('\r');
                case 't' -> out.write('\t');
                case '0' -> out.write(0);
                case 'x' -> {
                    if (i + 2 >= line.length()) {
                        throw new ParseException("truncated \\x escape");
                    }
                    out.write(hexByte(line.charAt(i + 1), line.charAt(i + 2)));
                    i += 2;
                }
                default -> writeChar(out, escaped);
            }
            i++;
        }
        throw new ParseException("unbalanced quotes");
    }

    private static int hexByte(char high, char low) throws ParseException {
        int h = Character.digit(high, 16);
        int l = Character.digit(low, 16);
        if (h < 0 || l < 0) {
            throw new ParseException("invalid \\x escape");
        }
        return (h << 4) | l;
    }

    private static void writeChar(ByteArrayOutputStream out, char c) {
        // UTF-8 encode so a typed non-ASCII character stores the bytes a client would send.
        out.writeBytes(String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
