package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;

import java.util.Set;

/** {@code DECRBY key delta} — subtract {@code delta}, treating a missing key as 0. */
public final class DecrByCommand implements Command {

    @Override
    public String name() {
        return "DECRBY";
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
        return Numeric.applyDelta(ctx, Args.toLong(ctx.arg(2)), true);
    }
}
