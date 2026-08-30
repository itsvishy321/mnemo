package dev.vishalverma.mnemo.core.command;

/**
 * Properties of a command that the dispatcher — not the command itself — acts on.
 *
 * <p>Nothing enforces {@link #DENYOOM} in M1; that needs M5's memory accounting. Tagging commands
 * correctly now means M5 adds one check in the dispatcher instead of auditing every command class.
 */
public enum Flag {

    /** Mutates the keyspace. */
    WRITE,

    /** Only reads. */
    READONLY,

    /** Administrative; not part of the normal data path. */
    ADMIN,

    /**
     * May increase memory, so it must be refused once memory is exhausted under the
     * {@code noeviction} policy — while {@code GET} and {@code DEL} keep working, which is what
     * keeps a full instance readable and recoverable rather than bricked.
     */
    DENYOOM
}
