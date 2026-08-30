package dev.vishalverma.mnemo.server;

import dev.vishalverma.mnemo.core.Engine;
import dev.vishalverma.mnemo.protocol.RespLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the server over a real loopback socket — the only way to prove the whole path (accept,
 * read, decode, dispatch, encode, write) actually fits together.
 */
class RespServerTest {

    private RespServer server;

    @BeforeEach
    void startServer() {
        // Port 0: the OS picks a free one, so parallel runs and CI never collide.
        server = new RespServer(new Engine(), new ServerConfig(0, 128, RespLimits.defaults()));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", server.port());
        socket.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
        return socket;
    }

    private static void send(Socket socket, String wire) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(wire.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads exactly {@code lines} CRLF-terminated protocol lines. */
    private static List<String> readLines(InputStream in, int lines) throws IOException {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int b;
        while (result.size() < lines && (b = in.read()) != -1) {
            if (b == '\n' && !current.isEmpty() && current.charAt(current.length() - 1) == '\r') {
                current.setLength(current.length() - 1);
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append((char) b);
            }
        }
        return result;
    }

    @Test
    void roundTripsSetAndGet() throws IOException {
        try (Socket socket = connect()) {
            InputStream in = new BufferedInputStream(socket.getInputStream());

            send(socket, "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$3\r\nAda\r\n");
            assertThat(readLines(in, 1)).containsExactly("+OK");

            send(socket, "*2\r\n$3\r\nGET\r\n$1\r\nk\r\n");
            assertThat(readLines(in, 2)).containsExactly("$3", "Ada");
        }
    }

    @Test
    void answersAnInlineCommand() throws IOException {
        try (Socket socket = connect()) {
            InputStream in = new BufferedInputStream(socket.getInputStream());

            send(socket, "PING\r\n");

            assertThat(readLines(in, 1)).containsExactly("+PONG");
        }
    }

    /** A whole batch in one write must produce a whole batch of replies, in order. */
    @Test
    void answersAPipelinedBatchInOrder() throws IOException {
        try (Socket socket = connect()) {
            InputStream in = new BufferedInputStream(socket.getInputStream());

            send(socket, "*1\r\n$4\r\nPING\r\n".repeat(100));

            List<String> replies = readLines(in, 100);
            assertThat(replies).hasSize(100).allMatch("+PONG"::equals);
        }
    }

    /** Fragmentation over a real socket, not just against the decoder in isolation. */
    @Test
    void assemblesACommandDeliveredOneByteAtATime() throws IOException {
        try (Socket socket = connect()) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            byte[] wire = "*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n".getBytes(StandardCharsets.UTF_8);

            OutputStream out = socket.getOutputStream();
            for (byte b : wire) {
                out.write(b);
                out.flush();   // force each byte into its own segment
            }

            assertThat(readLines(in, 2)).containsExactly("$2", "hi");
        }
    }

    @Test
    void reportsUnknownCommandsWithoutDroppingTheConnection() throws IOException {
        try (Socket socket = connect()) {
            InputStream in = new BufferedInputStream(socket.getInputStream());

            send(socket, "*1\r\n$7\r\nNOSUCH1\r\n");
            assertThat(readLines(in, 1).get(0)).startsWith("-ERR unknown command");

            // A bad command is a reply, not a disconnect — the session must survive it.
            send(socket, "*1\r\n$4\r\nPING\r\n");
            assertThat(readLines(in, 1)).containsExactly("+PONG");
        }
    }

    @Test
    void quitAcknowledgesThenClosesTheConnection() throws IOException {
        try (Socket socket = connect()) {
            InputStream in = new BufferedInputStream(socket.getInputStream());

            send(socket, "*1\r\n$4\r\nQUIT\r\n");

            assertThat(readLines(in, 1)).containsExactly("+OK");
            // End of stream is how a clean server-side close presents to the client.
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    void closesTheConnectionOnAProtocolViolation() throws IOException {
        try (Socket socket = connect()) {
            InputStream in = new BufferedInputStream(socket.getInputStream());

            // A declared length that must be refused outright rather than allocated.
            send(socket, "*1\r\n$999999999999\r\n");

            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    void servesSeveralConnectionsAtOnce() throws Exception {
        int clients = 8;
        CountDownLatch done = new CountDownLatch(clients);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < clients; i++) {
            String key = "k" + i;
            Thread thread = new Thread(() -> {
                try (Socket socket = connect()) {
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    send(socket, "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key
                        + "\r\n$1\r\nv\r\n");
                    if (readLines(in, 1).equals(List.of("+OK"))) {
                        done.countDown();
                    }
                } catch (IOException e) {
                    // Leaves the latch un-counted, which fails the assertion below.
                }
            });
            threads.add(thread);
            thread.start();
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        for (Thread thread : threads) {
            thread.join();
        }

        // Every client's write must be visible — proof the dispatch lock did its job.
        try (Socket socket = connect()) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            send(socket, "*1\r\n$6\r\nDBSIZE\r\n");
            assertThat(readLines(in, 1)).containsExactly(":" + clients);
        }
    }
}
