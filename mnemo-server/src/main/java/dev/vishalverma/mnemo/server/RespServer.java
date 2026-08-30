package dev.vishalverma.mnemo.server;

import dev.vishalverma.mnemo.core.Engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A RESP server: one platform thread per connection.
 *
 * <p>This is deliberately the naive model — roughly a megabyte of stack per connection and real
 * context-switch cost at scale. It is here because it is the honest baseline the M6 benchmark
 * measures Netty and virtual threads <em>against</em>; a chart needs something to compare to.
 *
 * <p>Not the final architecture, and the code says so rather than pretending otherwise.
 */
public final class RespServer implements AutoCloseable {

    private final Engine engine;
    private final ServerConfig config;

    /** Serialises command execution; see {@link Connection#execute}. */
    private final Lock dispatchLock = new ReentrantLock();

    private final AtomicLong connectionCount = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;

    public RespServer(Engine engine, ServerConfig config) {
        this.engine = engine;
        this.config = config;
    }

    /** Binds and begins accepting. Returns once the port is open, so callers can connect at once. */
    public void start() {
        if (running) {
            throw new IllegalStateException("server already started");
        }
        try {
            ServerSocket socket = new ServerSocket();
            // Without this, a restart within the TIME_WAIT window fails to bind.
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(config.port()), config.backlog());
            this.serverSocket = socket;
        } catch (IOException e) {
            throw new UncheckedIOException("could not bind RESP port " + config.port(), e);
        }

        running = true;
        acceptThread = new Thread(this::acceptLoop, "mnemo-resp-accept");
        acceptThread.start();
    }

    /** The bound port — resolves the real one when the config asked for 0. */
    public int port() {
        ServerSocket socket = serverSocket;
        if (socket == null) {
            throw new IllegalStateException("server not started");
        }
        return socket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);   // replies are small; Nagle would add latency

                Thread worker = new Thread(
                    new Connection(client, engine, dispatchLock, config.limits()),
                    "mnemo-resp-" + connectionCount.incrementAndGet());
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                // close() shuts the listening socket, which unblocks accept() with an exception.
                // That is the expected way out, so only an unexpected one is worth reacting to.
                if (running) {
                    running = false;
                }
            }
        }
    }

    /**
     * Stops accepting and closes the listening socket. In-flight connections are daemon threads
     * and end when their clients disconnect — draining them properly is M6's graceful shutdown.
     */
    @Override
    public void close() {
        running = false;
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
        }
        Thread thread = acceptThread;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
