package com.botwithus.bot.core.pipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Named pipe client connecting to \\.\pipe\BotWithUs.
 * Uses 4-byte LE length-prefix framing.
 *
 * <p>Windows named pipes opened via {@link RandomAccessFile} use synchronous
 * (non-overlapped) handles where all I/O is serialized by the kernel.
 * Concurrent read and write from different threads will deadlock. Callers
 * must ensure only one thread accesses the pipe at a time.</p>
 *
 * <p>Use {@link #available()} to check for data without blocking. This calls
 * {@code PeekNamedPipe} under the hood via {@link FileInputStream#available()}.</p>
 */
public class PipeClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PipeClient.class);
    static final String PIPE_PREFIX = "\\\\.\\pipe\\";

    /**
     * Producer-side name prefix. NXTLibrary publishes one pipe per injected
     * game as {@code BotWithUs_<pid>}; the legacy single {@code BotWithUs}
     * name (no suffix) was retired so the suffix doubles as a discovery key
     * for the snapshot mapping at {@code Local\nxt_snapshot_<pid>}.
     */
    public static final String NAME_PREFIX = "BotWithUs_";

    private final String pipePath;
    private volatile Transport transport;
    private volatile boolean open = true;

    /**
     * Auto-discover: scans for an available {@code BotWithUs_<pid>} pipe and
     * connects to the first match. Throws {@link PipeException} if none is
     * visible — i.e. the DLL isn't injected into any running game.
     *
     * <p>For multi-game setups, prefer the explicit-name constructor with a
     * pid you've selected via {@link #scanPipes()}.</p>
     */
    public PipeClient() {
        this(firstAvailableOrThrow());
    }

    public PipeClient(String pipeName) {
        this.pipePath = PIPE_PREFIX + pipeName;
        this.transport = openTransport(pipePath);
    }

    /**
     * Returns the first pipe name matching {@link #NAME_PREFIX}, or throws
     * {@link PipeException} if none is visible.
     */
    public static String firstAvailableOrThrow() {
        List<String> candidates = scanPipes(NAME_PREFIX);
        if (candidates.isEmpty()) {
            throw new PipeException(
                    "No " + NAME_PREFIX + "<pid> pipes visible — is the BotWithUs DLL injected?");
        }
        return candidates.getFirst();
    }

    static Transport openTransport(String pipePath) {
        try {
            RandomAccessFile pipe = new RandomAccessFile(pipePath, "rw");
            FileInputStream pipeInput = new FileInputStream(pipe.getFD());
            return new Transport(pipe, pipeInput);
        } catch (IOException e) {
            throw new PipeException("Failed to connect to pipe: " + pipePath, e);
        }
    }

    public static List<String> scanPipes() {
        return scanPipes("BotWithUs");
    }

    public static List<String> scanPipes(String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.list(Path.of(PIPE_PREFIX))) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains(lowerPrefix))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public String getPipePath() {
        return pipePath;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Returns the number of bytes available to read without blocking.
     * Uses {@code PeekNamedPipe} on Windows.
     */
    public int available() {
        if (!open) {
            return 0;
        }
        try {
            return transport.input.available();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Sends a length-prefixed message over the pipe.
     * <p>Not thread-safe — caller must ensure exclusive pipe access.</p>
     */
    public void send(byte[] data) {
        if (!open) {
            throw new PipeException("Pipe is closed");
        }
        int n = data.length;
        byte[] frame = new byte[4 + n];
        frame[0] = (byte) n;
        frame[1] = (byte) (n >>> 8);
        frame[2] = (byte) (n >>> 16);
        frame[3] = (byte) (n >>> 24);
        System.arraycopy(data, 0, frame, 4, n);
        // Combine header + body into a single write. On Windows named pipes
        // in message mode, each WriteFile call is a separate pipe message.
        // Split writes would cause the server to read the 4-byte header as
        // its own message and crash trying to parse it as msgpack.
        try {
            transport.pipe.write(frame);
        } catch (IOException e) {
            throw new PipeException("Failed to send message", e);
        }
    }

    /**
     * Reads the next length-prefixed message from the pipe.
     * Blocks until a complete message is available.
     * <p>Not thread-safe — caller must ensure exclusive pipe access.</p>
     */
    public byte[] readMessage() {
        if (!open) {
            throw new PipeException("Pipe is closed");
        }
        try {
            byte[] header = new byte[4];
            readFully(header);
            int length = (header[0] & 0xFF)
                    | ((header[1] & 0xFF) << 8)
                    | ((header[2] & 0xFF) << 16)
                    | ((header[3] & 0xFF) << 24);
            if (length <= 0 || length > 16 * 1024 * 1024) {
                throw new PipeException("Invalid message length: " + length);
            }
            byte[] payload = new byte[length];
            readFully(payload);
            return payload;
        } catch (IOException e) {
            throw new PipeException("Pipe read error", e);
        }
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = transport.pipe.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("Pipe closed");
            }
            off += n;
        }
    }

    /**
     * Swaps the underlying pipe transport atomically, closing the previous one.
     *
     * <p>Caller must hold any external I/O lock (e.g. {@code RpcClient}'s pipe
     * lock) to ensure no thread is mid-read/write during the swap.</p>
     */
    void swapTransport(Transport next) {
        Transport prev = this.transport;
        this.transport = next;
        this.open = true;
        closeTransport(prev);
    }

    private static void closeTransport(Transport t) {
        if (t == null) {
            return;
        }
        try {
            t.input.close();
        } catch (IOException e) {
            log.debug("Pipe input close failed", e);
        }
        // RandomAccessFile and FileInputStream share the same FD; closing the
        // input above already closed the native handle. Calling pipe.close()
        // here lets the Java-side object release its bookkeeping.
        try {
            t.pipe.close();
        } catch (IOException e) {
            log.debug("Pipe handle close failed", e);
        }
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        closeTransport(transport);
    }

    /**
     * Owned pair of {@link RandomAccessFile} (read+write) and a
     * {@link FileInputStream} view over the same FD (for {@code available()}).
     */
    static final class Transport {
        final RandomAccessFile pipe;
        final FileInputStream input;

        Transport(RandomAccessFile pipe, FileInputStream input) {
            this.pipe = pipe;
            this.input = input;
        }
    }
}
