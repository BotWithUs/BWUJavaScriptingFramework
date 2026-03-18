package com.botwithus.bot.core.pipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/**
 * Reads JPEG frames from a one-way stream pipe created by the game server's
 * {@code start_stream} RPC. The pipe uses length-prefixed framing:
 * {@code [4-byte LE uint32 size][JPEG bytes]}.
 *
 * <p>Runs a read loop on a virtual thread and delivers each raw JPEG
 * {@code byte[]} to a callback. Call {@link #close()} to stop.</p>
 */
public class StreamPipeReader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StreamPipeReader.class);
    private static final String PIPE_PREFIX = "\\\\.\\pipe\\";
    private static final int MAX_FRAME_SIZE = 8 * 1024 * 1024; // 8 MB

    private final String pipePath;
    private final Consumer<byte[]> frameCallback;
    private Consumer<String> errorCallback;
    private volatile boolean running;
    private RandomAccessFile pipeFile;

    public StreamPipeReader(String pipeName, Consumer<byte[]> frameCallback) {
        // Server may return full path (\\.\pipe\...) or bare name
        this.pipePath = pipeName.startsWith(PIPE_PREFIX) ? pipeName : PIPE_PREFIX + pipeName;
        this.frameCallback = frameCallback;
    }

    public void setErrorCallback(Consumer<String> errorCallback) {
        this.errorCallback = errorCallback;
    }

    /**
     * Opens the pipe and starts reading frames on a virtual thread.
     */
    public void start() {
        if (running) return;
        running = true;
        Thread.ofVirtual().name("stream-reader").start(this::readLoop);
    }

    private void readLoop() {
        try {
            // Use RandomAccessFile with "r" (read-only) to open the pipe.
            // On Windows, this calls CreateFileA with GENERIC_READ which
            // connects to a PIPE_ACCESS_OUTBOUND server pipe.
            pipeFile = new RandomAccessFile(pipePath, "r");
            reportError("Stream pipe connected: " + pipePath);

            byte[] header = new byte[4];
            while (running) {
                readFully(header);
                int length = ByteBuffer.wrap(header)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt();
                if (length <= 0 || length > MAX_FRAME_SIZE) {
                    reportError("Invalid frame size: " + length + " — stopping stream.");
                    break;
                }
                byte[] frame = new byte[length];
                readFully(frame);
                if (running) {
                    frameCallback.accept(frame);
                }
            }
        } catch (IOException e) {
            if (running) {
                reportError("Stream pipe error: " + e.getMessage());
            }
        } finally {
            running = false;
            closePipe();
        }
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = pipeFile.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("Stream pipe closed");
            off += n;
        }
    }

    private void reportError(String message) {
        Consumer<String> cb = this.errorCallback;
        if (cb != null) {
            cb.accept(message);
        }
    }

    private void closePipe() {
        if (pipeFile != null) {
            try { pipeFile.close(); } catch (IOException e) {
                log.error("Error closing stream pipe: {}", e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        closePipe();
    }
}
