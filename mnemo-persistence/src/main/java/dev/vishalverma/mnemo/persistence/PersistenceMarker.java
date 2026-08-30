package dev.vishalverma.mnemo.persistence;

/**
 * Placeholder occupying mnemo-persistence until the snapshot writer/reader and AOF (Milestone
 * 7) land. Still pure JDK.
 */
public final class PersistenceMarker {

    public static final String MODULE_NAME = "mnemo-persistence";
    public static final String DEPENDS_ON = "mnemo-core";

    private PersistenceMarker() {
    }
}
