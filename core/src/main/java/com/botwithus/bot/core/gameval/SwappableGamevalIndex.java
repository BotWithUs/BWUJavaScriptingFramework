package com.botwithus.bot.core.gameval;

import com.botwithus.bot.api.gameval.GamevalEntry;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link GamevalIndex} whose backing index can be replaced while scripts use
 * it — how a background local build reaches connections that were handed the
 * shipped DB at startup. Every lookup reads the current delegate once, so a
 * single call never mixes two indexes.
 *
 * <p>A swap closes the index it replaces. A lookup already inside the old one
 * finishes or degrades to empty (that index's own close contract), and never
 * throws. Memos live in each delegate, so a swap also drops answers from the old
 * data.</p>
 *
 * <p><b>Resolved ids are not re-resolved.</b> A query that turned a name into an
 * id before the swap keeps that id; only lookups made afterwards see the new
 * index.</p>
 */
public final class SwappableGamevalIndex implements GamevalIndex {

    private final AtomicReference<GamevalIndex> current;
    /** Orders swaps against close, so a swap after close cannot leak the incoming index. */
    private final ReentrantLock lifecycle = new ReentrantLock();
    private boolean isClosed;

    /** Starts out reading {@code initial}, which this index now owns. */
    public SwappableGamevalIndex(GamevalIndex initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /**
     * Makes {@code next} the backing index and closes the previous one. After
     * {@link #close()} this closes {@code next} instead and returns false.
     *
     * @return whether {@code next} was installed
     */
    public boolean swapTo(GamevalIndex next) {
        Objects.requireNonNull(next, "next");
        GamevalIndex previous;
        lifecycle.lock();
        try {
            previous = isClosed ? next : current.getAndSet(next);
        } finally {
            lifecycle.unlock();
        }
        previous.close();
        return previous != next;
    }

    /** The index lookups currently go to. */
    GamevalIndex current() {
        return current.get();
    }

    @Override
    public OptionalInt id(GamevalType type, String gameval) {
        return current.get().id(type, gameval);
    }

    @Override
    public Optional<String> gameval(GamevalType type, int id) {
        return current.get().gameval(type, id);
    }

    @Override
    public List<GamevalEntry> startingWith(GamevalType type, String prefix, int limit) {
        return current.get().startingWith(type, prefix, limit);
    }

    @Override
    public boolean isAvailable() {
        return current.get().isAvailable();
    }

    @Override
    public Optional<String> meta(String key) {
        return current.get().meta(key);
    }

    /**
     * Closes the current index and refuses later swaps. The closed delegate stays
     * installed, so lookups degrade exactly as its own close contract says —
     * memoised answers keep working, cold ones resolve empty — rather than every
     * answer vanishing at once under a script the shutdown left running.
     */
    @Override
    public void close() {
        GamevalIndex previous;
        lifecycle.lock();
        try {
            isClosed = true;
            previous = current.get();
        } finally {
            lifecycle.unlock();
        }
        previous.close();
    }
}
