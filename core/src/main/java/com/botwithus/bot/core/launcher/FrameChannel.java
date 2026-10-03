package com.botwithus.bot.core.launcher;

/**
 * A length-framed, duplex byte channel to the service: one frame per
 * {@link #send}, one per {@link #read}. Production is a named pipe
 * ({@link PipeFrameChannel}); tests use an in-memory peer.
 *
 * <p>Like the pipe it wraps, a channel may not be read and written at the same
 * time from two threads. {@link ServiceSession} serialises access, and reads
 * only when {@link #available()} says a read will not block.</p>
 */
public interface FrameChannel extends AutoCloseable {

    /**
     * Sends one frame body.
     *
     * @throws ChannelClosedException if the channel is closed or broken
     */
    void send(byte[] body);

    /** @return bytes readable without blocking; 0 when none or closed */
    int available();

    /**
     * Reads one frame body, blocking until the whole frame has arrived.
     *
     * @throws ChannelClosedException if the channel is closed, broken, or the frame is malformed
     */
    byte[] read();

    /** @return whether the channel is still open */
    boolean isOpen();

    @Override
    void close();

    /** Opens a channel to the service. */
    @FunctionalInterface
    interface Opener {

        /**
         * @return an open channel
         * @throws ChannelClosedException when the service's pipe cannot be opened
         */
        FrameChannel open();
    }

    /** The channel is closed, broken, or could not be opened. */
    final class ChannelClosedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ChannelClosedException(String message) {
            super(message);
        }

        public ChannelClosedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
