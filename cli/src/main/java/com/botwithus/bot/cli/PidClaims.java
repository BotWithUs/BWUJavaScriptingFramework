package com.botwithus.bot.cli;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The game pids some thread is connecting to right now. Every path that opens
 * an agent pipe (a command's connect, the pipe scanner's auto-connect, and the
 * launcher's attach after a launch) claims the pid first and releases it when
 * done, so two of them can never hold a {@code PipeClient} to one game at the
 * same time. That matters because the agent has only four pipe slots, and a
 * losing connect used to open its pipe before it found out it had lost
 * (launcher ADR 0007, section 10.1, "the autoConnect race").
 */
final class PidClaims {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition released = lock.newCondition();
    private final Set<Long> claimed = new HashSet<>();

    /** @return {@code true} if this caller now holds {@code pid}; {@code false} if another does */
    boolean tryClaim(long pid) {
        lock.lock();
        try {
            return claimed.add(pid);
        } finally {
            lock.unlock();
        }
    }

    /** Releases a pid this caller claimed. */
    void release(long pid) {
        lock.lock();
        try {
            claimed.remove(pid);
            released.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** @return whether some caller holds {@code pid} now */
    boolean isClaimed(long pid) {
        lock.lock();
        try {
            return claimed.contains(pid);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Claims {@code pid}, waiting for another holder to release it.
     *
     * @return {@code true} once claimed; {@code false} if {@code timeout} passed first
     */
    boolean claimWithin(long pid, Duration timeout) throws InterruptedException {
        long remaining = timeout.toNanos();
        lock.lock();
        try {
            while (!claimed.add(pid)) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = released.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** {@link #claimWithin(long, Duration)} without an interrupt: an interrupt counts as a timeout. */
    boolean claimWithinUninterruptibly(long pid, Duration timeout) {
        try {
            return claimWithin(pid, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
