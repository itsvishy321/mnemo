package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.RedisValue;

/** {@code STRLEN key} — length in bytes, or 0 when absent. Missing keys are not an error. */
public final class StrlenCommand implements Command {

    @Override
    public String name() {
        return "STRLEN";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(2);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        RedisValue value = ctx.keyspace().get(ctx.key());
        return new Reply.Int(value == null ? 0 : Args.asString(value).value().length());
    }
}
