package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.core.rpc.ReconnectPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The words and numbers the Connections page prints. */
class ConnectionTextTest {

    private static final int WORLD = 84;
    private static final int BUDGET = 10;
    private static final long FIVE_S = 5_000L;
    private static final long HALF_S = 500L;
    private static final long FIFTEEN_S = 15_000L;

    @Test
    void game_namesEachSettledStateAndDashesTheRest() {
        assertAll(
                () -> assertEquals("Logged in", ConnectionText.game(inGame(true))),
                () -> assertEquals("Lobby", ConnectionText.game(status(GameState.LOBBY))),
                () -> assertEquals("Login screen", ConnectionText.game(status(GameState.LOGIN_SCREEN))),
                () -> assertEquals("—", ConnectionText.game(GameStatus.UNKNOWN)));
    }

    @Test
    void gameLong_addsMembershipOnlyInAWorld() {
        assertAll(
                () -> assertEquals("logged in · members", ConnectionText.gameLong(inGame(true))),
                () -> assertEquals("logged in", ConnectionText.gameLong(inGame(false))),
                () -> assertEquals("lobby", ConnectionText.gameLong(status(GameState.LOBBY))),
                () -> assertEquals("login screen", ConnectionText.gameLong(status(GameState.LOGIN_SCREEN))),
                () -> assertEquals("—", ConnectionText.gameLong(GameStatus.UNKNOWN)));
    }

    @Test
    void isMemberNote_onlyForAMemberInAWorld() {
        assertAll(
                () -> assertTrue(ConnectionText.isMemberNote(inGame(true))),
                () -> assertFalse(ConnectionText.isMemberNote(inGame(false))),
                () -> assertFalse(ConnectionText.isMemberNote(
                        new GameStatus(GameState.LOBBY, OptionalInt.empty(), true))));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0h 00m",
            "59, 0h 00m",
            "180, 0h 03m",
            "8040, 2h 14m",
            "86399, 23h 59m",
            "86400, 1d 00h",
            "187200, 2d 04h"})
    void uptime_isHoursAndMinutesThenDaysAndHours(long seconds, String expected) {
        assertEquals(expected, ConnectionText.uptime(Duration.ofSeconds(seconds)));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0:00",
            "42, 0:42",
            "725, 12:05",
            "3723, 1:02:03"})
    void downFor_isAClock(long seconds, String expected) {
        assertEquals(expected, ConnectionText.downFor(Duration.ofSeconds(seconds)));
    }

    @ParameterizedTest
    @CsvSource({
            "0, just now, just now",
            "59, just now, just now",
            "180, 3m ago, 3 min ago",
            "7200, 2h ago, 2 h ago",
            "259200, 3d ago, 3 days ago"})
    void ago_shortForTheTableAndLongForTheDetail(long seconds, String brief, String spelled) {
        Duration d = Duration.ofSeconds(seconds);
        assertAll(
                () -> assertEquals(brief, ConnectionText.ago(d)),
                () -> assertEquals(spelled, ConnectionText.agoLong(d)));
    }

    @Test
    void rpcAndCounts() {
        assertAll(
                () -> assertEquals("2.8 ms", ConnectionText.rpc(2.84)),
                () -> assertEquals("0.0 ms", ConnectionText.rpc(0.0)),
                () -> assertEquals("18,204", ConnectionText.count(18_204L)),
                () -> assertEquals("7", ConnectionText.count(7L)));
    }

    @Test
    void attempt_saysWhenThereIsNoLimit() {
        assertAll(
                () -> assertEquals("4 · no limit", ConnectionText.attempt(4, OptionalInt.empty())),
                () -> assertEquals("4 of 10", ConnectionText.attempt(4, OptionalInt.of(BUDGET))));
    }

    @Test
    void seconds_roundsUpAndSaysNowAtZero() {
        assertAll(
                () -> assertEquals("8 s", ConnectionText.seconds(Duration.ofMillis(7_200L))),
                () -> assertEquals("1 s", ConnectionText.seconds(Duration.ofMillis(1L))),
                () -> assertEquals("now", ConnectionText.seconds(Duration.ZERO)));
    }

    @Test
    void policy_readsTheBackOffTheWayTheDesignWritesIt() {
        assertAll(
                () -> assertEquals("0.5 s × 2, max 15 s", ConnectionText.policy(ReconnectPolicy.DEFAULT)),
                () -> assertEquals("1.5 s × 1.5, max 1 min, 10 tries",
                        ConnectionText.policy(new ReconnectPolicy(BUDGET, 1_500L, 1.5, 60_000L))),
                () -> assertEquals("5 s, every time",
                        ConnectionText.policy(new ReconnectPolicy(ReconnectPolicy.UNLIMITED, FIVE_S, 1.0, FIVE_S))));
    }

    @Test
    void link_labelsEveryState() {
        assertAll(
                () -> assertEquals("Connected", ConnectionText.link(new LinkState.Connected())),
                () -> assertEquals("Identifying…", ConnectionText.link(new LinkState.Identifying())),
                () -> assertEquals("Resuming", ConnectionText.link(new LinkState.Resuming(Optional.empty()))),
                () -> assertEquals("Retry 4 · 8 s", ConnectionText.link(new LinkState.NotResponding(4,
                        OptionalInt.empty(), Optional.of(Duration.ofSeconds(8)), Duration.ZERO))),
                () -> assertEquals("Not retrying", ConnectionText.link(new LinkState.NotResponding(4,
                        OptionalInt.empty(), Optional.empty(), Duration.ZERO))),
                () -> assertEquals("Not connected", ConnectionText.link(new LinkState.Found())),
                () -> assertEquals("Client closed", ConnectionText.link(new LinkState.Closed(Duration.ZERO, false))));
    }

    @Test
    void identityAndHeaderBits() {
        assertAll(
                () -> assertEquals("3f9a1c2e", ConnectionText.shortUuid("3f9a1c2e58b04d7a9e216c0f4b7d2a18")),
                () -> assertEquals("abc", ConnectionText.shortUuid("abc")),
                () -> assertEquals("W84", ConnectionText.world(OptionalInt.of(WORLD))),
                () -> assertEquals("—", ConnectionText.world(OptionalInt.empty())),
                () -> assertEquals("BotWithUs*", ConnectionText.pipeGlob("BotWithUs")),
                () -> assertEquals("Auto-connect · scanning every 5 s",
                        ConnectionText.autoConnectLine(true, Duration.ofMillis(FIVE_S))),
                () -> assertEquals("Auto-connect · scanning every 0.5 s",
                        ConnectionText.autoConnectLine(true, Duration.ofMillis(HALF_S))),
                () -> assertEquals("Auto-connect off · scan by hand",
                        ConnectionText.autoConnectLine(false, Duration.ofMillis(FIFTEEN_S))));
    }

    private static GameStatus inGame(boolean isMember) {
        return new GameStatus(GameState.IN_GAME, OptionalInt.of(WORLD), isMember);
    }

    private static GameStatus status(GameState state) {
        return new GameStatus(state, OptionalInt.empty(), false);
    }
}
