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
 * <p>The name is {@link Connection#getCharacterName()}: the logged-in character
 * when known, else the Jagex character the client was launched for, so a client
 * at the login screen is named as soon as its first reading lands.</p>
 *
 * <p>The name normally arrives without help, because the status tracker reads the
 * account on attach and re-reads it whenever the login state changes. But the
 * client can announce the world before it has resolved the player's name, and a
 * reading taken in that gap stores no logged-in name; and a first read can fail.
 * So when a reader finds no logged-in name while the client is, or may be, in a
 * world, or finds that no reading has landed yet, this asks the tracker for
 * another read, at most once per {@link #RETRY_INTERVAL}. That holds even while
 * the launched name stands in. The ask only queues work on the tracker's
 * executor: a read never blocks the calling script thread.</p>
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
        if (couldLearnMore(conn) && claimRetry()) {
            requestRefresh.accept(conn);
        }
        return conn.getCharacterName();
    }

    /**
     * Whether a re-read could tell more than is known. Before any reading it
     * could. After one, only a logged-in name can still arrive, and at the login
     * screen or in the lobby it cannot: the next login state change triggers a
     * read anyway.
     */
    private static boolean couldLearnMore(Connection conn) {
        if (!conn.isAlive() || conn.getInGameName().isPresent()) {
            return false;
        }
        GameState state = conn.getGameState();
        boolean canBeInWorld = state == GameState.IN_GAME || state == GameState.UNKNOWN;
        return canBeInWorld || conn.getAccountInfo() == null;
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
