package com.botwithus.bot.api.runtime;

import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One script's pause switch. While it is paused the runtime does not call
 * {@code onLoop}, and the condition waits in {@link com.botwithus.bot.api.util.Timing} hold
 * where they are, without spending their timeout, until it is resumed. A stop request still
 * ends a paused script: the runtime interrupts its thread, which wakes both.
 *
 * <p>Scripts reach their own switch through
 * {@link com.botwithus.bot.api.ScriptContext#pause()} and friends. The runtime binds it to
 * {@link #CURRENT} for the script's thread, which is how {@code Timing}, a static helper with
 * no context of its own, finds it.</p>
 */
public final class ScriptPause {

    /**
     * The switch of the script whose thread this is. Bound by the runtime around the script's
     * lifecycle; unbound on any other thread, where nothing pauses. A scoped value is an
     * immutable per-thread binding, not shared mutable state: nothing can rebind it from
     * outside the scope that bound it.
     */
    public static final ScopedValue<ScriptPause> CURRENT = ScopedValue.newInstance();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition resumed = lock.newCondition();
    private final Runnable onResume;
    private volatile boolean paused;

    /** A switch with nothing to tell on resume. */
    public ScriptPause() {
        this(() -> { });
    }

    /**
     * @param onResume run after every {@link #resume()} that ended a pause, on the resuming
     *                 thread; the runtime uses it to restart its stall clock
     */
    public ScriptPause(Runnable onResume) {
        this.onResume = onResume;
    }

    /** The calling thread's script switch, or empty off a script thread. */
    public static Optional<ScriptPause> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** Pauses the script. Idempotent. */
    public void pause() {
        paused = true;
    }

    /** Resumes the script and wakes anything waiting in {@link #awaitResumed()}. Idempotent. */
    public void resume() {
        boolean wasPaused;
        lock.lock();
        try {
            wasPaused = paused;
            paused = false;
            resumed.signalAll();
        } finally {
            lock.unlock();
        }
        if (wasPaused) {
            onResume.run();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Blocks while paused; returns at once when not.
     *
     * @throws InterruptedException when the thread is interrupted, which is how a stop request
     *                              reaches a paused script
     */
    public void awaitResumed() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (paused) {
                resumed.await();
            }
        } finally {
            lock.unlock();
        }
    }
}
