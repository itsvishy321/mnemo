package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;

import java.util.Set;

/** {@code FLUSHDB} — remove every key. */
public final class FlushDbCommand implements Command {

    @Override
    public String name() {
        return "FLUSHDB";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(1);
    }

    @Override
    public Set<Flag> flags() {
        return Set.of(Flag.WRITE, Flag.ADMIN);
    }

    @Override
    public int firstKey() {
        return 0;
    }

    @Override
    public Reply execute(CommandContext ctx) {
        ctx.keyspace().flush();
        return Reply.OK;
    }
}
