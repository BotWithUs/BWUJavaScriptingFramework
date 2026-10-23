package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.gui.pages.connections.TableColumns.Column;
import com.botwithus.bot.cli.gui.pages.connections.TableColumns.Layout;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the table's columns share a width, and which give way first when it is short. */
class TableColumnsTest {

    private static final float EM = 15f;
    private static final float WIDE = 1200f;
    private static final float TOLERANCE = 0.01f;

    @Test
    void aWideTableShowsEveryColumnInOrderWithoutOverlap() {
        Layout layout = TableColumns.fit(WIDE, EM);

        float end = 0f;
        for (Column column : Column.values()) {
            assertTrue(layout.shows(column), column.name());
            assertTrue(layout.cell(column).x() >= end, column + " starts after the one before");
            end = layout.cell(column).x() + layout.cell(column).width();
        }
        assertTrue(end <= WIDE, "fits in the table");
    }

    @Test
    void theAccountAndPipeColumnsShareTheSpareWidth() {
        Layout layout = TableColumns.fit(WIDE, EM);

        float account = layout.cell(Column.ACCOUNT).width();
        float pipe = layout.cell(Column.PIPE).width();
        assertEquals(1.2f, account / pipe, TOLERANCE);
    }

    @Test
    void asTheTableNarrowsRpcGoesFirst_thenUptime_thenWorld_thenGame() {
        assertAll(
                () -> assertEquals(hidden(), hiddenAt(900f)),
                () -> assertEquals(hidden(Column.RPC), hiddenAt(800f)),
                () -> assertEquals(hidden(Column.RPC, Column.UP), hiddenAt(740f)),
                () -> assertEquals(hidden(Column.RPC, Column.UP, Column.WORLD), hiddenAt(680f)),
                () -> assertEquals(hidden(Column.RPC, Column.UP, Column.WORLD, Column.GAME), hiddenAt(560f)));
    }

    @Test
    void theRadioAccountPipeLinkAndActionsNeverGo() {
        Layout layout = TableColumns.fit(200f, EM);

        assertAll(EnumSet.of(Column.TARGET, Column.ACCOUNT, Column.PIPE, Column.LINK, Column.ACTIONS).stream()
                .map(column -> () -> assertTrue(layout.shows(column), column.name())));
    }

    private static Set<Column> hiddenAt(float width) {
        Layout layout = TableColumns.fit(width, EM);
        Set<Column> gone = EnumSet.noneOf(Column.class);
        for (Column column : Column.values()) {
            if (!layout.shows(column)) {
                gone.add(column);
            }
        }
        return gone;
    }

    private static Set<Column> hidden(Column... columns) {
        Set<Column> set = EnumSet.noneOf(Column.class);
        set.addAll(List.of(columns));
        return set;
    }
}
