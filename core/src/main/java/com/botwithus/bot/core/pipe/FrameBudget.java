package com.botwithus.bot.core.pipe;

import java.util.function.LongSupplier;

/**
 * Tumbling-window budget bounding how much a stream pipe may deliver per unit
 * of time, in both frame count and total bytes.
 *
 * <p>Both limits are needed because they bound different attacks. The byte
 * budget bounds a producer sending a few enormous frames; the frame budget
 * bounds one sending a flood of tiny ones, which costs the consumer far more
 * than its byte count suggests — every frame is decoded to a
 * {@code BufferedImage} and queued as a GPU texture upload.</p>
 *
 * <p>The window is tumbling, not sliding: a burst straddling a window boundary
 * can deliver up to twice a single window's budget before tripping. That is
 * accepted — this is a guard against sustained abuse, not a rate shaper, and a
 * 2x transient is orders of magnitude below the unbounded case it replaces.</p>
 *
 * <p><b>Not thread-safe.</b> Confined to the single reader thread that owns the
 * pipe; {@link #record} mutates window state without synchronization.</p>
 */
final class FrameBudget {

    /** Outcome of charging a frame against the budget. */
    enum Verdict {
        WITHIN_BUDGET("within budget"),
        FRAME_RATE_EXCEEDED("too many frames"),
        BYTE_RATE_EXCEEDED("too many bytes");

        private final String description;

        Verdict(String description) {
            this.description = description;
        }

        String description() {
            return description;
        }
    }

    private final int maxFrames;
    private final long maxBytes;
    private final long windowNanos;
    private final LongSupplier clock;

    private long windowStartNanos;
    private int frames;
    private long bytes;

    /**
     * @param maxFrames   frames allowed per window before {@link #record} reports
     *                    {@link Verdict#FRAME_RATE_EXCEEDED}
     * @param maxBytes    bytes allowed per window before {@link #record} reports
     *                    {@link Verdict#BYTE_RATE_EXCEEDED}
     * @param windowNanos window length
     * @param clock       monotonic nanosecond source, normally {@code System::nanoTime};
     *                    injected so the budget is testable without sleeping
     */
    FrameBudget(int maxFrames, long maxBytes, long windowNanos, LongSupplier clock) {
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
        this.windowNanos = windowNanos;
        this.clock = clock;
        this.windowStartNanos = clock.getAsLong();
    }

    /**
     * Charges one frame of {@code frameBytes} against the current window,
     * rolling the window over first if it has elapsed.
     *
     * <p>Call this <em>before</em> allocating the frame buffer — bounding the
     * reader's allocation churn is the whole point, so the charge has to
     * precede the allocation it is guarding.</p>
     *
     * @return {@link Verdict#WITHIN_BUDGET} if the frame fits, otherwise the
     *         limit it broke
     */
    Verdict record(int frameBytes) {
        long now = clock.getAsLong();
        // Subtraction, not comparison: it stays correct across a nanoTime wrap.
        if (now - windowStartNanos >= windowNanos) {
            windowStartNanos = now;
            frames = 0;
            bytes = 0;
        }
        frames++;
        bytes += frameBytes;
        if (frames > maxFrames) {
            return Verdict.FRAME_RATE_EXCEEDED;
        }
        if (bytes > maxBytes) {
            return Verdict.BYTE_RATE_EXCEEDED;
        }
        return Verdict.WITHIN_BUDGET;
    }
}
