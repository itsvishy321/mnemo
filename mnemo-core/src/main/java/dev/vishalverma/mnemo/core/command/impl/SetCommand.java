package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.Arity;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Flag;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.error.MnemoException;
import dev.vishalverma.mnemo.core.error.SyntaxException;
import dev.vishalverma.mnemo.core.store.Keyspace;
import dev.vishalverma.mnemo.core.type.Bytes;
import dev.vishalverma.mnemo.core.type.RedisValue;
import dev.vishalverma.mnemo.core.type.StringValue;

import java.util.Set;

/** {@code SET key value [NX|XX] [GET] [EX seconds | PX millis | KEEPTTL]}. */
public final class SetCommand implements Command {

    /** No expiry option seen yet — distinct from a deadline of 0. */
    private static final long NO_TTL = -1;

    private record Options(boolean nx, boolean xx, boolean get, boolean keepTtl, long ttlMs) { }

    @Override
    public String name() {
        return "SET";
    }

    @Override
    public Arity arity() {
        return Arity.atLeast(3);
    }

    @Override
    public Set<Flag> flags() {
        return Set.of(Flag.WRITE, Flag.DENYOOM);
    }

    @Override
    public Reply execute(CommandContext ctx) {
        Options options = parse(ctx);
        Keyspace keyspace = ctx.keyspace();
        Bytes key = ctx.key();

        RedisValue existing = keyspace.get(key);

        // GET reports the OLD value, and must raise WRONGTYPE on a non-string even when NX/XX is
        // about to abort the write — so it is evaluated before the abort checks, not after.
        Reply old = options.get()
            ? (existing == null ? Reply.NIL : new Reply.Bulk(Args.asString(existing).value()))
            : null;

        if (options.nx() && existing != null) {
            return options.get() ? old : Reply.NIL;
        }
        if (options.xx() && existing == null) {
            return options.get() ? old : Reply.NIL;
        }

        StringValue value = new StringValue(ctx.arg(2));
        if (options.keepTtl()) {
            keyspace.setKeepTtl(key, value);
        } else if (options.ttlMs() != NO_TTL) {
            keyspace.setWithDeadline(key, value, deadline(keyspace.nowMs(), options.ttlMs()));
        } else {
            keyspace.set(key, value);  // a plain SET clears any existing TTL
        }

        return options.get() ? old : Reply.OK;
    }

    /**
     * Walks the option list once, rejecting contradictions as it goes.
     *
     * <p>The subtle parts are not the individual flags but the interactions: NX and XX exclude
     * each other, at most one expiry option may appear (and KEEPTTL counts as one), and EX/PX
     * consume the argument that follows — so a trailing {@code EX} with nothing after it is a
     * syntax error rather than a silently ignored token.
     */
    private static Options parse(CommandContext ctx) {
        boolean nx = false;
        boolean xx = false;
        boolean get = false;
        boolean keepTtl = false;
        long ttlMs = NO_TTL;

        for (int i = 3; i < ctx.argCount(); i++) {
            String option = Args.upper(ctx.arg(i));
            switch (option) {
                case "NX" -> {
                    if (xx) {
                        throw new SyntaxException();
                    }
                    nx = true;
                }
                case "XX" -> {
                    if (nx) {
                        throw new SyntaxException();
                    }
                    xx = true;
                }
                case "GET" -> get = true;
                case "KEEPTTL" -> {
                    if (ttlMs != NO_TTL) {
                        throw new SyntaxException();
                    }
                    keepTtl = true;
                }
                case "EX", "PX" -> {
                    if (keepTtl || ttlMs != NO_TTL) {
                        throw new SyntaxException();
                    }
                    if (++i >= ctx.argCount()) {
                        throw new SyntaxException();
                    }
                    long amount = Args.toLong(ctx.arg(i));
                    if (amount <= 0) {
                        throw new MnemoException("ERR", "invalid expire time in 'set' command");
                    }
                    ttlMs = option.equals("EX") ? secondsToMillis(amount) : amount;
                }
                default -> throw new SyntaxException();
            }
        }
        return new Options(nx, xx, get, keepTtl, ttlMs);
    }

    // multiplyExact, not `* 1000`: SET k v EX 9223372036854775807 must be rejected rather than
    // wrapping into a deadline in the past, which would delete the key on the very next read.
    private static long secondsToMillis(long seconds) {
        try {
            return Math.multiplyExact(seconds, 1000L);
        } catch (ArithmeticException overflow) {
            throw new MnemoException("ERR", "invalid expire time in 'set' command");
        }
    }

    private static long deadline(long nowMs, long ttlMs) {
        try {
            return Math.addExact(nowMs, ttlMs);
        } catch (ArithmeticException overflow) {
            throw new MnemoException("ERR", "invalid expire time in 'set' command");
        }
    }
}
