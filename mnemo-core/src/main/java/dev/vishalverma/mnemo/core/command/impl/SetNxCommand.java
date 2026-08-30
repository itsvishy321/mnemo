package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.StringValue;

import java.util.Set;

/** {@code SETNX key value} — set only if absent; 1 if set, 0 if the key already existed. */
public final class SetNxCommand implements Command {

    @Override
    public String name() {
        return "SETNX";
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
        if (ctx.keyspace().exists(ctx.key())) {
            return new Reply.Int(0);
        }
        ctx.keyspace().set(ctx.key(), new StringValue(ctx.arg(2)));
        return new Reply.Int(1);
    }
}
