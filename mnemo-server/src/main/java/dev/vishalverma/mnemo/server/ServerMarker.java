package dev.vishalverma.mnemo.server;

import dev.vishalverma.mnemo.persistence.PersistenceMarker;
import dev.vishalverma.mnemo.protocol.ProtocolMarker;
import io.netty.util.Version;

import java.util.Map;

/**
 * Placeholder occupying mnemo-server until the Netty transport (Milestone 2/6) lands. Touches
 * a real Netty class so the dependency is exercised, not just declared.
 */
public final class ServerMarker {

    public static final String MODULE_NAME = "mnemo-server";
    public static final String DEPENDS_ON = ProtocolMarker.MODULE_NAME + ", " + PersistenceMarker.MODULE_NAME;

    private ServerMarker() {
    }

    public static Map<String, Version> nettyVersions() {
        return Version.identify();
    }
}
