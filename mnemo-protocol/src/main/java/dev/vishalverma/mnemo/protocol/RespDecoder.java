package dev.vishalverma.mnemo.protocol;

import dev.vishalverma.mnemo.core.type.Bytes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An incremental RESP2 request decoder.
 *
 * <p>TCP is a byte stream, not a message stream: one {@code read()} can deliver half a command,
 * three commands, or two and a half. This decoder therefore owns an accumulator — callers
 * {@link #feed} whatever bytes arrived and then call {@link #next()} until it returns {@code null}.
 * Draining in a loop like that <em>is</em> pipelining support; there is no separate feature.
 *
 * <p>Owning the buffer rather than parsing an {@code InputStream} is also what keeps this reusable:
 * the blocking server hands over bytes today, and a Netty adapter can hand over the same bytes in
 * M6 without the parser changing.
 *
 * <p>Not thread-safe — one decoder per connection.
 */
public final class RespDecoder {

    private static final int INITIAL_CAPACITY = 1024;

    private final RespLimits limits;

    private byte[] buffer;
    private int readIndex;    // next byte to parse
    private int writeIndex;   // next free slot

    public RespDecoder() {
        this(RespLimits.defaults());
    }

    public RespDecoder(RespLimits limits) {
        this.limits = limits;
        this.buffer = new byte[Math.min(INITIAL_CAPACITY, limits.maxBuffer())];
    }

    /** Appends freshly read bytes to the accumulator. */
    public void feed(byte[] data, int offset, int length) {
        ensureCapacity(length);
        System.arraycopy(data, offset, buffer, writeIndex, length);
        writeIndex += length;
    }

    /**
     * The next complete command, or {@code null} if more bytes are needed.
     *
     * <p>{@code null} rather than {@code Optional} because this is called in a tight drain loop and
     * an allocation per poll would buy nothing; the same nullable convention as {@code Keyspace.get}.
     *
     * @throws ProtocolException if the stream is malformed or exceeds a limit — the connection can
     *     no longer be framed and the caller must close it
     */
    public List<Bytes> next() {
        while (true) {
            int start = readIndex;
            List<Bytes> command = tryParse();

            if (command == null) {
                // Incomplete input must consume nothing, so the whole parse can simply be re-run
                // once more bytes land. Rewinding in one place — rather than at every early return
                // inside the parser — is what makes that invariant hard to break.
                readIndex = start;
                return null;
            }

            if (!command.isEmpty()) {
                compactIfNeeded();
                return command;
            }
            // `*0` or a blank inline line: input was consumed but there is no command to run.
            // Loop rather than return, otherwise the caller would see null and stall forever.
        }
    }

    private List<Bytes> tryParse() {
        if (readIndex >= writeIndex) {
            return null;
        }
        // Anything not starting with '*' is an inline command — a bare `PING\r\n` typed into a
        // telnet session. redis-cli never sends these, but the protocol requires accepting them.
        return buffer[readIndex] == '*' ? parseMultibulk() : parseInline();
    }

    private List<Bytes> parseMultibulk() {
        int crlf = findCrlf(readIndex);
        if (crlf < 0) {
            guardUnterminatedLine();
            return null;
        }

        long count = parseLong(readIndex + 1, crlf);
        if (count > limits.maxMultibulkLength()) {
            throw new ProtocolException("multibulk length " + count + " exceeds limit");
        }
        readIndex = crlf + 2;

        // Redis treats a non-positive count as "no command" rather than an error.
        if (count <= 0) {
            return List.of();
        }

        List<Bytes> args = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            Bytes arg = parseBulk();
            if (arg == null) {
                return null;   // next() rewinds and we re-parse from the top when more data lands
            }
            args.add(arg);
        }
        return args;
    }

    private Bytes parseBulk() {
        if (readIndex >= writeIndex) {
            return null;
        }
        if (buffer[readIndex] != '$') {
            throw new ProtocolException("expected '$' at start of bulk string");
        }

        int crlf = findCrlf(readIndex);
        if (crlf < 0) {
            guardUnterminatedLine();
            return null;
        }

        long length = parseLong(readIndex + 1, crlf);
        // Both checks happen BEFORE any allocation. `$999999999999` must be rejected, never
        // turned into `new byte[999999999999]` — that is the CVE-shaped bug in every hand-rolled
        // protocol parser. A negative length is only meaningful in a reply, never in a request.
        if (length < 0) {
            throw new ProtocolException("invalid bulk length: " + length);
        }
        if (length > limits.maxBulkLength()) {
            throw new ProtocolException("bulk length " + length + " exceeds limit");
        }

        int payloadStart = crlf + 2;
        int payloadEnd = payloadStart + (int) length;
        if (payloadEnd + 2 > writeIndex) {
            return null;   // payload and its terminator have not fully arrived
        }
        if (buffer[payloadEnd] != '\r' || buffer[payloadEnd + 1] != '\n') {
            throw new ProtocolException("bulk payload not terminated by CRLF");
        }

        readIndex = payloadEnd + 2;
        // Length-prefixed, so \r and \n inside the payload are ordinary bytes. copyOfRange gives a
        // fresh array, which is exactly what Bytes.wrap's ownership contract needs.
        return Bytes.wrap(Arrays.copyOfRange(buffer, payloadStart, payloadEnd));
    }

    /** Splits an inline line on ASCII whitespace. Byte-level, so it stays binary safe. */
    private List<Bytes> parseInline() {
        int crlf = findCrlf(readIndex);
        int lineEnd;
        int nextRead;
        if (crlf < 0) {
            // Redis also accepts a bare LF terminator for inline commands.
            int lf = indexOf((byte) '\n', readIndex);
            if (lf < 0) {
                guardUnterminatedLine();
                return null;
            }
            lineEnd = lf;
            nextRead = lf + 1;
        } else {
            lineEnd = crlf;
            nextRead = crlf + 2;
        }

        if (lineEnd - readIndex > limits.maxInlineLength()) {
            throw new ProtocolException("inline command exceeds limit");
        }

        List<Bytes> args = new ArrayList<>();
        int i = readIndex;
        while (i < lineEnd) {
            while (i < lineEnd && isSpace(buffer[i])) {
                i++;
            }
            int tokenStart = i;
            while (i < lineEnd && !isSpace(buffer[i])) {
                i++;
            }
            if (i > tokenStart) {
                args.add(Bytes.wrap(Arrays.copyOfRange(buffer, tokenStart, i)));
            }
        }

        readIndex = nextRead;
        return args;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t';
    }

    private int findCrlf(int from) {
        for (int i = from; i + 1 < writeIndex; i++) {
            if (buffer[i] == '\r' && buffer[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private int indexOf(byte target, int from) {
        for (int i = from; i < writeIndex; i++) {
            if (buffer[i] == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A header line with no terminator in sight is either garbage or an attempt to make us buffer
     * forever, so it is bounded even though the line is still "incomplete".
     */
    private void guardUnterminatedLine() {
        if (writeIndex - readIndex > limits.maxInlineLength()) {
            throw new ProtocolException("unterminated protocol line exceeds limit");
        }
    }

    /** Parses a signed decimal in {@code [from, toExclusive)}, rejecting overflow. */
    private long parseLong(int from, int toExclusive) {
        if (from >= toExclusive) {
            throw new ProtocolException("missing length");
        }
        int i = from;
        boolean negative = buffer[i] == '-';
        if (negative && ++i == toExclusive) {
            throw new ProtocolException("malformed length");
        }

        long value = 0;
        try {
            for (; i < toExclusive; i++) {
                int digit = buffer[i] - '0';
                if (digit < 0 || digit > 9) {
                    throw new ProtocolException("malformed length");
                }
                value = Math.addExact(Math.multiplyExact(value, 10), digit);
            }
        } catch (ArithmeticException overflow) {
            // e.g. `$99999999999999999999` — reject rather than silently wrapping to a small
            // (or negative) length that would then pass the limit check.
            throw new ProtocolException("length out of range");
        }
        return negative ? -value : value;
    }

    private void compactIfNeeded() {
        if (readIndex == writeIndex) {
            readIndex = 0;
            writeIndex = 0;
        } else if (readIndex > buffer.length / 2) {
            compact();
        }
    }

    private void compact() {
        if (readIndex == 0) {
            return;
        }
        System.arraycopy(buffer, readIndex, buffer, 0, writeIndex - readIndex);
        writeIndex -= readIndex;
        readIndex = 0;
    }

    private void ensureCapacity(int additional) {
        if (writeIndex + additional <= buffer.length) {
            return;
        }
        // Reclaim already-parsed bytes first; without this a long-lived pipelining connection
        // would grow its buffer forever even though it never holds much unparsed data.
        compact();
        if (writeIndex + additional <= buffer.length) {
            return;
        }

        int needed = writeIndex + additional;
        if (needed > limits.maxBuffer()) {
            throw new ProtocolException("connection buffer would exceed limit");
        }
        int grown = Math.min(Math.max(needed, buffer.length * 2), limits.maxBuffer());
        buffer = Arrays.copyOf(buffer, grown);
    }
}
