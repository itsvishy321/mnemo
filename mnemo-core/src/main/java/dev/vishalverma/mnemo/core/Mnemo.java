package dev.vishalverma.mnemo.core;

/** Project-wide constants. */
public final class Mnemo {

    /**
     * Reported by {@code INFO} as {@code mnemo_version}.
     *
     * <p>A constant rather than the Gradle project version because nothing in the build declares
     * one yet, and generating a resource to carry it is packaging work that belongs with M10.
     * Bump it by hand until then.
     */
    public static final String VERSION = "0.2.0";

    private Mnemo() {
    }
}
