package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.gui.usermode.ClientFilter.View;
import com.botwithus.bot.cli.gui.usermode.board.ClientStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientFilterTest {

    private static final ScriptInfo WOODCUTTING =
            new ScriptInfo("Woodcutting", "BotWithUs", "2.0", ScriptCategory.WOODCUTTING, "", 0, false);

    private static final List<ClientView> CLIENTS = List.of(
            new ClientView("BotWithUs_1", "Alpha", 1, new ClientStatus.Running(WOODCUTTING, 100, new long[0])),
            new ClientView("BotWithUs_2", "Bravo", 2, new ClientStatus.Idle()),
            new ClientView("BotWithUs_3", "Charlie", 3, new ClientStatus.Lost(1000, null)),
            new ClientView("BotWithUs_4", "Delta", 4, new ClientStatus.Reconnecting(2, 5, 4000)),
            new ClientView("BotWithUs_5", "Echo", 5, new ClientStatus.Crashed(WOODCUTTING, "NPE in onLoop()")),
            new ClientView("BotWithUs_6", "Foxtrot", 6, new ClientStatus.Loading()),
            new ClientView("BotWithUs_7", "Golf", 7, new ClientStatus.Idle()));

    private static List<String> accounts(List<ClientView> views) {
        return views.stream().map(ClientView::account).toList();
    }

    @Test
    void needsAttention_isLostReconnectingAndCrashed() {
        assertEquals(List.of("Charlie", "Delta", "Echo"),
                accounts(ClientFilter.apply(CLIENTS, View.NEEDS_ATTENTION, "")));
    }

    @Test
    void running_isOnlyRunning() {
        assertEquals(List.of("Alpha"), accounts(ClientFilter.apply(CLIENTS, View.RUNNING, "")));
    }

    @Test
    void query_matchesAccountScriptOrPipeCaseInsensitively() {
        assertEquals(List.of("Alpha", "Echo"), accounts(ClientFilter.apply(CLIENTS, View.ALL, "WOODcut")));
        assertEquals(List.of("Bravo"), accounts(ClientFilter.apply(CLIENTS, View.ALL, "bravo")));
        assertEquals(List.of("Foxtrot"), accounts(ClientFilter.apply(CLIENTS, View.ALL, "botwithus_6")));
    }

    @Test
    void query_isIgnoredWhileTheSearchBoxIsHidden() {
        List<ClientView> few = CLIENTS.subList(0, ClientFilter.SEARCH_THRESHOLD);

        assertEquals(accounts(few), accounts(ClientFilter.apply(few, View.ALL, "bravo")),
                "no box on screen, so a leftover query must not hide any card");
        assertEquals(List.of("Charlie", "Delta", "Echo"),
                accounts(ClientFilter.apply(few, View.NEEDS_ATTENTION, "bravo")),
                "the segments still apply");
    }

    @Test
    void showsSearch_onlyAboveTheThreshold() {
        assertFalse(ClientFilter.showsSearch(ClientFilter.SEARCH_THRESHOLD));
        assertTrue(ClientFilter.showsSearch(ClientFilter.SEARCH_THRESHOLD + 1));
    }

    @Test
    void viewAndQueryCombine() {
        assertEquals(List.of("Echo"), accounts(ClientFilter.apply(CLIENTS, View.NEEDS_ATTENTION, "wood")));
    }
}
