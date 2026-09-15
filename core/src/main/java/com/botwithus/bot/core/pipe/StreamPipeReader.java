package com.botwithus.bot.core.pipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    private static final int BYTES_PER_MIB = 1024 * 1024;

    /** Size of the little-endian uint32 length prefix in front of each frame. */
    private static final int HEADER_BYTES = 4;

    /**
     * Upper bound on a single JPEG frame; anything larger is treated as framing
     * corruption. Held equal to the producer's other pipe caps ({@code kMaxMsgSize}
     * in {@code NXTLibrary/src/rpc/PipeServer.cpp}, {@code kMaxFrameBytes} in
     * {@code Broker.cpp}) so all three pipes bound a frame the same way. The
     * producer's {@code start_stream} is still {@code not_implemented}, so this
     * is currently the specification that side has to meet rather than a mirror
     * of a value it already enforces — raise both together or not at all.
     */
    private static final int MAX_FRAME_SIZE = 4 * BYTES_PER_MIB;

    /**
     * Sustained throughput a producer is allowed on the stream pipe. A frame is
     * only self-limiting individually — nothing stopped a producer sending them
     * back to back, and each one costs the consumer a {@code byte[]}, a decoded
     * {@code BufferedImage} and a queued texture upload. A demanding legitimate
     * stream (30 fps of ~300 KiB frames) sits an order of magnitude under both
     * numbers.
     */
    private static final int MAX_FRAMES_PER_WINDOW = 120;
    private static final long MAX_BYTES_PER_WINDOW = 32L * BYTES_PER_MIB;
    private static final long BUDGET_WINDOW_NANOS = 1_000_000_000L;

    private final String pipePath;
    private final Consumer<byte[]> frameCallback;
    private Consumer<String> errorCallback;
    private volatile boolean running;
    // Written by the reader thread on open, read by whichever thread calls
    // close() — volatile so that thread sees the handle rather than null.
    private volatile RandomAccessFile pipeFile;

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
        if (running) {
            return;
        }
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
            pumpFrames();
        } catch (IOException e) {
            if (running) {
                reportError("Stream pipe error: " + e.getMessage());
            }
        } finally {
            running = false;
            closePipe();
        }
    }

    /**
     * Reads frames until the reader is stopped, the pipe closes, or the producer
     * breaks framing or outruns its throughput budget. A guard trip stops the
     * stream rather than dropping frames: the bytes still have to be read to
     * stay framed, so dropping would spare only the callback and leave the
     * reader servicing an abusive producer forever.
     */
    private void pumpFrames() throws IOException {
        FrameBudget budget = new FrameBudget(MAX_FRAMES_PER_WINDOW, MAX_BYTES_PER_WINDOW,
                BUDGET_WINDOW_NANOS, System::nanoTime);
        byte[] header = new byte[HEADER_BYTES];
        while (running) {
            readFully(header);
            int length = ByteBuffer.wrap(header)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
            if (length <= 0 || length > MAX_FRAME_SIZE) {
                reportError("Invalid frame size: " + length + " — stopping stream.");
                return;
            }
            FrameBudget.Verdict verdict = budget.record(length);
            if (verdict != FrameBudget.Verdict.WITHIN_BUDGET) {
                reportError(throttleMessage(verdict));
                return;
            }
            byte[] frame = new byte[length];
            readFully(frame);
            if (running) {
                frameCallback.accept(frame);
            }
        }
    }

    private static String throttleMessage(FrameBudget.Verdict verdict) {
        return "Stream throttled (" + verdict.description() + "): a producer may send at most "
                + MAX_FRAMES_PER_WINDOW + " frames and " + MAX_BYTES_PER_WINDOW / BYTES_PER_MIB
                + " MiB per second — stopping stream.";
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = pipeFile.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("Stream pipe closed");
            }
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
