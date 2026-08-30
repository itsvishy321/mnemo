package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;

import java.util.Set;

/** {@code DEL key [key ...]} — how many of the given keys were actually removed. */
public final class DelCommand implements Command {

    @Override
    public String name() {
        return "DEL";
    }

    @Override
    public Arity arity() {
        return Arity.atLeast(2);
    }

    @Override
    public Set<Flag> flags() {
        return Set.of(Flag.WRITE);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        long removed = 0;
        for (int i = 1; i < ctx.argCount(); i++) {
            if (ctx.keyspace().delete(ctx.arg(i))) {
                removed++;
            }
        }
        return new Reply.Int(removed);
    }
}
