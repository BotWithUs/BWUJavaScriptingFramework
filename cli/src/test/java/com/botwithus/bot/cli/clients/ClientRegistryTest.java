package com.botwithus.bot.cli.clients;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.clients.ClientLifecycle.Closed;
import com.botwithus.bot.cli.clients.ClientLifecycle.Connected;
import com.botwithus.bot.cli.clients.ClientLifecycle.Identifying;
import com.botwithus.bot.cli.clients.ClientLifecycle.NotResponding;
import com.botwithus.bot.cli.clients.ClientLifecycle.Resuming;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ClientResumed;
import com.botwithus.bot.cli.events.HostEvent.CloseCause;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real registry with events as the host bus would deliver them.
 * The connections behind the pipes and the store are in-memory fakes.
 */
class ClientRegistryTest {

    private static final String PIPE = "BotWithUs_4242";
    private static final String NEW_PIPE = "BotWithUs_5151";
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final ClientKey ACCOUNT = ClientKey.account(UUID);
    private static final String NAME = "Zezima";
    private static final int WORLD = 84;
    private static final int ATTEMPT = 3;
    private static final int MAX_ATTEMPTS = 10;
    private static final long NEXT_DELAY_MS = 4_000L;
    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");

    /** A connection whose readings a test sets. */
    private static final class FakeLink implements LiveClient {
        private final Instant connectedAt;
        private volatile Optional<String> name = Optional.empty();
        private volatile GameStatus status = GameStatus.UNKNOWN;
        private volatile boolean isGone;

        FakeLink(Instant connectedAt) {
            this.connectedAt = connectedAt;
        }

        @Override public Instant connectedAt() { return connectedAt; }
        @Override public Optional<String> displayName() { return name; }
        @Override public GameStatus gameStatus() { return status; }
        @Override public OptionalInt maxAttempts() { return OptionalInt.of(MAX_ATTEMPTS); }
        @Override public boolean isClientGone() { return isGone; }

        void inWorld(int world) {
            status = new GameStatus(GameState.IN_GAME, OptionalInt.of(world), true);
        }
    }

    /** Keeps what was saved in memory. */
    private static final class MemoryStore implements ClientStore {
        private List<RememberedClient> saved = List.of();
        private int saves;

        @Override
        public List<RememberedClient> load() {
            return saved;
        }

        @Override
        public void save(List<RememberedClient> clients) throws IOException {
            saved = List.copyOf(clients);
            saves++;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = START;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private final Map<String, FakeLink> links = new HashMap<>();
    private final MemoryStore store = new MemoryStore();
    private final MutableClock clock = new MutableClock();
    private final ConnectionHistory history = new ConnectionHistory();
    private final List<HostEvent> published = new ArrayList<>();

    private ClientRegistry newRegistry() {
        return new ClientRegistry(store, pipe -> Optional.ofNullable(links.get(pipe)), history,
                published::add, clock, Runnable::run);
    }

    // ── Identify ───────────────────────────────────────────────────────────

    @Test
    void anOpenedPipe_isIdentifying_underItsPipeKey() {
        ClientRegistry registry = newRegistry();

        open(registry, PIPE);

        ClientRecord record = only(registry);
        assertAll(
                () -> assertEquals(ClientKey.pipe(PIPE), record.key()),
                () -> assertEquals(new Identifying(), record.lifecycle()),
                () -> assertEquals(Optional.of(PIPE), record.pipe()),
                () -> assertEquals(Optional.of(START), record.connectedAt()));
    }

    @Test
    void identifying_movesTheClientOntoItsAccount_andConnectsIt() {
        ClientRegistry registry = newRegistry();
        FakeLink link = open(registry, PIPE);
        link.inWorld(WORLD);

        identify(registry, PIPE, ACCOUNT);

        ClientRecord record = only(registry);
        assertAll(
                () -> assertEquals(ACCOUNT, record.key()),
                () -> assertEquals(new Connected(), record.lifecycle()),
                () -> assertEquals(Optional.of(NAME), record.name()),
                () -> assertEquals(OptionalInt.of(WORLD), record.lastWorld()),
                () -> assertEquals(GameState.IN_GAME, record.gameStatus().state()),
                () -> assertTrue(registry.get(ClientKey.pipe(PIPE)).isEmpty(), "the pipe key is retired"));
        assertEquals(List.of(new RememberedClient(UUID, Optional.of(NAME), OptionalInt.of(WORLD), START)),
                store.saved, "an identified account is remembered at once");
    }

    @Test
    void aSnapshotShowsTheConnectionAsItIsNow_andDoesNotChangeAfterwards() {
        ClientRegistry registry = newRegistry();
        FakeLink link = open(registry, PIPE);
        identify(registry, PIPE, ACCOUNT);
        List<ClientRecord> before = registry.clients();

        link.inWorld(WORLD);

        assertEquals(OptionalInt.empty(), before.getFirst().lastWorld());
        assertEquals(OptionalInt.of(WORLD), only(registry).lastWorld());
    }

    // ── Dropped and closed ─────────────────────────────────────────────────

    @Test
    void aDroppedPipe_isNotRespondingWithTheRetryInProgress() {
        ClientRegistry registry = newRegistry();
        open(registry, PIPE);
        identify(registry, PIPE, ACCOUNT);
        Instant at = START.plusSeconds(1);

        registry.accept(new ReconnectStateChanged(new ClientRef(ACCOUNT, PIPE),
                new ReconnectState.Reconnecting(at.toEpochMilli(), ATTEMPT, NEXT_DELAY_MS), at));

        NotResponding state = notResponding(only(registry));
        assertAll(
                () -> assertEquals(ATTEMPT, state.attempt()),
                () -> assertEquals(Optional.of(at.plusMillis(NEXT_DELAY_MS)), state.nextAttemptAt()),
                () -> assertEquals(OptionalInt.of(MAX_ATTEMPTS), state.maxAttempts()));
    }

    @Test
    void givingUpOnALiveProcess_isNotResponding_butOnAnExitedOne_isClosed() {
        ClientRegistry registry = newRegistry();
        FakeLink link = open(registry, PIPE);
        identify(registry, PIPE, ACCOUNT);
        registry.accept(new ReconnectStateChanged(new ClientRef(ACCOUNT, PIPE),
                new ReconnectState.GivingUp(START.toEpochMilli(), ATTEMPT, null), START));

        assertFalse(notResponding(only(registry)).isRetrying());

        link.isGone = true;
        assertEquals(new Closed(START), only(registry).lifecycle());
    }

    @Test
    void aClosedAccount_isKept_andRememberedWithItsNameAndLastWorld() {
        ClientRegistry registry = newRegistry();
        FakeLink link = open(registry, PIPE);
        identify(registry, PIPE, ACCOUNT);
        link.inWorld(WORLD);
        Instant closedAt = START.plusSeconds(60);

        registry.accept(new ClientClosed(new ClientRef(ACCOUNT, PIPE), CloseCause.CONNECTION_LOST, closedAt));

        ClientRecord record = only(registry);
        assertAll(
                () -> assertEquals(new Closed(closedAt), record.lifecycle()),
                () -> assertEquals(Optional.empty(), record.pipe()),
                () -> assertEquals(Optional.of(NAME), record.name()),
                () -> assertEquals(OptionalInt.of(WORLD), record.lastWorld()),
                () -> assertEquals(GameStatus.UNKNOWN, record.gameStatus()));
        assertEquals(List.of(new RememberedClient(UUID, Optional.of(NAME), OptionalInt.of(WORLD), closedAt)),
                store.saved);
    }

    // ── Resume ─────────────────────────────────────────────────────────────

    @Test
    void afterAHostRestart_theSameAccountOnANewPipe_resumesItsRememberedCard() {
        store.saved = List.of(new RememberedClient(UUID, Optional.of(NAME), OptionalInt.of(WORLD), START));
        ClientRegistry registry = newRegistry();
        registry.load();
        assertEquals(new Closed(START), only(registry).lifecycle(), "remembered, closed, before any pipe");

        open(registry, NEW_PIPE);
        assertEquals(2, registry.clients().size(), "an unidentified pipe is not merged on a guess");
        clock.advance(Duration.ofSeconds(1));
        Instant resumedAt = clock.instant();
        identify(registry, NEW_PIPE, ACCOUNT);

        ClientRecord record = only(registry);
        assertEquals(ACCOUNT, record.key());
        assertEquals(new Resuming(Optional.empty(), resumedAt), record.lifecycle());
        assertEquals(Optional.of(NEW_PIPE), record.pipe());
        assertEquals(new ClientResumed(new ClientRef(ACCOUNT, NEW_PIPE), Optional.empty(), resumedAt),
                published.getLast());

        clock.advance(ClientRegistry.RESUMING_FOR);
        assertEquals(new Connected(), only(registry).lifecycle());
    }

    @Test
    void aClientClosedThisSession_resumesWithThePipeItWasOn() {
        ClientRegistry registry = newRegistry();
        open(registry, PIPE);
        identify(registry, PIPE, ACCOUNT);
        registry.accept(new ClientClosed(new ClientRef(ACCOUNT, PIPE), CloseCause.CONNECTION_LOST, START));

        open(registry, NEW_PIPE);
        identify(registry, NEW_PIPE, ACCOUNT);

        assertEquals(new Resuming(Optional.of(PIPE), START), only(registry).lifecycle());
    }

    @Test
    void aClientWhoseDeadPipeIsStillHeld_resumesOntoTheNewPipe() {
        ClientRegistry registry = newRegistry();
        FakeLink old = open(registry, PIPE);
        identify(registry, PIPE, ACCOUNT);
        old.isGone = true;
        registry.accept(new ReconnectStateChanged(new ClientRef(ACCOUNT, PIPE),
                new ReconnectState.GivingUp(START.toEpochMilli(), 1, null), START));

        open(registry, NEW_PIPE);
        identify(registry, NEW_PIPE, ACCOUNT);
        registry.accept(new ClientClosed(new ClientRef(PIPE), CloseCause.CONNECTION_LOST, START));

        ClientRecord record = only(registry);
        assertEquals(Optional.of(NEW_PIPE), record.pipe());
        assertEquals(new Resuming(Optional.of(PIPE), START), record.lifecycle(),
                "the dead pipe's own close must not close the account again");
    }

    // ── Duplicates and the development fallback ────────────────────────────

    @Test
    void twoLivePipesOnOneAccount_areBothShown_andOnlyTheFirstIsRemembered() {
        ClientRegistry registry = newRegistry();
        ClientKey second = new ClientKey.Account(UUID, 2);
        open(registry, PIPE);
        identify(registry, PIPE, ACCOUNT);
        open(registry, NEW_PIPE);

        identify(registry, NEW_PIPE, second);

        assertEquals(List.of(ACCOUNT, second), registry.clients().stream().map(ClientRecord::key).toList());
        assertEquals(List.of(UUID), store.saved.stream().map(RememberedClient::accountUuid).toList());
    }

    @Test
    void aPipeKeyedClient_isConnectedOnceIdentified_keptWhenClosed_andNeverSaved() {
        ClientRegistry registry = newRegistry();
        ClientKey pipeKey = ClientKey.pipe(PIPE);
        open(registry, PIPE);

        identify(registry, PIPE, pipeKey);
        assertEquals(new Connected(), only(registry).lifecycle());
        registry.accept(new ClientClosed(new ClientRef(PIPE), CloseCause.DISCONNECTED, START));

        assertEquals(new Closed(START), only(registry).lifecycle());
        registry.saveNow();
        assertEquals(List.of(), store.saved);
    }

    // ── Forget ─────────────────────────────────────────────────────────────

    @Test
    void forgettingARememberedClient_removesItFromTheStore() {
        store.saved = List.of(new RememberedClient(UUID, Optional.of(NAME), OptionalInt.of(WORLD), START));
        ClientRegistry registry = newRegistry();
        registry.load();

        registry.accept(new ClientForgotten(new ClientRef(ACCOUNT, ClientRef.NO_PIPE), START));

        assertTrue(registry.clients().isEmpty());
        assertEquals(List.of(), store.saved);
    }

    @Test
    void forgettingAPipeKeyedClient_doesNotTouchTheStore() {
        ClientRegistry registry = newRegistry();
        open(registry, PIPE);
        registry.accept(new ClientClosed(new ClientRef(PIPE), CloseCause.DISCONNECTED, START));

        registry.accept(new ClientForgotten(new ClientRef(PIPE), START));

        assertTrue(registry.clients().isEmpty());
        assertEquals(0, store.saves);
    }

    @Test
    void historyIsReadUnderTheClientsKey() {
        ClientRegistry registry = newRegistry();
        HostEvent opened = new ClientOpened(new ClientRef(PIPE), START);
        history.accept(opened);

        assertEquals(List.of(opened), registry.history(ClientKey.pipe(PIPE)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private FakeLink open(ClientRegistry registry, String pipe) {
        FakeLink link = new FakeLink(clock.instant());
        links.put(pipe, link);
        registry.accept(new ClientOpened(new ClientRef(pipe), clock.instant()));
        return link;
    }

    private void identify(ClientRegistry registry, String pipe, ClientKey key) {
        links.get(pipe).name = Optional.of(NAME);
        registry.accept(new ClientIdentified(new ClientRef(key, pipe), Optional.of(NAME), clock.instant()));
    }

    private static ClientRecord only(ClientRegistry registry) {
        List<ClientRecord> clients = registry.clients();
        assertEquals(1, clients.size(), () -> "expected one client, got " + clients);
        return clients.getFirst();
    }

    private static NotResponding notResponding(ClientRecord record) {
        return switch (record.lifecycle()) {
            case NotResponding state -> state;
            case Identifying _, Connected _, Resuming _, Closed _ ->
                    throw new AssertionError("not responding expected, was " + record.lifecycle());
        };
    }
}
