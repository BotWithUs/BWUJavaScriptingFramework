package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.usermode.CardText.Chip;
import com.botwithus.bot.cli.gui.usermode.CardText.Meta;
import com.botwithus.bot.cli.gui.usermode.CardText.Note;
import com.botwithus.bot.cli.gui.usermode.CardText.Stat;
import com.botwithus.bot.cli.gui.usermode.board.ClientState;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ResumeSwitch;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.gui.usermode.board.ScriptRow;
import com.botwithus.bot.cli.gui.usermode.board.ScriptState;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The words and figures a client card shows, for every client and script state. */
class CardTextTest {

    private static final String UUID = "3f9a1c2e-58b0-4d7a-9e21-6c0f4b7d2a18";
    private static final ClientKey ACCOUNT = ClientKey.account(UUID);
    private static final String PIPE = "BotWithUs_7716";
    private static final int WORLD = 84;
    private static final ResumeSwitch ON = new ResumeSwitch.Available(true);
    private static final ResumeSwitch OFF = new ResumeSwitch.Available(false);
    private static final ClientState.Connected CONNECTED =
            new ClientState.Connected(Duration.ofMinutes(134), OptionalDouble.of(2.84));

    private static ScriptRow row(String name, ScriptState state) {
        return ScriptRow.idle(new ScriptInfo(name, "BotWithUs", "1.0", ScriptCategory.UTILITY, "", 0, false),
                state);
    }

    private static final ScriptRow RUNNING = new ScriptRow(
            new ScriptInfo("Woodcutting", "BotWithUs", "2.0", ScriptCategory.WOODCUTTING, "", 6, true),
            new ScriptState.Running(Duration.ofMinutes(41).plusSeconds(7)), new long[0], 142.4, Optional.empty());
    private static final ScriptRow STOPPED = row("Location Probe", new ScriptState.Stopped());
    private static final ScriptRow STALLED = row("Divination", new ScriptState.Stalled(Duration.ofSeconds(38)));
    private static final ScriptRow CRASHED = row("Cook's Assistant",
            new ScriptState.Crashed("NullPointerException in onLoop()", LocalTime.of(14, 2, 31)));
    private static final ScriptRow CUT_OFF = row("Woodcutting", new ScriptState.CutOff());
    private static final ScriptRow WAITING = row("Divination", new ScriptState.Waiting());

    private static ClientView view(ClientKey key, ClientState state, List<ScriptRow> rows) {
        return new ClientView(key, Optional.of(PIPE), Optional.of("Oakheart"), OptionalInt.of(WORLD), state, rows,
                ON);
    }

    private static ClientView connected(ScriptRow... rows) {
        return view(ACCOUNT, CONNECTED, List.of(rows));
    }

    // ── Header ─────────────────────────────────────────────────────────────

    @Test
    void subLine_isTheShortUuidAndWorld_orThePipeWithNoAccount() {
        ClientView noWorld = new ClientView(ACCOUNT, Optional.empty(), Optional.empty(), OptionalInt.empty(),
                new ClientState.Closed(Duration.ZERO), List.of(), ON);
        ClientView pipeKey = view(ClientKey.pipe(PIPE), new ClientState.Identifying(), List.of());

        assertAll(
                () -> assertEquals("3f9a1c2e · World 84", CardText.subLine(connected())),
                () -> assertEquals("3f9a1c2e", CardText.subLine(noWorld)),
                () -> assertEquals("pipe " + PIPE, CardText.subLine(pipeKey)));
    }

    @Test
    void title_isTheAccount_orNewClient() {
        ClientView unnamed = new ClientView(ClientKey.pipe(PIPE), Optional.of(PIPE), Optional.empty(),
                OptionalInt.empty(), new ClientState.Identifying(), List.of(), ON);

        assertAll(
                () -> assertEquals("Oakheart", CardText.title(connected())),
                () -> assertEquals(CardText.NEW_CLIENT, CardText.title(unnamed)));
    }

    @Test
    void stats_areOnlineRpcAndHowManyOfTheScriptsRun() {
        ClientView view = connected(RUNNING, STOPPED);

        assertEquals(List.of(
                        new Stat("Online", "2h 14m", ""),
                        new Stat("RPC", "2.8", "ms"),
                        new Stat("Scripts", "1", "of 2 running")),
                CardText.stats(view, CONNECTED));
    }

    @Test
    void stats_withNoRpcYet_showADash() {
        ClientState.Connected fresh = new ClientState.Connected(Duration.ofMinutes(3), OptionalDouble.empty());
        ClientView view = view(ACCOUNT, fresh, List.of());

        assertEquals(List.of(
                        new Stat("Online", "0h 03m", ""),
                        new Stat("RPC", "—", ""),
                        new Stat("Scripts", "0", "of 0 running")),
                CardText.stats(view, fresh));
    }

    @Test
    void online_isHoursAndTwoDigitMinutes() {
        assertAll(
                () -> assertEquals("3h 12m", CardText.online(Duration.ofMinutes(192))),
                () -> assertEquals("0h 03m", CardText.online(Duration.ofMinutes(3).plusSeconds(59))),
                () -> assertEquals("26h 00m", CardText.online(Duration.ofHours(26))));
    }

    @Test
    void runtime_isMinutesAndSeconds_withHoursOnceItHasThem() {
        assertAll(
                () -> assertEquals("41:07", CardText.runtime(Duration.ofSeconds(41 * 60 + 7))),
                () -> assertEquals("0:48", CardText.runtime(Duration.ofSeconds(48))),
                () -> assertEquals("1:12:30", CardText.runtime(Duration.ofSeconds(3600 + 12 * 60 + 30))));
    }

    @Test
    void ago_readsLikeASentence() {
        assertAll(
                () -> assertEquals("just now", CardText.ago(Duration.ofSeconds(59))),
                () -> assertEquals("3 min ago", CardText.ago(Duration.ofMinutes(3))),
                () -> assertEquals("2 h ago", CardText.ago(Duration.ofMinutes(150))),
                () -> assertEquals("1 day ago", CardText.ago(Duration.ofHours(30))),
                () -> assertEquals("3 days ago", CardText.ago(Duration.ofDays(3))));
    }

    // ── Chip ───────────────────────────────────────────────────────────────

    @Test
    void chip_namesTheClientState_first() {
        assertAll(
                () -> assertEquals(new Chip("Identifying", CardTone.INFO),
                        CardText.chip(view(ACCOUNT, new ClientState.Identifying(), List.of(CRASHED)))),
                () -> assertEquals(new Chip("Not responding", CardTone.WARN), CardText.chip(view(ACCOUNT,
                        new ClientState.NotResponding(Duration.ZERO, 1, OptionalInt.empty(), Optional.empty()),
                        List.of(CRASHED)))),
                () -> assertEquals(new Chip("Client closed", CardTone.IDLE),
                        CardText.chip(view(ACCOUNT, new ClientState.Closed(Duration.ZERO), List.of(WAITING)))),
                () -> assertEquals(new Chip("Resuming", CardTone.INFO),
                        CardText.chip(view(ACCOUNT, new ClientState.Resuming(), List.of(RUNNING)))));
    }

    @Test
    void chip_forAConnectedClient_isItsWorstScript() {
        assertAll(
                () -> assertEquals(new Chip("Script cut off", CardTone.ERR),
                        CardText.chip(connected(RUNNING, CRASHED, CUT_OFF))),
                () -> assertEquals(new Chip("Script crashed", CardTone.ERR),
                        CardText.chip(connected(STALLED, CRASHED))),
                () -> assertEquals(new Chip("Stalled", CardTone.WARN), CardText.chip(connected(RUNNING, STALLED))),
                () -> assertEquals(new Chip("Running", CardTone.RUN), CardText.chip(connected(STOPPED, RUNNING))),
                () -> assertEquals(new Chip("Idle", CardTone.IDLE), CardText.chip(connected(STOPPED))),
                () -> assertEquals(new Chip("Idle", CardTone.IDLE), CardText.chip(connected())));
    }

    // ── Notes ──────────────────────────────────────────────────────────────

    @Test
    void note_forAConnectedClient_isNone() {
        assertEquals(Optional.empty(), CardText.note(connected(RUNNING)));
    }

    @Test
    void note_forEachClientThatIsNotConnected() {
        ClientState retrying = new ClientState.NotResponding(Duration.ofSeconds(42), 4, OptionalInt.empty(),
                Optional.of(Duration.ofMillis(7_600)));
        ClientState capped = new ClientState.NotResponding(Duration.ofSeconds(42), 4, OptionalInt.of(10),
                Optional.of(Duration.ofMillis(400)));
        ClientState stopped = new ClientState.NotResponding(Duration.ofSeconds(75), 4, OptionalInt.empty(),
                Optional.empty());

        assertAll(
                () -> assertEquals(Optional.of(new Note(CardTone.INFO, "Reading the account…",
                                "A new game client opened this pipe. Once we know the account, its card and "
                                        + "scripts appear here.")),
                        CardText.note(view(ClientKey.pipe(PIPE), new ClientState.Identifying(), List.of()))),
                () -> assertEquals(Optional.of(new Note(CardTone.WARN, "No reply for 0:42",
                                "Retrying: attempt 4, next in 8 s. The game may be frozen or restarting.")),
                        CardText.note(view(ACCOUNT, retrying, List.of()))),
                () -> assertEquals(Optional.of(new Note(CardTone.WARN, "No reply for 0:42",
                                "Retrying: attempt 4 of 10, next in 1 s. The game may be frozen or restarting.")),
                        CardText.note(view(ACCOUNT, capped, List.of()))),
                () -> assertEquals(Optional.of(new Note(CardTone.WARN, "No reply for 1:15",
                                "Stopped retrying after attempt 4. Retry when the game is back.")),
                        CardText.note(view(ACCOUNT, stopped, List.of()))),
                () -> assertEquals(Optional.of(new Note(CardTone.INFO, "Same account is back",
                                "Matched by UUID on a new pipe. Restarting the scripts it was running.")),
                        CardText.note(view(ACCOUNT, new ClientState.Resuming(), List.of()))));
    }

    @Test
    void note_forAClosedClient_saysWhetherItsScriptsComeBack() {
        ClientState closed = new ClientState.Closed(Duration.ofMinutes(3));
        ClientView on = new ClientView(ACCOUNT, Optional.empty(), Optional.of("Brackenridge"), OptionalInt.empty(),
                closed, List.of(WAITING), ON);
        ClientView off = new ClientView(ACCOUNT, Optional.empty(), Optional.of("Mirelock"), OptionalInt.empty(),
                closed, List.of(WAITING), OFF);

        assertAll(
                () -> assertEquals(Optional.of(new Note(CardTone.IDLE, "Game client closed 3 min ago",
                        "When this account logs in again, its scripts restart on their own.")), CardText.note(on)),
                () -> assertEquals(Optional.of(new Note(CardTone.IDLE, "Game client closed 3 min ago",
                                "Resume after restart is off, so nothing will start when it comes back.")),
                        CardText.note(off)));
    }

    // ── Script rows ────────────────────────────────────────────────────────

    @Test
    void meta_forEachScriptStateOnAConnectedClient() {
        assertAll(
                () -> assertEquals(new Meta("41:07 · 142 ms/loop", CardTone.IDLE, false),
                        CardText.meta(RUNNING, CONNECTED, ON)),
                () -> assertEquals(new Meta("Stopped", CardTone.IDLE, false), CardText.meta(STOPPED, CONNECTED, ON)),
                () -> assertEquals(new Meta("Not responding · 38 s in onLoop()", CardTone.WARN, true),
                        CardText.meta(STALLED, CONNECTED, ON)),
                () -> assertEquals(new Meta("NullPointerException in onLoop() · 14:02", CardTone.ERR, true),
                        CardText.meta(CRASHED, CONNECTED, ON)),
                () -> assertEquals(new Meta("Ignored Stop, so it was cut off from the game. It stays "
                                + "quarantined until you restart BotWithUs.", CardTone.ERR, true),
                        CardText.meta(CUT_OFF, CONNECTED, ON)));
    }

    @Test
    void meta_forARunningScriptBeforeItsFirstLoop_hasNoAverage() {
        ScriptRow fresh = row("Woodcutting", new ScriptState.Running(Duration.ofSeconds(1)));

        assertEquals(new Meta("0:01", CardTone.IDLE, false), CardText.meta(fresh, CONNECTED, ON));
    }

    @Test
    void meta_forAWaitingScript_followsItsClient() {
        ClientState closed = new ClientState.Closed(Duration.ofMinutes(3));
        ClientState retrying = new ClientState.NotResponding(Duration.ZERO, 1, OptionalInt.empty(),
                Optional.of(Duration.ofSeconds(1)));

        assertAll(
                () -> assertEquals(new Meta("Starting…", CardTone.INFO, false),
                        CardText.meta(WAITING, new ClientState.Resuming(), ON)),
                () -> assertEquals(new Meta("Resumes when this account is back", CardTone.IDLE, false),
                        CardText.meta(WAITING, closed, ON)),
                () -> assertEquals(new Meta("Won’t resume on its own", CardTone.IDLE, false),
                        CardText.meta(WAITING, closed, OFF)),
                () -> assertEquals(new Meta("Waiting for the client", CardTone.IDLE, false),
                        CardText.meta(WAITING, retrying, ON)));
    }
}
