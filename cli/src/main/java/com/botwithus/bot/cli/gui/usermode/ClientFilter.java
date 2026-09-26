package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.usermode.board.ClientView;

import java.util.List;

/**
 * The grid's view filter: which segment is selected and what is typed in the
 * search box. The segments show whenever there is at least one client; the
 * search box only above {@link #SEARCH_THRESHOLD} clients, and the typed query
 * applies only while the box is shown.
 */
final class ClientFilter {

    /** The three segments, in display order. */
    enum View { ALL, RUNNING, NEEDS_ATTENTION }

    /** More clients than this and the filter row shows the search box. */
    static final int SEARCH_THRESHOLD = 6;

    private ClientFilter() {}

    /** Whether the search box is shown for {@code clientCount} clients. */
    static boolean showsSearch(int clientCount) {
        return clientCount > SEARCH_THRESHOLD;
    }

    /**
     * The clients the grid shows. {@code query} is ignored when the search box
     * is not shown, so text typed into a box that has since disappeared cannot
     * keep hiding cards.
     */
    static List<ClientView> apply(List<ClientView> clients, View view, String query) {
        String effective = showsSearch(clients.size()) ? query : "";
        return clients.stream()
                .filter(c -> switch (view) {
                    case ALL -> true;
                    case RUNNING -> c.status().isRunning();
                    case NEEDS_ATTENTION -> c.status().needsAttention();
                })
                .filter(c -> c.matches(effective))
                .toList();
    }
}
