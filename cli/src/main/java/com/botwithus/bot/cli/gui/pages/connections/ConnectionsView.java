package com.botwithus.bot.cli.gui.pages.connections;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One frame's worth of the Connections page: every row, in table order, and
 * the header's state. Immutable.
 *
 * @param rows          connected and reconnecting first, then found, then closed
 * @param isAutoConnect the host connects to new pipes on its own
 * @param pipePrefix    the prefix scans look for, as stored (no glob)
 * @param scanInterval  how often auto-connect scans
 * @param isScanning    a scan started from this page is still running
 * @param scanMessage   what the last scan from this page said, if it said anything
 */
public record ConnectionsView(List<ConnectionRow> rows, boolean isAutoConnect, String pipePrefix,
                              Duration scanInterval, boolean isScanning, Optional<String> scanMessage) {

    public ConnectionsView {
        rows = List.copyOf(rows);
        Objects.requireNonNull(pipePrefix, "pipePrefix");
        Objects.requireNonNull(scanInterval, "scanInterval");
        Objects.requireNonNull(scanMessage, "scanMessage");
    }

    /** The rows {@code filter} and {@code query} leave, in table order. */
    public List<ConnectionRow> shown(RowFilter filter, String query) {
        return rows.stream().filter(filter::matches).filter(row -> row.matches(query)).toList();
    }

    /** How many rows {@code filter} alone leaves. */
    public int count(RowFilter filter) {
        return (int) rows.stream().filter(filter::matches).count();
    }

    /** The found pipes, in table order. */
    public List<ConnectionRow> found() {
        return rows.stream().filter(row -> row.group() == RowGroup.FOUND).toList();
    }

    /** The row with {@code id}, if it is still in the table. */
    public Optional<ConnectionRow> find(String id) {
        return rows.stream().filter(row -> row.id().equals(id)).findFirst();
    }

    /** The row console commands run on, if any. */
    public Optional<ConnectionRow> consoleTarget() {
        return rows.stream().filter(ConnectionRow::isConsoleTarget).findFirst();
    }

    /** The row the console output is filtered to, if any. */
    public Optional<ConnectionRow> outputFilter() {
        return rows.stream().filter(ConnectionRow::isOutputFilter).findFirst();
    }

    /** How many clients are reconnecting or stopped: the sidebar's warning count. */
    public int notResponding() {
        return (int) rows.stream().filter(ConnectionRow::isNotResponding).count();
    }
}
