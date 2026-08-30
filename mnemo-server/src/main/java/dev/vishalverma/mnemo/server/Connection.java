package dev.vishalverma.mnemo.server;

import dev.vishalverma.mnemo.core.Engine;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.Bytes;
import dev.vishalverma.mnemo.protocol.ProtocolException;
import dev.vishalverma.mnemo.protocol.RespDecoder;
import dev.vishalverma.mnemo.protocol.RespEncoder;
import dev.vishalverma.mnemo.protocol.RespLimits;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Lock;

/**
 * One client connection: read bytes, parse commands, execute, write replies.
 *
 * <p>Lifecycle is deliberately minimal — {@code ACTIVE → CLOSING}. Idle timeouts, backpressure,
 * and draining belong to M6; {@code IN_MULTI} and {@code SUBSCRIBED} to M8.
 */
final class Connection implements Runnable {

    private static final int READ_BUFFER = 16 * 1024;

    private final Socket socket;
    private final Engine engine;
    private final Lock dispatchLock;
    private final RespDecoder decoder;

    Connection(Socket socket, Engine engine, Lock dispatchLock, RespLimits limits) {
        this.socket = socket;
        this.engine = engine;
        this.dispatchLock = dispatchLock;
        this.decoder = new RespDecoder(limits);
    }

    @Override
    public void run() {
        byte[] chunk = new byte[READ_BUFFER];
        try (Socket managed = socket;
             InputStream in = managed.getInputStream();
             OutputStream out = new BufferedOutputStream(managed.getOutputStream())) {

            int read;
            while ((read = in.read(chunk)) != -1) {
                decoder.feed(chunk, 0, read);
                if (!drain(out)) {
                    return;   // QUIT — reply already flushed by drain()
                }
                // One flush per read, not per command: a pipelined batch collapses into a single
                // write. This is the whole of M2's pipelining support.
                out.flush();
            }
        } catch (ProtocolException e) {
            // The byte stream can no longer be framed, so there is nothing safe to reply with.
            // Closing is the specified response to a limit violation or malformed input.
            closeQuietly();
        } catch (IOException e) {
            // Client vanished mid-command, or the socket broke. Normal; nothing to report.
            closeQuietly();
        }
    }

    /** Executes every complete command currently buffered. Returns false if the client sent QUIT. */
    private boolean drain(OutputStream out) throws IOException {
        List<Bytes> args;
        while ((args = decoder.next()) != null) {
            Reply reply = execute(args);
            RespEncoder.encode(reply, out);

            if (isQuit(args)) {
                out.flush();   // acknowledge before hanging up
                return false;
            }
        }
        return true;
    }

    private Reply execute(List<Bytes> args) {
        // The engine's Keyspace is a plain HashMap, single-threaded by design, so concurrent
        // connections must not enter it at once. Serialising here keeps reads, parsing, and
        // encoding parallel while making execution safe.
        //
        // A ReentrantLock rather than `synchronized` on purpose: on Java 21 a virtual thread that
        // blocks inside `synchronized` pins its carrier thread, and M6 adds a virtual-thread
        // transport. Choosing `synchronized` here would plant a deadlock that only surfaces then.
        //
        // Temporary by design — M5 replaces this with one writer per shard.
        dispatchLock.lock();
        try {
            return engine.dispatch(args);   // total: never throws
        } finally {
            dispatchLock.unlock();
        }
    }

    private static boolean isQuit(List<Bytes> args) {
        return !args.isEmpty() && args.get(0).toString().toUpperCase(Locale.ROOT).equals("QUIT");
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already broken; nothing useful to do.
        }
    }
}
