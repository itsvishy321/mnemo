package dev.vishalverma.mnemo.core.store;

/**
 * The engine's source of wall-clock time.
 *
 * <p>Wall clock, not monotonic, and that is deliberate: TTL deadlines must survive a restart and
 * be comparable with a client-supplied {@code EXPIREAT} timestamp, neither of which a monotonic
 * clock can do. Monotonic time is the right choice for measuring <em>intervals</em>, which is a
 * different job and arrives with M3's reaper.
 *
 * <p>It is an interface purely so tests can advance time by hand. Awaitility is not on this
 * module's test classpath, and even where it is, sleeping to observe an expiry is slower and
 * flakier than simply choosing what "now" means.
 */
@FunctionalInterface
public interface Clock {

    Clock SYSTEM = System::currentTimeMillis;

    long nowMs();
}
