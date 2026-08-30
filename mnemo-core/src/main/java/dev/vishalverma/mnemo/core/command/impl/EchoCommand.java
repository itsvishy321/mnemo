package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;

/** {@code ECHO message} — returns the message unchanged. */
public final class EchoCommand implements Command {

    @Override
    public String name() {
        return "ECHO";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(2);
    }

    @Override
    public int firstKey() {
        return 0;
    }

    @Override
    public Reply execute(CommandContext ctx) {
        return new Reply.Bulk(ctx.arg(1));
    }
}
