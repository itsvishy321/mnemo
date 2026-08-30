package dev.vishalverma.mnemo.app;

import dev.vishalverma.mnemo.core.Engine;
import dev.vishalverma.mnemo.server.RespServer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the engine and the RESP server into the running process.
 *
 * <p>All of Spring's involvement with the data path lives here: constructing objects and owning
 * their lifecycle. Nothing below this module knows Spring exists.
 */
@Configuration
@EnableConfigurationProperties(RespProperties.class)
class MnemoConfiguration {

    @Bean
    Engine engine() {
        return new Engine();
    }

    /**
     * Starts the server as it is created, and lets Spring close it on shutdown via
     * {@code destroyMethod}.
     *
     * <p>Starting here rather than through a {@code SmartLifecycle} keeps the wiring to one method:
     * the server's only dependency is the engine, which Spring has already supplied by this point,
     * so there is nothing later in the refresh worth waiting for. A port clash then fails context
     * startup loudly, which is the behaviour you want.
     */
    @Bean(destroyMethod = "close")
    RespServer respServer(Engine engine, RespProperties properties) {
        RespServer server = new RespServer(engine, properties.toServerConfig());
        server.start();
        return server;
    }
}
