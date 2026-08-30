package dev.vishalverma.mnemo.server;

import dev.vishalverma.mnemo.protocol.RespLimits;

/**
 * How the RESP server should be started.
 *
 * <p>A plain record with no annotations. Spring binds <em>into</em> this from {@code mnemo-app};
 * the dependency arrow never points back out, which is what keeps the framework at the edge.
 *
 * @param port      TCP port; 0 asks the OS for a free one, which is what tests use
 * @param backlog   accept queue depth
 * @param limits    protocol bounds applied to every connection
 */
public record ServerConfig(int port, int backlog, RespLimits limits) {

    public ServerConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (backlog <= 0) {
            throw new IllegalArgumentException("backlog must be positive");
        }
    }

    public static ServerConfig defaults() {
        // 6380, not 6379 — so a real Redis can run alongside for differential testing.
        return new ServerConfig(6380, 128, RespLimits.defaults());
    }

    public ServerConfig withPort(int newPort) {
        return new ServerConfig(newPort, backlog, limits);
    }
}
