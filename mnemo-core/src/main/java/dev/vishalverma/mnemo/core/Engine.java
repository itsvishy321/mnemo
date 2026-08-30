package dev.vishalverma.mnemo.core;

import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.CommandRegistry;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.error.MnemoException;
import dev.vishalverma.mnemo.core.store.Clock;
import dev.vishalverma.mnemo.core.store.Keyspace;
import dev.vishalverma.mnemo.core.type.Bytes;

import java.util.List;
import java.util.Locale;

/**
 * The engine: a keyspace, a command registry, and the single boundary where arguments become a
 * reply. Every front end — the REPL now, the RESP server in M2 — drives it through
 * {@link #dispatch}.
 *
 * <p>This class also anchors the ArchUnit purity rule. {@code @AnalyzeClasses(packagesOf = ...)}
 * sweeps the given class's package <em>and its subpackages</em>, so the anchor has to live in the
 * root package; pointing it at a class in {@code store} or {@code command} would quietly narrow
 * the rule to that subpackage while still appearing to pass.
 */
public final class Engine {

    private final Keyspace keyspace;
    private final CommandRegistry registry;

    public Engine() {
        this(Clock.SYSTEM);
    }

    public Engine(Clock clock) {
        this.keyspace = new Keyspace(clock);
        this.registry = CommandRegistry.standard();
    }

    public Keyspace keyspace() {
        return keyspace;
    }

    public CommandRegistry registry() {
        return registry;
    }

    /**
     * Runs one command and always returns a reply — never throws.
     *
     * <p>That total-ness is the point of centralising it here. M2 calls this from a Netty event
     * loop, where a single escaped throwable kills the loop thread and silently stops serving
     * every connection bound to it. Establishing the discipline now means the event loop inherits
     * it rather than having to remember it.
     *
     * @param args the command name followed by its arguments, Redis {@code argv} style
     */
    public Reply dispatch(List<Bytes> args) {
        if (args.isEmpty()) {
            return new Reply.Err("ERR", "empty command");
        }

        String name = args.get(0).toString().toUpperCase(Locale.ROOT);
        Command command = registry.lookup(name);
        if (command == null) {
            return new Reply.Err("ERR", "unknown command '" + name + "'");
        }
        if (!command.arity().accepts(args.size())) {
            return new Reply.Err("ERR", "wrong number of arguments for '"
                + name.toLowerCase(Locale.ROOT) + "' command");
        }

        try {
            return command.execute(new CommandContext(args, keyspace));
        } catch (MnemoException e) {
            return new Reply.Err(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            // A bug in a command, not bad input. Report it without leaking internals or taking
            // the caller down with it; M2 will log this at ERROR and keep the connection alive.
            return new Reply.Err("ERR", "internal error");
        }
    }
}
