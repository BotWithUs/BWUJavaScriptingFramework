package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.runtime.Liveness;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The stop/responsiveness state of one script runner, and the only place the
 * {@link Liveness} ladder is walked.
 *
 * <p>Composed into both {@link ScriptRunner} and {@link ManagementScriptRunner}
 * rather than inherited, so the two runners share one implementation of a
 * safety-critical mechanism instead of two copies that drift.</p>
 *
 * <p>Owned by its runner and safe to read from the watchdog thread: every field
 * is atomic or volatile.</p>
 */
public final class RunnerLiveness {

    /**
     * Sentinel for "no stop pending" / "not inside onLoop". Not {@code 0}:
     * {@code System.nanoTime()}'s origin is arbitrary, so zero is a value it can
     * legitimately return — which would permanently disable the watchdog.
     */
    private static final long UNSET_NANOS = Long.MIN_VALUE;

    private final AtomicReference<Liveness> state = new AtomicReference<>(Liveness.LIVE);
    private final AtomicLong stopRequestedNanos = new AtomicLong(UNSET_NANOS);
    private volatile long loopStartNanos = UNSET_NANOS;

    public RunnerLiveness() {}

    public Liveness get() {
        return state.get();
    }

    /** {@code true} once a stop has been requested on the current run. */
    public boolean isStopRequested() {
        return stopRequestedNanos.get() != UNSET_NANOS;
    }

    /**
     * Records that a stop was requested. Idempotent — the first call wins, so
     * a GUI Stop racing {@code stopAll} can't move the deadline.
     */
    public void requestStop() {
        stopRequestedNanos.compareAndSet(UNSET_NANOS, System.nanoTime());
    }

    /**
     * Clears the previous run's bookkeeping. Called when a runner restarts:
     * without it the watchdog would see a stop from long ago and quarantine the
     * freshly-started thread, and {@link #isStopRequested()} would be true on
     * the new run's first loop.
     */
    public void resetForRestart() {
        stopRequestedNanos.set(UNSET_NANOS);
        loopStartNanos = UNSET_NANOS;
        state.set(Liveness.LIVE);
    }

    public void enterLoop() {
        loopStartNanos = System.nanoTime();
    }

    /** Leaves the loop and clears an advisory stall; terminal states stick. */
    public void exitLoop() {
        loopStartNanos = UNSET_NANOS;
        state.compareAndSet(Liveness.STALLED, Liveness.LIVE);
    }

    /** Milliseconds inside the current {@code onLoop}, or {@code -1} when outside one. */
    public long millisInLoop(long nowNanos) {
        return elapsedMillis(loopStartNanos, nowNanos);
    }

    /** Milliseconds since stop was requested, or {@code -1} when none is pending. */
    public long millisSinceStopRequested(long nowNanos) {
        return elapsedMillis(stopRequestedNanos.get(), nowNanos);
    }

    private static long elapsedMillis(long sinceNanos, long nowNanos) {
        return sinceNanos == UNSET_NANOS ? -1L : TimeUnit.NANOSECONDS.toMillis(nowNanos - sinceNanos);
    }

    /**
     * Flags the runner unresponsive. Recoverable — {@link #exitLoop()} clears it.
     *
     * @return {@code true} if this call performed the transition
     */
    public boolean markStalled() {
        return state.compareAndSet(Liveness.LIVE, Liveness.STALLED);
    }

    /**
     * Marks the runner cut off from the game. Terminal; never downgrades an
     * already-{@link Liveness#ABANDONED} runner.
     *
     * @return {@code true} if this call performed the transition
     */
    public boolean markRevoked() {
        Liveness previous = state.getAndUpdate(
                l -> l == Liveness.ABANDONED ? l : Liveness.REVOKED);
        return previous != Liveness.REVOKED && previous != Liveness.ABANDONED;
    }

    /**
     * Marks the runner written off — revoked and still alive. Terminal.
     *
     * @return {@code true} if this call performed the transition
     */
    public boolean markAbandoned() {
        return state.getAndSet(Liveness.ABANDONED) != Liveness.ABANDONED;
    }
}
