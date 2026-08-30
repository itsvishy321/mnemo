package dev.vishalverma.mnemo.protocol;

import dev.vishalverma.mnemo.core.command.Reply;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes a {@link Reply} as RESP2.
 *
 * <p>Targets {@link OutputStream} so the module stays pure JDK: the blocking server writes into a
 * {@code BufferedOutputStream} today, and M6 can wrap a Netty buffer in one. Writing several
 * replies and flushing once is exactly the batching a pipelined batch needs — no extra machinery.
 */
public final class RespEncoder {

    private static final byte[] CRLF = {'\r', '\n'};

    /**
     * Simple strings and errors are line-framed, so a stray CR or LF inside one would terminate
     * the reply early and let the rest be read as a separate reply. Redis truncates the text it
     * echoes back for the same reason; 128 matches its behaviour closely enough.
     */
    private static final int MAX_LINE_TEXT = 128;

    private RespEncoder() {
    }

    public static void encode(Reply reply, OutputStream out) throws IOException {
        // No `default` branch: Reply is sealed, so adding a variant makes the compiler point here.
        // Named bindings, not `case Reply.Nil _` — unnamed patterns are preview in Java 21.
        switch (reply) {
            case Reply.Simple simple -> writeLine(out, '+', simple.value());
            case Reply.Err err -> writeLine(out, '-', err.code() + " " + err.message());
            case Reply.Int integer -> writeLine(out, ':', Long.toString(integer.value()));
            case Reply.Nil nil -> out.write(new byte[] {'$', '-', '1', '\r', '\n'});
            case Reply.Bulk bulk -> writeBulk(out, bulk);
            case Reply.Arr arr -> {
                writeLine(out, '*', Integer.toString(arr.items().size()));
                for (Reply item : arr.items()) {
                    encode(item, out);
                }
            }
        }
    }

    private static void writeBulk(OutputStream out, Reply.Bulk bulk) throws IOException {
        byte[] payload = bulk.payload().array();
        writeLine(out, '$', Integer.toString(payload.length));
        // Length-prefixed, so the payload is written raw — CR, LF, and NUL are all legal here.
        // That is what makes the store binary safe end to end.
        out.write(payload);
        out.write(CRLF);
    }

    private static void writeLine(OutputStream out, char prefix, String text) throws IOException {
        out.write(prefix);
        out.write(sanitize(text).getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }

    /**
     * Strips CR and LF from line-framed text, and truncates it.
     *
     * <p>This is a security boundary, not tidiness. Error messages embed client-supplied text —
     * {@code unknown command '<name>'} — and a RESP bulk string may legally contain {@code \r\n}.
     * Without this, a client sending a command named {@code "A\r\nB"} would split our error into
     * two frames and desynchronise the connection. Doing it in the encoder rather than at each
     * call site means no future error message can reintroduce the hole.
     */
    private static String sanitize(String text) {
        String truncated = text.length() > MAX_LINE_TEXT ? text.substring(0, MAX_LINE_TEXT) : text;
        return truncated.replace('\r', ' ').replace('\n', ' ');
    }
}
