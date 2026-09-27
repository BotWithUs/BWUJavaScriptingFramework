package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.usermode.ClientFilter.View;
import com.botwithus.bot.cli.gui.usermode.board.ClientState;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ResumeSwitch;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.gui.usermode.board.ScriptRow;
import com.botwithus.bot.cli.gui.usermode.board.ScriptState;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientFilterTest {

    private static final ClientState CONNECTED = new ClientState.Connected(Duration.ZERO, OptionalDouble.empty());
    private static final String DIVINATION = "Divination";

    private static ScriptRow row(String name, ScriptState state) {
        return ScriptRow.idle(new ScriptInfo(name, "BotWithUs", "1.0", ScriptCategory.UTILITY, "", 0, false),
                state);
    }

    private static final ScriptRow RUNNING = row("Woodcutting", new ScriptState.Running(Duration.ZERO));
    private static final ScriptRow STOPPED = row("Location Probe", new ScriptState.Stopped());

    private static ClientView client(int n, String account, ClientState state, ScriptRow... rows) {
        String uuid = n + "a9b8c7d6e5f40312";
        return new ClientView(ClientKey.account(uuid), Optional.of("BotWithUs_" + n), Optional.of(account),
                OptionalInt.empty(), state, List.of(rows), new ResumeSwitch.Available(true));
    }

    private static final List<ClientView> CLIENTS = List.of(
            client(1, "Alpha", CONNECTED, STOPPED, RUNNING),
            client(2, "Bravo", CONNECTED),
            client(3, "Charlie", new ClientState.Closed(Duration.ZERO), row("Woodcutting", new ScriptState.Waiting())),
            client(4, "Delta", new ClientState.NotResponding(Duration.ZERO, 2, OptionalInt.empty(),
                    Optional.of(Duration.ofSeconds(4)))),
            client(5, "Echo", CONNECTED, RUNNING, row("Cook's Assistant",
                    new ScriptState.Crashed("NPE in onLoop()", LocalTime.NOON))),
            client(6, "Foxtrot", new ClientState.Identifying()),
            client(7, "Golf", CONNECTED, row(DIVINATION, new ScriptState.Stalled(Duration.ofSeconds(30)))),
            client(8, "Hotel", CONNECTED, row("Woodcutting", new ScriptState.CutOff())),
            client(9, "India", new ClientState.Resuming(), row(DIVINATION, new ScriptState.Waiting())));

    private static List<String> accounts(List<ClientView> views) {
        return views.stream().map(view -> view.account().orElseThrow()).toList();
    }

    @Test
    void needsAttention_isNotRespondingOrAScriptThatStalledCrashedOrWasCutOff() {
        assertEquals(List.of("Delta", "Echo", "Golf", "Hotel"),
                accounts(ClientFilter.apply(CLIENTS, View.NEEDS_ATTENTION, "")),
                "a closed client is remembered, not in trouble");
    }

    @Test
    void running_isAConnectedClientWithAScriptRunning_anywhereInItsRows() {
        assertEquals(List.of("Alpha", "Echo"), accounts(ClientFilter.apply(CLIENTS, View.RUNNING, "")),
                "Alpha's running script is its second row");
    }

    @Test
    void query_matchesAccountAnyScriptUuidOrPipe_caseInsensitively() {
        assertEquals(List.of("Alpha", "Charlie", "Echo", "Hotel"),
                accounts(ClientFilter.apply(CLIENTS, View.ALL, "WOODcut")));
        assertEquals(List.of("Bravo"), accounts(ClientFilter.apply(CLIENTS, View.ALL, "bravo")));
        assertEquals(List.of("Alpha"), accounts(ClientFilter.apply(CLIENTS, View.ALL, "location")),
                "a script that is not the first row still matches");
        assertEquals(List.of("Foxtrot"), accounts(ClientFilter.apply(CLIENTS, View.ALL, "botwithus_6")));
        assertEquals(List.of("Golf"), accounts(ClientFilter.apply(CLIENTS, View.ALL, "7a9b8c7d")));
    }

    @Test
    void query_isIgnoredWhileTheSearchBoxIsHidden() {
        List<ClientView> few = CLIENTS.subList(0, ClientFilter.SEARCH_THRESHOLD);

        assertEquals(accounts(few), accounts(ClientFilter.apply(few, View.ALL, "bravo")),
                "no box on screen, so a leftover query must not hide any card");
        assertEquals(List.of("Delta", "Echo", "Golf"),
                accounts(ClientFilter.apply(few, View.NEEDS_ATTENTION, "bravo")),
                "the segments still apply");
    }

    @Test
    void showsSearch_onlyAboveSevenClients() {
        assertFalse(ClientFilter.showsSearch(7));
        assertTrue(ClientFilter.showsSearch(8));
    }

    @Test
    void viewAndQueryCombine() {
        assertEquals(List.of("Echo"), accounts(ClientFilter.apply(CLIENTS, View.NEEDS_ATTENTION, "cook")));
    }

    @Test
    void aClientRunningTwoScripts_isOneCard() {
        List<ClientView> fleet = new ArrayList<>(CLIENTS);
        fleet.add(client(10, "Juliet", CONNECTED, RUNNING, RUNNING));

        assertEquals(List.of("Alpha", "Echo", "Juliet"), accounts(ClientFilter.apply(fleet, View.RUNNING, "")));
    }
}
