package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.RedisValue;
import dev.vishalverma.mnemo.core.type.StringValue;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code MGET key [key ...]} — one reply element per key, nil where absent.
 *
 * <p>A key holding a non-string yields nil rather than WRONGTYPE: MGET is defined never to fail
 * partway, so one odd key cannot poison the whole batch.
 */
public final class MGetCommand implements Command {

    @Override
    public String name() {
        return "MGET";
    }

    @Override
    public Arity arity() {
        return Arity.atLeast(2);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        List<Reply> items = new ArrayList<>(ctx.argCount() - 1);
        for (int i = 1; i < ctx.argCount(); i++) {
            RedisValue value = ctx.keyspace().get(ctx.arg(i));
            items.add(value instanceof StringValue s ? new Reply.Bulk(s.value()) : Reply.NIL);
        }
        return new Reply.Arr(items);
    }
}
