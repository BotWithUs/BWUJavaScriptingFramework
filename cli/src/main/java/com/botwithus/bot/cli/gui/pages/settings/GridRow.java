package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.core.alerts.AlertKind;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One alert kind's row of the Integrations event grid: which services it is sent to.
 *
 * @param label  e.g. {@code Client closed}
 * @param detail the second line, e.g. {@code the game exited}; empty for none
 * @param cells  one per column, in column order
 */
public record GridRow(AlertKind kind, String label, String detail, List<Cell> cells) {

    /**
     * One tick box.
     *
     * @param keyName   the {@code alerts.<service>.send.<kind>} setting it edits
     * @param isTicked  whether the kind is sent to the service
     * @param isEnabled whether the service is on; when off the box is greyed and cannot be changed
     */
    public record Cell(String keyName, boolean isTicked, boolean isEnabled) {

        public Cell {
            Objects.requireNonNull(keyName, "keyName");
        }
    }

    public GridRow {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(detail, "detail");
        cells = List.copyOf(cells);
    }

    String findText() {
        StringBuilder text = new StringBuilder(label).append(' ').append(detail);
        for (Cell cell : cells) {
            text.append(' ').append(cell.keyName());
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }
}
