package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.store.Keyspace;
import dev.vishalverma.mnemo.core.type.Bytes;

import java.util.List;

/**
 * Everything a command needs to run: its arguments and the keyspace.
 *
 * <p>{@code args.get(0)} is the command name, matching Redis's {@code argv}. A record, so M2 can
 * add connection state as another component without reshaping call sites.
 */
public record CommandContext(List<Bytes> args, Keyspace keyspace) {

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
