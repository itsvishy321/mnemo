package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.command.impl.AppendCommand;
import dev.vishalverma.mnemo.core.command.impl.DbSizeCommand;
import dev.vishalverma.mnemo.core.command.impl.DecrByCommand;
import dev.vishalverma.mnemo.core.command.impl.DecrCommand;
import dev.vishalverma.mnemo.core.command.impl.DelCommand;
import dev.vishalverma.mnemo.core.command.impl.ExistsCommand;
import dev.vishalverma.mnemo.core.command.impl.FlushDbCommand;
import dev.vishalverma.mnemo.core.command.impl.GetCommand;
import dev.vishalverma.mnemo.core.command.impl.GetDelCommand;
import dev.vishalverma.mnemo.core.command.impl.IncrByCommand;
import dev.vishalverma.mnemo.core.command.impl.IncrCommand;
import dev.vishalverma.mnemo.core.command.impl.MGetCommand;
import dev.vishalverma.mnemo.core.command.impl.MSetCommand;
import dev.vishalverma.mnemo.core.command.impl.SetCommand;
import dev.vishalverma.mnemo.core.command.impl.SetNxCommand;
import dev.vishalverma.mnemo.core.command.impl.StrlenCommand;
import dev.vishalverma.mnemo.core.command.impl.TypeCommand;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Every known command, looked up by name. */
public final class CommandRegistry {

    private final Map<String, Command> byName;

    private CommandRegistry(List<Command> commands) {
        Map<String, Command> map = new HashMap<>();
        for (Command command : commands) {
            String name = command.name();
            if (!name.equals(name.toUpperCase(Locale.ROOT))) {
                throw new IllegalStateException("command names must be uppercase: " + name);
            }
            if (map.put(name, command) != null) {
                throw new IllegalStateException("duplicate command: " + name);
            }
        }
        this.byName = Map.copyOf(map);
    }

    /**
     * The one and only registration point.
     *
     * <p>Adding a command is its own class plus one line here — no dispatch switch, no parallel
     * arity table, no annotations, no reflection. Everything dispatch needs (name, arity, flags,
     * first key) is asked of the {@link Command} object itself, so this list is the only shared
     * thing a new command touches.
     *
     * <p>A static list rather than {@code ServiceLoader}: both are two files, but the second file
     * there is an unchecked text resource where a typo yields a silently missing command that no
     * compiler and no "find usages" would catch.
     */
    public static CommandRegistry standard() {
        return new CommandRegistry(List.of(
            new GetCommand(),
            new SetCommand(),
            new SetNxCommand(),
            new GetDelCommand(),
            new AppendCommand(),
            new StrlenCommand(),
            new IncrCommand(),
            new IncrByCommand(),
            new DecrCommand(),
            new DecrByCommand(),
            new MGetCommand(),
            new MSetCommand(),
            new DelCommand(),
            new ExistsCommand(),
            new TypeCommand(),
            new DbSizeCommand(),
            new FlushDbCommand()));
    }

    /** The command, or {@code null} if the name is unknown. */
    public Command lookup(String upperCaseName) {
        return byName.get(upperCaseName);
    }

    /** Every registered command — for {@code COMMAND} in M2, and for the registry's own tests. */
    public Collection<Command> all() {
        return byName.values();
    }
}
