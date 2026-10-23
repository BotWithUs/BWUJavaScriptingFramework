package com.botwithus.bot.core.runtime;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/**
 * Tracks execution timing for a single script.
 *
 * <p>Two kinds of figure: the <em>aggregates</em> (loop count, total, min, max),
 * which {@link #setAggregating} can switch off, and the last loop plus the
 * recent-loop ring behind the pulse lane, which are always kept because the lane
 * is how a user sees that a script is looping at all.</p>
 */
public class ScriptProfiler {

    public ScriptProfiler() {}

    private final LongAdder loopCount = new LongAdder();
    private final LongAdder totalLoopTimeNanos = new LongAdder();
    private final AtomicLong minLoopNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxLoopNanos = new AtomicLong(0);
    private final AtomicLong lastLoopNanos = new AtomicLong(0);
    private final LoopHistory recent = new LoopHistory();
    private volatile BooleanSupplier aggregating = () -> true;

    /**
     * Decides, loop by loop, whether the aggregates are kept. Read once per
     * recorded loop, so it must be cheap and thread-safe. The last loop and the
     * recent-loop ring are kept either way; figures already kept stay.
     */
    public void setAggregating(BooleanSupplier gate) {
        this.aggregating = gate != null ? gate : () -> true;
    }

    public void recordLoop(long nanos) {
        lastLoopNanos.set(nanos);
        recent.record(nanos);
        if (!aggregating.getAsBoolean()) {
            return;
        }
        loopCount.increment();
        totalLoopTimeNanos.add(nanos);
        minLoopNanos.accumulateAndGet(nanos, Math::min);
        maxLoopNanos.accumulateAndGet(nanos, Math::max);
    }

    public long getLoopCount() { return loopCount.sum(); }
    public long getTotalLoopTimeNanos() { return totalLoopTimeNanos.sum(); }
    public long getMinLoopNanos() { return loopCount.sum() > 0 ? minLoopNanos.get() : 0; }
    public long getMaxLoopNanos() { return maxLoopNanos.get(); }
    public long getLastLoopNanos() { return lastLoopNanos.get(); }

    /**
     * The most recent loop durations in nanoseconds, oldest first, at most
     * {@link LoopHistory#DEFAULT_CAPACITY} of them. A copy; safe to keep.
     */
    public long[] recentLoopNanos() {
        return recent.snapshot();
    }

    public double avgLoopMs() {
        long count = loopCount.sum();
        return count > 0 ? (totalLoopTimeNanos.sum() / 1_000_000.0) / count : 0;
    }

    public void reset() {
        loopCount.reset();
        totalLoopTimeNanos.reset();
        minLoopNanos.set(Long.MAX_VALUE);
        maxLoopNanos.set(0);
        lastLoopNanos.set(0);
        recent.clear();
    }
}
