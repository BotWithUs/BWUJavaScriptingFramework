package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.runners.RunnerStatus;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the Dashboard page shows, as opposed to what the host holds: the scope,
 * the dock's tab, the filters and what is expanded. Render thread only.
 */
public final class DashboardState {

    /** The dock's three tabs, left to right. */
    public enum DockTab { CONSOLE, LOGS, EVENTS }

    /** The runners table's Show filter. */
    public enum RunnerFilter {
        ALL, RUNNING, PROBLEMS;

        public boolean admits(RunnerRow row) {
            return switch (this) {
                case ALL -> true;
                case RUNNING -> row.status() == RunnerStatus.RUNNING;
                case PROBLEMS -> row.status().isProblem();
            };
        }
    }

    /** The runners table's sortable columns. */
    public enum RunnerColumn {
        SCRIPT(Comparator.comparing(RunnerRow::script, String.CASE_INSENSITIVE_ORDER), true),
        CLIENT(Comparator.comparing(RunnerRow::clientLabel, String.CASE_INSENSITIVE_ORDER), true),
        STATE(Comparator.comparing(RunnerRow::status), true),
        LOOPS(Comparator.comparingLong(RunnerRow::loops), false),
        AVG(Comparator.comparingDouble(RunnerRow::avgMs), false),
        LAST(Comparator.comparingDouble(RunnerRow::lastMs), false),
        MAX(Comparator.comparingDouble(RunnerRow::maxMs), false);

        private final Comparator<RunnerRow> order;
        private final boolean isTextual;

        RunnerColumn(Comparator<RunnerRow> order, boolean isTextual) {
            this.order = order;
            this.isTextual = isTextual;
        }

        /** Words and states sort A to Z first; numbers sort biggest first. */
        boolean startsAscending() {
            return isTextual;
        }
    }

    private Scope scope = Scope.ALL;
    private RunnerColumn sortColumn = RunnerColumn.STATE;
    private boolean isSortAscending = true;
    private DockTab tab = DockTab.CONSOLE;
    private boolean isDockCollapsed;
    private LogLevel level = LogLevel.ALL;
    private boolean isFollowing = true;
    private RunnerFilter filter = RunnerFilter.ALL;
    private final Set<String> expanded = new HashSet<>();

    /**
     * Shows the Logs tab for {@code client}, or for every client when empty. The
     * whole page scopes with it, and the level chip goes back to All so the
     * client's lines are not hidden by a filter picked earlier.
     */
    public void openLogs(Optional<ClientKey> client) {
        scope = client.map(Scope::of).orElse(Scope.ALL);
        level = LogLevel.ALL;
        showTab(DockTab.LOGS);
    }

    public Scope scope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public DockTab tab() {
        return tab;
    }

    /** Shows {@code next}, opening the dock if it was collapsed. */
    public void showTab(DockTab next) {
        this.tab = Objects.requireNonNull(next, "next");
        this.isDockCollapsed = false;
    }

    public boolean isDockCollapsed() {
        return isDockCollapsed;
    }

    public void setDockCollapsed(boolean isCollapsed) {
        this.isDockCollapsed = isCollapsed;
    }

    public LogLevel level() {
        return level;
    }

    public void setLevel(LogLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    /** Whether the Logs tab keeps the newest line in view. */
    public boolean isFollowing() {
        return isFollowing;
    }

    public void setFollowing(boolean isFollowing) {
        this.isFollowing = isFollowing;
    }

    public RunnerFilter filter() {
        return filter;
    }

    public void setFilter(RunnerFilter filter) {
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    public RunnerColumn sortColumn() {
        return sortColumn;
    }

    public boolean isSortAscending() {
        return isSortAscending;
    }

    /** Sorts by {@code column}; clicking the sorted column again reverses it. */
    public void sortBy(RunnerColumn column) {
        if (column == sortColumn) {
            isSortAscending = !isSortAscending;
        } else {
            sortColumn = Objects.requireNonNull(column, "column");
            isSortAscending = column.startsAscending();
        }
    }

    /** {@code rows} in the table's order; rows that tie keep the order they came in. */
    public List<RunnerRow> sorted(List<RunnerRow> rows) {
        Comparator<RunnerRow> order = isSortAscending ? sortColumn.order : sortColumn.order.reversed();
        return rows.stream().filter(filter::admits).sorted(order).toList();
    }

    /** Whether the attention entry with {@code key} shows its stack trace. */
    public boolean isExpanded(String key) {
        return expanded.contains(key);
    }

    public void toggleExpanded(String key) {
        if (!expanded.remove(key)) {
            expanded.add(key);
        }
    }
}
