package com.botwithus.bot.core.runtime;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks execution timing for a single script.
 */
public class ScriptProfiler {

    private final LongAdder loopCount = new LongAdder();
    private final LongAdder totalLoopTimeNanos = new LongAdder();
    private final AtomicLong minLoopNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxLoopNanos = new AtomicLong(0);
    private final AtomicLong lastLoopNanos = new AtomicLong(0);

    public void recordLoop(long nanos) {
        loopCount.increment();
        totalLoopTimeNanos.add(nanos);
        lastLoopNanos.set(nanos);
        minLoopNanos.accumulateAndGet(nanos, Math::min);
        maxLoopNanos.accumulateAndGet(nanos, Math::max);
    }

    public long getLoopCount() { return loopCount.sum(); }
    public long getTotalLoopTimeNanos() { return totalLoopTimeNanos.sum(); }
    public long getMinLoopNanos() { return loopCount.sum() > 0 ? minLoopNanos.get() : 0; }
    public long getMaxLoopNanos() { return maxLoopNanos.get(); }
    public long getLastLoopNanos() { return lastLoopNanos.get(); }

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
    }
}
