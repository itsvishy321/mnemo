package dev.vishalverma.mnemo.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M0 definition of done, made executable: the Spring Boot app module starts, and
 * /actuator/health responds UP. Everything else in this module is still a placeholder.
 *
 * <p>Uses {@link RestTestClient} rather than {@code TestRestTemplate}: Spring Boot 4 no longer
 * auto-configures a {@code TestRestTemplate} bean for {@code @SpringBootTest}, and moved the
 * class itself to a separate {@code spring-boot-resttestclient} module. {@code RestTestClient}
 * is the built-in replacement and ships with {@code spring-test}, which is already on the test
 * classpath via {@code spring-boot-starter-test}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

    @LocalServerPort
    private int port;

    @Test
    void applicationContextStartsAndHealthEndpointReportsUp() {
        RestTestClient client = RestTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build();

        client.get().uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .value(body -> assertThat(body).contains("\"status\":\"UP\""));
    }
}
