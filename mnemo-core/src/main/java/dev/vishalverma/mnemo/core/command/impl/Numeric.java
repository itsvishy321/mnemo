package dev.vishalverma.mnemo.core.command.impl;

import dev.vishalverma.mnemo.core.command.Args;
import dev.vishalverma.mnemo.core.command.CommandContext;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.error.MnemoException;
import dev.vishalverma.mnemo.core.type.Bytes;
import dev.vishalverma.mnemo.core.type.RedisValue;
import dev.vishalverma.mnemo.core.type.StringValue;

/**
 * The shared body of INCR / INCRBY / DECR / DECRBY.
 *
 * <p>A package-private helper rather than a base class: the four commands differ only in where
 * the delta comes from, and sharing a function is simpler than sharing an inheritance hierarchy.
 * It also keeps the "adding a command touches two files" rule intact, which is about there being
 * no central dispatch switch or metadata table — not about never reusing a method.
 */
final class Numeric {

    private Numeric() {
    }

    /**
     * Applies a delta to the integer stored at {@code key}, treating a missing key as 0.
     *
     * <p>{@code subtract} rather than negating the delta at the call site: {@code DECRBY key
     * -9223372036854775808} is legal, but negating that value overflows even though the
     * subtraction itself is perfectly representable.
     */
    static Reply applyDelta(CommandContext ctx, long delta, boolean subtract) {
        Bytes key = ctx.key();
        RedisValue existing = ctx.keyspace().get(key);
        long current = existing == null ? 0 : Args.toLong(Args.asString(existing).value());

        long next;
        try {
            next = subtract ? Math.subtractExact(current, delta) : Math.addExact(current, delta);
        } catch (ArithmeticException overflow) {
            // Redis errors rather than wrapping — a silently wrapped counter is worse than a
            // refused write, because nothing downstream can tell it happened.
            throw new MnemoException("ERR", "increment or decrement would overflow");
        }

        // Counters keep their TTL across increments — unlike SET, which clears it.
        ctx.keyspace().setKeepTtl(key, new StringValue(Args.fromLong(next)));
        return new Reply.Int(next);
    }
}
