package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;

/**
 * {@code EXISTS key [key ...]} — how many of the given keys exist.
 *
 * <p>Counts repeats: {@code EXISTS k k} on an existing key returns 2, matching Redis.
 */
public final class ExistsCommand implements Command {

    @Override
    public String name() {
        return "EXISTS";
    }

    @Override
    public Arity arity() {
        return Arity.atLeast(2);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        long found = 0;
        for (int i = 1; i < ctx.argCount(); i++) {
            if (ctx.keyspace().exists(ctx.arg(i))) {
                found++;
            }
        }
        return new Reply.Int(found);
    }
}
