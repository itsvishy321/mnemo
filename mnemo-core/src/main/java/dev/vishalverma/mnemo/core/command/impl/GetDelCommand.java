package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.RedisValue;

import java.util.Set;

/** {@code GETDEL key} — return the value and delete the key atomically. */
public final class GetDelCommand implements Command {

    @Override
    public String name() {
        return "GETDEL";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(2);
    }

    @Override
    public Set<Flag> flags() {
        return Set.of(Flag.WRITE);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        RedisValue value = ctx.keyspace().get(ctx.key());
        if (value == null) {
            return Reply.NIL;
        }
        // Type-check before deleting: GETDEL on a list must fail without destroying the list.
        Reply reply = new Reply.Bulk(Args.asString(value).value());
        ctx.keyspace().delete(ctx.key());
        return reply;
    }
}
