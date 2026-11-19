package com.botwithus.bot.core.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One serial lane per owner for {@code ui.event} delivery, so a slow listener in
 * one script delays only that script's events.
 *
 * <p>Each lane is a single thread with a bounded queue. When a lane's queue is
 * full the event is dropped with a WARN naming the owner, rather than blocking the
 * push thread every other owner's events come through. Within a lane, events run
 * in arrival order. An idle lane's thread exits and is recreated on the next
 * event, so a script with no traffic costs no thread.</p>
 */
final class UiEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UiEventDispatcher.class);

    /** Events a lane holds before it starts dropping. */
    static final int DEFAULT_CAPACITY = 256;
    private static final long IDLE_SECONDS = 30L;

    private final int capacity;
    private final Map<String, ThreadPoolExecutor> lanes = new ConcurrentHashMap<>();
    /** Owners whose lane was closed; their events are dropped rather than given a new lane. */
    private final Set<String> closed = ConcurrentHashMap.newKeySet();

    UiEventDispatcher(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Queues {@code task} on {@code owner}'s lane; drops it with a WARN when the lane
     * is full, and silently when the owner was closed.
     */
    void submit(String owner, Runnable task) {
        if (closed.contains(owner)) {
            log.debug("ui.event for {} dropped: the owner was released", owner);
            return;
        }
        lanes.computeIfAbsent(owner, this::newLane).execute(task);
    }

    /**
     * Retires {@code owner}'s lane. What is already queued still runs; nothing more is
     * accepted, and no new lane is made for it until {@link #reopen}.
     */
    void close(String owner) {
        closed.add(owner);
        ThreadPoolExecutor lane = lanes.remove(owner);
        if (lane != null) {
            lane.shutdown();
        }
    }

    /** Lets a released owner's next run receive events again. */
    void reopen(String owner) {
        closed.remove(owner);
    }

    private ThreadPoolExecutor newLane(String owner) {
        // rule-exception: platform threads, not virtual. Each lane runs a script's
        // listener, which is untrusted code that may spin; see ScriptRunner's
        // rule-exception and CLAUDE.md "Java rules exceptions". A lane is created by
        // whichever thread submits first, so it inherits no thread-locals: its owner
        // is set per delivery, never borrowed from its creator.
        ThreadPoolExecutor lane = new ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                r -> Thread.ofPlatform().name("ui-event-" + owner).daemon(true)
                        .inheritInheritableThreadLocals(false).unstarted(r),
                dropping(owner));
        lane.allowCoreThreadTimeOut(true);
        return lane;
    }

    private RejectedExecutionHandler dropping(String owner) {
        return (task, lane) -> {
            if (lane.isShutdown()) {
                log.debug("ui.event for {} dropped: its lane is closed", owner);
            } else {
                log.warn("ui.event for {} dropped: {} events already queued; its listener is not keeping up",
                        owner, capacity);
            }
        };
    }
}
