package com.botwithus.bot.core.runtime;

import java.util.Arrays;

/**
 * Fixed-size ring of the most recent loop durations for one script.
 *
 * <p>The script thread records each {@code onLoop()} duration; the GUI thread
 * reads a copy to draw the pulse lane on the client card. Both sides go through
 * the same monitor, so a reader never sees a half-written slot. At one write per
 * loop and one read per frame, contention is not measurable next to the loop
 * itself.</p>
 */
public final class LoopHistory {

    /** How many loops the pulse lane shows. */
    public static final int DEFAULT_CAPACITY = 24;

    private final long[] ring;
    private int next;
    private int size;

    public LoopHistory() {
        this(DEFAULT_CAPACITY);
    }

    public LoopHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.ring = new long[capacity];
    }

    /** Records one loop duration, evicting the oldest when full. */
    public synchronized void record(long nanos) {
        ring[next] = nanos;
        next = (next + 1) % ring.length;
        if (size < ring.length) {
            size++;
        }
    }

    /**
     * Returns the recorded durations in nanoseconds, oldest first and newest
     * last. The array is a copy of length {@link #size()}, never longer.
     */
    public synchronized long[] snapshot() {
        long[] out = new long[size];
        int start = (next - size + ring.length) % ring.length;
        for (int i = 0; i < size; i++) {
            out[i] = ring[(start + i) % ring.length];
        }
        return out;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return ring.length;
    }

    public synchronized void clear() {
        Arrays.fill(ring, 0L);
        next = 0;
        size = 0;
    }
}
