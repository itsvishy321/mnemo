package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.store.Keyspace;
import dev.vishalverma.mnemo.core.type.Bytes;

import java.util.List;

/**
 * Everything a command needs to run: its arguments, the keyspace, and the registry.
 *
 * <p>{@code args.get(0)} is the command name, matching Redis's {@code argv}. A record, so M8 can
 * add connection state as another component without reshaping call sites.
 *
 * <p>The registry is here so {@code COMMAND} can describe its peers without holding a reference to
 * the registry that contains it — a construction cycle. Passing it through the context keeps every
 * command uniformly constructible with no arguments, which is what keeps registration a one-liner.
 */
public record CommandContext(List<Bytes> args, Keyspace keyspace, CommandRegistry registry) {

    public CommandContext {
        args = List.copyOf(args);
    }

    public int argCount() {
        return args.size();
    }

    public Bytes arg(int index) {
        return args.get(index);
    }

    /** The key most commands operate on. */
    public Bytes key() {
        return args.get(1);
    }
}
