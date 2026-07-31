package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.runtime.Liveness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Escalates script runners that won't stop, on a background thread.
 *
 * <p>One instance per runtime, shared by the script and management runtimes so
 * there is a single implementation of the escalation ladder. The two runtimes
 * previously carried hand-maintained copies of this and had already drifted
 * apart once — a missed edit here silently leaves a class of scripts
 * uncontained, which nothing would catch until a zombie was seen live.</p>
 *
 * <p>Runners are reached through {@link Subject} so the watchdog doesn't care
 * which runner type it is sweeping.</p>
 */
final class LivenessWatchdog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LivenessWatchdog.class);

    /** Sweep cadence. Fine enough to escalate promptly, coarse enough to be free. */
    private static final long SWEEP_INTERVAL_MS = 250L;

    /**
     * Time inside a single {@code onLoop} with no stop pending before the runner
     * is flagged {@link Liveness#STALLED}. Deliberately generous: a blocking
     * walk legitimately parks inside {@code onLoop} for up to {@code Walker}'s
     * 300s timeout, so a shorter threshold would flag healthy scripts. Advisory
     * only — the runner recovers as soon as a loop completes.
     */
    private static final long IDLE_STALL_MS = 600_000L;

    /** After a stop request, how long the thread may stay in {@code onLoop} before STALLED. */
    static final long STOP_STALL_MS = 2_000L;

    /** After a stop request, how long before the runner is cut off from the game. */
    static final long REVOKE_GRACE_MS = 5_000L;

    /** After revocation, how long before the thread is written off and quarantined. */
    static final long ABANDON_GRACE_MS = 15_000L;

    /**
     * What the watchdog needs of a runner. Implemented by both
     * {@link ScriptRunner} and {@link ManagementScriptRunner}.
     */
    interface Subject {

        String getScriptName();

        /** The runner's liveness state; the watchdog drives its transitions. */
        RunnerLiveness livenessState();

        /** {@code true} while the runner's thread exists and has not terminated. */
        boolean isThreadAlive();

        /**
         * Withdraw this runner's ability to affect the game. May be a no-op
         * where no enforcement point exists (management scripts are
         * cross-client, so there is no per-connection RPC gate to close).
         */
        void revokeAccess();

        /** Make this runner's classes outlive the reload that would close them. */
        void pinClassLoader();

        /**
         * Report a transition the watchdog just made. Keeps
         * {@link RunnerLiveness} a pure state holder — the runner decides how
         * (or whether) to surface it.
         */
        void onLivenessChanged(Liveness to);
    }

    private final String threadName;
    private final Supplier<Iterable<? extends Subject>> subjects;
    private final Object lock = new Object();
    private ScheduledExecutorService executor;

    LivenessWatchdog(String threadName, Supplier<Iterable<? extends Subject>> subjects) {
        this.threadName = threadName;
        this.subjects = subjects;
    }

    /**
     * Starts sweeping on first use. Lazy so the many test seams that build a
     * runtime without ever starting a script don't each spawn a thread.
     *
     * <p>Armed from the runner's {@code start()}, not the runtime's
     * {@code startScript()}: the CLI and GUI start a script by resolving its
     * runner and calling {@code start()} directly, so arming any higher would
     * leave the watchdog dead for every user-initiated start.</p>
     */
    void arm() {
        synchronized (lock) {
            if (executor != null) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(this::sweepNow,
                    SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    private void sweepNow() {
        try {
            sweep(System.nanoTime());
        } catch (Exception e) {
            // A watchdog that dies on one bad runner stops protecting the rest.
            log.error("Watchdog sweep failed: {}", e.getMessage());
        }
    }

    /**
     * One pass over every runner. Package-private and time-parameterised so
     * tests drive the escalation deterministically rather than sleeping through
     * the real grace windows.
     */
    void sweep(long nowNanos) {
        for (Subject subject : subjects.get()) {
            sweepOne(subject, nowNanos);
        }
    }

    /**
     * Escalates a single runner. With no stop pending this only raises the
     * advisory stall flag; once a stop is pending the runner walks
     * STALLED → REVOKED → ABANDONED as each grace window elapses.
     */
    private void sweepOne(Subject subject, long nowNanos) {
        RunnerLiveness liveness = subject.livenessState();
        if (liveness.get() == Liveness.ABANDONED) {
            return;
        }
        long sinceStopMs = liveness.millisSinceStopRequested(nowNanos);
        if (sinceStopMs < 0L) {
            flagIdleStall(subject, liveness, nowNanos);
            return;
        }
        if (!subject.isThreadAlive()) {
            return;
        }
        if (sinceStopMs >= ABANDON_GRACE_MS) {
            abandon(subject, liveness);
        } else if (sinceStopMs >= REVOKE_GRACE_MS) {
            revoke(subject, liveness);
        } else if (sinceStopMs >= STOP_STALL_MS && liveness.markStalled()) {
            subject.onLivenessChanged(Liveness.STALLED);
            log.warn("Script {} has not stopped after {} ms; still inside onLoop()",
                    subject.getScriptName(), sinceStopMs);
        }
    }

    /** Advisory only: nobody asked this script to stop, it has just been in one loop a long time. */
    private void flagIdleStall(Subject subject, RunnerLiveness liveness, long nowNanos) {
        long inLoopMs = liveness.millisInLoop(nowNanos);
        if (inLoopMs >= IDLE_STALL_MS && liveness.markStalled()) {
            subject.onLivenessChanged(Liveness.STALLED);
            log.warn("Script {} has been inside a single onLoop() for {} ms",
                    subject.getScriptName(), inLoopMs);
        }
    }

    private void revoke(Subject subject, RunnerLiveness liveness) {
        if (liveness.markRevoked()) {
            subject.revokeAccess();
            subject.onLivenessChanged(Liveness.REVOKED);
            log.warn("Script {} ignored stop; revoking its access to the game",
                    subject.getScriptName());
        }
    }

    private void abandon(Subject subject, RunnerLiveness liveness) {
        if (liveness.markAbandoned()) {
            // The thread is unkillable, so its classes must stay loaded for as
            // long as it runs. Pinning leaks the loader deliberately; closing it
            // would give the live thread NoClassDefFoundError and, on Windows,
            // wedge every later reload behind a JAR handle it can't release.
            subject.pinClassLoader();
            subject.onLivenessChanged(Liveness.ABANDONED);
            log.error("Script {} survived revocation; quarantining its thread "
                    + "and pinning its classloader", subject.getScriptName());
        }
    }
}
