package dev.vishalverma.mnemo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wiring only. The RESP/TCP data path (mnemo-server) and the engine (mnemo-core) know nothing
 * about Spring; this class — and this module — is where they get assembled into a running
 * process alongside the admin REST surface and Actuator.
 */
@SpringBootApplication
public class MnemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MnemoApplication.class, args);
    }
}
