package dev.vishalverma.mnemo.core.store;

import dev.vishalverma.mnemo.core.type.Bytes;
import dev.vishalverma.mnemo.core.type.RedisValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The keyspace: every key the engine holds, and the only type commands use to reach storage.
 *
 * <p><strong>Single-shard and not thread-safe.</strong> A plain {@link HashMap} is correct here
 * because there is exactly one thread. M5 introduces shards, each owned by one writer, and the
 * facade shape means commands will not change when it does.
 *
 * <p>Keeping {@link ValueEntry} package-private and routing every read through {@link #live} is
 * the design decision that carries the most weight in this class: lazy expiry and the version
 * bump each live in exactly one place, so no command can forget them.
 */
public final class Keyspace {

    private final Map<Bytes, ValueEntry> map = new HashMap<>();
    private final Clock clock;

    /**
     * Monotonic across the whole keyspace, not per entry. A per-entry counter would restart at 0
     * when a key is deleted and recreated, so the ABA case — delete a watched key, put an
     * identical value back — would look unchanged and {@code WATCH}/{@code EXEC} would wrongly
     * succeed. Nothing reads this until M7; getting it wrong now means re-auditing every
     * mutation site then.
     */
    private long nextVersion = 1;

    public Keyspace() {
        this(Clock.SYSTEM);
    }

    public Keyspace(Clock clock) {
        this.clock = clock;
    }

    public long nowMs() {
        return clock.nowMs();
    }

    // ---------------------------------------------------------------- reads

    /** The value, or {@code null} if the key is absent or has expired. */
    public RedisValue get(Bytes key) {
        ValueEntry entry = live(key);
        return entry == null ? null : entry.value;
    }

    public boolean exists(Bytes key) {
        return live(key) != null;
    }

    /** The name {@code TYPE} reports — {@code none} when the key is absent, never an error. */
    public String type(Bytes key) {
        RedisValue value = get(key);
        return value == null ? "none" : value.typeName();
    }

    /**
     * Number of live keys. Sweeps expired entries first so the count cannot drift from what
     * {@code GET} would report — affordable while the keyspace is small and single-shard, and it
     * lets TTL tests assert that memory is actually reclaimed. M3's active expirer makes the raw
     * {@code map.size()} accurate and this sweep unnecessary.
     */
    public int size() {
        purgeExpired();
        return map.size();
    }

    // --------------------------------------------------------------- writes

    /** Sets the value and clears any TTL — plain {@code SET} semantics. */
    public void set(Bytes key, RedisValue value) {
        write(key, value, TtlAction.CLEAR, 0);
    }

    /** Sets the value and leaves any existing TTL alone — {@code SET ... KEEPTTL}, {@code INCR}. */
    public void setKeepTtl(Bytes key, RedisValue value) {
        write(key, value, TtlAction.KEEP, 0);
    }

    /** Sets the value and an absolute expiry deadline — {@code SET ... EX/PX}. */
    public void setWithDeadline(Bytes key, RedisValue value, long deadlineMs) {
        write(key, value, TtlAction.SET, deadlineMs);
    }

    /** Removes the key, reporting whether it was actually there and live. */
    public boolean delete(Bytes key) {
        if (live(key) == null) {
            return false;
        }
        map.remove(key);
        return true;
    }

    public void flush() {
        map.clear();
    }

    // -------------------------------------------------------------- internals

    private enum TtlAction { CLEAR, KEEP, SET }

    /**
     * Lazy expiry, and the single choke point every read passes through.
     *
     * <p>Deleting the entry rather than merely hiding it is what makes lazy expiry sufficient for
     * <em>correctness</em> on its own. It is not sufficient for <em>memory</em> — a key that
     * expires and is never read again would sit there forever, which is the different problem
     * M3's active expirer solves.
     */
    private ValueEntry live(Bytes key) {
        ValueEntry entry = map.get(key);
        if (entry == null) {
            return null;
        }
        long now = clock.nowMs();
        if (entry.isExpired(now)) {
            map.remove(key);
            return null;
        }
        entry.lastAccess = (int) (now / 1000);
        return entry;
    }

    private void write(Bytes key, RedisValue value, TtlAction ttl, long deadlineMs) {
        ValueEntry entry = live(key);
        if (entry == null) {
            entry = new ValueEntry(value, nextVersion++, (int) (clock.nowMs() / 1000));
            map.put(key, entry);
        } else {
            // Mutated in place rather than replaced: one entry object per key lifetime is what
            // keeps `version` a meaningful "has this key changed?" signal.
            entry.value = value;
            entry.sizeBytes = value.sizeBytes();
            entry.version = nextVersion++;
        }
        switch (ttl) {
            case CLEAR -> entry.expireAtMs = 0;
            case SET -> entry.expireAtMs = deadlineMs;
            case KEEP -> { }
        }
    }

    private void purgeExpired() {
        long now = clock.nowMs();
        Iterator<Map.Entry<Bytes, ValueEntry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired(now)) {
                it.remove();
            }
        }
    }

    /** Live keys, for {@code FLUSHDB} accounting and tests. Not ordered. */
    public List<Bytes> keys() {
        purgeExpired();
        return new ArrayList<>(map.keySet());
    }
}
