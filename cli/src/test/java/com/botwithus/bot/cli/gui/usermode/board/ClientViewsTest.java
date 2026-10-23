package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientKey;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a card reads each state of the client lifecycle: which client state it
 * shows with which figures, which script rows it lists, and what its
 * "Resume after restart" switch offers.
 */
class ClientViewsTest {

    private static final String UUID = "3f9a1c2e58b04d7a9e216c0f4b7d2a18";
    private static final ClientKey ACCOUNT = ClientKey.account(UUID);
    private static final String PIPE = "BotWithUs_14208";
    private static final ClientKey PIPE_KEY = ClientKey.pipe(PIPE);
    private static final Instant NOW = Instant.parse("2026-09-26T15:00:00Z");
    private static final Duration ONLINE = Duration.ofHours(3).plusMinutes(12);
    private static final int WORLD = 84;
    private static final int LAST_WORLD = 44;
    private static final double RPC_MS = 2.8;
    private static final int ATTEMPT = 4;
    private static final int CAP = 10;
    private static final Duration NEXT = Duration.ofSeconds(8);
    private static final Duration SILENT = Duration.ofSeconds(42);
    private static final Duration CLOSED_FOR = Duration.ofMinutes(3);

    private static final ScriptRow WOODCUTTING = ScriptRow.idle(info("Woodcutting"),
            new ScriptState.Running(Duration.ofMinutes(41)));
    private static final ScriptRow PROBE = ScriptRow.idle(info("Location Probe"), new ScriptState.Stopped());
    private static final ScriptRow DIVINATION_SAVED = ScriptRow.idle(info("Divination"), new ScriptState.Waiting());
    private static final ScriptRow WOODCUTTING_SAVED =
            ScriptRow.idle(info("Woodcutting"), new ScriptState.Waiting());

    private static ScriptInfo info(String name) {
        return new ScriptInfo(name, "BotWithUs", "1.0", ScriptCategory.UTILITY, "", 0, false);
    }

    private static final GameStatus IN_WORLD = new GameStatus(GameState.IN_GAME, OptionalInt.of(WORLD), true);
    private static final GameStatus LOBBY = new GameStatus(GameState.LOBBY, OptionalInt.empty(), false);

    private static ClientRecord open(ClientKey key, ClientLifecycle lifecycle, GameStatus status) {
        return new ClientRecord(key, lifecycle, Optional.of(PIPE), Optional.of("Oakheart"), status,
                OptionalInt.of(LAST_WORLD), Optional.of(NOW.minus(ONLINE)));
    }

    private static ClientRecord closed(Duration ago) {
        return new ClientRecord(ACCOUNT, new ClientLifecycle.Closed(NOW.minus(ago)), Optional.empty(),
                Optional.of("Brackenridge"), GameStatus.UNKNOWN, OptionalInt.of(LAST_WORLD), Optional.empty());
    }

    private static ClientViews.Facts facts(List<ScriptRow> runners, List<ScriptRow> remembered, boolean autoStart) {
        return new ClientViews.Facts(runners, remembered, Optional.of(autoStart), OptionalDouble.of(RPC_MS),
                Optional.empty());
    }

    private static List<String> names(ClientView view) {
        return view.scripts().stream().map(ScriptRow::name).toList();
    }

    private static List<ScriptState> states(ClientView view) {
        return view.scripts().stream().map(ScriptRow::state).toList();
    }

    @Test
    void identifying_showsNoRows_andWaitsForTheAccountBeforeOfferingResume() {
        ClientRecord record = open(PIPE_KEY, new ClientLifecycle.Identifying(), GameStatus.UNKNOWN);

        ClientView view = ClientViews.of(record, facts(List.of(WOODCUTTING), List.of(), true), NOW);

        assertAll(
                () -> assertEquals(new ClientState.Identifying(), view.state()),
                () -> assertEquals(List.of(), view.scripts()),
                () -> assertEquals(new ResumeSwitch.Unavailable(ClientViews.AWAITING_ACCOUNT), view.resume()));
    }

    @Test
    void connected_showsTimeOnlineAndRpc_itsRunners_andTheWorldItIsIn() {
        ClientRecord record = open(ACCOUNT, new ClientLifecycle.Connected(), IN_WORLD);

        ClientView view = ClientViews.of(record,
                facts(List.of(WOODCUTTING, PROBE), List.of(DIVINATION_SAVED), true), NOW);

        assertAll(
                () -> assertEquals(new ClientState.Connected(ONLINE, OptionalDouble.of(RPC_MS)), view.state()),
                () -> assertEquals(List.of("Woodcutting", "Location Probe"), names(view),
                        "a connected card lists what is on the client, not what the profile remembers"),
                () -> assertEquals(OptionalInt.of(WORLD), view.world()),
                () -> assertEquals(Optional.of(PIPE), view.pipe()),
                () -> assertEquals(new ResumeSwitch.Available(true), view.resume()));
    }

    @Test
    void connectedInTheLobby_showsNoWorld() {
        ClientView view = ClientViews.of(open(ACCOUNT, new ClientLifecycle.Connected(), LOBBY),
                facts(List.of(), List.of(), false), NOW);

        assertEquals(OptionalInt.empty(), view.world());
    }

    @Test
    void notResponding_showsTheAttemptAndTheWait_andItsRunnersAsWaiting() {
        Instant reported = NOW.minusSeconds(2);
        ClientLifecycle retrying = new ClientLifecycle.NotResponding(ATTEMPT,
                Optional.of(NEXT.plusSeconds(2)), OptionalInt.empty(), reported);
        ClientViews.Facts facts = new ClientViews.Facts(List.of(WOODCUTTING), List.of(DIVINATION_SAVED),
                Optional.of(true), OptionalDouble.empty(), Optional.of(NOW.minus(SILENT)));

        ClientView view = ClientViews.of(open(ACCOUNT, retrying, IN_WORLD), facts, NOW);

        assertAll(
                () -> assertEquals(new ClientState.NotResponding(SILENT, ATTEMPT, OptionalInt.empty(),
                        Optional.of(NEXT)), view.state()),
                () -> assertEquals(List.of("Woodcutting"), names(view)),
                () -> assertEquals(List.of(new ScriptState.Waiting()), states(view)),
                () -> assertEquals(OptionalInt.of(LAST_WORLD), view.world(),
                        "a client that is not answering shows where it was last seen"));
    }

    @Test
    void notResponding_withACap_andNotRetryingAnyMore() {
        ClientLifecycle stopped = new ClientLifecycle.NotResponding(ATTEMPT, Optional.empty(),
                OptionalInt.of(CAP), NOW.minus(SILENT));

        ClientView view = ClientViews.of(open(ACCOUNT, stopped, IN_WORLD),
                facts(List.of(), List.of(WOODCUTTING_SAVED), true), NOW);

        assertAll(
                () -> assertEquals(new ClientState.NotResponding(SILENT, ATTEMPT, OptionalInt.of(CAP),
                        Optional.empty()), view.state(), "with no first-seen time, the report's time is used"),
                () -> assertEquals(List.of("Woodcutting"), names(view),
                        "with no runners left, the card lists what the account will resume"));
    }

    @Test
    void aRetryThatIsAlreadyDue_isShownAsNow_notNegative() {
        ClientLifecycle overdue = new ClientLifecycle.NotResponding(ATTEMPT, Optional.of(Duration.ofSeconds(1)),
                OptionalInt.empty(), NOW.minusSeconds(5));

        ClientState state = ClientViews.of(open(ACCOUNT, overdue, IN_WORLD),
                facts(List.of(), List.of(), true), NOW).state();

        assertEquals(new ClientState.NotResponding(Duration.ofSeconds(5), ATTEMPT, OptionalInt.empty(),
                Optional.of(Duration.ZERO)), state);
    }

    @Test
    void closed_isKept_withWhatItWillResume_andTheWorldItWasLastIn() {
        ClientView view = ClientViews.of(closed(CLOSED_FOR),
                facts(List.of(), List.of(DIVINATION_SAVED), true), NOW);

        assertAll(
                () -> assertEquals(new ClientState.Closed(CLOSED_FOR), view.state()),
                () -> assertEquals(List.of("Divination"), names(view)),
                () -> assertEquals(List.of(new ScriptState.Waiting()), states(view)),
                () -> assertEquals(OptionalInt.of(LAST_WORLD), view.world()),
                () -> assertEquals(Optional.empty(), view.pipe()),
                () -> assertEquals(new ResumeSwitch.Available(true), view.resume()));
    }

    @Test
    void closed_withResumeOff_stillListsTheSavedScripts_andSaysTheSwitchIsOff() {
        ClientView view = ClientViews.of(closed(CLOSED_FOR),
                facts(List.of(), List.of(WOODCUTTING_SAVED), false), NOW);

        assertAll(
                () -> assertEquals(List.of("Woodcutting"), names(view)),
                () -> assertEquals(new ResumeSwitch.Available(false), view.resume()));
    }

    @Test
    void closedWithADeadPipe_andNoProfile_listsWhatItWasRunning() {
        ClientRecord deadGame = new ClientRecord(ACCOUNT, new ClientLifecycle.Closed(NOW.minus(CLOSED_FOR)),
                Optional.of(PIPE), Optional.of("Oakheart"), GameStatus.UNKNOWN, OptionalInt.of(LAST_WORLD),
                Optional.empty());

        ClientView view = ClientViews.of(deadGame, facts(List.of(WOODCUTTING), List.of(), true), NOW);

        assertAll(
                () -> assertEquals(List.of("Woodcutting"), names(view)),
                () -> assertEquals(List.of(new ScriptState.Waiting()), states(view)),
                () -> assertEquals(Optional.of(PIPE), view.pipe(), "kept so the card can still be dismissed"));
    }

    @Test
    void resuming_listsItsRunners_thenTheSavedScriptsStillToStart() {
        ClientLifecycle resuming = new ClientLifecycle.Resuming(Optional.of("BotWithUs_9932"), NOW);

        ClientView view = ClientViews.of(open(ACCOUNT, resuming, IN_WORLD),
                facts(List.of(WOODCUTTING), List.of(WOODCUTTING_SAVED, DIVINATION_SAVED), true), NOW);

        assertAll(
                () -> assertEquals(new ClientState.Resuming(), view.state()),
                () -> assertEquals(List.of("Woodcutting", "Divination"), names(view)),
                () -> assertEquals(List.of(WOODCUTTING.state(), new ScriptState.Waiting()), states(view)));
    }

    @Test
    void resumingWithResumeOff_listsOnlyItsRunners() {
        ClientLifecycle resuming = new ClientLifecycle.Resuming(Optional.empty(), NOW);

        ClientView view = ClientViews.of(open(ACCOUNT, resuming, IN_WORLD),
                facts(List.of(), List.of(DIVINATION_SAVED), false), NOW);

        assertEquals(List.of(), view.scripts());
    }

    @Test
    void aClientWithNoAccountUuid_cannotResume_andSaysWhy() {
        ClientView view = ClientViews.of(open(PIPE_KEY, new ClientLifecycle.Connected(), IN_WORLD),
                facts(List.of(), List.of(), true), NOW);

        assertEquals(new ResumeSwitch.Unavailable(ClientViews.NO_ACCOUNT), view.resume());
    }

    @Test
    void anAccountWhenTheHostKeepsNoProfiles_cannotResume() {
        ClientViews.Facts noProfiles = new ClientViews.Facts(List.of(), List.of(), Optional.empty(),
                OptionalDouble.empty(), Optional.empty());

        ClientView view = ClientViews.of(open(ACCOUNT, new ClientLifecycle.Connected(), IN_WORLD), noProfiles, NOW);

        assertEquals(new ResumeSwitch.Unavailable(ClientViews.NO_PROFILES), view.resume());
    }

    @Test
    void theCardCarriesTheRecordsKeyAndName() {
        ClientView view = ClientViews.of(open(ACCOUNT, new ClientLifecycle.Connected(), IN_WORLD),
                facts(List.of(), List.of(), true), NOW);

        assertAll(
                () -> assertEquals(ACCOUNT, view.id()),
                () -> assertEquals(Optional.of("Oakheart"), view.account()));
    }
}
