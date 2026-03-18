package com.botwithus.bot.core.pipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    private static final String PIPE_PREFIX = "\\\\.\\pipe\\";
    private static final String DEFAULT_PIPE_NAME = "BotWithUs";

    private final String pipePath;
    private final RandomAccessFile pipe;
    private final FileInputStream pipeInput;
    private volatile boolean open = true;

    public PipeClient() {
        this(DEFAULT_PIPE_NAME);
    }

    public PipeClient(String pipeName) {
        this.pipePath = PIPE_PREFIX + pipeName;
        try {
            this.pipe = new RandomAccessFile(pipePath, "rw");
            this.pipeInput = new FileInputStream(pipe.getFD());
        } catch (IOException e) {
            throw new PipeException("Failed to connect to pipe: " + pipePath, e);
        }
    }

    public static List<String> scanPipes() {
        return scanPipes("BotWithUs");
    }

    public static List<String> scanPipes(String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        try (Stream<Path> stream = Files.list(Path.of(PIPE_PREFIX))) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase().contains(lowerPrefix))
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
        if (!open) return 0;
        try {
            return pipeInput.available();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Sends a length-prefixed message over the pipe.
     * <p>Not thread-safe — caller must ensure exclusive pipe access.</p>
     */
    public void send(byte[] data) {
        if (!open) throw new PipeException("Pipe is closed");
        try {
            byte[] header = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(data.length)
                    .array();
            // Combine header + body into a single write. On Windows named pipes
            // in message mode, each WriteFile call is a separate pipe message.
            // Split writes would cause the server to read the 4-byte header as
            // its own message and crash trying to parse it as msgpack.
            byte[] frame = new byte[header.length + data.length];
            System.arraycopy(header, 0, frame, 0, header.length);
            System.arraycopy(data, 0, frame, header.length, data.length);
            pipe.write(frame);
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
        if (!open) throw new PipeException("Pipe is closed");
        try {
            byte[] header = new byte[4];
            readFully(header);
            int length = ByteBuffer.wrap(header)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
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
            int n = pipe.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("Pipe closed");
            off += n;
        }
    }

    @Override
    public void close() {
        open = false;
        try { pipeInput.close(); } catch (IOException ignored) {}
        try { pipe.close(); } catch (IOException e) {
            log.error("Error closing pipe {}: {}", pipePath, e.getMessage());
        }
    }
}
