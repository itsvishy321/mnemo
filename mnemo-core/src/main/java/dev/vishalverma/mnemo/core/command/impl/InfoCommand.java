package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.Mnemo;
import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.Bytes;

import java.util.Locale;
import java.util.Set;

/**
 * {@code INFO [section]} — server and keyspace statistics as a bulk string.
 *
 * <p>The format is line-oriented text, not a RESP structure: {@code # Section} headers followed by
 * {@code field:value} lines. Clients parse it by splitting on colons, so the shape matters more
 * than the content.
 *
 * <p>Only the sections that can be answered honestly today are present. Connection counts,
 * throughput, and memory figures arrive with M5/M6 when something actually measures them —
 * reporting zeros for them now would be worse than omitting them.
 */
public final class InfoCommand implements Command {

    @Override
    public String name() {
        return "INFO";
    }

    @Override
    public Arity arity() {
        return new Arity(1, 2);
    }

    @Override
    public int firstKey() {
        return 0;
    }

    @Override
    public Set<Flag> flags() {
        return Set.of(Flag.READONLY, Flag.ADMIN);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        String section = ctx.argCount() > 1 ? Args.upper(ctx.arg(1)) : "ALL";
        StringBuilder out = new StringBuilder();

        if (wants(section, "SERVER")) {
            out.append("# Server\r\n")
                .append("mnemo_version:").append(Mnemo.VERSION).append("\r\n")
                .append("os:").append(System.getProperty("os.name", "unknown")).append("\r\n")
                .append("arch_bits:").append(System.getProperty("sun.arch.data.model", "64")).append("\r\n")
                .append("java_version:").append(Runtime.version().feature()).append("\r\n")
                .append("\r\n");
        }
        if (wants(section, "KEYSPACE")) {
            out.append("# Keyspace\r\n");
            int keys = ctx.keyspace().size();
            if (keys > 0) {
                // Redis omits the db line entirely when the database is empty.
                out.append("db0:keys=").append(keys).append(",expires=0,avg_ttl=0\r\n");
            }
            out.append("\r\n");
        }

        return new Reply.Bulk(Bytes.of(out.toString()));
    }

    private static boolean wants(String requested, String sectionName) {
        return requested.equals("ALL")
            || requested.equals("DEFAULT")
            || requested.equals(sectionName.toUpperCase(Locale.ROOT));
    }
}
