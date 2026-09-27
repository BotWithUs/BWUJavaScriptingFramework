package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientKey;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the registry's clients and the last scan become the Connections table:
 * which group each lands in, in what order, what it shows and what it offers.
 */
class ConnectionRowsTest {

    private static final Instant NOW = Instant.parse("2026-09-26T14:06:00Z");
    private static final String OAK_PIPE = "BotWithUs_14208";
    private static final String HOLLOW_PIPE = "BotWithUs_10344";
    private static final String FOUND_PIPE = "BotWithUs_19544";
    private static final String OAK_UUID = "3f9a1c2e58b04d7a9e216c0f4b7d2a18";
    private static final String HOLLOW_UUID = "e4410b7a9d254c6ba1f873e02b5d9c46";
    private static final String BRACKEN_UUID = "0a6d2f58c3e147b99f045e8b17a2d6c9";
    private static final String ASH_UUID = "c93f5a2781d04e6b9c42b7e15f0a38d1";
    private static final int WORLD = 84;
    private static final int LAST_WORLD = 44;
    private static final int ATTEMPT = 4;
    private static final double AVG_MS = 2.8;
    private static final long CALLS = 18_204L;

    private final List<ClientRecord> clients = new ArrayList<>();
    private final List<FoundPipe> scanned = new ArrayList<>();
    private final Set<String> connected = new HashSet<>();
    private final Map<String, LinkStats> stats = new HashMap<>();
    private final Map<ClientKey, Instant> droppedAt = new HashMap<>();
    private Optional<String> consoleTarget = Optional.empty();
    private Optional<String> outputFilter = Optional.empty();

    // ── Groups and order ───────────────────────────────────────────────────

    @Test
    void connectedAndReconnectingComeFirstInRegistryOrder_thenFound_thenClosedNewestFirst() {
        closed(BRACKEN_UUID, Duration.ofHours(2));
        live(OAK_UUID, OAK_PIPE, new ClientLifecycle.Connected());
        closed(ASH_UUID, Duration.ofMinutes(3));
        live(HOLLOW_UUID, HOLLOW_PIPE, retrying());
        found(FOUND_PIPE, Optional.empty(), GameStatus.UNKNOWN);

        List<ConnectionRow> rows = build();

        assertEquals(List.of(OAK_UUID, HOLLOW_UUID, FOUND_PIPE, ASH_UUID, BRACKEN_UUID),
                rows.stream().map(ConnectionRow::id).toList());
        assertEquals(List.of(RowGroup.CONNECTED, RowGroup.CONNECTED, RowGroup.FOUND, RowGroup.CLOSED,
                RowGroup.CLOSED), rows.stream().map(ConnectionRow::group).toList());
    }

    @Test
    void aScannedPipeTheHostIsConnectedToIsNotFound() {
        live(OAK_UUID, OAK_PIPE, new ClientLifecycle.Connected());
        found(OAK_PIPE, Optional.of("Oakheart"), GameStatus.UNKNOWN);
        found(FOUND_PIPE, Optional.empty(), GameStatus.UNKNOWN);

        List<ConnectionRow> rows = build();

        assertEquals(List.of(OAK_UUID, FOUND_PIPE), rows.stream().map(ConnectionRow::id).toList());
    }

    @Test
    void aClosedClientsOldPipeShowingUpInAScanIsStillFound() {
        clients.add(new ClientRecord(ClientKey.account(BRACKEN_UUID), new ClientLifecycle.Closed(NOW),
                Optional.of(FOUND_PIPE), Optional.empty(), GameStatus.UNKNOWN, OptionalInt.empty(),
                Optional.empty()));
        found(FOUND_PIPE, Optional.empty(), GameStatus.UNKNOWN);

        assertEquals(List.of(RowGroup.FOUND, RowGroup.CLOSED), build().stream().map(ConnectionRow::group).toList());
    }

    @Test
    void aScannedPipeWithAConnectionButNoRegistryEntryYetIsNotFoundEither() {
        connected.add(FOUND_PIPE);
        found(FOUND_PIPE, Optional.empty(), GameStatus.UNKNOWN);

        assertTrue(build().isEmpty(), "a pipe being connected to is not offered for connecting");
    }

    @Test
    void aFoundPipeShowsWhatTheProbeRead_andItsUuidOnlyOnConnect() {
        GameStatus inGame = new GameStatus(GameState.IN_GAME, OptionalInt.of(WORLD), true);
        found(FOUND_PIPE, Optional.of("Mirelock"), inGame);

        ConnectionRow row = build().getFirst();

        assertAll(
                () -> assertEquals(new LinkState.Found(), row.link()),
                () -> assertEquals(Optional.of("Mirelock"), row.account()),
                () -> assertEquals(Optional.empty(), row.accountUuid()),
                () -> assertEquals(Optional.empty(), row.key()),
                () -> assertEquals(Optional.of(FOUND_PIPE), row.pipe()),
                () -> assertEquals(OptionalInt.of(WORLD), row.world()),
                () -> assertEquals(inGame, row.game()),
                () -> assertEquals(EnumSet.of(RowAction.CONNECT), row.actions()));
    }

    // ── What a row shows ───────────────────────────────────────────────────

    @Test
    void aConnectedRowShowsItsStatsAndOnlyTheWorldItIsInNow() {
        GameStatus lobby = new GameStatus(GameState.LOBBY, OptionalInt.empty(), false);
        clients.add(new ClientRecord(ClientKey.account(OAK_UUID), new ClientLifecycle.Connected(),
                Optional.of(OAK_PIPE), Optional.of("Oakheart"), lobby, OptionalInt.of(LAST_WORLD),
                Optional.of(NOW.minusSeconds(1))));
        connected.add(OAK_PIPE);
        LinkStats link = new LinkStats(Duration.ofMinutes(2), CALLS, 0L, AVG_MS);
        stats.put(OAK_PIPE, link);

        ConnectionRow row = build().getFirst();

        assertAll(
                () -> assertEquals(Optional.of(OAK_UUID), row.accountUuid()),
                () -> assertEquals(Optional.of(link), row.stats()),
                () -> assertEquals(OptionalInt.empty(), row.world(), "the lobby has no world"),
                () -> assertEquals(lobby, row.game()));
    }

    @Test
    void aClosedRowShowsTheLastWorldAndHowLongAgo() {
        closed(BRACKEN_UUID, Duration.ofMinutes(3));

        ConnectionRow row = build().getFirst();

        assertAll(
                () -> assertEquals(OptionalInt.of(LAST_WORLD), row.world()),
                () -> assertEquals(new LinkState.Closed(Duration.ofMinutes(3), false), row.link()),
                () -> assertEquals(Optional.empty(), row.stats()));
    }

    @Test
    void aPipeKeyedClientHasNoUuid() {
        live(null, OAK_PIPE, new ClientLifecycle.Connected());

        ConnectionRow row = build().getFirst();

        assertAll(
                () -> assertEquals(Optional.empty(), row.accountUuid()),
                () -> assertEquals("pipe:" + OAK_PIPE, row.id()));
    }

    @Test
    void aReconnectingRowCountsDownToItsNextTryAndUpFromTheDrop() {
        live(HOLLOW_UUID, HOLLOW_PIPE, new ClientLifecycle.NotResponding(ATTEMPT,
                Optional.of(Duration.ofSeconds(8)), OptionalInt.empty(), NOW.minusSeconds(3)));
        droppedAt.put(ClientKey.account(HOLLOW_UUID), NOW.minusSeconds(42));

        LinkState link = build().getFirst().link();

        assertEquals(new LinkState.NotResponding(ATTEMPT, OptionalInt.empty(),
                Optional.of(Duration.ofSeconds(5)), Duration.ofSeconds(42)), link);
    }

    @Test
    void aRetryThatIsOverdueShowsZeroNotANegativeWait() {
        live(HOLLOW_UUID, HOLLOW_PIPE, new ClientLifecycle.NotResponding(ATTEMPT,
                Optional.of(Duration.ofSeconds(1)), OptionalInt.empty(), NOW.minusSeconds(3)));

        LinkState.NotResponding link = notResponding(build().getFirst());

        assertEquals(Optional.of(Duration.ZERO), link.nextIn());
        assertEquals(Duration.ofSeconds(3), link.downFor(), "without a recorded drop, since the state was reported");
    }

    @Test
    void consoleTargetAndOutputFilterMarkTheirRows() {
        live(OAK_UUID, OAK_PIPE, new ClientLifecycle.Connected());
        live(HOLLOW_UUID, HOLLOW_PIPE, new ClientLifecycle.Connected());
        consoleTarget = Optional.of(OAK_PIPE);
        outputFilter = Optional.of(HOLLOW_PIPE);

        List<ConnectionRow> rows = build();

        assertAll(
                () -> assertTrue(rows.get(0).isConsoleTarget()),
                () -> assertFalse(rows.get(0).isOutputFilter()),
                () -> assertFalse(rows.get(1).isConsoleTarget()),
                () -> assertTrue(rows.get(1).isOutputFilter()));
    }

    @Test
    void withNoTargetAndNoFilterARowWithoutAPipeIsNeither() {
        closed(BRACKEN_UUID, Duration.ofMinutes(3));

        ConnectionRow row = build().getFirst();

        assertFalse(row.isConsoleTarget() || row.isOutputFilter(), "an empty pipe must not match an empty setting");
    }

    // ── What a row offers ──────────────────────────────────────────────────

    @Test
    void aConnectedRowOffersConsoleRoutingAndDisconnect() {
        live(OAK_UUID, OAK_PIPE, new ClientLifecycle.Connected());

        assertEquals(EnumSet.of(RowAction.CONSOLE_TARGET, RowAction.OUTPUT_FILTER, RowAction.DISCONNECT),
                build().getFirst().actions());
    }

    @Test
    void anIdentifyingOrResumingRowOffersTheSameAsAConnectedOne() {
        live(OAK_UUID, OAK_PIPE, new ClientLifecycle.Identifying());
        live(HOLLOW_UUID, HOLLOW_PIPE, new ClientLifecycle.Resuming(Optional.of("BotWithUs_1"), NOW));

        Set<RowAction> expected = EnumSet.of(RowAction.CONSOLE_TARGET, RowAction.OUTPUT_FILTER, RowAction.DISCONNECT);
        assertAll(build().stream().map(row -> () -> assertEquals(expected, row.actions(), row.id())));
    }

    @Test
    void aReconnectingRowOffersRetryNowAndStopRetrying() {
        live(HOLLOW_UUID, HOLLOW_PIPE, retrying());

        assertEquals(EnumSet.of(RowAction.RETRY_NOW, RowAction.STOP_RETRYING), build().getFirst().actions());
    }

    @Test
    void aRowThatStoppedRetryingOffersRetryNowAndDisconnect() {
        live(HOLLOW_UUID, HOLLOW_PIPE, new ClientLifecycle.NotResponding(ATTEMPT, Optional.empty(),
                OptionalInt.empty(), NOW));

        assertEquals(EnumSet.of(RowAction.RETRY_NOW, RowAction.DISCONNECT), build().getFirst().actions());
    }

    @Test
    void aClientWhoseGameExitedOffersForgetNotRetry() {
        clients.add(new ClientRecord(ClientKey.account(HOLLOW_UUID), new ClientLifecycle.Closed(NOW),
                Optional.of(HOLLOW_PIPE), Optional.of("Hollowmere"), GameStatus.UNKNOWN,
                OptionalInt.empty(), Optional.empty()));
        connected.add(HOLLOW_PIPE);

        ConnectionRow row = build().getFirst();

        assertAll(
                () -> assertEquals(RowGroup.CLOSED, row.group()),
                () -> assertEquals(new LinkState.Closed(Duration.ZERO, true), row.link()),
                () -> assertEquals(EnumSet.of(RowAction.FORGET), row.actions()));
    }

    @Test
    void aRememberedClosedClientOffersForget() {
        closed(BRACKEN_UUID, Duration.ofMinutes(3));

        assertEquals(EnumSet.of(RowAction.FORGET), build().getFirst().actions());
    }

    @Test
    void withoutARegisteredConnectionNothingThatNeedsOneIsOffered() {
        clients.add(new ClientRecord(ClientKey.account(OAK_UUID), new ClientLifecycle.Connected(),
                Optional.of(OAK_PIPE), Optional.empty(), GameStatus.UNKNOWN, OptionalInt.empty(),
                Optional.of(NOW)));

        assertEquals(Set.of(), build().getFirst().actions(), "the connection was dropped a moment ago");
    }

    // ── The view over the rows ─────────────────────────────────────────────

    @Test
    void theFiltersCountAndKeepTheirRows() {
        live(OAK_UUID, OAK_PIPE, new ClientLifecycle.Connected());
        live(HOLLOW_UUID, HOLLOW_PIPE, retrying());
        found(FOUND_PIPE, Optional.empty(), GameStatus.UNKNOWN);
        closed(BRACKEN_UUID, Duration.ofMinutes(3));
        ConnectionsView view = new ConnectionsView(build(), true, "BotWithUs", Duration.ofSeconds(5), false,
                Optional.empty());

        assertAll(
                () -> assertEquals(4, view.count(RowFilter.ALL)),
                () -> assertEquals(2, view.count(RowFilter.CONNECTED)),
                () -> assertEquals(1, view.count(RowFilter.FOUND)),
                () -> assertEquals(2, view.count(RowFilter.PROBLEMS)),
                () -> assertEquals(1, view.notResponding()),
                () -> assertEquals(List.of(HOLLOW_UUID, BRACKEN_UUID),
                        view.shown(RowFilter.PROBLEMS, "").stream().map(ConnectionRow::id).toList()),
                () -> assertEquals(List.of(FOUND_PIPE), view.found().stream().map(ConnectionRow::id).toList()));
    }

    @Test
    void theSearchMatchesAccountPipeOrUuid() {
        live(OAK_UUID, OAK_PIPE, new ClientLifecycle.Connected());
        live(HOLLOW_UUID, HOLLOW_PIPE, new ClientLifecycle.Connected());
        ConnectionsView view = new ConnectionsView(build(), true, "BotWithUs", Duration.ofSeconds(5), false,
                Optional.empty());

        assertAll(
                () -> assertEquals(1, view.shown(RowFilter.ALL, "e4410b").size(), "by uuid"),
                () -> assertEquals(1, view.shown(RowFilter.ALL, "_14208").size(), "by pipe"),
                () -> assertEquals(2, view.shown(RowFilter.ALL, "  ").size(), "blank matches all"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<ConnectionRow> build() {
        return ConnectionRows.build(new ConnectionRows.Reading(clients, scanned, connected, stats, consoleTarget,
                outputFilter, droppedAt, NOW));
    }

    /** A client on {@code pipe} with a registered connection; a null uuid keys it by its pipe. */
    private void live(String uuid, String pipe, ClientLifecycle lifecycle) {
        ClientKey key = uuid != null ? ClientKey.account(uuid) : ClientKey.pipe(pipe);
        GameStatus inGame = new GameStatus(GameState.IN_GAME, OptionalInt.of(WORLD), true);
        clients.add(new ClientRecord(key, lifecycle, Optional.of(pipe), Optional.of("Name of " + pipe), inGame,
                OptionalInt.of(WORLD), Optional.of(NOW.minusSeconds(60))));
        connected.add(pipe);
    }

    private void closed(String uuid, Duration ago) {
        clients.add(new ClientRecord(ClientKey.account(uuid), new ClientLifecycle.Closed(NOW.minus(ago)),
                Optional.empty(), Optional.of("Name of " + uuid), GameStatus.UNKNOWN,
                OptionalInt.of(LAST_WORLD), Optional.empty()));
    }

    private void found(String pipe, Optional<String> account, GameStatus game) {
        scanned.add(new FoundPipe(pipe, account, game));
    }

    private static ClientLifecycle retrying() {
        return new ClientLifecycle.NotResponding(ATTEMPT, Optional.of(Duration.ofSeconds(8)), OptionalInt.empty(),
                NOW);
    }

    private static LinkState.NotResponding notResponding(ConnectionRow row) {
        return switch (row.link()) {
            case LinkState.NotResponding n -> n;
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _, LinkState.Found _,
                 LinkState.Closed _ -> throw new AssertionError("not reconnecting: " + row.link());
        };
    }
}
