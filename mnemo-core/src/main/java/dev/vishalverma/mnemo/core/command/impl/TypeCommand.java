package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;

/** {@code TYPE key} — the type name, or {@code none} when absent. Never WRONGTYPE. */
public final class TypeCommand implements Command {

    @Override
    public String name() {
        return "TYPE";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(2);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        return new Reply.Simple(ctx.keyspace().type(ctx.key()));
    }
}
