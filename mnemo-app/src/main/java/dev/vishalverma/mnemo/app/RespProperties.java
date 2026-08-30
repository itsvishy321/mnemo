package dev.vishalverma.mnemo.app;

import dev.vishalverma.mnemo.server.ServerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code mnemo.resp.*} from application config.
 *
 * <p>This type exists so Spring's annotations stay in {@code mnemo-app}: it converts into the
 * plain {@link ServerConfig} record that the server module actually consumes. The dependency
 * arrow points from Spring into plain Java and never back.
 *
 * <p>Components are boxed so "unset" is distinguishable from a deliberate 0 — {@code port: 0} is
 * a real request for an OS-assigned port, which the tests rely on.
 */
@ConfigurationProperties("mnemo.resp")
record RespProperties(Integer port, Integer backlog) {

    ServerConfig toServerConfig() {
        ServerConfig defaults = ServerConfig.defaults();
        return new ServerConfig(
            port != null ? port : defaults.port(),
            backlog != null ? backlog : defaults.backlog(),
            defaults.limits());
    }
}
