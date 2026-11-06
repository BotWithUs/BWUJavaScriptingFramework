package com.botwithus.bot.core.shm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Fans {@link ActionRefusal}s out to whoever is listening: one per connection, fed by its
 * {@link SharedRegionEventPump}. A WorldWalker run listens for the length of the run.
 *
 * <p>Thread-safe. {@link #publish} runs every listener on the caller's thread (the pump's), so a
 * listener only records what it saw and acts on it from its own thread. A listener that throws is
 * logged and skipped; the others still hear the refusal.</p>
 */
public final class ActionRefusalFeed {

    private static final Logger log = LoggerFactory.getLogger(ActionRefusalFeed.class);

    private final List<Consumer<ActionRefusal>> listeners = new CopyOnWriteArrayList<>();

    /** A feed with no listeners yet. */
    public ActionRefusalFeed() {
        // Listeners arrive through subscribe.
    }

    /** A listener's registration; {@link #close()} removes it, and is safe to repeat. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** Registers {@code listener} until the returned subscription is closed. */
    public Subscription subscribe(Consumer<ActionRefusal> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Hands {@code refusal} to every listener registered now. */
    public void publish(ActionRefusal refusal) {
        for (Consumer<ActionRefusal> listener : listeners) {
            try {
                listener.accept(refusal);
            } catch (RuntimeException e) {
                log.warn("action-refusal listener threw; continuing", e);
            }
        }
    }
}
