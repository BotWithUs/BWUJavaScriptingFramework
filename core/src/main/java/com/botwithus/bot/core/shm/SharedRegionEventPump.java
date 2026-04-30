package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.GameEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Drains the producer's shared-memory event ring on a background daemon
 * thread and forwards typed {@link GameEvent} instances to the supplied
 * consumer (typically {@code EventBus::publish}).
 *
 * <p>Owns the {@link SharedRegion} and the polling thread. {@link #close()}
 * stops the thread and releases the mapping. Constructed in {@code start}
 * state — there is no separate {@code start()} call.</p>
 *
 * <p>Poll cadence is 50 ms. Smoke-tested with that cadence under live tick
 * + chat traffic and observed zero ring overruns; the producer ring holds
 * 1024 slots which gives ~17 s of slack at 60 Hz publish.</p>
 */
public final class SharedRegionEventPump implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SharedRegionEventPump.class);

    private static final long POLL_INTERVAL_MS = 50L;

    private final SharedRegion region;
    private final EventRingReader reader;
    private final Thread thread;
    private volatile boolean running = true;

    /**
     * @param pid      pid of the injected game process — typically extracted
     *                 from the matching {@code BotWithUs_<pid>} pipe name via
     *                 {@link SharedRegion#parsePid(String)}.
     * @param consumer destination for decoded events. Must be safe to invoke
     *                 from a non-Bot virtual thread; subscribers downstream
     *                 are expected to handle their own threading.
     */
    public SharedRegionEventPump(long pid, Consumer<GameEvent> consumer) {
        this(SharedRegion.open(pid), consumer, "event-pump-" + pid);
    }

    SharedRegionEventPump(SharedRegion region, Consumer<GameEvent> consumer, String threadName) {
        this.region = region;
        this.reader = new EventRingReader(region, /*fromHead*/ true);
        this.thread = new Thread(() -> runLoop(consumer), threadName);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void runLoop(Consumer<GameEvent> consumer) {
        log.info("Event pump started ({})", thread.getName());
        try {
            while (running) {
                try {
                    reader.poll(consumer);
                } catch (RuntimeException re) {
                    // A read error on a single slot must not kill the pump.
                    // The reader's internal state has already advanced past
                    // the offending slot by the time it returns, so just
                    // log and continue draining.
                    log.warn("event-ring poll failed; continuing", re);
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    // close() interrupts us — exit promptly.
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            log.info("Event pump stopped (lifetime drops: writer={} reader={})",
                    reader.writerSideDroppedCount(), reader.droppedCount());
        }
    }

    /** Diagnostic — exposes the underlying reader's drop counters. */
    public EventRingReader reader() { return reader; }

    /** Diagnostic — exposes the region for snapshot probes. */
    public SharedRegion region() { return region; }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        region.close();
    }
}
