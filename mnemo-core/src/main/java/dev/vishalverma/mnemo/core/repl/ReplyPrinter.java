package dev.vishalverma.mnemo.core.repl;

import dev.vishalverma.mnemo.core.command.Reply;

import java.util.List;

/** Renders a {@link Reply} the way {@code redis-cli} displays it. */
final class ReplyPrinter {

    private ReplyPrinter() {
    }

    /**
     * No {@code default} branch, on purpose: {@link Reply} is sealed, so when M2 adds a reply
     * type the compiler points here rather than letting an unhandled variant fall through to
     * something plausible-looking.
     */
    static String render(Reply reply) {
        return switch (reply) {
            case Reply.Simple simple -> simple.value();
            case Reply.Err err -> "(error) " + err.code() + " " + err.message();
            case Reply.Int integer -> "(integer) " + integer.value();
            case Reply.Nil nil -> "(nil)";
            case Reply.Bulk bulk -> '"' + escape(bulk.payload().array()) + '"';
            case Reply.Arr arr -> renderArray(arr.items());
        };
    }

    private static String renderArray(List<Reply> items) {
        if (items.isEmpty()) {
            return "(empty array)";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(i + 1).append(") ").append(render(items.get(i)));
        }
        return out.toString();
    }

    /**
     * Escapes non-printable bytes as {@code \xHH}. Without this a value containing a NUL or a
     * newline would corrupt the transcript — and the whole point of the binary-safe storage layer
     * is that such values are legal.
     */
    private static String escape(byte[] payload) {
        StringBuilder out = new StringBuilder(payload.length);
        for (byte b : payload) {
            int c = b & 0xFF;
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        out.append(String.format("\\x%02x", c));
                    } else {
                        out.append((char) c);
                    }
                }
            }
        }
        return out.toString();
    }
}
