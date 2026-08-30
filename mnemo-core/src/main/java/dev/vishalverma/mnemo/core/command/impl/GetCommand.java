package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.RedisValue;

/** {@code GET key} — the value, or nil when the key is absent or expired. */
public final class GetCommand implements Command {

    @Override
    public String name() {
        return "GET";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(2);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        RedisValue value = ctx.keyspace().get(ctx.key());
        return value == null ? Reply.NIL : new Reply.Bulk(Args.asString(value).value());
    }
}
