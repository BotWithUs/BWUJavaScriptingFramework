package com.botwithus.bot.cli;

import com.botwithus.bot.api.runtime.ReconnectState;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lifecycle and game-status state a {@link Connection} keeps for the GUI. */
class ConnectionLifecycleTest {

    private static final Instant CONNECTED_AT = Instant.parse("2026-09-26T10:00:00Z");
    private static final long RECONNECTED_MS = CONNECTED_AT.plusSeconds(90).toEpochMilli();
    private static final long LATER_MS = RECONNECTED_MS + 1_000;
    private static final int WORLD = 301;

    private final Connection conn = new FakeAgent().connection("BotWithUs_1", CONNECTED_AT);

    @Test
    void connectedAt_isWhenTheHostConnected_andAReconnectKeepsIt() {
        conn.onReconnectState(new ReconnectState.Connected(RECONNECTED_MS));

        assertEquals(CONNECTED_AT, conn.getConnectedAt());
        assertEquals(Instant.ofEpochMilli(RECONNECTED_MS), conn.getLastReconnectedAt().orElseThrow());
    }

    @Test
    void lastReconnectedAt_onlyMovesOnAReturnToConnected() {
        assertTrue(conn.getLastReconnectedAt().isEmpty(), "never dropped");

        conn.onReconnectState(new ReconnectState.Disconnected(RECONNECTED_MS, new IllegalStateException()));
        conn.onReconnectState(new ReconnectState.Reconnecting(RECONNECTED_MS, 1, 0));
        conn.onReconnectState(new ReconnectState.GivingUp(RECONNECTED_MS, 1, new IllegalStateException()));
        assertTrue(conn.getLastReconnectedAt().isEmpty());

        conn.onReconnectState(new ReconnectState.Connected(LATER_MS));
        assertEquals(Instant.ofEpochMilli(LATER_MS), conn.getLastReconnectedAt().orElseThrow());
    }

    @Test
    void publishGameStatus_anOlderReadingNeverOverwritesANewerOne() {
        long older = conn.beginStatusRead();
        long newer = conn.beginStatusRead();
        GameStatus inWorld = new GameStatus(GameState.IN_GAME, OptionalInt.of(WORLD), true);

        assertTrue(conn.publishGameStatus(newer, previous -> previous.withState(GameState.LOBBY)));
        assertFalse(conn.publishGameStatus(older, previous -> inWorld), "a slow read finishing late loses");

        assertEquals(GameState.LOBBY, conn.getGameState());
        assertTrue(conn.getWorldId().isEmpty());
    }
}
