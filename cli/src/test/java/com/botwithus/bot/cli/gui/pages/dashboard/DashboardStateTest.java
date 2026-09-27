package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.runners.RunnerStatus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** "View log" on a toast or a card lands on the Logs tab, scoped to that client. */
class DashboardStateTest {

    private static final ClientKey CLIENT = ClientKey.account("2c4e6a8b0d1f43a5b7c9d1e3f5a7b9c1");

    @Test
    void openLogs_forAClient_scopesTheDashboardToIt_andShowsLogs() {
        DashboardState state = new DashboardState();
        state.setDockCollapsed(true);

        state.openLogs(Optional.of(CLIENT));

        assertEquals(Scope.of(CLIENT), state.scope());
        assertEquals(DashboardState.DockTab.LOGS, state.tab());
        assertFalse(state.isDockCollapsed(), "a collapsed dock opens so the logs are visible");
    }

    @Test
    void openLogs_forNoClient_showsEveryClientsLogs() {
        DashboardState state = new DashboardState();
        state.setScope(Scope.of(CLIENT));

        state.openLogs(Optional.empty());

        assertEquals(Scope.ALL, state.scope());
        assertEquals(DashboardState.DockTab.LOGS, state.tab());
    }

    @Test
    void openLogs_resetsTheLevelChip_soAClientsErrorsAreNotHiddenByAnOldFilter() {
        DashboardState state = new DashboardState();
        state.setLevel(LogLevel.DEBUG);

        state.openLogs(Optional.of(CLIENT));

        assertEquals(LogLevel.ALL, state.level());
    }

    @Test
    void sortBy_aNumberColumn_startsBiggestFirst_andAgainReverses() {
        DashboardState state = new DashboardState();
        List<RunnerRow> rows = List.of(row("Quick", 10), row("Slow", 900), row("Mid", 120));

        state.sortBy(DashboardState.RunnerColumn.AVG);
        List<String> first = scripts(state.sorted(rows));
        state.sortBy(DashboardState.RunnerColumn.AVG);
        List<String> second = scripts(state.sorted(rows));

        assertEquals(List.of("Slow", "Mid", "Quick"), first);
        assertEquals(List.of("Quick", "Mid", "Slow"), second);
    }

    @Test
    void problemsFilter_keepsOnlyStalledCrashedAndCutOffRunners() {
        DashboardState state = new DashboardState();
        state.setFilter(DashboardState.RunnerFilter.PROBLEMS);
        List<RunnerRow> rows = List.of(row("Running", RunnerStatus.RUNNING), row("Stalled", RunnerStatus.STALLED),
                row("Stopped", RunnerStatus.STOPPED), row("Crashed", RunnerStatus.CRASHED),
                row("CutOff", RunnerStatus.CUT_OFF));

        assertEquals(List.of("Crashed", "Stalled", "CutOff"), scripts(state.sorted(rows)));
    }

    private static RunnerRow row(String script, double avgMs) {
        return new RunnerRow(new RunnerRef(CLIENT, script), "Oakheart", "1.0", ScriptCategory.UTILITY,
                RunnerStatus.RUNNING, 1L, avgMs, avgMs, avgMs, new long[0], 0L, Optional.empty(), false);
    }

    private static RunnerRow row(String script, RunnerStatus status) {
        return new RunnerRow(new RunnerRef(CLIENT, script), "Oakheart", "1.0", ScriptCategory.UTILITY,
                status, 1L, 1.0, 1.0, 1.0, new long[0], 0L, Optional.empty(), false);
    }

    private static List<String> scripts(List<RunnerRow> rows) {
        return rows.stream().map(RunnerRow::script).toList();
    }
}
