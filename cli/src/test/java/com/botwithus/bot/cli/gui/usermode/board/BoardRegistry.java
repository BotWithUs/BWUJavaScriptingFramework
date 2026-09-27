package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.clients.ClientRegistry;
import com.botwithus.bot.cli.clients.ClientStore;
import com.botwithus.bot.cli.clients.LiveClient;
import com.botwithus.bot.cli.clients.RememberedClient;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A real {@link ClientRegistry} for the board tests, fed host events by hand.
 * Each pipe's connection is read through a {@link LiveClient} over the
 * test's {@link Connection}, the way the host reads it; saves stay in memory.
 */
final class BoardRegistry {

    private final Map<String, LiveClient> links = new HashMap<>();
    private final Clock clock;
    private final List<RememberedClient> saved = new ArrayList<>();
    final ClientRegistry registry;

    BoardRegistry(Clock clock) {
        this.clock = clock;
        ClientStore store = new ClientStore() {
            @Override
            public List<RememberedClient> load() {
                return List.copyOf(saved);
            }

            @Override
            public void save(List<RememberedClient> clients) {
                saved.clear();
                saved.addAll(clients);
            }
        };
        registry = new ClientRegistry(store, pipe -> Optional.ofNullable(links.get(pipe)), new ConnectionHistory(),
                event -> { }, clock, Runnable::run);
    }

    /** Remembers {@code uuid} as if an earlier run had saved it, then loads it. */
    ClientKey remember(String uuid, String name) {
        saved.add(new RememberedClient(uuid, Optional.of(name), OptionalInt.empty(), clock.instant()));
        registry.load();
        return ClientKey.account(uuid);
    }

    /** Opens {@code conn}'s pipe; the client is identifying under its pipe key. */
    ClientKey open(Connection conn) {
        links.put(conn.getName(), new ConnectionLink(conn, clock.instant()));
        registry.accept(new ClientOpened(new ClientRef(conn.getName()), clock.instant()));
        return ClientKey.pipe(conn.getName());
    }

    /** Opens {@code conn}'s pipe and identifies it as {@code uuid}. */
    ClientKey connect(Connection conn, String uuid, String name) {
        open(conn);
        ClientKey key = ClientKey.account(uuid);
        registry.accept(new ClientIdentified(new ClientRef(key, conn.getName()), Optional.of(name),
                clock.instant()));
        return key;
    }

    /** Reports a reconnect state for the client under {@code key} on {@code pipe}. */
    void linkState(ClientKey key, String pipe, ReconnectState state) {
        registry.accept(new ReconnectStateChanged(new ClientRef(key, pipe), state, clock.instant()));
    }

    /** Reads a test connection the way the host's own link does. */
    private record ConnectionLink(Connection conn, Instant connectedAt) implements LiveClient {

        @Override
        public Optional<String> displayName() {
            return Optional.ofNullable(conn.getAccountName());
        }

        @Override
        public GameStatus gameStatus() {
            GameStatus status = conn.getGameStatus();
            return status != null ? status : GameStatus.UNKNOWN;
        }

        @Override
        public OptionalInt maxAttempts() {
            return conn.getReconnectController() != null
                    ? conn.getReconnectController().maxAttempts() : OptionalInt.empty();
        }

        @Override
        public boolean isClientGone() {
            return conn.getReconnectController() != null && conn.getReconnectController().isClientGone();
        }
    }
}
