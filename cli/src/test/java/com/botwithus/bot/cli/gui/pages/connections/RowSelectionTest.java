package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.events.ClientKey;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which row the detail pane follows as the table changes under it. */
class RowSelectionTest {

    private static final String PIPE = "BotWithUs_19544";
    private static final String UUID = "3f9a1c2e58b04d7a9e216c0f4b7d2a18";

    @Test
    void aFoundPipeThatConnectsStaysSelectedUnderItsNewId() {
        RowSelection selection = new RowSelection();
        selection.select(found());

        Optional<ConnectionRow> resolved = selection.resolve(view(connected()));

        assertEquals(Optional.of(UUID), resolved.map(ConnectionRow::id));
        assertTrue(selection.isSelected(connected()));
        assertTrue(selection.takeReveal(), "the row moved groups, so the table scrolls to it");
        assertFalse(selection.takeReveal(), "once");
    }

    @Test
    void aRowPickedByClickingIsNotScrolledTo() {
        RowSelection selection = new RowSelection();
        selection.select(connected());

        selection.resolve(view(connected()));

        assertFalse(selection.takeReveal());
    }

    @Test
    void aRowThatLeavesTheTableIsDeselected() {
        RowSelection selection = new RowSelection();
        selection.select(connected());

        assertEquals(Optional.empty(), selection.resolve(view()));
        assertEquals(Optional.empty(), selection.resolve(view(connected())), "it does not come back on its own");
    }

    private static ConnectionRow found() {
        return new ConnectionRow(PIPE, RowGroup.FOUND, new LinkState.Found(), Optional.empty(), Optional.of(PIPE),
                Optional.empty(), Optional.empty(), OptionalInt.empty(), GameStatus.UNKNOWN, Optional.empty(),
                false, false, Set.of(RowAction.CONNECT));
    }

    private static ConnectionRow connected() {
        return new ConnectionRow(UUID, RowGroup.CONNECTED, new LinkState.Identifying(),
                Optional.of(ClientKey.account(UUID)), Optional.of(PIPE), Optional.empty(), Optional.of(UUID),
                OptionalInt.empty(), GameStatus.UNKNOWN, Optional.empty(), false, false, Set.of());
    }

    private static ConnectionsView view(ConnectionRow... rows) {
        return new ConnectionsView(List.of(rows), true, "BotWithUs", Duration.ofSeconds(5), false, Optional.empty());
    }
}
