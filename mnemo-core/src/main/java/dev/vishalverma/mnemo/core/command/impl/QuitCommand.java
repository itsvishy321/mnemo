package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;

/**
 * {@code QUIT} — acknowledges, then the client is disconnected.
 *
 * <p>Closing is the <em>front end's</em> job, not this command's: the engine has no notion of a
 * connection, and giving {@code Command} a "close afterwards" channel would contaminate every
 * other command's signature for the sake of one. Instead each front end watches for this command
 * name and closes after writing the reply. Both the REPL and the RESP server do exactly that, so
 * the rule reads the same in both places: <em>QUIT replies OK, then the front end closes.</em>
 */
public final class QuitCommand implements Command {

    @Override
    public String name() {
        return "QUIT";
    }

    @Override
    public Arity arity() {
        return Arity.exactly(1);
    }

    @Override
    public int firstKey() {
        return 0;
    }

    @Override
    public Reply execute(CommandContext ctx) {
        return Reply.OK;
    }
}
