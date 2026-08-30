package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;

/** {@code DBSIZE} — number of live keys. */
public final class DbSizeCommand implements Command {

    @Override
    public String name() {
        return "DBSIZE";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(1);
    }

    @Override
    public int firstKey() {
        return 0;  // operates on the whole keyspace, not a key
    }

    @Override
    public Reply execute(CommandContext ctx) {
        return new Reply.Int(ctx.keyspace().size());
    }
}
