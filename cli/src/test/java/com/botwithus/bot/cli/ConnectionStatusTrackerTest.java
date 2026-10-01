package com.botwithus.bot.cli;

import com.botwithus.bot.api.event.LoginStateChangeEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.runtime.ReconnectState;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.Executor;

import static com.botwithus.bot.cli.FakeAgent.GET_ACCOUNT_INFO;
import static com.botwithus.bot.cli.FakeAgent.GET_CURRENT_WORLD;
import static com.botwithus.bot.cli.FakeAgent.GET_LOGIN_STATE;
import static com.botwithus.bot.cli.FakeAgent.accountInfo;
import static com.botwithus.bot.cli.FakeAgent.olderAccountInfo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real tracker against a {@link FakeAgent}: what one refresh stores,
 * which events trigger another, and that nothing blocking runs on the thread that
 * delivered the event.
 */
class ConnectionStatusTrackerTest {

    private static final String PIPE = "BotWithUs_4242";
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final String NAME = "Zezima";
    private static final String OTHER_NAME = "Lynx Titan";
    private static final int LOGIN_SCREEN = 10;
    private static final int LOBBY = 20;
    private static final int IN_GAME = 30;
    private static final int WORLD = 301;
    private static final int OTHER_WORLD = 302;

    private final Queue<Runnable> queue = new ArrayDeque<>();
    private final Executor queued = queue::add;
    private final FakeAgent agent = new FakeAgent();

    private static ConnectionStatusTracker tracker(Executor executor) {
        return new ConnectionStatusTracker(executor, Duration.ofDays(1));
    }

    @Test
    void refresh_noDisplayNameYet_storesTheUuidAndBindsTheRuntime() {
        agent.reply(GET_ACCOUNT_INFO, olderAccountInfo("", UUID, false, false)).loginState(LOGIN_SCREEN);
        Connection conn = agent.connection(PIPE);

        AccountReply reply = tracker(Runnable::run).refresh(conn);

        assertAll(
                () -> assertTrue(reply.displayName().isEmpty()),
                () -> assertEquals(UUID, conn.getAccountUuid()),
                () -> assertEquals(UUID, conn.getIdentifiedUuid().orElseThrow()),
                () -> assertEquals(UUID, conn.getRuntime().getAccountUuid(),
                        "per-script config must persist under the account from the first probe"),
                () -> assertEquals(GameState.LOGIN_SCREEN, conn.getGameState()),
                () -> assertTrue(conn.getWorldId().isEmpty()),
                () -> assertEquals(0, agent.callsTo(GET_CURRENT_WORLD), "no world outside the game"));
    }

    @Test
    void refresh_withDisplayName_storesUuidStateWorldAndMembership() {
        agent.reply(GET_ACCOUNT_INFO, accountInfo(NAME, NAME, UUID, IN_GAME, true)).world(WORLD);
        Connection conn = agent.connection(PIPE);

        AccountReply reply = tracker(Runnable::run).refresh(conn);

        assertAll(
                () -> assertEquals(NAME, reply.displayName().orElseThrow()),
                () -> assertEquals(UUID, conn.getAccountUuid()),
                () -> assertEquals(GameState.IN_GAME, conn.getGameState()),
                () -> assertEquals(OptionalInt.of(WORLD), conn.getWorldId()),
                () -> assertTrue(conn.isMember()),
                () -> assertEquals(0, agent.callsTo(GET_LOGIN_STATE),
                        "a reply carrying game_state needs no second call"));
    }

    @Test
    void refresh_olderAgentWithoutTheNewKeys_readsTheStateFromGetLoginState() {
        agent.reply(GET_ACCOUNT_INFO, olderAccountInfo(NAME, UUID, true, false))
                .loginState(IN_GAME).world(WORLD);
        Connection conn = agent.connection(PIPE);

        tracker(Runnable::run).refresh(conn);

        assertAll(
                () -> assertEquals(1, agent.callsTo(GET_LOGIN_STATE)),
                () -> assertEquals(GameState.IN_GAME, conn.getGameState()),
                () -> assertEquals(OptionalInt.of(WORLD), conn.getWorldId()),
                () -> assertFalse(conn.isMember()));
    }

    @Test
    void refresh_getLoginStateFails_leavesTheStateUnknown() {
        agent.reply(GET_ACCOUNT_INFO, olderAccountInfo("", UUID, false, false));
        Connection conn = agent.connection(PIPE);

        tracker(Runnable::run).refresh(conn);

        assertEquals(GameState.UNKNOWN, conn.getGameState());
        assertEquals(UUID, conn.getAccountUuid(), "the account reply is stored even so");
    }

    @Test
    void refresh_developmentLaunchUuids_doNotBindTheRuntime() {
        for (String placeholder : List.of("", AccountReply.DEV_UUID)) {
            agent.reply(GET_ACCOUNT_INFO, accountInfo("", "", placeholder, LOBBY, false));
            Connection conn = agent.connection(PIPE);

            tracker(Runnable::run).refresh(conn);

            assertTrue(conn.getIdentifiedUuid().isEmpty(), placeholder);
            assertNull(conn.getRuntime().getAccountUuid(), placeholder);
            assertEquals(GameState.LOBBY, conn.getGameState(), placeholder);
        }
    }

    @Test
    void loginStateChange_refreshesTheWorld() {
        agent.reply(GET_ACCOUNT_INFO, accountInfo(NAME, NAME, UUID, IN_GAME, true)).world(WORLD);
        Connection conn = agent.connection(PIPE);
        ConnectionStatusTracker tracker = tracker(Runnable::run);
        tracker.attach(conn);
        assertEquals(OptionalInt.of(WORLD), conn.getWorldId());

        agent.reply(GET_ACCOUNT_INFO, accountInfo(NAME, NAME, UUID, LOBBY, false));
        conn.getEventBus().publish(new LoginStateChangeEvent(IN_GAME, LOBBY));
        assertEquals(GameState.LOBBY, conn.getGameState());
        assertTrue(conn.getWorldId().isEmpty(), "leaving the world drops it");

        agent.reply(GET_ACCOUNT_INFO, accountInfo(NAME, NAME, UUID, IN_GAME, true)).world(OTHER_WORLD);
        conn.getEventBus().publish(new LoginStateChangeEvent(LOBBY, IN_GAME));
        assertEquals(GameState.IN_GAME, conn.getGameState());
        assertEquals(OptionalInt.of(OTHER_WORLD), conn.getWorldId());
    }

    @Test
    void characterName_isTheInGameNameOnly_andWaitsForTheClientToResolveIt() {
        agent.reply(GET_ACCOUNT_INFO, accountInfo("", NAME, UUID, IN_GAME, false)).world(WORLD);
        Connection conn = agent.connection(PIPE);
        ConnectionStatusTracker tracker = tracker(Runnable::run);

        tracker.refresh(conn);
        assertTrue(conn.getCharacterName().isEmpty(), "the launcher's account name is not the character");

        agent.reply(GET_ACCOUNT_INFO, accountInfo(NAME, "", UUID, IN_GAME, false));
        tracker.refresh(conn);
        assertEquals(NAME, conn.getCharacterName().orElseThrow());
    }

    @Test
    void characterName_logoutDropsIt_andARelogNeverShowsThePreviousCharacter() {
        agent.reply(GET_ACCOUNT_INFO, accountInfo(NAME, "", UUID, IN_GAME, false)).world(WORLD);
        Connection conn = agent.connection(PIPE);
        tracker(queued).attach(conn);
        drain();
        assertEquals(NAME, conn.getCharacterName().orElseThrow());

        agent.reply(GET_ACCOUNT_INFO, accountInfo("", "", UUID, LOBBY, false));
        conn.getEventBus().publish(new LoginStateChangeEvent(IN_GAME, LOBBY));
        assertTrue(conn.getCharacterName().isEmpty(), "dropped before the follow-up read runs");
        drain();

        agent.reply(GET_ACCOUNT_INFO, accountInfo(OTHER_NAME, "", UUID, IN_GAME, false));
        conn.getEventBus().publish(new LoginStateChangeEvent(LOBBY, IN_GAME));
        assertTrue(conn.getCharacterName().isEmpty(), "back in a world, but not yet read");
        drain();
        assertEquals(OTHER_NAME, conn.getCharacterName().orElseThrow());
    }

    @Test
    void characterName_aReadingOvertakenByAStateChange_doesNotRestoreTheOldName() {
        agent.reply(GET_ACCOUNT_INFO, accountInfo(NAME, "", UUID, IN_GAME, false)).world(WORLD);
        Connection conn = agent.connection(PIPE);
        long staleTicket = conn.beginStatusRead();

        conn.publishGameStatus(conn.beginStatusRead(), previous -> previous.withState(GameState.LOBBY));
        conn.publishReading(staleTicket, new GameStatus(GameState.IN_GAME, OptionalInt.of(WORLD), false),
                Optional.of(NAME));

        assertEquals(GameState.LOBBY, conn.getGameState());
        assertTrue(conn.getCharacterName().isEmpty());
    }

    @Test
    void loginStateChange_appliesTheStateAtOnceAndLeavesTheAgentToTheExecutor() {
        agent.reply(GET_ACCOUNT_INFO, accountInfo(NAME, NAME, UUID, IN_GAME, true)).world(WORLD);
        Connection conn = agent.connection(PIPE);
        ConnectionStatusTracker tracker = tracker(queued);
        tracker.attach(conn);
        drain();
        agent.forgetCalls();

        conn.getEventBus().publish(new LoginStateChangeEvent(IN_GAME, LOBBY));
        conn.getEventBus().publish(new LoginStateChangeEvent(LOBBY, LOGIN_SCREEN));

        assertEquals(GameState.LOGIN_SCREEN, conn.getGameState());
        assertTrue(agent.calls().isEmpty(), "the event thread must not wait on the pipe");
        assertEquals(1, queue.size(), "a burst of events shares one queued refresh");
    }

    @Test
    void reconnected_refreshes_otherReconnectStatesDoNot() {
        agent.reply(GET_ACCOUNT_INFO, accountInfo(NAME, NAME, UUID, IN_GAME, true)).world(WORLD);
        Connection conn = agent.connection(PIPE);
        tracker(Runnable::run).attach(conn);
        agent.world(OTHER_WORLD).forgetCalls();

        conn.getEventBus().publish(new ReconnectStateChangedEvent(PIPE, new ReconnectState.Reconnecting(0, 1, 0)));
        assertTrue(agent.calls().isEmpty());

        conn.getEventBus().publish(new ReconnectStateChangedEvent(PIPE, new ReconnectState.Connected(0)));
        assertEquals(OptionalInt.of(OTHER_WORLD), conn.getWorldId());
    }

    @Test
    void pollOnce_rereadsUnsettledAndInWorldClients_notTheLobbyOrLoginScreen() {
        FakeAgent lobby = new FakeAgent().reply(GET_ACCOUNT_INFO, accountInfo("", "", UUID, LOBBY, false));
        FakeAgent inGame = new FakeAgent().reply(GET_ACCOUNT_INFO, accountInfo(NAME, "", UUID, IN_GAME, false))
                .world(WORLD);
        FakeAgent unknown = new FakeAgent().reply(GET_ACCOUNT_INFO, accountInfo("", "", UUID, 0, false));
        List<Connection> conns = List.of(lobby.connection("a"), inGame.connection("b"), unknown.connection("c"));
        ConnectionStatusTracker tracker = tracker(Runnable::run);
        conns.forEach(tracker::refresh);
        List.of(lobby, inGame, unknown).forEach(FakeAgent::forgetCalls);

        tracker.pollOnce(conns);

        assertEquals(0, lobby.callsTo(GET_ACCOUNT_INFO));
        assertEquals(1, inGame.callsTo(GET_ACCOUNT_INFO));
        assertEquals(1, unknown.callsTo(GET_ACCOUNT_INFO));
    }

    private void drain() {
        Runnable task;
        while ((task = queue.poll()) != null) {
            task.run();
        }
    }
}
