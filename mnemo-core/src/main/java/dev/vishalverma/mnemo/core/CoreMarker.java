package dev.vishalverma.mnemo.core;

/**
 * Placeholder occupying mnemo-core until Milestone 1 (Keyspace, ValueEntry, Command/Reply)
 * lands. Its only job right now is to give this module compilable content, so the ArchUnit
 * rule in {@code dev.vishalverma.mnemo.arch} and the pure-JDK Gradle check both have something
 * real to analyze instead of an empty class set.
 */
public final class CoreMarker {

    public static final String MODULE_NAME = "mnemo-core";

    private CoreMarker() {
    }
}
