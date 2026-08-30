package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.CommandRegistry;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.Bytes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code COMMAND [COUNT | DOCS | INFO name...]} — introspection over the registry.
 *
 * <p>Not optional: {@code redis-cli} issues this on connect to build tab completion, and answering
 * it with an error is the documented reason a first compatibility attempt appears to hang. The
 * guidance is explicit that an <em>empty array beats an error</em>, so any subcommand we do not
 * model returns an empty array rather than failing.
 *
 * <p>Reads the registry from the {@link CommandContext} rather than holding one: a command that
 * lives <em>in</em> the registry cannot also be constructed <em>with</em> it.
 */
public final class CommandCommand implements Command {

    @Override
    public String name() {
        return "COMMAND";
    }

    @Override
    public Arity arity() {
        return Arity.atLeast(1);
    }

    @Override
    public int firstKey() {
        return 0;
    }

    @Override
    public Reply execute(CommandContext ctx) {
        if (ctx.argCount() == 1) {
            return describeAll(ctx.registry());
        }

        String subcommand = Args.upper(ctx.arg(1));
        return switch (subcommand) {
            case "COUNT" -> new Reply.Int(ctx.registry().all().size());
            case "INFO" -> describeNamed(ctx);
            // DOCS carries a large, largely RESP3-shaped structure. An empty array is a valid
            // answer and redis-cli falls back to its built-in help, which is all we need.
            default -> new Reply.Arr(List.of());
        };
    }

    private static Reply describeAll(CommandRegistry registry) {
        List<Reply> entries = new ArrayList<>();
        for (Command command : registry.all()) {
            entries.add(describe(command));
        }
        return new Reply.Arr(entries);
    }

    private static Reply describeNamed(CommandContext ctx) {
        List<Reply> entries = new ArrayList<>();
        for (int i = 2; i < ctx.argCount(); i++) {
            Command command = ctx.registry().lookup(Args.upper(ctx.arg(i)));
            // Redis replies with a nil element for names it does not know, keeping the reply
            // positionally aligned with the request.
            entries.add(command == null ? Reply.NIL : describe(command));
        }
        return new Reply.Arr(entries);
    }

    /** One entry in Redis's {@code COMMAND} format: name, arity, flags, first/last key, step. */
    private static Reply describe(Command command) {
        List<Reply> flags = new ArrayList<>();
        for (Flag flag : command.flags()) {
            flags.add(new Reply.Simple(flag.name().toLowerCase(Locale.ROOT)));
        }

        int firstKey = command.firstKey();
        return new Reply.Arr(List.of(
            new Reply.Bulk(Bytes.of(command.name().toLowerCase(Locale.ROOT))),
            new Reply.Int(encodeArity(command.arity())),
            new Reply.Arr(flags),
            new Reply.Int(firstKey),
            // We do not track a last-key index, so single-key commands are described exactly and
            // variadic ones understate their range. Only cluster-aware clients read this, and we
            // are not a cluster; the names are what redis-cli actually uses.
            new Reply.Int(firstKey),
            new Reply.Int(firstKey == 0 ? 0 : 1)));
    }

    /** Redis packs arity into one int: positive is exact, negative is "at least |n|". */
    private static long encodeArity(Arity arity) {
        return arity.min() == arity.max() ? arity.min() : -arity.min();
    }
}
