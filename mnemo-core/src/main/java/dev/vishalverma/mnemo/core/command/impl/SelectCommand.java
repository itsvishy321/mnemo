package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.error.MnemoException;

/**
 * {@code SELECT index} — accepts database 0 only.
 *
 * <p>Mnemo has a single keyspace. Answering {@code SELECT 0} rather than rejecting the command
 * outright matters because clients and tooling issue it routinely on connect; a hard error there
 * would break sessions that never actually use multiple databases.
 */
public final class SelectCommand implements Command {

    @Override
    public String name() {
        return "SELECT";
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
        long index = Args.toLong(ctx.arg(1));
        if (index != 0) {
            throw new MnemoException("ERR", "DB index is out of range");
        }
        return Reply.OK;
    }
}
