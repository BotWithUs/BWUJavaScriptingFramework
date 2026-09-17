package com.botwithus.bot.core.rpc;

import java.util.Arrays;

/**
 * Bounded ring buffer of recent latency samples (nanoseconds) supporting
 * percentile queries via copy-then-sort. Designed to be held as an instance
 * field of {@link RpcMetrics} — one window per method.
 *
 * <p>All public methods are thread-safe via a single intrinsic lock; the
 * critical sections are short (array write, array copy) and dominate over
 * any contention for the percentile snapshot path. The buffer is intentionally
 * small (default {@link #DEFAULT_CAPACITY}) so percentiles reflect *recent*
 * call latency rather than long-term averages, which are already tracked by
 * {@link RpcMetrics.MethodStats#totalTimeNanos()}.</p>
 */
public final class LatencyWindow {

    /** Default ring capacity. Sized so percentile copy/sort stays well under a millisecond. */
    public static final int DEFAULT_CAPACITY = 512;

    private final long[] samples;
    private int writeIdx;
    private int size;

    public LatencyWindow() {
        this(DEFAULT_CAPACITY);
    }

    public LatencyWindow(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.samples = new long[capacity];
    }

    public synchronized void record(long nanos) {
        samples[writeIdx] = nanos;
        writeIdx = (writeIdx + 1) % samples.length;
        if (size < samples.length) {
            size++;
        }
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return samples.length;
    }

    /**
     * Returns the requested percentiles as nanoseconds, in the order requested.
     * Percentile inputs must lie in [0, 100]. Returns zeros if the window is
     * empty.
     */
    public synchronized long[] percentiles(int[] percentiles) {
        long[] out = new long[percentiles.length];
        if (size == 0) {
            return out;
        }
        long[] sorted = new long[size];
        System.arraycopy(samples, 0, sorted, 0, size);
        Arrays.sort(sorted);
        for (int i = 0; i < percentiles.length; i++) {
            int p = percentiles[i];
            if (p < 0 || p > 100) {
                throw new IllegalArgumentException("percentile out of range: " + p);
            }
            out[i] = percentileFromSorted(sorted, p);
        }
        return out;
    }

    public long percentile(int p) {
        return percentiles(new int[]{p})[0];
    }

    public synchronized void reset() {
        Arrays.fill(samples, 0L);
        writeIdx = 0;
        size = 0;
    }

    private static long percentileFromSorted(long[] sorted, int p) {
        if (p <= 0) {
            return sorted[0];
        }
        if (p >= 100) {
            return sorted[sorted.length - 1];
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sorted.length) {
            idx = sorted.length - 1;
        }
        return sorted[idx];
    }
}
