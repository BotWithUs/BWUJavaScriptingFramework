package com.botwithus.bot.cli.gui.pages.connections;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lays the Connections table's columns across a width. Account and Pipe share
 * whatever the fixed columns leave, 1.2 : 1; when even their minimum does not
 * fit, the least-needed columns give way one at a time: RPC, then Up, then
 * World, then Game. The radio, Account, Pipe, Link and the actions always stay.
 * Every size is in ems of the body font, so the table scales with the text.
 */
final class TableColumns {

    /** The table's columns, left to right. */
    enum Column {
        TARGET(1.467f, 0f),
        ACCOUNT(7f, 1.2f),
        PIPE(6.667f, 1f),
        WORLD(2.933f, 0f),
        GAME(7.467f, 0f),
        LINK(8.267f, 0f),
        RPC(3.733f, 0f),
        UP(4.267f, 0f),
        ACTIONS(6.667f, 0f);

        /** A fixed column's width, or a flexible one's minimum, in ems. */
        private final float widthEm;
        /** How much of the spare width a flexible column takes; zero for a fixed one. */
        private final float weight;

        Column(float widthEm, float weight) {
            this.widthEm = widthEm;
            this.weight = weight;
        }
    }

    /** Where a column's cell starts, from the table's left edge, and how wide it is. */
    record Cell(float x, float width) { }

    /** The columns shown and their cells. */
    record Layout(Map<Column, Cell> cells) {
        Layout {
            cells = Map.copyOf(cells);
        }

        boolean shows(Column column) {
            return cells.containsKey(column);
        }

        Cell cell(Column column) {
            return cells.get(column);
        }
    }

    /** The order columns give way in when the table is too narrow. */
    private static final List<Column> DROP_ORDER = List.of(Column.RPC, Column.UP, Column.WORLD, Column.GAME);
    private static final float PAD_LEFT_EM = 1.067f;
    private static final float PAD_RIGHT_EM = 0.8f;
    private static final float GAP_EM = 0.667f;

    private TableColumns() {
    }

    /** The layout for a table {@code width} wide whose body font is {@code em} pixels. */
    static Layout fit(float width, float em) {
        Set<Column> shown = EnumSet.allOf(Column.class);
        for (Column column : DROP_ORDER) {
            if (needed(shown, em) <= width) {
                break;
            }
            shown.remove(column);
        }
        return place(shown, width, em);
    }

    /** The width {@code shown} needs with the flexible columns at their minimum. */
    private static float needed(Set<Column> shown, float em) {
        float sum = (PAD_LEFT_EM + PAD_RIGHT_EM + GAP_EM * (shown.size() - 1)) * em;
        for (Column column : shown) {
            sum += column.widthEm * em;
        }
        return sum;
    }

    private static Layout place(Set<Column> shown, float width, float em) {
        float fixed = (PAD_LEFT_EM + PAD_RIGHT_EM + GAP_EM * (shown.size() - 1)) * em;
        float weights = 0f;
        for (Column column : shown) {
            if (column.weight > 0f) {
                weights += column.weight;
            } else {
                fixed += column.widthEm * em;
            }
        }
        float spare = Math.max(0f, width - fixed);
        Map<Column, Cell> cells = new EnumMap<>(Column.class);
        float x = PAD_LEFT_EM * em;
        for (Column column : shown) {
            float w = column.weight > 0f
                    ? Math.max(column.widthEm * em, spare * column.weight / weights)
                    : column.widthEm * em;
            cells.put(column, new Cell(x, w));
            x += w + GAP_EM * em;
        }
        return new Layout(cells);
    }
}
