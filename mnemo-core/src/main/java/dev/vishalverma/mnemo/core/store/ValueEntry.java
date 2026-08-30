package dev.vishalverma.mnemo.core.store;

import dev.vishalverma.mnemo.core.type.RedisValue;

/**
 * One key's stored state: the value plus the metadata expiry, eviction, and replication will need.
 *
 * <p>Package-private and mutable on purpose. {@code lastAccess} and {@code lfuCounter} are written
 * on every <em>read</em>, so making this an immutable record would force a {@code map.put} on the
 * {@code GET} path — turning reads into writes. That is the same contention trap that rules out a
 * textbook move-to-front LRU list, wearing a different hat.
 *
 * <p>Commands never see this type; they go through {@link Keyspace}. That is what makes it
 * impossible to forget the expiry check or the version bump.
 */
final class ValueEntry {

    RedisValue value;
    long expireAtMs;   // absolute wall-clock deadline; 0 = no TTL
    int lastAccess;    // coarse seconds clock, for LRU (written in M1, read in M5)
    byte lfuCounter;   // 8-bit Morris counter, for LFU (M5)
    long version;      // keyspace-scoped and monotonic — see Keyspace.nextVersion
    int sizeBytes;     // cached estimate; M5 calibrates the constants

    ValueEntry(RedisValue value, long version, int nowSeconds) {
        this.value = value;
        this.version = version;
        this.lastAccess = nowSeconds;
        this.sizeBytes = value.sizeBytes();
    }

    boolean isExpired(long nowMs) {
        return expireAtMs != 0 && expireAtMs <= nowMs;
    }
}
