package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;

import java.util.Set;

/** {@code INCR key} — add one, treating a missing key as 0. */
public final class IncrCommand implements Command {

    @Override
    public String name() {
        return "INCR";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(2);
    }

    @Override
    public Set<Flag> flags() {
        return Set.of(Flag.WRITE, Flag.DENYOOM);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        return Numeric.applyDelta(ctx, 1, false);
    }
}
