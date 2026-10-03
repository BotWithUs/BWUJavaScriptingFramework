package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.core.runtime.ConnectionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Delivers launcher events to one script listener on a thread of its own, so
 * a slow or failing listener can never hold up the thread reading the service
 * (launcher ADR 0007, section 10.1).
 *
 * <p>The thread carries the logging context ({@code MDC}) and the connection
 * tag of the thread that registered the listener, as a script runner's own
 * thread does, so what the listener logs is attributed to its script.</p>
 *
 * <p>The queue holds {@value #CAPACITY} events. When it is full the oldest is
 * dropped, and the listener is told how many with
 * {@link LauncherEvent.EventsDropped} before the next event it gets.</p>
 */
final class ListenerDispatch implements AutoCloseable {

    /** Events held for a listener that has fallen behind. */
    static final int CAPACITY = 1024;

    private static final Logger log = LoggerFactory.getLogger(ListenerDispatch.class);

    private final Consumer<LauncherEvent> listener;
    private final Map<String, String> loggingContext;
    private final String connectionTag;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Deque<LauncherEvent> queue = new ArrayDeque<>();
    private long dropped;
    private boolean isClosed;

    /** Captures the calling thread's context; call it on the registering thread. */
    ListenerDispatch(Consumer<LauncherEvent> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.loggingContext = MDC.getCopyOfContextMap();
        this.connectionTag = ConnectionContext.get();
        Thread.ofVirtual().name("launcher-events").start(this::run);
    }

    /** Queues an event. Never blocks. */
    void offer(LauncherEvent event) {
        lock.lock();
        try {
            if (isClosed) {
                return;
            }
            if (queue.size() == CAPACITY) {
                queue.removeFirst();
                dropped++;
            }
            queue.addLast(event);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            isClosed = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void run() {
        if (loggingContext != null) {
            MDC.setContextMap(loggingContext);
        }
        if (connectionTag != null) {
            ConnectionContext.set(connectionTag);
        }
        try {
            LauncherEvent next = take();
            while (next != null) {
                deliver(next);
                next = take();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return the next event, an {@link LauncherEvent.EventsDropped} first if any were dropped; null once closed */
    private LauncherEvent take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty() && !isClosed) {
                changed.await();
            }
            if (isClosed) {
                return null;
            }
            if (dropped > 0) {
                long count = dropped;
                dropped = 0;
                return new LauncherEvent.EventsDropped(count);
            }
            return queue.removeFirst();
        } finally {
            lock.unlock();
        }
    }

    private void deliver(LauncherEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            log.warn("A launcher event listener threw on {}", event, e);
        }
    }
}
