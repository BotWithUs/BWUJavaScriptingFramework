package com.botwithus.bot.core.cache;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialises every use of one native {@code nxt_cache*} handle, and its close.
 *
 * <p>The handle is not safe for concurrent use (it wraps a sqlite connection and decoder
 * state), yet one {@link NXTCache} is shared by every connection and every script thread.
 * Each native call on the handle therefore runs inside {@link #call}, which holds this
 * guard's lock for the call and for anything read after it, such as the error text.</p>
 *
 * <p>Close takes the same lock, which is what makes it safe to race: a call that is in
 * flight finishes before the handle is freed, and a call that arrives after the close is
 * refused with an {@link IllegalStateException} instead of reaching a freed handle.
 * Before this guard the closed flag was checked, then the handle used, with nothing
 * stopping a close in between.</p>
 */
final class HandleGuard {

    /** One native call, or a sequence of them that must not interleave with another. */
    @FunctionalInterface
    interface NativeCall<T> {
        T run() throws Throwable;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Runnable onEnter;
    private boolean isClosed;

    HandleGuard() {
        this(() -> { });
    }

    /**
     * @param onEnter runs at the start of every guarded call, with the lock held and the
     *                handle known to be open; a test seam for proving calls are serialised
     */
    HandleGuard(Runnable onEnter) {
        this.onEnter = onEnter;
    }

    /**
     * Runs {@code body} with the handle locked.
     *
     * @throws IllegalStateException if the handle is already closed
     */
    <T> T call(NativeCall<T> body) throws Throwable {
        lock.lock();
        try {
            if (isClosed) {
                throw new IllegalStateException("NXTCache handle is closed");
            }
            onEnter.run();
            return body.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code nativeClose} exactly once, after every in-flight call has finished.
     *
     * @return {@code true} if this call closed the handle, {@code false} if it was already closed
     */
    boolean close(NativeCall<?> nativeClose) throws Throwable {
        lock.lock();
        try {
            if (isClosed) {
                return false;
            }
            isClosed = true;
            nativeClose.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** True while the current thread is inside a guarded call. */
    boolean isHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    /** True while another call or a close is waiting for the handle; a test seam. */
    boolean hasQueuedCalls() {
        return lock.hasQueuedThreads();
    }
}
