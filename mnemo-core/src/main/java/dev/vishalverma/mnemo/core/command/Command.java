package dev.vishalverma.mnemo.core.command;

import java.util.Set;

/**
 * One command. Implementations are stateless and shared — all per-request state lives in the
 * {@link CommandContext}.
 *
 * <p>Every piece of per-command metadata the dispatcher needs is declared here rather than in a
 * side table, which is what lets {@link CommandRegistry} dispatch, arity-check, and later meter
 * every command with code written exactly once — and what keeps adding a command down to this
 * class plus one registration line.
 */
public interface Command {

    /** Canonical uppercase name. {@link CommandRegistry} enforces the casing. */
    String name();

    Arity arity();

    Reply execute(CommandContext ctx);

    /** Read-only unless stated otherwise — the safe default to forget. */
    default Set<Flag> flags() {
        return Set.of(Flag.READONLY);
    }

    /**
     * 1-based index of the first key in {@code args}, or 0 for commands that take none. Used for
     * shard routing (M5) and {@code WATCH} tracking (M7).
     */
    default int firstKey() {
        return 1;
    }
}
