package dev.vishalverma.mnemo.protocol;

import dev.vishalverma.mnemo.core.CoreMarker;

/**
 * Placeholder occupying mnemo-protocol until the incremental RESP2 decoder/encoder (Milestone
 * 2) lands. Depends on mnemo-core to prove the module dependency, still pure JDK.
 */
public final class ProtocolMarker {

    public static final String MODULE_NAME = "mnemo-protocol";
    public static final String DEPENDS_ON = CoreMarker.MODULE_NAME;

    private ProtocolMarker() {
    }
}
