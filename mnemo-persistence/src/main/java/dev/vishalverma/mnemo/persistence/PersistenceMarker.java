package dev.vishalverma.mnemo.persistence;

import dev.vishalverma.mnemo.core.CoreMarker;

/**
 * Placeholder occupying mnemo-persistence until the snapshot writer/reader and AOF (Milestone
 * 7) land. Depends on mnemo-core to prove the module dependency, still pure JDK.
 */
public final class PersistenceMarker {

    public static final String MODULE_NAME = "mnemo-persistence";
    public static final String DEPENDS_ON = CoreMarker.MODULE_NAME;

    private PersistenceMarker() {
    }
}
