package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.usermode.board.ClientView;

import java.util.List;

/**
 * The grid's view filter: which segment is selected and what is typed in the
 * search box. The filter row only appears once there are more clients than fit
 * one screen, but the rule is the same either way.
 */
final class ClientFilter {

    /** The three segments, in display order. */
    enum View { ALL, RUNNING, NEEDS_ATTENTION }

    /** More clients than this and the filter row shows the search box. */
    static final int SEARCH_THRESHOLD = 6;

    private ClientFilter() {}

    static List<ClientView> apply(List<ClientView> clients, View view, String query) {
        return clients.stream()
                .filter(c -> switch (view) {
                    case ALL -> true;
                    case RUNNING -> c.status().isRunning();
                    case NEEDS_ATTENTION -> c.status().needsAttention();
                })
                .filter(c -> c.matches(query))
                .toList();
    }
}
