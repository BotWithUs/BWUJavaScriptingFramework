package com.botwithus.bot.cli;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The character name one connection's scripts and management view read: what the
 * {@link Connection} last published, with a cheap nudge when it is missing.
 *
 * <p>The name normally arrives without help, because the status tracker re-reads
 * the account whenever the login state changes. But the client can announce the
 * world before it has resolved the player's name, and a reading taken in that gap
 * stores none. So when a reader finds the name empty while the client is, or may
 * be, in a world, this asks the tracker for another read, at most once per
 * {@link #RETRY_INTERVAL}. The ask only queues work on the tracker's executor:
 * a read never blocks the calling script thread.</p>
 *
 * <p>Built before its connection, because the script context and client view
 * that hold it are needed to build the connection; {@link #bind} closes the loop.
 * Until then it reports no name.</p>
 */
final class CharacterNameSource implements Supplier<Optional<String>> {

    /** The shortest gap between two re-reads asked for by readers. */
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    private final Consumer<Connection> requestRefresh;
    private final Supplier<Instant> now;
    private final AtomicReference<Instant> lastRequest = new AtomicReference<>();
    private volatile Connection connection;

    /**
     * @param requestRefresh queues a read of a connection's account; must not block
     * @param now            the current time, for the retry interval
     */
    CharacterNameSource(Consumer<Connection> requestRefresh, Supplier<Instant> now) {
        this.requestRefresh = requestRefresh;
        this.now = now;
    }

    /** Points this source at the connection it names. */
    void bind(Connection conn) {
        this.connection = conn;
    }

    @Override
    public Optional<String> get() {
        Connection conn = connection;
        if (conn == null) {
            return Optional.empty();
        }
        Optional<String> name = conn.getCharacterName();
        if (name.isEmpty() && canBeInWorld(conn) && claimRetry()) {
            requestRefresh.accept(conn);
        }
        return name;
    }

    /**
     * Whether a re-read could find a name. At the login screen or in the lobby it
     * cannot, and the next login state change triggers a read anyway.
     */
    private static boolean canBeInWorld(Connection conn) {
        GameState state = conn.getGameState();
        return conn.isAlive() && (state == GameState.IN_GAME || state == GameState.UNKNOWN);
    }

    /** Takes the retry slot if the interval has passed; one caller wins a race. */
    private boolean claimRetry() {
        Instant current = now.get();
        Instant last = lastRequest.get();
        if (last != null && current.isBefore(last.plus(RETRY_INTERVAL))) {
            return false;
        }
        return lastRequest.compareAndSet(last, current);
    }
}
