package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;

import java.util.Set;

/** {@code INCRBY key delta} — add {@code delta}, treating a missing key as 0. */
public final class IncrByCommand implements Command {

    @Override
    public String name() {
        return "INCRBY";
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
        // The delta is parsed with the same strict rules as the stored value, so a bad argument
        // and a bad stored value produce the identical error — which is what Redis does.
        return Numeric.applyDelta(ctx, Args.toLong(ctx.arg(2)), false);
    }
}
