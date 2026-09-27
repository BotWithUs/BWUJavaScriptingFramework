package com.botwithus.bot.cli.gui.pages.connections;

import java.util.Optional;

/**
 * The row the detail pane shows. A found pipe that the host then connects to
 * becomes a client's row with a new id, in another group; the selection follows
 * it by pipe and scrolls to it, so clicking Connect does not close the pane.
 * Render thread only.
 */
final class RowSelection {

    private Optional<String> id = Optional.empty();
    private Optional<String> pipe = Optional.empty();
    private boolean isRevealPending;

    void select(ConnectionRow row) {
        id = Optional.of(row.id());
        pipe = row.pipe();
    }

    /** Selects {@code row} and asks the table to scroll it into view once. */
    void selectAndReveal(ConnectionRow row) {
        select(row);
        isRevealPending = true;
    }

    void clear() {
        id = Optional.empty();
        pipe = Optional.empty();
        isRevealPending = false;
    }

    /** Whether the table should scroll to the selected row now; true once per request. */
    boolean takeReveal() {
        boolean isPending = isRevealPending;
        isRevealPending = false;
        return isPending;
    }

    boolean isSelected(ConnectionRow row) {
        return id.map(row.id()::equals).orElse(false);
    }

    /** The selected row in {@code view}; clears the selection when its row is gone. */
    Optional<ConnectionRow> resolve(ConnectionsView view) {
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Optional<ConnectionRow> byId = view.find(id.get());
        if (byId.isPresent()) {
            return byId;
        }
        Optional<ConnectionRow> moved = samePipe(view);
        moved.ifPresentOrElse(this::selectAndReveal, this::clear);
        return moved;
    }

    private Optional<ConnectionRow> samePipe(ConnectionsView view) {
        return pipe.flatMap(p -> view.rows().stream().filter(row -> row.pipe().equals(Optional.of(p))).findFirst());
    }
}
