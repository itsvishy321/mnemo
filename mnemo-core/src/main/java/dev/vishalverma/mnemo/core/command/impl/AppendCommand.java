package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.Bytes;
import dev.vishalverma.mnemo.core.type.RedisValue;
import dev.vishalverma.mnemo.core.type.StringValue;

import java.util.Set;

/**
 * {@code APPEND key value} — append to the value, creating it if absent. Returns the new length.
 *
 * <p>Appending to a missing key behaves like {@code SET}, per Redis.
 */
public final class AppendCommand implements Command {

    @Override
    public String name() {
        return "APPEND";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(3);
    }

    @Override
    public Set<Flag> flags() {
        return Set.of(Flag.WRITE, Flag.DENYOOM);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        RedisValue existing = ctx.keyspace().get(ctx.key());
        Bytes suffix = ctx.arg(2);

        Bytes combined;
        if (existing == null) {
            combined = suffix;
        } else {
            byte[] head = Args.asString(existing).value().array();
            byte[] tail = suffix.array();
            byte[] merged = new byte[head.length + tail.length];
            System.arraycopy(head, 0, merged, 0, head.length);
            System.arraycopy(tail, 0, merged, head.length, tail.length);
            combined = Bytes.wrap(merged);
        }

        // APPEND preserves the TTL — unlike SET, which clears it.
        ctx.keyspace().setKeepTtl(ctx.key(), new StringValue(combined));
        return new Reply.Int(combined.length());
    }
}
