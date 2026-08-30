package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.error.SyntaxException;
import dev.vishalverma.mnemo.core.type.StringValue;

import java.util.Set;

/** {@code MSET key value [key value ...]} — set several keys, always returning OK. */
public final class MSetCommand implements Command {

    @Override
    public String name() {
        return "MSET";
    }

    @Override
    public Arity arity() {
        return Arity.atLeast(3);
    }

    @Override
    public Set<Flag> flags() {
        return Set.of(Flag.WRITE, Flag.DENYOOM);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        // Pairs, so an even total argc (name + odd payload) means a dangling key.
        if (ctx.argCount() % 2 == 0) {
            throw new SyntaxException();
        }
        // The arity check above is the only thing that can fail, so by this point every pair will
        // be written — which is what makes MSET's all-or-nothing contract hold. Values are opaque
        // bytes and overwrite any existing type, so there is no WRONGTYPE case to guard against.
        for (int i = 1; i < ctx.argCount(); i += 2) {
            ctx.keyspace().set(ctx.arg(i), new StringValue(ctx.arg(i + 1)));
        }
        return Reply.OK;
    }
}
