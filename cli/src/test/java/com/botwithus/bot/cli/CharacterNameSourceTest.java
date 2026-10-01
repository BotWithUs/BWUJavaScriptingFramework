package com.botwithus.bot.cli;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.botwithus.bot.cli.FakeAgent.GET_ACCOUNT_INFO;
import static com.botwithus.bot.cli.FakeAgent.accountInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-connection name source scripts read: what it reports, and when it asks
 * the tracker for another read.
 */
class CharacterNameSourceTest {

    private static final String PIPE = "BotWithUs_4242";
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final String NAME = "Zezima";
    private static final int LOBBY = 20;
    private static final int IN_GAME = 30;
    private static final int WORLD = 301;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final FakeAgent agent = new FakeAgent();
    private final List<Connection> refreshRequests = new ArrayList<>();
    private Instant now = START;
    private final CharacterNameSource source = new CharacterNameSource(refreshRequests::add, () -> now);

    private Connection connectionReading(String displayName, int gameState) {
        agent.reply(GET_ACCOUNT_INFO, accountInfo(displayName, "", UUID, gameState, false)).world(WORLD);
        Connection conn = agent.connection(PIPE);
        new ConnectionStatusTracker(Runnable::run, Duration.ofDays(1)).refresh(conn);
        source.bind(conn);
        return conn;
    }

    @Test
    void get_unbound_reportsNoNameAndAsksForNothing() {
        assertEquals(Optional.empty(), source.get());
        assertTrue(refreshRequests.isEmpty());
    }

    @Test
    void get_nameKnown_reportsItWithoutAskingAgain() {
        connectionReading(NAME, IN_GAME);

        assertEquals(Optional.of(NAME), source.get());
        assertTrue(refreshRequests.isEmpty());
    }

    @Test
    void get_inWorldWithoutAName_asksAtMostOncePerInterval() {
        Connection conn = connectionReading("", IN_GAME);

        assertEquals(Optional.empty(), source.get());
        source.get();
        assertEquals(List.of(conn), refreshRequests, "a burst of reads shares one request");

        now = now.plus(CharacterNameSource.RETRY_INTERVAL);
        source.get();
        assertEquals(List.of(conn, conn), refreshRequests);
    }

    @Test
    void get_inTheLobby_asksForNothing() {
        connectionReading(NAME, LOBBY);

        assertEquals(Optional.empty(), source.get(), "a lobby reply carries no in-game name");
        assertTrue(refreshRequests.isEmpty(), "the next login state change brings a read anyway");
    }
}
