package com.botwithus.bot.cli.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Publish/subscribe for {@link HostEvent}s, owned by the host and alive whether
 * or not any client is connected.
 *
 * <p><b>Delivery.</b> {@link #publish} only queues the event and returns; one
 * dispatcher thread hands each event to every subscriber in turn. So every
 * subscriber sees every event, all subscribers see them in the same order, and
 * that order is the order the {@code publish} calls happened in. Publishers are
 * script threads, RPC threads and the render thread; none of them ever waits on
 * a subscriber, and a subscriber can call back into the host without any risk
 * of a lock-order deadlock with a publisher.</p>
 *
 * <p><b>Subscribers</b> run on the dispatcher thread. They should be quick — a
 * slow one delays delivery to the rest — and hand anything slow elsewhere. A
 * subscriber that throws is logged and skipped for that event; it stays
 * subscribed and every other subscriber still gets the event.</p>
 */
public final class HostEventBus implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HostEventBus.class);
    private static final String THREAD_NAME = "host-events";
    /** How long {@link #close()} waits for queued events to be delivered. */
    private static final Duration CLOSE_DRAIN = Duration.ofSeconds(5);

    /** One unit of dispatcher work, in queue order. */
    private sealed interface Work {
        record Deliver(HostEvent event) implements Work { }
        record Barrier(CountDownLatch reached) implements Work { }
        record Stop() implements Work { }
    }

    private final List<Consumer<? super HostEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Work> queue = new LinkedBlockingQueue<>();
    private final Object lifecycleLock = new Object();
    private final Thread dispatcher;
    private boolean closed;

    public HostEventBus() {
        dispatcher = Thread.ofVirtual().name(THREAD_NAME).unstarted(this::dispatchLoop);
        dispatcher.start();
    }

    /**
     * Adds a subscriber. It receives every event published after this call.
     *
     * @return runs to remove the subscriber again
     * @throws IllegalStateException if the bus is closed
     */
    public Runnable subscribe(Consumer<? super HostEvent> subscriber) {
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("host event bus is closed");
            }
            subscribers.add(subscriber);
        }
        return () -> subscribers.remove(subscriber);
    }

    /** Queues {@code event} for delivery and returns at once. Dropped once the bus is closed. */
    public void publish(HostEvent event) {
        if (!enqueue(new Work.Deliver(event))) {
            log.debug("Host event bus is closed; dropped {}", event);
        }
    }

    /**
     * Waits until every event published before this call has been delivered.
     *
     * @return {@code false} if {@code timeout} passed first, or the bus is closed
     * @throws IllegalStateException if called from a subscriber, which would wait on itself
     */
    public boolean flush(Duration timeout) {
        if (Thread.currentThread() == dispatcher) {
            throw new IllegalStateException("flush() from a subscriber would wait on itself");
        }
        CountDownLatch reached = new CountDownLatch(1);
        if (!enqueue(new Work.Barrier(reached))) {
            return false;
        }
        try {
            return reached.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Stops accepting events, delivers the ones already queued, and ends the
     * dispatcher. Waits a bounded time for that; a subscriber stuck forever
     * cannot hang shutdown. Idempotent.
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            // Under the same lock enqueue() checks, so nothing lands behind the stop.
            closed = true;
            queue.add(new Work.Stop());
        }
        if (Thread.currentThread() == dispatcher) {
            return;
        }
        try {
            if (!dispatcher.join(CLOSE_DRAIN)) {
                log.warn("Host event bus did not drain within {}; a subscriber is stuck",
                        CLOSE_DRAIN);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Queues {@code work} unless the bus is closed. */
    private boolean enqueue(Work work) {
        synchronized (lifecycleLock) {
            if (closed) {
                return false;
            }
            queue.add(work);
            return true;
        }
    }

    private void dispatchLoop() {
        try {
            while (true) {
                Work work = queue.take();
                switch (work) {
                    case Work.Deliver deliver -> deliver(deliver.event());
                    case Work.Barrier barrier -> barrier.reached().countDown();
                    case Work.Stop _ -> {
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliver(HostEvent event) {
        for (Consumer<? super HostEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException e) {
                log.warn("Host event subscriber threw on {}: {}", event, e.toString());
            }
        }
    }
}
