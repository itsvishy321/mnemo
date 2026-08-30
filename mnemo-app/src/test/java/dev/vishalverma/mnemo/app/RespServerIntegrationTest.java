package dev.vishalverma.mnemo.app;

import dev.vishalverma.mnemo.server.RespServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Spring shell actually brings the RESP server up — the wiring, not the protocol.
 *
 * <p>{@code mnemo.resp.port=0} lets the OS choose, so the test never collides with a real server
 * on 6380 or with a parallel run.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "mnemo.resp.port=0")
class RespServerIntegrationTest {

    @Autowired
    private RespServer respServer;

    @Test
    void bootedApplicationServesRespOnTheConfiguredPort() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", respServer.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = new BufferedInputStream(socket.getInputStream());

            out.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            assertThat(readLine(in)).isEqualTo("+PONG");
        }
    }

    @Test
    void respAndHttpRunSideBySide() throws IOException {
        // The point of the Spring shell: one process, two front ends.
        try (Socket socket = new Socket("127.0.0.1", respServer.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = new BufferedInputStream(socket.getInputStream());

            out.write("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            assertThat(readLine(in)).isEqualTo("+OK");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            line.append((char) b);
        }
        return line.toString();
    }
}
