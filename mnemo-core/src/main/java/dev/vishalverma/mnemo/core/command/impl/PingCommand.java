package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;

/** {@code PING [message]} — {@code +PONG}, or the message echoed back as a bulk string. */
public final class PingCommand implements Command {

    private static final Reply PONG = new Reply.Simple("PONG");

    @Override
    public String name() {
        return "PING";
    }

    @Override
    public Arity arity() {
        return new Arity(1, 2);
    }

    @Override
    public int firstKey() {
        return 0;
    }

    @Override
    public Reply execute(CommandContext ctx) {
        return ctx.argCount() == 1 ? PONG : new Reply.Bulk(ctx.arg(1));
    }
}
